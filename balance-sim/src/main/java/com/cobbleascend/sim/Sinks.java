package com.cobbleascend.sim;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Prototype sinks for the balance sim only (concepts R1 and G1); nothing here ships. The sim answers "how big must the stakes be and what does the
 * late-game sink ratio become" before any block, screen or API is built. When one is built the tables move into the domain.
 *
 * <p><b>R1, the Reclaimer</b> turns an unwanted Pokemon into dust by rarity alone, never by the materials invested in it, so promote, trade and recycle can
 * never loop into a profit. <b>G1, the Wager Altar</b> takes a stake bundle in three tiers (dust alone, dust and facets, then Cores and Apex Shards too, the
 * materials with no other repeatable sink) and pays prizes that are not tracked materials (Unique Catalysts, Fragments, Sigils, capture-floor tickets): the
 * whole stake leaves the ledger, and the prizes are counted here at a stated value.
 */
public final class Sinks {
    private Sinks() {}

    /** Dust the Reclaimer pays for one Pokemon, by rarity ordinal (Common to Mythical). */
    public static final long[] RECLAIM_DUST = {3, 10, 35, 110, 400, 1500};
    /** A player's first this many reclaimed Pokemon of a UTC day pay in full, the rest at the reduced percent (the daily chest budget's rule). */
    public static final int RECLAIM_FULL_PER_DAY = 20, RECLAIM_REDUCED_PERCENT = 25;

    /** One altar tier: the stake bundle. */
    public record Tier(String name, long dust, long facets, long cores, long apex) {
        /**
         * The bundle's worth in dust, for the "value back" figure only. The weights (a Facet 15, a Core 250, an Apex Shard 60) are assumptions about relative
         * scarcity, not prices anything charges.
         */
        public double value() { return dust + 15.0 * facets + 250.0 * cores + 60.0 * apex; }
    }

    /**
     * The altar's stake tiers, cheapest first, drawing on different materials so that one cannot starve another: Bronze is dust, Silver is facets with a
     * little dust, Gold is Cores and Apex Shards. (Tiers that all needed a pile of dust would never be reached: the Bronze plays drain it first.)
     */
    public static final Tier[] TIERS = {
            new Tier("Bronze", 2_000, 0, 0, 0),
            new Tier("Silver", 3_000, 150, 0, 0),
            new Tier("Gold", 0, 0, 30, 60),
    };
    /** Chance of each prize class per play, and the prize's value as a multiple of the stake. The rest of the time the stake is lost. */
    public static final double SMALL_CHANCE = 0.30, BIG_CHANCE = 0.04, JACKPOT_CHANCE = 0.002;
    public static final double SMALL_X = 1.5, BIG_X = 4.0, JACKPOT_X = 20.0;

    /** What a play is worth back, as a share of the stake: one minus the house edge. */
    public static double expectedReturn() { return SMALL_CHANCE * SMALL_X + BIG_CHANCE * BIG_X + JACKPOT_CHANCE * JACKPOT_X; }

    /** What the sinks did over a run. */
    public static final class Stats {
        public long reclaimed, reclaimedDust, plays, stakedDust, stakedFacets, stakedCores, stakedApex, small, big, jackpots;
        public double stakeValue, prizeValue;
        public final long[] playsByTier = new long[TIERS.length];
        public final Set<UUID> reclaimers = new HashSet<>(), gamblers = new HashSet<>();

        void wager(UUID player, int tier, double roll) {
            Tier t = TIERS[tier];
            plays++;
            playsByTier[tier]++;
            stakedDust += t.dust();
            stakedFacets += t.facets();
            stakedCores += t.cores();
            stakedApex += t.apex();
            stakeValue += t.value();
            gamblers.add(player);
            if (roll < JACKPOT_CHANCE) { jackpots++; prizeValue += t.value() * JACKPOT_X; }
            else if (roll < JACKPOT_CHANCE + BIG_CHANCE) { big++; prizeValue += t.value() * BIG_X; }
            else if (roll < JACKPOT_CHANCE + BIG_CHANCE + SMALL_CHANCE) { small++; prizeValue += t.value() * SMALL_X; }
        }
    }
}
