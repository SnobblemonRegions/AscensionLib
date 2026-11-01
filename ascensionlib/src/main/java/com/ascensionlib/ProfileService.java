package com.ascensionlib;

import com.cobbleascend.domain.Rarity;
import com.cobbleascend.domain.v1.*;
import com.cobbleascend.store.CraftRequest;
import com.cobbleascend.store.ProgressionStore;
import com.cobbleascend.store.StoreException;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.random.RandomGenerator;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-thread application service between Cobblemon and the canonical store. The store is the authority;
 * the Pokemon's persistent data holds a schema-1 projection that is rewritten whenever it differs, so a crash
 * between commit and projection heals itself on the next reconcile. Schema-zero prototype data is imported
 * once. Anything that cannot be reconciled is quarantined (left untouched) rather than re-rolled.
 */
public final class ProfileService {
    // Data keys and operation-ID prefix predate the library and are persisted; changing them would orphan data.
    static final String KEY = "cobbleascend";
    static final String LEGACY_KEY = "cobbleascend_schema0";
    private static final Logger LOG = LoggerFactory.getLogger(AscensionLib.MOD_ID);

    private final AscensionRuntime runtime;
    private final ProgressionStore store;
    private final RankedRules rules;
    private final RankedProgression progression;
    private final ProfileV1Codec codec;
    private final RandomGenerator random;
    private final Set<UUID> quarantined = new HashSet<>();

    public ProfileService(AscensionRuntime runtime, RankedRules rules) {
        this.runtime = runtime;
        this.store = runtime.store();
        this.rules = rules;
        this.progression = new RankedProgression(rules);
        this.codec = new ProfileV1Codec(rules);
        this.random = runtime.newRandom();
    }

    public RankedRules rules() { return rules; }
    public ProgressionStore store() { return store; }
    public Set<UUID> quarantined() { return Collections.unmodifiableSet(quarantined); }

    /** Deterministic so a repeated callback for the same subject resolves the same committed operation. */
    static UUID operationId(String purpose, String subject) {
        return UUID.nameUUIDFromBytes(("cobbleascend:" + purpose + ":" + subject).getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> typesOf(Pokemon pokemon) {
        var types = new ArrayList<String>();
        pokemon.getTypes().forEach(type -> types.add(type.getShowdownId()));
        return types;
    }

    public Optional<ProfileV1> canonical(Pokemon pokemon) { return store.profile(pokemon.getUuid()); }

    /** The Pokemon's types as Showdown ids, the input an affix with a type parameter draws from (a reforge needs them). */
    public List<String> typesFor(Pokemon pokemon) { return typesOf(pokemon); }

    /**
     * Commits a confirmed craft (upgrade, refine or reforge) and rewrites the Pokemon's projection. The store checks the
     * operation id, both revisions and the wallet inside one transaction, so a duplicate returns the committed result and a
     * stale or unaffordable request changes nothing. The caller has already verified the player owns the Pokemon.
     */
    public com.cobbleascend.store.Outcome craft(Pokemon pokemon, CraftRequest request) {
        // The last line of defence: whichever way a request got here, a locked (lent) Pokemon is never crafted on.
        if (CraftLocks.locked(pokemon)) throw new IllegalStateException(CraftLocks.REASON);
        var outcome = store.craft(request);
        if (outcome.profile() != null) project(pokemon, outcome.profile());
        return outcome;
    }

    /**
     * Server-driven: first awards any level milestones the Pokemon has reached, then spends EVERY pending upgrade credit on
     * randomly chosen slots below rank V (one rank per credit), the way a player would have. For a Pokemon lent out with a
     * profile (a rental draft), where nobody will craft. No wallet cost, and the craft lock does not apply: this is not a player
     * craft. The slot picks are seeded from the Pokemon's id and its spent credits, so a repeat after a crash chooses the same
     * slot and the store's operation ids make it a replay, never a double spend.
     *
     * @return the profile after the upgrades, or empty when the Pokemon has no owner or no canonical profile
     */
    public Optional<ProfileV1> autoUpgrade(Pokemon pokemon) {
        var found = canonical(pokemon);
        if (found.isEmpty() || pokemon.getOwnerUUID() == null || pokemon.isBattleClone()) return Optional.empty();
        var profile = observeLevel(pokemon, found.get());
        UUID id = pokemon.getUuid();
        long seed = id.getMostSignificantBits() * 31L + id.getLeastSignificantBits();
        // A profile has at most 6 slots of 4 upgrades each; the guard only stops a broken store from looping.
        for (int guard = 0; guard < 24 && profile.pendingCredits() > 0; guard++) {
            var open = profile.ordinarySlots().stream().filter(slot -> slot.rank() < RankedAffix.RANKS).toList();
            if (open.isEmpty()) break;
            var slot = open.get(new java.util.Random(seed ^ (profile.spentUpgradeCredits() * 0x9E3779B97F4A7C15L)).nextInt(open.size()));
            var outcome = store.craft(CraftRequest.upgrade(
                    operationId("auto-upgrade", id + ":" + profile.spentUpgradeCredits() + ":" + slot.slotId()),
                    pokemon.getOwnerUUID(), id, slot.slotId(), profile.revision(), CraftRequest.ANY_REVISION));
            profile = outcome.profile();
        }
        project(pokemon, profile);
        return Optional.of(profile);
    }

    /**
     * Registers an owned Pokemon once. The rarity roll happens inside the store transaction only when this
     * Pokemon has no committed acquisition, so repeated callbacks and restarts never re-roll.
     */
    public ProfileV1 acquire(Pokemon pokemon, Origin origin, boolean rollRarity) { return acquire(pokemon, origin, rollRarity, 1); }

    /** As above for a capture in a profile zone of this tier: the rating is the one {@link #previewWild(Pokemon, int)} showed. */
    public ProfileV1 acquire(Pokemon pokemon, Origin origin, boolean rollRarity, int tier) {
        return register(pokemon, (id, level, types) -> {
            // Wild captures use the rating fixed for that Pokemon, so a Scouter preview equals the result.
            if (rollRarity && (origin.kind().equals("wild_capture") || origin.kind().equals("external_grant")))
                return progression.createWild(store.wildSecret(), id, runtime.authority(), origin, level, types, Math.max(1, tier));
            Rarity rarity = rollRarity ? rules.base().rollRarity(random) : Rarity.COMMON;
            return progression.create(id, runtime.authority(), rarity, origin, level, types, random);
        });
    }

    /**
     * Registers an owned Pokemon whose rarity the operator fixed (a shop purchase): the rarity is never rolled,
     * the modifier slots are rolled for this Pokemon. Same once-only guarantee as {@link #acquire}.
     */
    public ProfileV1 acquireWithRarity(Pokemon pokemon, Origin origin, Rarity rarity) {
        Objects.requireNonNull(rarity, "rarity");
        return register(pokemon, (id, level, types) ->
                progression.create(id, runtime.authority(), rarity, origin, level, types, random));
    }

    /**
     * Balance phase 3: registers an owned Pokemon with a rarity rolled from the normal table but never below {@code floor}
     * (a captured raid boss). Same once-only guarantee as {@link #acquire}: a Pokemon that already has a profile keeps it.
     */
    public ProfileV1 acquireWithRarityFloor(Pokemon pokemon, Origin origin, Rarity floor) {
        Objects.requireNonNull(floor, "floor");
        return register(pokemon, (id, level, types) ->
                progression.create(id, runtime.authority(), atLeast(rules.base().rollRarity(random), floor), origin, level, types, random));
    }

    /** The higher of two rarities. Package-visible for the tests. */
    static Rarity atLeast(Rarity rolled, Rarity floor) {
        return rolled.ordinal() >= floor.ordinal() ? rolled : floor;
    }

    /**
     * Spends one Ascension Sigil to give a Pokemon of the player's that has no profile one, with a rarity rolled from the normal table.
     * The Sigil and the profile are one transaction, so a refusal costs nothing and a retry after a crash never charges twice.
     * Refused: not the player's own, on loan, in a battle, already profiled, quarantined, or no Sigil in the wallet.
     */
    public ProfileV1 useSigil(ServerPlayer player, Pokemon pokemon) {
        if (pokemon.isBattleClone() || !player.getUUID().equals(pokemon.getOwnerUUID()) || !owns(player, pokemon))
            throw new IllegalArgumentException("That Pokemon is not yours");
        if (CraftLocks.locked(pokemon)) throw new IllegalStateException(CraftLocks.REASON);
        if (com.cobblemon.mod.common.battles.BattleRegistry.INSTANCE.getBattleByParticipatingPlayer(player) != null)
            throw new IllegalStateException("Not during a battle");
        // Reconcile first: it imports a prototype profile and rewrites a stale projection, so only a truly unprofiled Pokemon proceeds.
        if (reconcile(pokemon).isPresent() || quarantined.contains(pokemon.getUuid()))
            throw new IllegalStateException("That Pokemon already has an ascension profile");
        UUID id = pokemon.getUuid();
        var types = typesOf(pokemon);
        int level = Math.max(1, pokemon.getLevel());
        try {
            var outcome = store.useSigil(operationId("sigil", id.toString()), player.getUUID(), id, () ->
                    progression.create(id, runtime.authority(), rules.base().rollRarity(random), Origin.of("sigil"), level, types, random));
            try {
                project(pokemon, outcome.profile());
            } catch (RuntimeException exception) {
                LOG.error("Sigil committed but {} could not be projected now; the next reconcile does it", id, exception);
            }
            return outcome.profile();
        } catch (CraftException exception) {
            if (exception.reason() == CraftException.Reason.INSUFFICIENT_FUNDS) throw new IllegalStateException("You have no Ascension Sigil");
            throw exception;
        }
    }

    /** The Haunting id this Pokemon holds, if any (docs/HALLOWEEN-EVENT-DESIGN.md). */
    public Optional<String> hauntingOf(UUID pokemonId) {
        return store.haunting(pokemonId).map(com.cobbleascend.store.HauntingRecord::hauntingId);
    }

    /** How many Haunting rolls this Pokemon has had (0 before its first). */
    public int hauntingRolls(UUID pokemonId) {
        return store.haunting(pokemonId).map(com.cobbleascend.store.HauntingRecord::rolls).orElse(0);
    }

    /** What the next Haunting roll on this Pokemon costs in Spooky Essence: 1, then 3, 5 and 8. */
    public long nextHauntingCost(UUID pokemonId) {
        return Hauntings.cost(store.haunting(pokemonId).map(com.cobbleascend.store.HauntingRecord::rolls).orElse(0));
    }

    /**
     * Spends Spooky Essence to give a Pokemon of the player's a random Haunting, or to re-roll the one it has. The price, the draw and the profile
     * revision are one store transaction, so a refusal costs nothing. Refused: not the player's own, on loan, in a battle, no profile, or too little
     * Spooky Essence.
     */
    public Hauntings.Haunting haunt(ServerPlayer player, Pokemon pokemon) {
        if (pokemon.isBattleClone() || !player.getUUID().equals(pokemon.getOwnerUUID()) || !owns(player, pokemon))
            throw new IllegalArgumentException("That Pokemon is not yours");
        if (CraftLocks.locked(pokemon)) throw new IllegalStateException(CraftLocks.REASON);
        if (com.cobblemon.mod.common.battles.BattleRegistry.INSTANCE.getBattleByParticipatingPlayer(player) != null)
            throw new IllegalStateException("Not during a battle");
        if (reconcile(pokemon).isEmpty()) throw new IllegalStateException("That Pokemon has no ascension profile");
        UUID id = pokemon.getUuid();
        long price = nextHauntingCost(id);
        try {
            var outcome = store.haunt(UUID.randomUUID(), player.getUUID(), id, store.wallet(player.getUUID()).revision());
            try {
                project(pokemon, outcome.profile());
            } catch (RuntimeException exception) {
                LOG.error("Haunting committed but {} could not be projected now; the next reconcile does it", id, exception);
            }
        } catch (CraftException exception) {
            if (exception.reason() == CraftException.Reason.INSUFFICIENT_FUNDS)
                throw new IllegalStateException("You need " + price + " Spooky Essence for this roll");
            throw exception;
        }
        return store.haunting(id).flatMap(record -> Hauntings.byId(record.hauntingId()))
                .orElseThrow(() -> new IllegalStateException("The Haunting was recorded but could not be read"));
    }

    private interface ProfileFactory { ProfileV1 create(UUID pokemonId, int level, List<String> types); }

    private ProfileV1 register(Pokemon pokemon, ProfileFactory factory) {
        if (pokemon.isBattleClone() || pokemon.getOwnerUUID() == null)
            throw new IllegalArgumentException("Only an owned, non-battle Pokemon can be registered");
        UUID id = pokemon.getUuid();
        var types = typesOf(pokemon);
        int level = Math.max(1, pokemon.getLevel());
        try {
            var outcome = store.acquire(operationId("acquire", id.toString()), id, () -> factory.create(id, level, types));
            project(pokemon, outcome.profile());
            return outcome.profile();
        } catch (StoreException exception) {
            if (exception.code() != StoreException.Code.DUPLICATE_POKEMON) throw exception;
            return reconcile(pokemon).orElseThrow(() -> exception);
        }
    }

    /**
     * The rating an unowned wild Pokemon will have if caught. For Scouter and other scouting sources only: callers
     * must have verified the scout before showing it. Assumes a wild Pokemon keeps its UUID through capture
     * (to be confirmed in a live world).
     */
    public RankedProgression.WildRating previewWild(Pokemon wild) { return previewWild(wild, 1); }

    /** As above for a wild Pokemon standing in a profile zone of this tier. */
    public RankedProgression.WildRating previewWild(Pokemon wild, int tier) {
        if (wild.getOwnerUUID() != null || wild.isBattleClone())
            throw new IllegalArgumentException("Only an unowned wild Pokemon can be previewed");
        return progression.rateWild(store.wildSecret(), wild.getUuid(), typesOf(wild), Math.max(1, wild.getLevel()), Math.max(1, tier));
    }

    /**
     * Brings one Pokemon in line with the store: imports prototype data, rewrites a stale projection and
     * catches up level milestones. Returns the canonical profile, or empty when there is none (or quarantined).
     */
    public Optional<ProfileV1> reconcile(Pokemon pokemon) {
        if (pokemon.isBattleClone()) return Optional.empty();
        UUID id = pokemon.getUuid();
        if (quarantined.contains(id)) return Optional.empty();
        String raw = pokemon.getPersistentData().contains(KEY) ? pokemon.getPersistentData().getString(KEY) : null;
        var canonical = store.profile(id);
        if (canonical.isEmpty()) {
            if (raw == null) return Optional.empty();
            return importPrototype(pokemon, raw);
        }
        var profile = canonical.get();
        if (!codec.encode(profile).equals(raw)) project(pokemon, profile);
        return Optional.of(observeLevel(pokemon, profile));
    }

    private Optional<ProfileV1> importPrototype(Pokemon pokemon, String raw) {
        UUID id = pokemon.getUuid();
        int schema;
        try {
            schema = JsonParser.parseString(raw).getAsJsonObject().get("schemaVersion").getAsInt();
        } catch (RuntimeException exception) {
            return quarantine(id, "stored data is not readable JSON", exception);
        }
        if (schema != com.cobbleascend.domain.Profile.SCHEMA)
            return quarantine(id, "stored schema " + schema + " has no canonical record in this world", null);
        int level = Math.max(1, pokemon.getLevel());
        try {
            var outcome = store.migrate(operationId("migrate", id + ":" + level), id, raw, level);
            pokemon.getPersistentData().putString(LEGACY_KEY, raw);
            project(pokemon, outcome.profile());
            LOG.info("Imported prototype profile for Pokemon {} (source preserved under {})", id, LEGACY_KEY);
            return Optional.of(outcome.profile());
        } catch (StoreException | IllegalArgumentException exception) {
            return quarantine(id, "prototype data failed validation or conflicts with the store", exception);
        }
    }

    private Optional<ProfileV1> quarantine(UUID id, String reason, Throwable cause) {
        quarantined.add(id);
        LOG.warn("Quarantined Pokemon {} (data left untouched): {}", id, reason, cause);
        return Optional.empty();
    }

    /** Awards any milestones the Pokemon's authoritative level has reached; idempotent. */
    public ProfileV1 observeLevel(Pokemon pokemon, ProfileV1 profile) {
        int level = Math.max(1, pokemon.getLevel());
        if (progression.observeLevel(profile, level).profile() == profile) return profile;
        var outcome = store.craft(CraftRequest.observeLevel(
                operationId("observe", profile.pokemonId() + ":" + profile.revision() + ":" + level),
                profile.pokemonId(), level, profile.revision()));
        project(pokemon, outcome.profile());
        return outcome.profile();
    }

    public Optional<ProfileV1> observeLevel(Pokemon pokemon) {
        return canonical(pokemon).map(profile -> observeLevel(pokemon, profile));
    }

    /**
     * Gives every profiled Pokemon in the player's party {@code points} attunement for one encounter. The operation ID is derived
     * from (encounter, Pokemon), so a repeated call or a post-crash retry changes nothing more. Failures are isolated per Pokemon.
     * Returns how many Pokemon were credited (including ones an earlier call already credited). Pokemon that fought are the ones credited
     * ({@link com.ascensionlib.battle.Participation}); one without a profile earns nothing and the player is told why.
     */
    public int awardPartyAttunement(ServerPlayer player, String encounterId, int points) {
        int credited = 0;
        // Only the Pokemon that fought count. With no battle seen (an award with no fight behind it, or after a restart) the whole party does, as before.
        var fought = com.ascensionlib.battle.Participation.consume(player.getUUID());
        var unprofiled = new ArrayList<String>();
        for (var pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
            if (pokemon.isBattleClone() || (!fought.isEmpty() && !fought.contains(pokemon.getUuid()))) continue;
            try {
                var profile = canonical(pokemon);
                if (profile.isEmpty()) {
                    if (!CraftLocks.locked(pokemon)) unprofiled.add(pokemon.getDisplayName(false).getString());
                    continue;
                }
                var outcome = store.craft(CraftRequest.awardAttunement(
                        operationId("attune", encounterId + ":" + pokemon.getUuid()), pokemon.getUuid(), points, profile.get().revision()));
                if (!outcome.replayed()) project(pokemon, outcome.profile());
                credited++;
            } catch (StoreException exception) {
                // The request hash includes the profile revision, so a retry after the profile moved on reads as a reused ID:
                // that means this encounter already credited the Pokemon.
                if (exception.code() == StoreException.Code.OPERATION_REUSED) { credited++; continue; }
                LOG.warn("Attunement for Pokemon {} in {} not recorded: {}", pokemon.getUuid(), encounterId, exception.getMessage());
            } catch (RuntimeException exception) {
                LOG.error("Attunement failed for Pokemon {}; nothing changed for it", pokemon.getUuid(), exception);
            }
        }
        if (!unprofiled.isEmpty())
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[Ascend] " + String.join(", ", unprofiled)
                    + (unprofiled.size() == 1 ? " has" : " have") + " no ascension profile, so it earned no attunement. An Ascension Sigil gives one: open it in the inspector."));
        return credited;
    }

    /**
     * The Unique line a screen shows for this profile: the Unique's name, or for a Transcendent its own name with the recipe, "Fused from
     * Unique + Unique" (the Uniques it replaced are not active any more, so the hover tells the player what it was made of). Empty for none.
     */
    public String uniqueLabel(ProfileV1 profile) {
        var fusion = store.fusion(profile.pokemonId());
        if (fusion.isPresent()) {
            try {
                var made = Transcendence.shared().resolve(fusion.get().hostSpecies(), fusion.get().hostUnique(), fusion.get().donorSpecies(), fusion.get().donorUnique());
                String from = made.uniqueIds().stream().map(id -> rules.unique(id).map(UniqueDefinition::name).orElse(id)).reduce((a, b) -> a + " + " + b).orElse("");
                return made.name() + " (fused from " + from + ")";
            } catch (RuntimeException exception) {
                return "Transcendent";
            }
        }
        return profile.unique() == null ? "" : rules.unique(profile.unique().uniqueId()).map(UniqueDefinition::name).orElse("Unknown Unique");
    }

    /** The species id the fusion book knows (the Cobblemon id without its namespace), such as {@code charizard}. */
    static String speciesId(Pokemon pokemon) { return pokemon.getSpecies().getResourceIdentifier().getPath(); }

    /** As {@link #speciesId} for code outside this package. */
    public static String speciesIdOf(Pokemon pokemon) { return speciesId(pokemon); }

    /**
     * The frozen combat form of a Pokemon: its committed profile, with the Transcendent in place of the Unique when it was fused.
     * Empty when it has no profile or a stored fusion cannot be resolved (it then fights without a Unique, never with a guess).
     */
    public Optional<CombatSnapshot> snapshot(UUID pokemonId) {
        var profile = store.profile(pokemonId);
        if (profile.isEmpty()) return Optional.empty();
        var fusion = store.fusion(pokemonId);
        if (fusion.isEmpty()) return Optional.of(CombatSnapshot.ofProfile(profile.get()));
        try {
            var fused = Transcendence.shared().resolve(fusion.get().hostSpecies(), fusion.get().hostUnique(),
                    fusion.get().donorSpecies(), fusion.get().donorUnique()).toFused();
            return Optional.of(CombatSnapshot.ofFusedProfile(profile.get(), fused));
        } catch (RuntimeException exception) {
            LOG.error("Fusion of Pokemon {} cannot be resolved; it fights without a Unique", pokemonId, exception);
            return Optional.of(new CombatSnapshot(CombatSnapshot.Source.PLAYER, pokemonId.toString(), profile.get().rarity(),
                    profile.get().catalogVersion(), profile.get().ordinarySlots(), null));
        }
    }

    /**
     * The best item-reward bonus in the player's party, in percent: the plain 777 Unique gives its fixed percent, a Transcendent
     * built on 777 gives its signature's percent times its benefit share. Read from the canonical store.
     */
    public int itemRewardPercent(ServerPlayer player) {
        int best = 0;
        for (var pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
            var profile = store.profile(pokemon.getUuid());
            if (profile.isEmpty()) continue;
            if (store.fusion(pokemon.getUuid()).isPresent()) {
                var fused = snapshot(pokemon.getUuid()).map(CombatSnapshot::transcendent);
                if (fused.isPresent() && fused.get() != null && fused.get().signature() != null)
                    best = Math.max(best, ItemQuantityBonus.percentFor(fused.get().signature(), fused.get().benefitPercent()));
            } else if (profile.get().unique() != null && profile.get().unique().uniqueId().equals("triple_seven")) {
                best = Math.max(best, ItemQuantityBonus.PERCENT);
            }
        }
        return best;
    }

    /**
     * Fuses {@code donor} into {@code host} for the player: the host is kept and becomes a Transcendent, the donor is consumed.
     * Ownership, the craft lock and "not in a battle" are checked here; the rules, revisions and price are the store's, in one
     * transaction. The donor Pokemon is removed after the commit, and the login sweep finishes that if the server stopped in
     * between. Returns the Transcendent that was made.
     */
    public Transcendence.Transcendent fuse(ServerPlayer player, Pokemon host, Pokemon donor, long hostRevision, long donorRevision, long walletRevision) {
        for (var pokemon : List.of(host, donor)) {
            if (pokemon.isBattleClone() || !player.getUUID().equals(pokemon.getOwnerUUID()) || !owns(player, pokemon))
                throw new IllegalArgumentException("Both Pokemon must be yours");
            if (CraftLocks.locked(pokemon)) throw new IllegalStateException(CraftLocks.REASON);
        }
        if (com.cobblemon.mod.common.battles.BattleRegistry.INSTANCE.getBattleByParticipatingPlayer(player) != null)
            throw new IllegalStateException("Not during a battle");
        var hostProfile = store.profile(host.getUuid()).orElseThrow(() -> new IllegalArgumentException("The host has no profile"));
        var donorProfile = store.profile(donor.getUuid()).orElseThrow(() -> new IllegalArgumentException("The donor has no profile"));
        if (hostProfile.unique() == null || donorProfile.unique() == null)
            throw new CraftException(CraftException.Reason.NO_UNIQUE, "Both Pokemon must hold a Unique");
        var transcendent = Transcendence.shared().resolve(speciesId(host), hostProfile.unique().uniqueId(),
                speciesId(donor), donorProfile.unique().uniqueId());
        var outcome = store.fuse(new com.cobbleascend.store.FuseRequest(
                operationId("fuse", host.getUuid() + ":" + donor.getUuid()), player.getUUID(), host.getUuid(), donor.getUuid(),
                speciesId(host), speciesId(donor), hostRevision, donorRevision, walletRevision));
        // The fusion is committed and paid for: nothing after this may read as a failure. A removal or projection that goes wrong is
        // logged, and the login sweep (donor) and the next reconcile (host) finish them from the store.
        try {
            removeFromStorage(player, donor);
        } catch (RuntimeException exception) {
            LOG.error("Fusion committed but donor {} could not be removed now; the next login sweep removes it", donor.getUuid(), exception);
        }
        try {
            project(host, outcome.profile());
        } catch (RuntimeException exception) {
            LOG.error("Fusion committed but host {} could not be projected now; the next reconcile does it", host.getUuid(), exception);
        }
        return transcendent;
    }

    private static boolean owns(ServerPlayer player, Pokemon pokemon) {
        var storage = Cobblemon.INSTANCE.getStorage();
        for (var owned : storage.getParty(player)) if (owned == pokemon) return true;
        for (var owned : storage.getPC(player)) if (owned == pokemon) return true;
        return false;
    }

    private static void removeFromStorage(ServerPlayer player, Pokemon pokemon) {
        var storage = Cobblemon.INSTANCE.getStorage();
        if (!storage.getParty(player).remove(pokemon) && !storage.getPC(player).remove(pokemon))
            LOG.warn("Consumed Pokemon {} was in neither party nor PC of {}", pokemon.getUuid(), player.getGameProfile().getName());
    }

    /** Whether this Pokemon is a Transcendent (its Unique cannot be replaced) or was consumed by a fusion. */
    public boolean isFused(UUID pokemonId) {
        return store.fusion(pokemonId).isPresent() || store.consumedBy(pokemonId).isPresent();
    }

    /**
     * Operator grant of lifetime attunement to one Pokemon. Unlike the encounter awards this is meant to be repeatable, so every call
     * is its own operation (a fresh id): running the command twice grants twice.
     */
    public ProfileV1 grantAttunement(Pokemon pokemon, int points) {
        var profile = canonical(pokemon).orElseThrow(() -> new IllegalArgumentException("That Pokemon has no ascension profile"));
        if (pokemon.isBattleClone() || pokemon.getOwnerUUID() == null) throw new IllegalArgumentException("Only an owned, non-battle Pokemon");
        var outcome = store.craft(CraftRequest.awardAttunement(UUID.randomUUID(), pokemon.getUuid(), points, profile.revision()));
        project(pokemon, outcome.profile());
        return outcome.profile();
    }

    /** Login sweep over the player's party and PC. Failures are isolated per Pokemon. */
    public int sweep(ServerPlayer player) {
        int handled = 0;
        var storage = Cobblemon.INSTANCE.getStorage();
        var all = new ArrayList<Pokemon>();
        storage.getParty(player).forEach(all::add);
        storage.getPC(player).forEach(all::add);
        for (var pokemon : all) {
            try {
                // A donor whose fusion committed but whose removal was cut short: finish it, never let it fight or craft again.
                if (store.consumedBy(pokemon.getUuid()).isPresent()) {
                    removeFromStorage(player, pokemon);
                    LOG.info("Removed Pokemon {} consumed by an earlier fusion", pokemon.getUuid());
                    continue;
                }
                if (reconcile(pokemon).isPresent()) handled++;
            } catch (RuntimeException exception) {
                LOG.error("Reconcile failed for Pokemon {}; data preserved", pokemon.getUuid(), exception);
            }
        }
        return handled;
    }

    /** Writes the schema-1 projection and marks the Pokemon dirty; the store remains authoritative. */
    private void project(Pokemon pokemon, ProfileV1 profile) {
        pokemon.getPersistentData().putString(KEY, codec.encode(profile));
        pokemon.onChange(null);
        store.markProjected(profile.profileId(), profile.revision());
    }
}
