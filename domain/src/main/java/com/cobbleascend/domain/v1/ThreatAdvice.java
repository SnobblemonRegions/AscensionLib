package com.cobbleascend.domain.v1;

/**
 * Advice for the tower door (balance phase 1): which Threat a party is built for, from the mean rarity of its profiled Pokemon. Each Threat band is
 * built for one team rarity (Threat I for Common, VI for Mythic), so the advice is the rounded mean plus one. It never locks anything: a party may pick
 * any band, and the door shows green at the advice, yellow one above and red two or more above.
 */
public final class ThreatAdvice {
    public enum Verdict { GREEN, YELLOW, RED }

    private ThreatAdvice() {}

    /** The Threat band (1-6) a team of this mean rarity ordinal (0 Common to 5 Mythic) is built for; 1 for an empty or unprofiled team. */
    public static int recommended(double meanRarity) {
        if (Double.isNaN(meanRarity) || meanRarity < 0) return 1;
        return Math.max(1, Math.min(6, (int) Math.floor(meanRarity + 0.5) + 1));
    }

    public static Verdict verdict(double meanRarity, int threat) {
        int gap = threat - recommended(meanRarity);
        return gap <= 0 ? Verdict.GREEN : gap == 1 ? Verdict.YELLOW : Verdict.RED;
    }
}
