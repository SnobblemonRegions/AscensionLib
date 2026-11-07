package com.cobbleascend.domain.v1;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * What each Threat band is, for a screen that lets a player pick one: who the enemies are, what they spend, what a Silver completion pays. Read from the
 * enemy tiers in force (so an owner's {@code ascensionlib-enemy-tiers.json} shows) and the same payout methods the payout uses, so the screen cannot
 * disagree with the game. Pure.
 */
public final class ThreatBands {

    /** The bands a party can pick at a door: Threat VII is reached only through Ascension cycles, so it is not offered. */
    public static final int FIRST = 1, LAST = 6;

    private ThreatBands() {}

    /**
     * The bands as a JSON array, lowest first: {@code band}, {@code mix} (the enemy rarities as {@code {rarity, percent}}, most common first; percents add to 100),
     * {@code credits} an ordinary enemy spends, {@code bossCredits}, {@code unique} (every enemy has a Unique), {@code bossUnique}, and a Silver completion's
     * {@code dust}, {@code facets}, {@code corePercent} and {@code apex} (Apex Shards, expected). A band the tiers do not define is left out.
     */
    public static JsonArray describe(EnemyTiers tiers) {
        JsonArray out = new JsonArray();
        for (int band = FIRST; band <= LAST; band++) {
            EnemyTier tier;
            try {
                tier = tiers.threat(band);
            } catch (IllegalArgumentException missing) {
                continue;   // an owner's override file that leaves a band out: the screen shows the bands that exist
            }
            JsonObject o = new JsonObject();
            o.addProperty("band", band);
            JsonArray mix = new JsonArray();
            int total = tier.totalWeight(), given = 0;
            var weights = tier.rarities();
            for (int i = 0; i < weights.size(); i++) {
                var w = weights.get(i);
                // the last one takes the rounding, so the percents always add to 100
                int percent = i == weights.size() - 1 ? 100 - given : (int) Math.round(100.0 * w.weight() / total);
                given += percent;
                JsonObject r = new JsonObject();
                r.addProperty("rarity", w.rarity().id());
                r.addProperty("percent", percent);
                mix.add(r);
            }
            o.add("mix", mix);
            o.addProperty("credits", tier.creditsFor(false));
            o.addProperty("bossCredits", tier.creditsFor(true));
            o.addProperty("unique", tier.unique());
            o.addProperty("bossUnique", tier.bossUnique());
            o.addProperty("dust", TowerCompletionBands.threatDust(band));
            o.addProperty("facets", TowerCompletionBands.threatFacets(band));
            o.addProperty("corePercent", TowerCompletionBands.threatCorePercent(band));
            o.addProperty("apex", TowerCompletionBands.threatApexPermille(band) / 1000.0);
            out.add(o);
        }
        return out;
    }
}
