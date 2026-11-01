package com.ascensionlib.scout;

import com.ascensionlib.AscensionApi;
import com.ascensionlib.AscensionLib;
import com.cobbleascend.domain.Rules;
import com.cobbleascend.domain.v1.CombatSnapshot;
import com.cobbleascend.domain.v1.EnemyGenerator;
import com.cobbleascend.domain.v1.EnemySpec;
import com.cobbleascend.domain.v1.EnemyTiers;
import com.cobbleascend.domain.v1.MaterialId;
import com.cobbleascend.domain.v1.ScoutView;
import com.cobbleascend.store.ScoutingService;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.pokemon.Species;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The enemies of the encounters that are running right now, who may scout them, and what each player has been shown.
 *
 * <p>An encounter's owner (CobbleTowers, CobbleRaids) declares each enemy once, through
 * {@link com.ascensionlib.AscensionEncounters}; the library generates its rarity and modifiers from the encounter ID
 * (so every caller and every retry sees the same enemy), keeps it in memory only, and shows a player nothing about it
 * until that player (or, for a boss, the party) spends a Scouter on it. Nothing here outlives the encounter or the
 * server: an enemy is never a profile.
 *
 * <p>A player's screen shows the most recently declared encounter they take part in. All calls are on the server thread.
 */
public final class ScoutEncounters {
    private static final Logger LOG = LoggerFactory.getLogger(AscensionLib.MOD_ID);

    /** Encounters this old are dropped when a new one is declared, in case an owner never reported its end. */
    private static final long STALE_MILLIS = 2L * 60 * 60 * 1000;

    private record Enemy(CombatSnapshot snapshot, String name, String speciesId, int level, List<String> types, List<Integer> baseStats,
                         boolean boss) {}

    private static final class Encounter {
        final String id;
        final long declaredAt = System.currentTimeMillis();
        final Set<UUID> participants = new LinkedHashSet<>();
        final Map<Integer, Enemy> enemies = new TreeMap<>();

        Encounter(String id) { this.id = id; }
    }

    private static final Map<String, Encounter> ACTIVE = new LinkedHashMap<>();
    /** Players whose next battle uses an encounter's enemies (an empty id means: explicitly native). */
    private static final Map<UUID, String> ARMED = new java.util.HashMap<>();
    private static volatile MinecraftServer server;
    private static EnemyTiers tiers;

    private ScoutEncounters() {}

    public static void attach(MinecraftServer started) {
        server = started;
    }

    /** Drops the cached enemy tiers, so the next declaration reads the override file again ({@code /ascend admin reload}). */
    public static synchronized void reloadTiers() { tiers = null; }

    /** The enemy tiers in force: {@code config/ascensionlib-enemy-tiers.json} when present and valid, else the shipped ones. */
    private static EnemyTiers tiers() {
        if (tiers == null) {
            var problems = new ArrayList<String>();
            tiers = EnemyTiers.loadOrDefaults(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("ascensionlib-enemy-tiers.json"), problems);
            problems.forEach(problem -> LOG.warn("Enemy tiers: {}", problem));
        }
        return tiers;
    }

    public static synchronized void detach() {
        server = null;
        ACTIVE.clear();
        ARMED.clear();
    }

    /** See {@link com.ascensionlib.AscensionEncounters#declareEnemy}. */
    public static synchronized String declareEnemy(String encounterId, Collection<UUID> participants, int enemyIndex,
                                                   String tierId, boolean boss, String uniqueId, String speciesId,
                                                   int level) {
        var service = AscensionApi.service();
        var scouting = AscensionApi.scouting();
        if (service.isEmpty() || scouting.isEmpty() || server == null) return "DISABLED";
        if (encounterId == null || encounterId.isBlank() || encounterId.contains("|") || enemyIndex < 0 || level < 1
                || participants.isEmpty()) return "INVALID";
        ResourceLocation id = ResourceLocation.tryParse(speciesId);
        Species species = id == null ? null : PokemonSpecies.INSTANCE.getByIdentifier(id);
        if (species == null) return "UNKNOWN_SPECIES";

        var types = new ArrayList<String>();
        types.add(species.getPrimaryType().getName().toLowerCase(java.util.Locale.ROOT));
        if (species.getSecondaryType() != null) types.add(species.getSecondaryType().getName().toLowerCase(java.util.Locale.ROOT));
        if (!Rules.TYPES.containsAll(types)) return "INVALID";

        purgeStale();
        Encounter encounter = ACTIVE.computeIfAbsent(encounterId, Encounter::new);
        encounter.participants.addAll(participants);
        if (!encounter.enemies.containsKey(enemyIndex)) {
            try {
                var all = tiers();
                // With Threat on, a legacy tier ID (trial_rank_N, boss) fights as its Threat band; with it off, as before.
                var tier = all.resolve(tierId, com.cobbleascend.domain.v1.Tunables.current().threat().enabled());
                var spec = new EnemySpec(tier, boss, uniqueId == null || uniqueId.isBlank() ? null : uniqueId);
                var snapshot = new EnemyGenerator(service.get().rules(), all.uniqueExclusions()).generate(encounterId, enemyIndex, spec, types);
                encounter.enemies.put(enemyIndex, new Enemy(snapshot, species.getName(), species.getResourceIdentifier().toString(), level,
                        List.copyOf(types), baseStats(species), boss));
            } catch (RuntimeException exception) {
                LOG.warn("Could not declare enemy {} of encounter {}: {}", enemyIndex, encounterId, exception.toString());
                if (encounter.enemies.isEmpty()) ACTIVE.remove(encounterId);
                return "INVALID";
            }
        }
        encounter.participants.forEach(ScoutEncounters::push);
        return "DECLARED";
    }

    /** See {@link com.ascensionlib.AscensionEncounters#armBattle}. */
    public static synchronized void armBattle(Collection<UUID> players, String encounterId) {
        String id = encounterId == null ? "" : encounterId;
        for (UUID player : players) ARMED.put(player, id);
    }

    public static synchronized void disarmBattle(Collection<UUID> players) {
        players.forEach(ARMED::remove);
    }

    /**
     * The enemies a battle of these players should fight with, in declared order: {@code null} when none of them is armed
     * (the battle is then rated by its own rules), an empty list when an armed encounter is explicitly native or was never
     * declared (so a failed declaration never turns a tower opponent into a randomly rated wild Pokemon).
     */
    public static synchronized List<CombatSnapshot> armedEnemies(Collection<UUID> players) {
        for (UUID player : players) {
            String id = ARMED.get(player);
            if (id == null) continue;
            Encounter encounter = id.isEmpty() ? null : ACTIVE.get(id);
            if (encounter == null) return List.of();
            return encounter.enemies.values().stream().map(Enemy::snapshot).toList();
        }
        return null;
    }

    /** Forgets the encounter and every reveal of it; its participants' screens go back to nothing. */
    public static synchronized void end(String encounterId) {
        Encounter ended = ACTIVE.remove(encounterId);
        // A player still armed for a finished encounter would otherwise fight natively (no wild rating either) until the server restarts.
        ARMED.values().removeIf(encounterId::equals);
        AscensionApi.scouting().ifPresent(scouting -> scouting.endEncounter(encounterId));
        if (ended != null) ended.participants.forEach(ScoutEncounters::push);
    }

    public static synchronized int active() {
        return ACTIVE.size();
    }

    private static void purgeStale() {
        long cutoff = System.currentTimeMillis() - STALE_MILLIS;
        ACTIVE.values().removeIf(encounter -> {
            if (encounter.declaredAt >= cutoff) return false;
            AscensionApi.scouting().ifPresent(scouting -> scouting.endEncounter(encounter.id));
            LOG.warn("Dropped scouting encounter {} that was never reported ended", encounter.id);
            return true;
        });
    }

    static List<Integer> baseStats(Species species) {
        var stats = species.getBaseStats();
        return List.of(
                stats.getOrDefault(Stats.HP, 0), stats.getOrDefault(Stats.ATTACK, 0),
                stats.getOrDefault(Stats.DEFENCE, 0), stats.getOrDefault(Stats.SPECIAL_ATTACK, 0),
                stats.getOrDefault(Stats.SPECIAL_DEFENCE, 0), stats.getOrDefault(Stats.SPEED, 0));
    }

    // ---- what a player sees ----------------------------------------------------------------------------

    /** The latest declared encounter this player takes part in, if any. A player's roaming-wild session is not one of these. */
    private static Optional<Encounter> currentFor(UUID player) {
        Encounter found = null;
        for (Encounter encounter : ACTIVE.values()) if (!encounter.id.startsWith(WILD_PREFIX) && encounter.participants.contains(player)) found = encounter;
        return Optional.ofNullable(found);
    }

    /** The state to show this player now. Detail is attached only to enemies this player may see. */
    public static synchronized ScoutPayloads.State stateFor(UUID player) {
        return stateOf(player, currentFor(player));
    }

    /** The player's roaming-wild session: the wild Pokemon they have looked at and scouted most recently (at most {@link #WILD_CAP}). */
    public static synchronized ScoutPayloads.State wildStateFor(UUID player) {
        return stateOf(player, Optional.ofNullable(ACTIVE.get(wildId(player))));
    }

    private static ScoutPayloads.State stateOf(UUID player, Optional<Encounter> current) {
        int scouters = AscensionApi.store().map(store -> (int) Math.min(Integer.MAX_VALUE,
                store.wallet(player).balance(MaterialId.SCOUTER))).orElse(0);
        var scouting = AscensionApi.scouting();
        var rules = AscensionApi.rules();
        if (current.isEmpty() || scouting.isEmpty() || rules.isEmpty()) return ScoutPayloads.State.none(scouters);
        Encounter encounter = current.get();
        var entries = new ArrayList<ScoutPayloads.Entry>();
        for (Enemy enemy : encounter.enemies.values()) {
            Optional<ScoutPayloads.Detail> detail = Optional.empty();
            if (scouting.get().isRevealed(player, encounter.id, enemy.snapshot().subjectId())) {
                var view = ScoutView.of(enemy.snapshot(), rules.get());
                var slots = view.slots().stream()
                        .map(line -> new ScoutPayloads.Slot(line.category(), line.name(), line.rank(), line.rolledValue(), -1, -1, "", line.theme()))
                        .toList();
                detail = Optional.of(new ScoutPayloads.Detail(enemy.level(), enemy.types(), enemy.baseStats(),
                        view.rarityId(), view.uniqueName(), slots));
            }
            entries.add(new ScoutPayloads.Entry(enemy.snapshot().subjectId(), enemy.name(), detail, enemy.speciesId()));
        }
        return new ScoutPayloads.State(encounter.id, scouters, entries);
    }

    /** Sends the player their current state, if they are online. */
    public static void push(UUID player) {
        var running = server;
        if (running == null) return;
        ServerPlayer online = running.getPlayerList().getPlayer(player);
        if (online != null) com.ascensionlib.web.HudWeb.pushScout(online, stateFor(player));
    }

    // ---- using a Scouter ---------------------------------------------------------------------------------

    /**
     * Spends one Scouter on an enemy of the encounter. A boss is revealed to the whole party (the user's wallet pays);
     * any other enemy to the user only. Returns a line for the player: what happened, never an exception.
     */
    public static synchronized String use(UUID player, String encounterId, String subjectId) {
        var scouting = AscensionApi.scouting();
        Encounter encounter = ACTIVE.get(encounterId);
        if (scouting.isEmpty()) return "Ascension progression is not running.";
        if (encounter == null || !encounter.participants.contains(player)) return "That encounter is over.";
        Enemy enemy = encounter.enemies.values().stream()
                .filter(candidate -> candidate.snapshot().subjectId().equals(subjectId)).findFirst().orElse(null);
        if (enemy == null) return "That enemy is not in this encounter.";

        var scope = enemy.boss() ? ScoutingService.Scope.SHARED : ScoutingService.Scope.PERSONAL;
        ScoutingService.Result result;
        try {
            result = scouting.get().use(player, encounterId, enemy.snapshot(), scope);
        } catch (RuntimeException exception) {
            LOG.error("Scouter use failed for {} on {}", player, subjectId, exception);
            return "The Scouter could not be used right now. You were not charged.";
        }
        switch (result.status()) {
            case REVEALED -> {
                if (scope == ScoutingService.Scope.SHARED) encounter.participants.forEach(ScoutEncounters::push);
                else if (encounterId.startsWith(WILD_PREFIX)) refreshWild(player);
                else push(player);
                return "Scouted " + enemy.name() + (scope == ScoutingService.Scope.SHARED ? " for your whole party." : ".");
            }
            case ALREADY_SCOUTED -> { if (encounterId.startsWith(WILD_PREFIX)) refreshWild(player); else push(player); return enemy.name() + " is already scouted."; }
            default -> { if (encounterId.startsWith(WILD_PREFIX)) refreshWild(player); else push(player); return "You have no Scouters."; }
        }
    }

    // ---- roaming wild Pokemon ------------------------------------------------------------------------------

    /** Wild sessions are encounters of this prefix, one per player: an enemy per wild Pokemon they have looked at. */
    static final String WILD_PREFIX = "wild:";
    /** How many wild Pokemon one player's session remembers; looking at a seventh forgets the oldest. */
    public static final int WILD_CAP = 6;

    private static String wildId(UUID player) { return WILD_PREFIX + player; }

    /** The outcome of putting a wild Pokemon into a player's session: {@code ok}, a line for the player, and the ids to scout it with. */
    public record WildDeclared(boolean ok, String message, String encounterId, String subjectId, String name) {
        static WildDeclared no(String message) { return new WildDeclared(false, message, "", "", ""); }
    }

    /**
     * Puts the wild Pokemon the player is looking at into their wild session, so a Scouter can reveal it ({@link #use}): the rating shown is
     * the one it will have in a battle and when caught, at its zone's tier. Any unowned Pokemon has a rating, except in a dimension the
     * configuration turns off (tier 0), where wild Pokemon are native and there is nothing to scout. Nothing is spent here. Server thread.
     */
    public static synchronized WildDeclared declareWild(net.minecraft.server.level.ServerPlayer player,
                                                        com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        var service = AscensionApi.service();
        if (service.isEmpty() || AscensionApi.scouting().isEmpty() || server == null) return WildDeclared.no("Ascension progression is not running.");
        var pokemon = entity.getPokemon();
        if (pokemon.getOwnerUUID() != null) return WildDeclared.no("That Pokémon belongs to someone.");
        int tier = com.ascensionlib.battle.ProfileZones.tierOf(entity.level().dimension().location());
        if (tier < 1) return WildDeclared.no("Profiles are switched off in this dimension, so wild Pokémon here have no Ascension rating.");
        var species = pokemon.getSpecies();
        var types = new ArrayList<String>();
        types.add(species.getPrimaryType().getName().toLowerCase(java.util.Locale.ROOT));
        if (species.getSecondaryType() != null) types.add(species.getSecondaryType().getName().toLowerCase(java.util.Locale.ROOT));
        if (!Rules.TYPES.containsAll(types)) return WildDeclared.no("That Pokémon cannot be rated.");
        String id = wildId(player.getUUID());
        String subject = pokemon.getUuid().toString();
        Encounter session = ACTIVE.computeIfAbsent(id, Encounter::new);
        session.participants.add(player.getUUID());
        boolean known = session.enemies.values().stream().anyMatch(enemy -> enemy.snapshot().subjectId().equals(subject));
        if (!known) {
            try {
                var rating = service.get().previewWild(pokemon, tier);
                var snapshot = new CombatSnapshot(CombatSnapshot.Source.ENEMY, subject, rating.rarity(), service.get().rules().catalogVersion(), rating.slots(), null);
                int index = session.enemies.isEmpty() ? 0 : ((TreeMap<Integer, Enemy>) session.enemies).lastKey() + 1;
                session.enemies.put(index, new Enemy(snapshot, species.getName(), species.getResourceIdentifier().toString(), Math.max(1, pokemon.getLevel()),
                        List.copyOf(types), baseStats(species), false));
                while (session.enemies.size() > WILD_CAP) session.enemies.remove(((TreeMap<Integer, Enemy>) session.enemies).firstKey());
            } catch (RuntimeException exception) {
                LOG.warn("Could not rate wild {} for {}: {}", subject, player.getUUID(), exception.toString());
                return WildDeclared.no("That Pokémon cannot be rated right now.");
            }
        }
        return new WildDeclared(true, "", id, subject, species.getName());
    }

    /** Sends the player's wild session to their open inspect page. */
    private static void refreshWild(UUID player) {
        var running = server;
        if (running == null) return;
        var online = running.getPlayerList().getPlayer(player);
        if (online != null) InspectWeb.refreshWild(online);
    }

    /** Forgets a player's wild session and its reveals (they left). Safe when there is none. */
    public static synchronized void endWild(UUID player) {
        String id = wildId(player);
        if (ACTIVE.remove(id) != null) AscensionApi.scouting().ifPresent(scouting -> scouting.endEncounter(id));
    }
}
