package com.ascensionlib.research;

import com.ascensionlib.AscensionApi;
import com.ascensionlib.laboratory.Laboratories;
import com.cobbleascend.domain.v1.BenchStudies;
import com.cobbleascend.domain.v1.FieldResearch;
import com.cobbleascend.domain.v1.LaboratoryLoot;
import com.cobbleascend.domain.v1.ResearchException;
import com.cobbleascend.store.ProgressionStore;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Field Research in the running game (docs/FIELD-RESEARCH-DESIGN.md, step 3): the species catalog, research from won raids and Laboratory
 * searches, the Bench locks, and starting, collecting and recalling studies. Every payment goes through the store under a deterministic operation,
 * so a repeated callback pays once. Nothing here ever throws into a caller (CobbleRaids, the Laboratory handler): a failure is logged.
 */
public final class FieldResearchRuntime {
    private static final Logger LOG = LoggerFactory.getLogger("AscensionLib/Research");
    private static volatile MinecraftServer server;

    private FieldResearchRuntime() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(started -> {
            server = started;
            ResearchCatalog.rebuild();
            try {
                ResearchLocks.reload();
            } catch (RuntimeException failure) {
                LOG.error("Field Research could not load the Pokemon on the Bench", failure);
            }
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((reloaded, resources, success) -> { if (success) ResearchCatalog.rebuild(); });
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> { server = null; ResearchLocks.clear(); });
        ResearchLocks.register();
    }

    private static Optional<ProgressionStore> store() { return AscensionApi.store(); }

    // ---- raids ----------------------------------------------------------------------------------------------------------------------------

    /**
     * The raid tier a boss was, from what CobbleRaids declares (species and level; it does not pass its tier): starter bosses are level 25,
     * powerhouse 50, legendary and mythical 100, a renowned boss ten more. Mythical and legendary are told apart by Cobblemon's species label.
     */
    static FieldResearch.RaidTier tierOf(String species, int level) {
        if (level <= 40) return FieldResearch.RaidTier.STARTER;
        if (level <= 75) return FieldResearch.RaidTier.POWERHOUSE;
        return ResearchCatalog.mythical(species) ? FieldResearch.RaidTier.MYTHICAL : FieldResearch.RaidTier.LEGENDARY;
    }

    /**
     * A won raid (called from {@code AscensionRewards.settleRaidAttunement}, which CobbleRaids calls before it ends the encounter): every
     * participant gets research for the boss's species by tier and one Boss Sample, keyed by the encounter (one per boss) and the player.
     */
    public static void raidWon(String encounterId, Collection<UUID> players) {
        try {
            var boss = com.ascensionlib.scout.ScoutEncounters.declaredBoss(encounterId);
            var store = store();
            if (boss.isEmpty() || store.isEmpty()) return;
            var catalog = ResearchCatalog.get();
            String species = ResearchCatalog.resolve(boss.get().speciesId()).orElse(null);
            if (species == null) {
                LOG.debug("Raid {} boss {} is not a researchable species", encounterId, boss.get().speciesId());
                return;
            }
            var tier = tierOf(species, boss.get().level());
            for (var player : players) {
                try {
                    int before = store.get().researchLedger(player).pointsOf(catalog, species);
                    var outcome = store.get().settleResearchRaid(FieldResearch.operation("raid", encounterId, player), player, catalog, species, tier);
                    if (outcome.replayed()) continue;
                    int gained = store.get().researchLedger(player).pointsOf(catalog, species) - before;
                    tell(player, Component.literal("[Research] ").withStyle(ChatFormatting.AQUA)
                            .append(Component.literal(display(species) + ": " + (gained > 0 ? "+" + gained + " research" : "research full")
                                    + ", 1 Boss Sample.").withStyle(ChatFormatting.WHITE)));
                } catch (RuntimeException failure) {
                    LOG.error("Could not pay raid research to {} for {}", player, encounterId, failure);
                }
            }
        } catch (RuntimeException failure) {
            LOG.error("Raid research for {} failed", encounterId, failure);
        }
    }

    // ---- Laboratories ---------------------------------------------------------------------------------------------------------------------

    /**
     * A player's search of a Laboratory container (after its materials were paid or replayed): the Research Terminal gives a Data Drive, a
     * Specimen Locker research on a lab-guard species (drawn per container and player). Once per (container, player), however often it is
     * searched.
     */
    public static void labSearched(ServerPlayer player, ServerLevel level, BlockPos pos, LaboratoryLoot.Container container) {
        try {
            var store = store();
            if (store.isEmpty()) return;
            var catalog = ResearchCatalog.get();
            String key = Laboratories.containerKey(level, pos);
            switch (container) {
                case TERMINAL -> {
                    var outcome = store.get().grantResearchItem(FieldResearch.operation("lab-drive", key, player.getUUID()), player.getUUID(), catalog,
                            FieldResearch.Item.DATA_DRIVE, "", 1);
                    if (!outcome.replayed()) player.sendSystemMessage(Component.literal("[Research] ").withStyle(ChatFormatting.AQUA)
                            .append(Component.literal("You recovered a Data Drive. Use it on any species with /ascend research drive <species>.")
                                    .withStyle(ChatFormatting.WHITE)));
                }
                case SPECIMEN -> FieldResearch.lockerSpecies(key, player.getUUID(), catalog).ifPresent(species -> {
                    int before = store.get().researchLedger(player.getUUID()).pointsOf(catalog, species);
                    var outcome = store.get().grantResearch(FieldResearch.operation("lab-locker", key, player.getUUID()), player.getUUID(), catalog,
                            species, FieldResearch.Source.LAB_LOCKER.points, "lab_locker");
                    int gained = store.get().researchLedger(player.getUUID()).pointsOf(catalog, species) - before;
                    if (!outcome.replayed()) player.sendSystemMessage(Component.literal("[Research] ").withStyle(ChatFormatting.AQUA)
                            .append(Component.literal("Specimen notes on " + display(species) + ": "
                                    + (gained > 0 ? "+" + gained + " research." : "research already full.")).withStyle(ChatFormatting.WHITE)));
                });
                case SUPPLY -> { }
            }
        } catch (RuntimeException failure) {
            LOG.error("Laboratory research for {} failed", player.getGameProfile().getName(), failure);
        }
    }

    // ---- Bench studies (the Bench block calls these; step 3 exposes them as admin commands for testing) -----------------------------------

    /**
     * Starts a study of one of the player's own Pokemon. The checks that need the game are made here (owned, in party or PC, not a battle
     * clone, not in a battle, not lent); the store makes the rest. Throws {@link ResearchException} or {@link IllegalStateException} with a
     * message for the player.
     */
    public static BenchStudies.Study start(ServerPlayer player, Pokemon pokemon, BenchStudies.Kind kind) {
        var store = store().orElseThrow(() -> new IllegalStateException("Ascension progression is not running."));
        if (pokemon.isBattleClone() || !player.getUUID().equals(pokemon.getOwnerUUID()) || !owns(player, pokemon))
            throw new IllegalStateException("That Pokemon must be yours, in your party or PC.");
        if (com.cobblemon.mod.common.battles.BattleRegistry.INSTANCE.getBattleByParticipatingPlayer(player) != null)
            throw new IllegalStateException("Not during a battle.");
        if (com.ascensionlib.CraftLocks.locked(pokemon)) throw new IllegalStateException(com.ascensionlib.CraftLocks.REASON);
        int zoneTier = pokemon.getPersistentData().getInt(BenchStudies.ZONE_TIER_KEY);
        var job = UUID.randomUUID();
        store.startResearchStudy(UUID.randomUUID(), player.getUUID(), ResearchCatalog.get(), kind, pokemon.getUuid(),
                speciesOf(pokemon), zoneTier, job);
        ResearchLocks.lock(pokemon.getUuid());
        return store.researchStudy(player.getUUID()).orElseThrow();
    }

    /** What collecting did. */
    public record Collected(BenchStudies.Study study, boolean paid, String message) {}

    /**
     * Collects the player's finished study. If the Pokemon is no longer in their party or PC (sold, moved by another mod), the study ends unpaid:
     * the Bench studies a Pokemon the player keeps.
     */
    public static Collected collect(ServerPlayer player) {
        var store = store().orElseThrow(() -> new IllegalStateException("Ascension progression is not running."));
        var study = store.researchStudy(player.getUUID()).orElseThrow(() -> new ResearchException(ResearchException.Reason.NOTHING_TO_COLLECT, ""));
        if (!ownsUuid(player, study.pokemon())) {
            store.recallResearchStudy(UUID.randomUUID(), player.getUUID());
            ResearchLocks.unlock(study.pokemon());
            return new Collected(study, false, "The studied Pokemon is no longer yours, so the study ended with nothing learned.");
        }
        var catalog = ResearchCatalog.get();
        int before = study.kind() == BenchStudies.Kind.ANALYSIS ? store.researchLedger(player.getUUID()).bossPercentOf(study.species())
                : store.researchLedger(player.getUUID()).pointsOf(catalog, study.species());
        store.collectResearchStudy(player.getUUID(), catalog);
        ResearchLocks.unlock(study.pokemon());
        var ledger = store.researchLedger(player.getUUID());
        String message = study.kind() == BenchStudies.Kind.ANALYSIS
                ? display(study.species()) + " boss research: " + before + "% -> " + ledger.bossPercentOf(study.species()) + "%."
                : display(study.species()) + ": +" + (ledger.pointsOf(catalog, study.species()) - before) + " research (family level "
                        + ledger.levelForSpecies(catalog, study.species()) + ").";
        return new Collected(study, true, message);
    }

    /** Recalls the player's Pokemon from the Bench early: nothing is paid. */
    public static BenchStudies.Study recall(ServerPlayer player) {
        var store = store().orElseThrow(() -> new IllegalStateException("Ascension progression is not running."));
        var study = store.researchStudy(player.getUUID()).orElseThrow(() -> new ResearchException(ResearchException.Reason.NOTHING_TO_COLLECT, ""));
        store.recallResearchStudy(UUID.randomUUID(), player.getUUID());
        ResearchLocks.unlock(study.pokemon());
        return study;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------------------------------

    /** A Pokemon's species in catalog form (namespaced unless it is Cobblemon's own). */
    public static String speciesOf(Pokemon pokemon) {
        var location = pokemon.getSpecies().getResourceIdentifier();
        return ResearchCatalog.speciesId(location.getNamespace(), location.getPath());
    }

    private static boolean owns(ServerPlayer player, Pokemon pokemon) { return ownsUuid(player, pokemon.getUuid()); }

    private static boolean ownsUuid(ServerPlayer player, UUID pokemon) {
        var storage = Cobblemon.INSTANCE.getStorage();
        for (var p : storage.getParty(player)) if (p.getUuid().equals(pokemon)) return true;
        for (var p : storage.getPC(player)) if (p.getUuid().equals(pokemon)) return true;
        return false;
    }

    /** A species id as players read it: {@code mr_mime} -> {@code Mr Mime}, {@code mymod:fakemon} -> {@code Fakemon}. */
    public static String display(String species) {
        if (species == null || species.isEmpty()) return "?";
        String name = species.substring(species.indexOf(':') + 1);                       // an addon species reads by its name alone
        if (name.isEmpty()) return "?";
        var words = name.replace('_', ' ').replace('-', ' ').split(" ");
        var out = new StringBuilder();
        for (var word : words) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static void tell(UUID player, Component message) {
        var s = server;
        if (s == null) return;
        var online = s.getPlayerList().getPlayer(player);
        if (online != null) online.sendSystemMessage(message);
    }
}
