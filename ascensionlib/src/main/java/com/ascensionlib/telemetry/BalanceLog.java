package com.ascensionlib.telemetry;

import com.ascensionlib.AscensionApi;
import com.ascensionlib.ProfileService;
import com.ascensionlib.Profiler;
import com.cobbleascend.domain.v1.ProfileV1;
import com.cobbleascend.domain.v1.Tunables;
import com.cobbleascend.store.Committed;
import com.cobbleascend.store.CraftRequest;
import com.cobbleascend.store.Outcome;
import com.cobbleascend.store.ProgressionStore;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What goes into the telemetry log, one method per event, so the call sites stay one line and the list of events is in one place. The events answer
 * the questions balancing asks:
 * <ul>
 * <li>{@code ledger}: every committed store operation, with the materials that left and entered the wallet and why (income by source, spending by use).</li>
 * <li>{@code craft} / {@code craft_refused}: what a craft did to a slot (rank and value before and after) and why one was turned down.</li>
 * <li>{@code acquire}: each new profile's origin, tier, dimension, rarity and modifiers (the rarity table as it plays).</li>
 * <li>{@code settlement} / {@code encounter}: how each raid, tower boss, trial and dungeon run ended and what it was worth (win rates by tier).</li>
 * <li>{@code join} / {@code leave}: a player's wallet and party at both ends of a session, and how long it lasted.</li>
 * <li>{@code economy}: the total of each material across all wallets every half hour (inflation).</li>
 * <li>{@code perf}: once a minute, the server tick, store transactions, page requests and the pages' own render times (see {@link Profiler}).</li>
 * </ul>
 */
public final class BalanceLog {
    private static final Logger LOG = LoggerFactory.getLogger("ascensionlib");
    private static final int PERF_TICKS = 20 * 60;
    private static final int ECONOMY_TICKS = 20 * 60 * 30;

    private static final Map<UUID, Long> JOINED = new ConcurrentHashMap<>();
    private static long tickStartedAt;
    private static int ticks;

    private BalanceLog() {}

    // --- lifecycle -------------------------------------------------------------------------------------

    /** Writes the server_start event: the build, and every configured number, so a log is read against the balance it was played under. */
    public static void serverStarted(MinecraftServer server, List<String> configChanges) {
        ticks = 0;
        Telemetry.log("server_start", e -> {
            e.addProperty("mod", modVersion());
            e.addProperty("minecraft", server.getServerVersion());
            e.add("config", Tunables.current().toJson());
            var changed = new JsonArray();
            configChanges.forEach(changed::add);
            e.add("configChanges", changed);
        });
    }

    /** Taps the store so every committed operation becomes a ledger event. */
    public static void attach(ProgressionStore store) { store.onCommit(BalanceLog::ledger); }

    public static void serverStopping(MinecraftServer server) {
        perf(server);
        economy(server);
        Telemetry.log("server_stop", e -> e.addProperty("players", server.getPlayerCount()));
    }

    private static String modVersion() {
        return FabricLoader.getInstance().getModContainer("ascensionlib").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    // --- the clock: server tick, minute and half-hour snapshots ----------------------------------------

    public static void tickStart(MinecraftServer server) { tickStartedAt = System.nanoTime(); }

    public static void tickEnd(MinecraftServer server) {
        Profiler.record("server.tick", System.nanoTime() - tickStartedAt);
        ticks++;
        if (ticks % PERF_TICKS == 0) perf(server);
        if (ticks % ECONOMY_TICKS == 0) economy(server);
    }

    private static void perf(MinecraftServer server) {
        var summaries = Profiler.drain();
        if (summaries.isEmpty()) return;
        Telemetry.log("perf", e -> {
            var rt = Runtime.getRuntime();
            e.addProperty("players", server.getPlayerCount());
            e.addProperty("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) >> 20);
            e.addProperty("heapMaxMb", rt.maxMemory() >> 20);
            var metrics = new JsonObject();
            for (var s : summaries) {
                var m = new JsonObject();
                m.addProperty("n", s.count());
                m.addProperty("avg", Profiler.round(s.avg()));
                m.addProperty("p95", Profiler.round(s.p95()));
                m.addProperty("max", Profiler.round(s.max()));
                if (!s.unit().equals("ms")) m.addProperty("unit", s.unit());
                metrics.add(s.label(), m);
            }
            e.add("metrics", metrics);
        });
    }

    private static void economy(MinecraftServer server) {
        if (!Telemetry.enabled()) return;
        AscensionApi.store().ifPresent(store -> {
            try {
                var supply = store.materialSupply();
                int wallets = store.walletCount();
                Telemetry.log("economy", e -> {
                    e.addProperty("online", server.getPlayerCount());
                    e.addProperty("wallets", wallets);
                    e.add("supply", Telemetry.materials(supply));
                });
            } catch (RuntimeException exception) {
                LOG.debug("Economy snapshot skipped: {}", exception.toString());
            }
        });
    }

    // --- store operations -----------------------------------------------------------------------------

    private static void ledger(Committed c) {
        Profiler.record("store." + c.kind().name().toLowerCase(java.util.Locale.ROOT), c.nanos());
        Telemetry.log("ledger", e -> {
            e.addProperty("kind", c.kind().name());
            e.addProperty("op", c.operationId().toString());
            if (c.playerId() != null) e.addProperty("player", Telemetry.playerKey(c.playerId()));
            if (c.pokemonId() != null) e.addProperty("pokemon", c.pokemonId().toString());
            if (!c.detail().isEmpty()) e.addProperty("detail", c.detail());
            if (!c.spent().isEmpty()) e.add("spent", Telemetry.materials(c.spent()));
            if (!c.gained().isEmpty()) e.add("gained", Telemetry.materials(c.gained()));
            if (c.creditsSpent() > 0) e.addProperty("credits", c.creditsSpent());
            if (c.profile() != null) e.addProperty("rarity", c.profile().rarity().id());
            if (c.wallet() != null) e.addProperty("walletRevision", c.wallet().revision());
            e.addProperty("ms", Profiler.round(c.nanos() / 1e6));
        });
    }

    // --- crafts ----------------------------------------------------------------------------------------

    /** A craft that committed: the slot it touched before and after, so a success rate or a value gain is a column, not a calculation. */
    public static void craft(ServerPlayer player, CraftRequest request, ProfileV1 before, Outcome outcome) {
        Telemetry.log("craft", e -> {
            e.addProperty("kind", request.kind().name());
            e.addProperty("player", Telemetry.playerKey(player.getUUID()));
            e.addProperty("pokemon", request.pokemonId().toString());
            e.addProperty("rarity", before.rarity().id());
            e.addProperty("replayed", outcome.replayed());
            if (request.target() != null) e.addProperty("slot", request.target());
            var after = outcome.profile();
            if (after != null && request.target() != null) {
                var was = before.slot(request.target());
                var now = after.slot(request.target());
                was.ifPresent(s -> { e.addProperty("affix", s.affixId()); e.addProperty("rankBefore", s.rank()); e.addProperty("valueBefore", s.rolledValue()); e.addProperty("floorBefore", s.floor()); });
                now.ifPresent(s -> {
                    if (was.isEmpty() || !was.get().affixId().equals(s.affixId())) e.addProperty("affixAfter", s.affixId());
                    e.addProperty("rankAfter", s.rank()); e.addProperty("valueAfter", s.rolledValue()); e.addProperty("floorAfter", s.floor());
                    e.addProperty("sealedAfter", s.sealed());
                });
            }
            if (after != null && after.rarity() != before.rarity()) e.addProperty("rarityAfter", after.rarity().id());
            if (!outcome.cost().isEmpty()) e.add("spent", Telemetry.materials(outcome.cost()));
            if (outcome.creditsSpent() > 0) e.addProperty("credits", outcome.creditsSpent());
        });
    }

    /** A craft the player asked for and did not get, with the message they were shown. */
    public static void craftRefused(ServerPlayer player, String kind, String slotId, String reason) {
        Telemetry.log("craft_refused", e -> {
            e.addProperty("kind", kind);
            e.addProperty("player", Telemetry.playerKey(player.getUUID()));
            if (!slotId.isEmpty()) e.addProperty("slot", slotId);
            e.addProperty("reason", reason);
        });
    }

    // --- acquisition -----------------------------------------------------------------------------------

    /** A profile was created for a Pokemon: how it was obtained, where, and what it rolled. */
    public static void acquired(UUID owner, String dimension, int tier, Pokemon pokemon, ProfileV1 profile) {
        Telemetry.log("acquire", e -> {
            e.addProperty("player", Telemetry.playerKey(owner));
            e.addProperty("origin", profile.origin().kind());
            if (dimension != null) e.addProperty("dimension", dimension);
            e.addProperty("tier", tier);
            e.addProperty("species", ProfileService.speciesIdOf(pokemon));
            e.addProperty("level", pokemon.getLevel());
            e.addProperty("shiny", pokemon.getShiny());
            e.addProperty("rarity", profile.rarity().id());
            e.add("slots", slots(profile));
        });
    }

    // --- encounters ------------------------------------------------------------------------------------

    /** The parameters of an encounter a reward was settled for (the tier, floor, cycle or arena count it was worth). */
    public static void encounter(String rewardKind, UUID encounterId, Map<String, Object> parameters) {
        encounter(rewardKind, encounterId.toString(), parameters, List.of());
    }

    /**
     * An encounter with the team's strength attached (balance phase 0): {@code teamRarity} is the mean rarity (0 Common to
     * 5 Mythic) of every profiled party Pokemon of the online participants, {@code teamRarityMax} the best one. Win rate
     * by team rarity and tier is what the Threat bands are tuned from.
     */
    public static void encounter(String rewardKind, UUID encounterId, Map<String, Object> parameters, java.util.Collection<UUID> players) {
        encounter(rewardKind, encounterId.toString(), parameters, players);
    }

    /** As above, for encounters whose ID is not a UUID (a raid's is its boss Pokemon's). */
    public static void encounter(String rewardKind, String encounterId, Map<String, Object> parameters, java.util.Collection<UUID> players) {
        double[] rarity = teamRarity(players);
        Telemetry.log("encounter", e -> {
            e.addProperty("kind", rewardKind);
            e.addProperty("id", encounterId);
            if (rarity != null) {
                e.addProperty("teamRarity", Math.round(rarity[0] * 100.0) / 100.0);
                e.addProperty("teamRarityMax", (int) rarity[1]);
            }
            parameters.forEach((key, value) -> {
                if (value instanceof Boolean b) e.addProperty(key, b);
                else if (value instanceof Number n) e.addProperty(key, n);
                else e.addProperty(key, String.valueOf(value));
            });
        });
    }

    /** The mean rarity ordinal (0 Common to 5 Mythic) of the players' profiled party Pokemon, or -1 when none is online or profiled. Never throws. */
    public static double meanTeamRarity(java.util.Collection<UUID> players) {
        double[] rarity = teamRarity(players);
        return rarity == null ? -1 : rarity[0];
    }

    /** {mean, max} rarity ordinal of the participants' profiled party Pokemon, or null when none is online or profiled. Never throws. */
    private static double[] teamRarity(java.util.Collection<UUID> players) {
        if (players == null || players.isEmpty()) return null;
        var service = AscensionApi.service().orElse(null);
        MinecraftServer server = AscensionApi.server();
        if (service == null || server == null) return null;
        try {
            int count = 0, sum = 0, max = 0;
            for (UUID id : players) {
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player == null) continue;
                for (var pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
                    var profile = service.canonical(pokemon);
                    if (profile.isEmpty()) continue;
                    int ordinal = profile.get().rarity().ordinal();
                    sum += ordinal;
                    max = Math.max(max, ordinal);
                    count++;
                }
            }
            return count == 0 ? null : new double[] {(double) sum / count, max};
        } catch (RuntimeException exception) {
            LOG.debug("Team rarity skipped: {}", exception.toString());
            return null;
        }
    }

    /** How a reward settlement ended for each participant. A defeat is logged too: the share of victories is the point. */
    public static void settlement(String rewardKind, UUID encounterId, String outcome, Map<UUID, String> statuses) {
        Telemetry.log("settlement", e -> {
            e.addProperty("kind", rewardKind);
            e.addProperty("id", encounterId.toString());
            e.addProperty("outcome", outcome);
            var players = new JsonObject();
            statuses.forEach((player, status) -> players.addProperty(Telemetry.playerKey(player), status));
            e.add("players", players);
        });
    }

    // --- sessions --------------------------------------------------------------------------------------

    public static void joined(ServerPlayer player) {
        JOINED.put(player.getUUID(), System.currentTimeMillis());
        Telemetry.log("join", e -> {
            e.addProperty("player", Telemetry.playerKey(player.getUUID()));
            snapshot(player, e);
        });
    }

    public static void left(ServerPlayer player) {
        Long since = JOINED.remove(player.getUUID());
        Telemetry.log("leave", e -> {
            e.addProperty("player", Telemetry.playerKey(player.getUUID()));
            if (since != null) e.addProperty("seconds", (System.currentTimeMillis() - since) / 1000);
            snapshot(player, e);
        });
    }

    /** The player's wallet and party as they stand: the progress a session is measured by. */
    private static void snapshot(ServerPlayer player, JsonObject e) {
        var service = AscensionApi.service().orElse(null);
        if (service == null) return;
        try {
            e.add("wallet", Telemetry.materials(service.store().wallet(player.getUUID()).balances()));
            var party = new JsonArray();
            for (var pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
                var profile = service.canonical(pokemon);
                var entry = new JsonObject();
                entry.addProperty("species", ProfileService.speciesIdOf(pokemon));
                entry.addProperty("level", pokemon.getLevel());
                if (profile.isPresent()) {
                    var p = profile.get();
                    entry.addProperty("rarity", p.rarity().id());
                    entry.addProperty("attunement", p.attunement());
                    entry.addProperty("pending", p.pendingCredits());
                    if (p.unique() != null) entry.addProperty("unique", p.unique().uniqueId());
                    entry.add("slots", slots(p));
                }
                party.add(entry);
            }
            e.add("party", party);
        } catch (RuntimeException exception) {
            // The party is unloaded on some paths out of the game; the session event is still worth having without it.
            LOG.debug("Session snapshot incomplete for {}: {}", player.getGameProfile().getName(), exception.toString());
        }
    }

    /** The modifiers as {@code affix:rank:value} (a floor, when set, as {@code :fN}; a sealed slot ends in {@code :sealed}). */
    private static JsonArray slots(ProfileV1 profile) {
        var out = new JsonArray();
        for (var s : profile.ordinarySlots())
            out.add(s.affixId() + ":" + s.rank() + ":" + s.rolledValue() + (s.floor() > 0 ? ":f" + s.floor() : "") + (s.sealed() ? ":sealed" : ""));
        return out;
    }

    /** Page timings the player's own page reported (see {@code UiMetrics}); recorded with the server's, one label per page and measure. */
    public static void pageTimings(String page, String label, double ms) {
        Profiler.record("ui." + page + "." + label, (long) (ms * 1e6));
    }
}
