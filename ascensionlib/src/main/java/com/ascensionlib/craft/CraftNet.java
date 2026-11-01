package com.ascensionlib.craft;

import com.ascensionlib.AscensionApi;
import com.ascensionlib.CraftLocks;
import com.ascensionlib.ProfileService;
import com.ascensionlib.Profiler;
import com.ascensionlib.net.RateLimit;
import com.ascensionlib.telemetry.BalanceLog;
import com.ascensionlib.wallet.WalletWeb;
import com.ascensionlib.web.WebSprites;
import com.ascensionlib.web.WebUi;
import com.cobbleascend.domain.Cost;
import com.cobbleascend.domain.Rarity;
import com.cobbleascend.domain.v1.Category;
import com.cobbleascend.domain.v1.CraftException;
import com.cobbleascend.domain.v1.MaterialId;
import com.cobbleascend.domain.v1.OrdinarySlot;
import com.cobbleascend.domain.v1.ProfileV1;
import com.cobbleascend.domain.v1.RankedProgression;
import com.cobbleascend.domain.v1.RankedRules;
import com.ascensionlib.event.HalloweenEvent;
import com.cobbleascend.domain.v1.Hauntings;
import com.cobbleascend.domain.v1.Tunables;
import com.cobbleascend.store.CraftRequest;
import com.cobbleascend.store.Kind;
import com.cobbleascend.store.StoreConfig;
import com.cobbleascend.store.StoreException;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server half of the upgrade / refine / reforge screen. the page's ready message only reads; {@link CraftPayloads.Confirm} is the one
 * way a craft happens, and it passes through {@link ProfileService#craft} so the store's operation id, revision and wallet checks
 * decide it. The player must own the Pokemon (party or PC) both times, and never sees another player's.
 */
public final class CraftNet {
    private static final Logger LOG = LoggerFactory.getLogger("ascensionlib");

    private CraftNet() {}

    /** The Pokemon each player's craft page should show once it reports that it has loaded. */
    private static final Map<UUID, String> PENDING = new ConcurrentHashMap<>();

    /** Forgets the page state of a player who left. */
    public static void forget(UUID player) { PENDING.remove(player); }

    public static void register() {
        WebUi.listen("ascension_craft_ready", (player, o) -> {
            var pokemon = PENDING.get(player.getUUID());
            if (pokemon == null) return;
            // Each open scans the player's party and PC and reads the wallet: a page must not be able to spam it.
            if (!RateLimit.allow(player.getUUID(), "craft.open", 6, 3)) return;
            try {
                findOwned(player, pokemon).ifPresent(p -> {
                    var locked = CraftLocks.reason(p);
                    if (locked.isPresent()) player.sendSystemMessage(Component.literal(locked.get()));
                    else sendView(player, p, false, "");
                });
            } catch (RuntimeException exception) {
                LOG.error("Craft open failed for {}", player.getGameProfile().getName(), exception);
            }
        });
        // The inspect and fusion pages open this one: the page names a Pokemon and the server opens the screen if it is allowed to.
        WebUi.listen("ascension_craft_open", (player, o) -> {
            if (!RateLimit.allow(player.getUUID(), "craft.openpage", 4, 2)) return;
            findOwned(player, WebUi.id(o, "pokemonId")).ifPresent(p -> openFor(player, p));
        });
        WebUi.listen("ascension_wallet_open", (player, o) -> {
            if (RateLimit.allow(player.getUUID(), "wallet.open", 3, 1)) WalletWeb.open(player);
        });
        WebUi.listen("ascension_craft_confirm", (player, o) -> {
            confirm(player, new CraftPayloads.Confirm(WebUi.id(o, "operationId"), WebUi.id(o, "pokemonId"), WebUi.str(o, "kind", 16),
                    WebUi.str(o, "slotId", 32), WebUi.num(o, "profileRevision"), WebUi.num(o, "walletRevision")));
        });
    }

    /**
     * Opens the craft page for this Pokemon. Returns false (and says why to the player) when it is lent, has no profile, or the player's
     * client cannot show web pages.
     */
    public static boolean openFor(ServerPlayer player, Pokemon pokemon) {
        var service = AscensionApi.service().orElse(null);
        if (service == null) return false;
        var locked = CraftLocks.reason(pokemon);
        if (locked.isPresent()) { player.sendSystemMessage(Component.literal(locked.get())); return false; }
        if (service.canonical(pokemon).isEmpty()) {
            player.sendSystemMessage(Component.literal("That Pokémon has no ascension profile."));
            return false;
        }
        PENDING.put(player.getUUID(), pokemon.getUuid().toString());
        return WebUi.open(player, "craft.html");
    }

    /** The player's own Pokemon in party or PC with this id, never a battle clone. */
    public static Optional<Pokemon> findOwned(ServerPlayer player, String pokemonId) {
        UUID id;
        try { id = UUID.fromString(pokemonId); } catch (IllegalArgumentException e) { return Optional.empty(); }
        long profiled = Profiler.start();
        List<Pokemon> all = new ArrayList<>();
        Cobblemon.INSTANCE.getStorage().getParty(player).forEach(all::add);
        Cobblemon.INSTANCE.getStorage().getPC(player).forEach(all::add);
        var found = all.stream().filter(p -> p.getUuid().equals(id) && !p.isBattleClone() && player.getUUID().equals(p.getOwnerUUID())).findFirst();
        Profiler.stop("server.findOwned", profiled);
        return found;
    }

    public static boolean sendView(ServerPlayer player, Pokemon pokemon, boolean open, String message) {
        var service = AscensionApi.service().orElse(null);
        if (service == null) return false;
        if (CraftLocks.locked(pokemon)) return false;     // a lent Pokemon has no upgrade screen
        long profiled = Profiler.start();
        var profile = service.canonical(pokemon);
        Profiler.stop("server.canonical", profiled);
        if (profile.isEmpty()) return false;
        profiled = Profiler.start();
        var built = view(player, pokemon, service, profile.get(), open, message);
        Profiler.stop("server.craftView", profiled);
        WebSprites.feed(player, List.of(built.speciesId()));
        WebUi.emit(player, "craftView", built);
        return true;
    }

    private static String slotName(ProfileService service, OrdinarySlot slot) {
        var affix = service.rules().affix(slot.affixId());
        return affix.base().name() + (slot.type() == null ? "" : " (" + slot.type() + ")");
    }

    static CraftPayloads.View view(ServerPlayer player, Pokemon pokemon, ProfileService service, ProfileV1 profile, boolean open, String message) {
        var rules = service.rules();
        var wallet = service.store().wallet(player.getUUID());
        var refineCost = rules.base().craftCost("refine");
        var reforgeCost = rules.base().craftCost("reforge");
        boolean canRefine = wallet.canAfford(MaterialId.of(refineCost));
        boolean canReforge = wallet.canAfford(MaterialId.of(reforgeCost));
        var progressionRules = new RankedProgression(rules);
        List<CraftPayloads.SlotView> slots = new ArrayList<>();
        for (var slot : profile.ordinarySlots()) {
            var affix = rules.affix(slot.affixId());
            var band = affix.band(slot.rank());
            boolean top = slot.rank() >= 5;
            var next = top ? null : affix.band(slot.rank() + 1);
            String upgradeBlock = profile.pendingCredits() < 1 ? "No pending upgrade" : top ? "Already at the top rank" : "";
            String refineBlock = slot.sealed() ? "Sealed: only a reforge" : canRefine ? "" : "Needs " + describe(refineCost);
            int effectiveFloor = progressionRules.effectiveFloor(slot);
            boolean canPerfect = !slot.sealed() && slot.rank() >= 5 && effectiveFloor < band.max();
            int perfectCost = canPerfect ? progressionRules.perfectCost(slot) : 0;
            String perfectBlock = slot.sealed() ? "Sealed: only a reforge" : slot.rank() < 5 ? "Needs rank V" : !canPerfect ? "The floor is already at the top of the band"
                    : wallet.balance(MaterialId.RESONANCE_DUST) < perfectCost ? "Needs " + perfectCost + " Resonance Dust" : "";
            var overreachPrice = RankedProgression.overreachCost();
            String overreachBlock = slot.sealed() ? "Already attempted: sealed" : slot.rank() < 5 ? "Needs rank V" : slot.rolledValue() < band.max() ? "The value must be at the band maximum"
                    : !wallet.canAfford(overreachPrice)
                    ? "Needs " + RankedProgression.OVERREACH_DUST + " Resonance Dust + " + RankedProgression.OVERREACH_FACETS + " Facets + "
                    + RankedProgression.OVERREACH_MARKS + " Tower Mark"
                    + (overreachPrice.containsKey(MaterialId.APEX_SHARD) ? " + " + overreachPrice.get(MaterialId.APEX_SHARD) + " Apex Shard" : "") : "";
            // The affixes this slot could become: its category, minus the families the other slots already hold (the same filter a reforge draws from).
            var others = profile.ordinarySlots().stream().filter(s -> !s.slotId().equals(slot.slotId())).map(s -> rules.affix(s.affixId()).family()).toList();
            int pool = (int) rules.affixes().stream().filter(a -> a.category() == slot.category() && !others.contains(a.family())).count();
            String reforgeBlock = pool == 0 ? "No other affix can take this slot" : !canReforge ? "Needs " + describe(reforgeCost) : "";
            slots.add(new CraftPayloads.SlotView(slot.slotId(), slot.category().id(), slotName(service, slot),
                    affix.base().condition() == null ? "" : affix.base().condition(), slot.rank(), slot.rolledValue(), band.min(), band.max(),
                    next == null ? -1 : next.min(), next == null ? -1 : next.max(), upgradeBlock, refineBlock, reforgeBlock, pool,
                    perfectBlock, perfectCost, effectiveFloor, overreachBlock, slot.sealed(), RankedProgression.OVERREACH_DUST,
                    RankedProgression.OVERREACH_FACETS, RankedProgression.OVERREACH_CHANCE_PERCENT,
                    band.max() + Math.max(1, Math.round(band.max() * RankedProgression.OVERREACH_MIN_PERCENT / 100f)),
                    Math.min(RankedRules.overreachCeiling(band.max()),
                            band.max() + Math.max(1, Math.round(band.max() * RankedProgression.OVERREACH_MAX_PERCENT / 100f)))));
        }
        CraftPayloads.Promotion promotion;
        if (profile.rarity() == Rarity.MYTHICAL) {
            promotion = new CraftPayloads.Promotion("", new CraftPayloads.Price(0, 0, 0), profile.attunement(), 0, "Already Mythical");
        } else {
            var step = rules.base().promotion(profile.rarity(), Tunables.current().threat().enabled());
            String block = profile.attunement() < step.attunement()
                    ? "Needs " + step.attunement() + " attunement (has " + profile.attunement() + ")"
                    : !wallet.canAfford(MaterialId.of(step.cost())) ? "Needs " + describe(step.cost()) : "";
            promotion = new CraftPayloads.Promotion(step.to().id(), new CraftPayloads.Price(step.cost().dust(), step.cost().facets(),
                    step.cost().cores(), step.cost().apex()), profile.attunement(), step.attunement(), block);
        }
        var held = profile.unique() == null ? "" : profile.unique().uniqueId();
        var uniqueOptions = new ArrayList<CraftPayloads.UniqueOption>();
        for (var definition : rules.uniques())
            uniqueOptions.add(new CraftPayloads.UniqueOption(definition.id(), definition.name(), definition.benefit(), definition.drawback(),
                    definition.id().equals(held)));
        long catalysts = wallet.balance(MaterialId.UNIQUE_CATALYST);
        var uniqueState = new CraftPayloads.UniqueState(held, catalysts, wallet.balance(MaterialId.UNIQUE_FRAGMENT),
                StoreConfig.defaults().catalystFragments(), uniqueOptions,
                service.isFused(pokemon.getUuid()) ? "A Transcendent's power cannot be replaced" : catalysts < 1 || wallet.balance(MaterialId.TOWER_MARK) < RankedProgression.INSTALL_MARKS
                        ? "Needs 1 Unique Catalyst + " + RankedProgression.INSTALL_MARKS + " Tower Mark" : "");
        long hourglasses = wallet.balance(MaterialId.RANK_RESPEC), prisms = wallet.balance(MaterialId.UNIQUE_RESPEC);
        String hourglassBlock = progressionRules.respecRanksBlock(profile);
        if (hourglassBlock.isEmpty() && hourglasses < 1) hourglassBlock = "You have no Hourglass of Undoing";
        String prismBlock = service.isFused(pokemon.getUuid()) ? "A Transcendent's power cannot be swapped"
                : profile.unique() == null ? "Install a Unique with a Catalyst first" : prisms < 1 ? "You have no Unbinding Prism" : "";
        var resets = new ArrayList<CraftPayloads.ResetLine>();
        for (var slot : profile.ordinarySlots()) resets.add(new CraftPayloads.ResetLine(slotName(service, slot), slot.rank(), slot.floor()));
        var respec = new CraftPayloads.Respec(hourglasses, prisms, wallet.balance(MaterialId.TOWER_MARK), Tunables.current().respecMarksPrice(),
                profile.spentUpgradeCredits(), hourglassBlock, prismBlock, resets);
        int nextMilestone = 0;
        for (int m = 10; m <= 100; m += 10) if (m > profile.highestLevelObserved()) { nextMilestone = m; break; }
        return new CraftPayloads.View(pokemon.getUuid().toString(), pokemon.getDisplayName(false).getString(),
                pokemon.getSpecies().getResourceIdentifier().toString(), new ArrayList<>(pokemon.getAspects()), pokemon.getLevel(),
                profile.rarity().id(), service.uniqueLabel(profile),
                profile.pendingCredits(), nextMilestone, profile.revision(), wallet.revision(), wallet.balance(MaterialId.RESONANCE_DUST),
                wallet.balance(MaterialId.FACET), wallet.balance(MaterialId.ASCENSION_CORE), wallet.balance(MaterialId.SCOUTER), wallet.balance(MaterialId.TOWER_MARK),
                new CraftPayloads.Price(refineCost.dust(), refineCost.facets(), refineCost.cores()),
                new CraftPayloads.Price(reforgeCost.dust(), reforgeCost.facets(), reforgeCost.cores()), promotion, uniqueState, respec, slots, message, open, haunt(player, pokemon, service, wallet),
                wallet.balance(MaterialId.APEX_SHARD), Tunables.current().threat().enabled() ? RankedProgression.OVERREACH_APEX : 0);
    }

    /** The Haunting row's data: what the Pokemon holds, what the next roll costs, and the ten Hauntings with their odds. */
    static CraftPayloads.HauntView haunt(ServerPlayer player, Pokemon pokemon, ProfileService service, com.cobbleascend.domain.v1.MaterialWallet wallet) {
        long essence = wallet.balance(MaterialId.SPOOKY_ESSENCE);
        var held = service.hauntingOf(pokemon.getUuid()).flatMap(Hauntings::byId);
        int rolls = service.hauntingRolls(pokemon.getUuid());
        long cost = Hauntings.cost(rolls);
        boolean show = HalloweenEvent.showHaunting(essence, held.isPresent(), HalloweenEvent.isOpen());
        String block = essence < cost ? "Needs " + cost + " Spooky Essence" : "";
        var all = new ArrayList<CraftPayloads.HauntOption>();
        // Hidden rows carry no list, so the ten Hauntings do not reach a client before the event.
        if (show) for (var h : Hauntings.all())
            all.add(new CraftPayloads.HauntOption(h.id(), h.name(), h.rare(), h.effect(),
                    h.rare() ? h.weight() / 10.0 : Math.round(h.weight() * (1000 - Hauntings.RARE_PERMILLE)) / 100.0 / 10.0));
        return new CraftPayloads.HauntView(show, essence, held.map(Hauntings.Haunting::id).orElse(""), held.map(Hauntings.Haunting::name).orElse(""),
                held.map(Hauntings.Haunting::rare).orElse(false), held.map(Hauntings.Haunting::effect).orElse(""), rolls, cost, block, all);
    }

    static String describe(Cost cost) {
        List<String> parts = new ArrayList<>();
        if (cost.dust() > 0) parts.add(cost.dust() + " Resonance Dust");
        if (cost.facets() > 0) parts.add(cost.facets() + " Facet" + (cost.facets() == 1 ? "" : "s"));
        if (cost.cores() > 0) parts.add(cost.cores() + " Core" + (cost.cores() == 1 ? "" : "s"));
        if (cost.apex() > 0) parts.add(cost.apex() + " Apex Shard" + (cost.apex() == 1 ? "" : "s"));
        return String.join(" + ", parts);
    }

    /**
     * Test seam for /ascendtestcraft (only registered under -Dascensionlib.testTrade=true): sends the same confirm the page would, with
     * the Pokemon's current profile and wallet revisions, and returns nothing; the result is in the server log and the player's wallet.
     */
    public static void testConfirm(ServerPlayer player, String pokemonId, String kind, String slotId) {
        var service = AscensionApi.service().orElse(null);
        if (service == null) return;
        long profileRevision = 0;
        var found = findOwned(player, pokemonId);
        if (found.isPresent()) profileRevision = service.canonical(found.get()).map(p -> p.revision()).orElse(0L);
        long walletRevision = service.store().wallet(player.getUUID()).revision();
        confirm(player, new CraftPayloads.Confirm(UUID.randomUUID().toString(), pokemonId, kind, slotId, profileRevision, walletRevision));
    }

    private static void confirm(ServerPlayer player, CraftPayloads.Confirm request) {
        // Every distinct operation id is a store transaction (an fsync): bound how fast one player can submit them.
        if (!RateLimit.allow(player.getUUID(), "craft.confirm", 4, 2)) {
            WebUi.emit(player, "craftDone", new CraftPayloads.Done(request.operationId(), false, "Too many requests. Wait a moment.",
                    request.slotId(), "", 0, 0, "", 0, 0, false));
            return;
        }
        var service = AscensionApi.service().orElse(null);
        UUID operation;
        try { operation = UUID.fromString(request.operationId()); } catch (IllegalArgumentException e) { return; }
        if (service == null) { fail(player, request, "Progression is unavailable."); return; }
        var found = findOwned(player, request.pokemonId());
        if (found.isEmpty()) { fail(player, request, "That Pokémon is no longer yours to change."); return; }
        var pokemon = found.get();
        var locked = CraftLocks.reason(pokemon);
        if (locked.isPresent()) { fail(player, request, locked.get()); return; }
        var before = service.canonical(pokemon);
        if (before.isEmpty()) { fail(player, request, "That Pokémon has no ascension profile."); return; }
        if (request.kind().equals("assemble")) { assemble(player, service, pokemon, request, operation); return; }
        if (request.kind().equals("buy_hourglass")) { buyHourglass(player, service, pokemon, request, operation); return; }
        if (request.kind().equals("haunt")) { haunt(player, service, pokemon, request); return; }
        if ((request.kind().equals("unique") || request.kind().equals("respec_unique")) && service.isFused(pokemon.getUuid())) {
            fail(player, request, "A Transcendent's power cannot be replaced.");
            return;
        }
        CraftRequest craft;
        switch (request.kind()) {
            case "upgrade" -> craft = CraftRequest.upgrade(operation, player.getUUID(), pokemon.getUuid(), request.slotId(), request.profileRevision(),
                    CraftRequest.ANY_REVISION);
            case "refine" -> craft = CraftRequest.refine(operation, player.getUUID(), pokemon.getUuid(), request.slotId(), request.profileRevision(),
                    request.walletRevision());
            case "overreach" -> craft = CraftRequest.overreach(operation, player.getUUID(), pokemon.getUuid(), request.slotId(), request.profileRevision(),
                    request.walletRevision());
            case "perfect" -> craft = CraftRequest.perfect(operation, player.getUUID(), pokemon.getUuid(), request.slotId(), request.profileRevision(),
                    request.walletRevision());
            case "reforge" -> craft = CraftRequest.reforge(operation, player.getUUID(), pokemon.getUuid(), request.slotId(), service.typesFor(pokemon),
                    request.profileRevision(), request.walletRevision());
            case "unique" -> craft = before.get().unique() == null
                    ? CraftRequest.installUnique(operation, player.getUUID(), pokemon.getUuid(), request.slotId(), request.profileRevision(), request.walletRevision())
                    : CraftRequest.replaceUnique(operation, player.getUUID(), pokemon.getUuid(), request.slotId(), request.profileRevision(), request.walletRevision());
            case "respec_ranks" -> craft = CraftRequest.respecRanks(operation, player.getUUID(), pokemon.getUuid(), request.profileRevision(), request.walletRevision());
            case "respec_unique" -> craft = CraftRequest.respecUnique(operation, player.getUUID(), pokemon.getUuid(), request.slotId(), request.profileRevision(),
                    request.walletRevision());
            case "promote" -> craft = CraftRequest.promote(operation, player.getUUID(), pokemon.getUuid(), service.typesFor(pokemon),
                    request.profileRevision(), request.walletRevision());
            default -> { fail(player, request, "Unknown action."); return; }
        }
        try {
            var outcome = service.craft(pokemon, craft);
            BalanceLog.craft(player, craft, before.get(), outcome);
            if (craft.kind() == Kind.RESPEC_RANKS) {
                LOG.info("respec_ranks committed on Pokemon {} ({} credits returned) by {} (operation {}{})", pokemon.getUuid(), before.get().spentUpgradeCredits(),
                        player.getGameProfile().getName(), operation, outcome.replayed() ? ", replayed" : "");
                WebUi.emit(player, "craftDone", new CraftPayloads.Done(request.operationId(), true, "", "", "", 0, before.get().spentUpgradeCredits(), "Respec", 0, 0, outcome.replayed()));
                sendView(player, pokemon, false, "");
                return;
            }
            if (craft.kind() == Kind.INSTALL_UNIQUE || craft.kind() == Kind.REPLACE_UNIQUE || craft.kind() == Kind.RESPEC_UNIQUE) {
                var was = before.get().unique() == null ? "" : uniqueName(service, before.get().unique().uniqueId());
                var now = uniqueName(service, outcome.profile().unique().uniqueId());
                LOG.info("{} committed on Pokemon {} -> {} by {} (operation {}{})", craft.kind().name().toLowerCase(java.util.Locale.ROOT), pokemon.getUuid(),
                        outcome.profile().unique().uniqueId(), player.getGameProfile().getName(), operation, outcome.replayed() ? ", replayed" : "");
                WebUi.emit(player, "craftDone", new CraftPayloads.Done(request.operationId(), true, "", request.slotId(), was, 0, 0, now, 0, 0, outcome.replayed()));
                sendView(player, pokemon, false, "");
                return;
            }
            if (craft.kind() == Kind.PROMOTE) {
                LOG.info("promote committed on Pokemon {} {} -> {} by {} (operation {}{})", pokemon.getUuid(), before.get().rarity().id(),
                        outcome.profile().rarity().id(), player.getGameProfile().getName(), operation, outcome.replayed() ? ", replayed" : "");
                WebUi.emit(player, "craftDone", new CraftPayloads.Done(request.operationId(), true, "", "", before.get().rarity().id(), 0, 0,
                        outcome.profile().rarity().id(), 0, 0, outcome.replayed()));
                sendView(player, pokemon, false, "");
                return;
            }
            var oldSlot = before.get().slot(request.slotId());
            var newSlot = outcome.profile() == null ? Optional.<OrdinarySlot>empty() : outcome.profile().slot(request.slotId());
            LOG.info("{} {} on Pokemon {} slot {} by {} (operation {}{})", request.kind(), "committed", pokemon.getUuid(), request.slotId(),
                    player.getGameProfile().getName(), operation, outcome.replayed() ? ", replayed" : "");
            WebUi.emit(player, "craftDone", new CraftPayloads.Done(request.operationId(), true, "", request.slotId(),
                    oldSlot.map(s -> slotName(service, s)).orElse(""), oldSlot.map(OrdinarySlot::rank).orElse(0), shownValue(service, craft, oldSlot),
                    newSlot.map(s -> slotName(service, s)).orElse(""), newSlot.map(OrdinarySlot::rank).orElse(0), shownValue(service, craft, newSlot),
                    outcome.replayed()));
            sendView(player, pokemon, false, "");
        } catch (StoreException exception) {
            fail(player, request, switch (exception.code()) {
                case STALE_PROFILE, STALE_WALLET -> "This Pokémon or your materials changed. Review it again.";
                case OPERATION_REUSED -> "That confirmation was already used.";
                default -> "The change could not be made: " + exception.getMessage();
            });
        } catch (CraftException exception) {
            fail(player, request, switch (exception.reason()) {
                case NO_PENDING_CREDIT -> "No pending upgrade to spend.";
                case MAX_RANK -> "That slot is already at the top rank.";
                case INSUFFICIENT_FUNDS -> "You do not have enough materials.";
                case NOT_MAX_RANK -> "Only a rank V slot can do that.";
                case NOT_AT_BAND_MAX -> "The value must be at the band maximum first.";
                case SLOT_SEALED -> "That slot is sealed: only a reforge can change it.";
                case AT_BAND_MAX -> "That slot is already at the top of its band.";
                case NO_ELIGIBLE_AFFIX -> "No other affix can take that slot.";
                case INSUFFICIENT_ATTUNEMENT -> "Not enough attunement to promote yet.";
                case UNIQUE_PRESENT, NO_UNIQUE, SAME_UNIQUE, UNKNOWN_UNIQUE -> "That Unique cannot be set on this Pokémon: " + exception.getMessage();
                case RESPEC_SEALED, NOTHING_TO_RESPEC -> exception.getMessage() + ". Nothing was spent.";
                case MAX_RARITY -> "Already at the highest rarity.";
                default -> "The change is not allowed: " + exception.getMessage();
            });
        } catch (RuntimeException exception) {
            LOG.error("Craft {} failed for {}", request.kind(), pokemon.getUuid(), exception);
            fail(player, request, "Something went wrong; nothing was spent.");
        }
    }

    /** A Haunting roll: the same store transaction as {@code /ascend haunt}; the page learns the result from {@code craftDone} (newName is the Haunting). */
    private static void haunt(ServerPlayer player, ProfileService service, Pokemon pokemon, CraftPayloads.Confirm request) {
        try {
            var haunting = service.haunt(player, pokemon);
            LOG.info("Player {} haunted Pokemon {} with {} from the upgrade screen", player.getUUID(), pokemon.getUuid(), haunting.id());
            WebUi.emit(player, "craftDone", new CraftPayloads.Done(request.operationId(), true, "", "", "", 0, 0, haunting.name(), haunting.rare() ? 1 : 0, 0, false));
            if (haunting.rare()) {
                var announcement = Component.literal("[Haunting] " + player.getGameProfile().getName() + "'s " + pokemon.getDisplayName(false).getString()
                        + " awakened a Rare Haunting: " + haunting.name() + "!");
                for (var online : player.getServer().getPlayerList().getPlayers()) online.sendSystemMessage(announcement);
            }
            sendView(player, pokemon, false, "");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            fail(player, request, exception.getMessage());
        } catch (StoreException exception) {
            fail(player, request, "The Haunting could not be rolled: " + exception.getMessage());
        } catch (RuntimeException exception) {
            LOG.error("Haunting failed for {}", pokemon.getUuid(), exception);
            fail(player, request, "Something went wrong; nothing was spent.");
        }
    }

    /** The number a finished craft reports for a slot: its value, or for Perfecting its floor (what the craft changed). */
    private static int shownValue(ProfileService service, CraftRequest craft, Optional<OrdinarySlot> slot) {
        if (slot.isEmpty()) return 0;
        return craft.kind() == Kind.PERFECT ? new RankedProgression(service.rules()).effectiveFloor(slot.get()) : slot.get().rolledValue();
    }

    private static String uniqueName(ProfileService service, String id) {
        return service.rules().unique(id).map(u -> u.name()).orElse(id);
    }

    /** The Marks route: {@code RESPEC_MARKS_PRICE} Tower Marks for one Hourglass of Undoing, in one transaction. */
    private static void buyHourglass(ServerPlayer player, ProfileService service, Pokemon pokemon, CraftPayloads.Confirm request, UUID operation) {
        try {
            var price = (long) Tunables.current().respecMarksPrice();
            var outcome = service.store().exchange(operation, player.getUUID(), Map.of(MaterialId.TOWER_MARK, price), Map.of(MaterialId.RANK_RESPEC, 1L),
                    "hourglass of undoing", request.walletRevision());
            LOG.info("hourglass bought for {} Marks by {} (operation {}{})", price, player.getGameProfile().getName(), operation, outcome.replayed() ? ", replayed" : "");
            WebUi.emit(player, "craftDone", new CraftPayloads.Done(request.operationId(), true, "", "", "", 0, 0, "Hourglass of Undoing", 0, 0, outcome.replayed()));
            sendView(player, pokemon, false, "");
        } catch (StoreException exception) {
            fail(player, request, exception.code() == StoreException.Code.STALE_WALLET
                    ? "Your materials changed. Review it again." : "The Hourglass could not be bought: " + exception.getMessage());
        } catch (CraftException exception) {
            fail(player, request, exception.reason() == CraftException.Reason.INSUFFICIENT_FUNDS
                    ? "You need " + Tunables.current().respecMarksPrice() + " Tower Marks." : "The Hourglass could not be bought: " + exception.getMessage());
        } catch (RuntimeException exception) {
            LOG.error("Hourglass purchase failed for {}", player.getGameProfile().getName(), exception);
            fail(player, request, "Something went wrong; nothing was spent.");
        }
    }

    /** Turns the player's Fragments into one Catalyst. Needs no Pokemon change; the view is refreshed so the new balance shows. */
    private static void assemble(ServerPlayer player, ProfileService service, Pokemon pokemon, CraftPayloads.Confirm request, UUID operation) {
        try {
            var outcome = service.store().assembleCatalyst(operation, player.getUUID(), request.walletRevision());
            LOG.info("assemble committed by {} (operation {}{})", player.getGameProfile().getName(), operation, outcome.replayed() ? ", replayed" : "");
            WebUi.emit(player, "craftDone", new CraftPayloads.Done(request.operationId(), true, "", "", "", 0, 0, "Unique Catalyst", 0, 0, outcome.replayed()));
            sendView(player, pokemon, false, "");
        } catch (StoreException exception) {
            fail(player, request, exception.code() == StoreException.Code.STALE_WALLET
                    ? "Your materials changed. Review it again." : "The Catalyst could not be assembled: " + exception.getMessage());
        } catch (CraftException exception) {
            fail(player, request, exception.reason() == CraftException.Reason.INSUFFICIENT_FUNDS
                    ? "You do not have enough Unique Fragments." : "The Catalyst could not be assembled: " + exception.getMessage());
        } catch (RuntimeException exception) {
            LOG.error("Catalyst assembly failed for {}", player.getGameProfile().getName(), exception);
            fail(player, request, "Something went wrong; nothing was spent.");
        }
    }

    private static void fail(ServerPlayer player, CraftPayloads.Confirm request, String message) {
        BalanceLog.craftRefused(player, request.kind(), request.slotId(), message);
        WebUi.emit(player, "craftDone", new CraftPayloads.Done(request.operationId(), false, message, request.slotId(), "", 0, 0, "", 0, 0, false));
        findOwned(player, request.pokemonId()).ifPresent(p -> sendView(player, p, false, message));
    }
}
