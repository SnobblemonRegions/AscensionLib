package com.cobbleascend.domain.v1;

import java.util.Map;
import java.util.Random;

/**
 * The 777 Unique's loot bonus: +20% on the quantity of an item reward. A fraction of an item is settled by a seeded roll, so
 * 5 items become 6 and 1 item becomes 2 one time in five, and the same reward always comes out the same (a retry or a
 * restart cannot re-roll it). Applies to item rewards only: materials in the wallet are tuned separately and never scale.
 */
public final class ItemQuantityBonus {
    public static final int PERCENT = 20;

    /** The item-reward percent each Transcendent signature built on the 777 Unique carries before harmony (docs/TRANSCENDENT-POWERS.md). */
    private static final Map<String, Integer> SIGNATURE_PERCENT = Map.ofEntries(
            Map.entry("gilded_ember", 10), Map.entry("last_gamble", 15), Map.entry("fortunes_rot", 15),
            Map.entry("skyfall_fortune", 15), Map.entry("wager_of_blood", 15), Map.entry("jackpot_titan", 20),
            // the 777 recipes of the second batch of Uniques (docs/AFFIX-EXPANSION-DESIGN.md; the "loot" part in tools/transcendent_powers.py)
            Map.entry("compound_interest", 15), Map.entry("blood_money", 15), Map.entry("golden_cage", 15), Map.entry("gold_rush", 15),
            Map.entry("bounty_hunter", 15), Map.entry("inheritance", 15), Map.entry("vault_keeper", 15), Map.entry("all_in", 20));

    private ItemQuantityBonus() {}

    /**
     * The item-reward percent of a Transcendent: its signature's percent times the benefit share (whole percent 0..100), rounded
     * down; 0 for a signature that carries none or an out-of-range share.
     */
    public static int percentFor(String signatureId, int benefitPercent) {
        Integer base = SIGNATURE_PERCENT.get(signatureId);
        if (base == null || benefitPercent < 0 || benefitPercent > 100) return 0;
        return base * benefitPercent / 100;
    }

    /**
     * @param count the base quantity (at least 1)
     * @param seed  identifies this reward (player, item, source), so the rounding is stable
     */
    public static int scale(int count, String seed) {
        return scale(count, seed, PERCENT);
    }

    /** As {@link #scale(int, String)} with another bonus: a Transcendent's {@link #percentFor}. A percent of 0 or less changes nothing. */
    public static int scale(int count, String seed, int percent) {
        if (count < 1 || percent <= 0) return count;
        long scaledTimes100 = (long) count * (100 + Math.min(percent, 100));
        int whole = (int) (scaledTimes100 / 100);
        int remainder = (int) (scaledTimes100 % 100);
        if (remainder > 0 && new Random(RewardSeed.of("item-quantity", seed, count)).nextInt(100) < remainder) whole++;
        return whole;
    }
}
