package com.cobbleascend.domain.v1;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * What a won raid pays in AscensionLib materials while the Threat system is on (balance phase 1, docs/BALANCE-THREAT.md). Raids are the only source
 * of Apex Shards; they also pay attunement and a little dust. CobbleRaids keeps its own item loot and Raid Points on top.
 *
 * <p>Each tier has an expected Apex amount: the whole part is paid, the fraction is a seeded chance of one more (Legendary 1.2 is one Shard plus a
 * 20% chance of a second). Everything scales by the player's share of the fight, {@code share x players} clamped to 0.5-1.5, so a carried player is
 * paid less and the carrier more, and the world boss uses the same rule. After a player's full-pay raids for the day, Apex is cut to
 * {@code reducedPercent} (dust and attunement are not). Seeded by (encounter, player), so a retried settlement recomputes the same amounts.
 */
public final class RaidRewardBands {
    public enum Tier {
        // Balance sim fixes: Apex halved (raids minted 5.5 Apex per Core); a small Core chance so raid-focused players are not gated.
        STARTER(15, 3, 60, 0), POWERHOUSE(30, 5, 60, 40), LEGENDARY(60, 8, 60, 80), MYTHICAL(90, 12, 60, 120), WORLD_BOSS(150, 15, 120, 200);

        /** Expected Apex Shards in hundredths, attunement, dust, all for a full share; Core chance in permille. */
        final int apexHundredths, attunement, dust, corePermille;

        Tier(int apexHundredths, int attunement, int dust, int corePermille) {
            this.apexHundredths = apexHundredths;
            this.attunement = attunement;
            this.dust = dust;
            this.corePermille = corePermille;
        }

        public int attunement() { return attunement; }

        /** {@code starter}, {@code powerhouse}, {@code legendary}, {@code mythical} or {@code world_boss} (CobbleRaids' rarity_tier ids). */
        public static Tier fromId(String id) {
            if (id == null) throw new IllegalArgumentException("A raid tier is required");
            try {
                return valueOf(id.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("Unknown raid tier: " + id);
            }
        }
    }

    /** One player's raid pay: materials for the wallet, and the attunement their party earns. */
    public record Pay(Map<MaterialId, Long> materials, int attunement, double multiplier) {}

    private RaidRewardBands() {}

    /** The share multiplier: {@code share x players} clamped to 0.5-1.5; a missing or invalid share counts as an even split (1.0). */
    public static double multiplier(double share, int players) {
        if (players < 1 || !(share > 0) || Double.isNaN(share) || Double.isInfinite(share)) return 1.0;
        return Math.max(0.5, Math.min(1.5, share * players));
    }

    /** Whether a share is too small to be paid: under a quarter of an even split (the world boss's rule, made relative). */
    public static boolean tooSmall(double share, int players) {
        return players > 1 && share >= 0 && share * players < 0.25;
    }

    public static Pay pay(String encounterId, UUID player, Tier tier, double share, int players, boolean apexCapped, int reducedPercent) {
        double m = multiplier(share, players);
        var random = new Random(RewardSeed.of("raid-threat", encounterId, player));
        double apex = tier.apexHundredths / 100.0 * m;
        if (apexCapped) apex = apex * Math.max(0, Math.min(100, reducedPercent)) / 100.0;
        long whole = (long) Math.floor(apex);
        if (random.nextDouble() < apex - whole) whole++;
        var materials = new EnumMap<MaterialId, Long>(MaterialId.class);
        if (whole > 0) materials.put(MaterialId.APEX_SHARD, whole);
        // The Core roll comes after the Apex roll, so the Apex a seed pays is unchanged. Scaled by share and cut like Apex past the cap.
        double core = tier.corePermille / 1000.0 * Math.min(1.0, m);
        if (apexCapped) core = core * Math.max(0, Math.min(100, reducedPercent)) / 100.0;
        if (random.nextDouble() < core) materials.put(MaterialId.ASCENSION_CORE, 1L);
        long dust = Math.round(tier.dust * m);
        if (dust > 0) materials.put(MaterialId.RESONANCE_DUST, dust);
        int attunement = Math.max(1, (int) Math.round(tier.attunement * m));
        return new Pay(Collections.unmodifiableMap(materials), attunement, m);
    }
}
