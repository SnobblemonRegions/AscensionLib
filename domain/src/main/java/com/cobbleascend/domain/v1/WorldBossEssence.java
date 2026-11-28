package com.cobbleascend.domain.v1;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What a defeated CobbleRaids World Boss pays in Spooky Essence (docs/HALLOWEEN-EVENT-DESIGN.md). Pure: the damage each player dealt in, the essence
 * each earns out. The owner's rules (2026-10-25): a flat amount for everyone who took part, no cap on how many World Bosses one player can
 * earn from. A player took part when they dealt at least {@link #MIN_OF_AVERAGE} of the average damage per player, the same rule CobbleRaids uses
 * for its own Raid Points, so one reward never reaches a player the other skips. Relative to the average on purpose: a fixed share of the pool
 * would pay nobody once enough players split it evenly, and the event has no player cap.
 */
public final class WorldBossEssence {
    /** Spooky Essence paid to each participant per defeated World Boss. */
    public static final long PER_PARTICIPANT = 2;
    /** The least a player may deal, as a fraction of the average damage per player who dealt any, to count as taking part. */
    public static final float MIN_OF_AVERAGE = 0.05f;

    private WorldBossEssence() {}

    /**
     * As {@link #payouts(Map)}, but only while the Halloween event is open: Spooky Essence is the event's currency, so a boss beaten after the
     * window (an event boss run late, or the Daily Boss) pays none of it. Essence already earned stays spendable.
     *
     * @param eventOpen whether the Halloween window is open now
     */
    public static Map<UUID, Long> payouts(Map<UUID, Float> damage, boolean eventOpen) {
        return eventOpen ? payouts(damage) : new LinkedHashMap<>();
    }

    /**
     * @param damage damage dealt to the pool per player
     * @return the essence each participant earns, in the order of {@code damage}
     */
    public static Map<UUID, Long> payouts(Map<UUID, Float> damage) {
        var out = new LinkedHashMap<UUID, Long>();
        double total = 0;
        int dealers = 0;
        for (var value : damage.values()) {
            if (value != null && value > 0f && Float.isFinite(value)) {
                total += value;
                dealers++;
            }
        }
        if (dealers == 0) return out;
        double least = total / dealers * MIN_OF_AVERAGE;
        for (var entry : damage.entrySet()) {
            float dealt = entry.getValue() == null ? 0f : entry.getValue();
            if (dealt > 0f && Float.isFinite(dealt) && dealt >= least) out.put(entry.getKey(), PER_PARTICIPANT);
        }
        return out;
    }

    /**
     * The store operation that pays one player for one World Boss. The same boss and player always give the same ID, so a listener that is told
     * twice (a restart between the defeat and its bookkeeping) credits once.
     */
    public static UUID operationId(UUID bossId, UUID playerId) {
        return UUID.nameUUIDFromBytes(("worldboss-essence:" + bossId + ":" + playerId).getBytes(StandardCharsets.UTF_8));
    }
}
