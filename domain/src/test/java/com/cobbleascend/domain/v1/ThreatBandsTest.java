package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** What the Threat picker shows for each band is what the game does. */
class ThreatBandsTest {

    private final EnemyTiers tiers = EnemyTiers.defaults();

    @Test void sixBandsAreOffered_notSeven() {
        var bands = ThreatBands.describe(tiers);
        assertEquals(6, bands.size());
        for (int i = 0; i < 6; i++) assertEquals(i + 1, bands.get(i).getAsJsonObject().get("band").getAsInt());
    }

    @Test void theMixIsTheTiersMixAndAddsToAHundred() {
        for (var e : ThreatBands.describe(tiers)) {
            JsonObject b = e.getAsJsonObject();
            int sum = 0;
            for (var m : b.getAsJsonArray("mix")) sum += m.getAsJsonObject().get("percent").getAsInt();
            assertEquals(100, sum, "band " + b.get("band"));
        }
        // the documented mixes: Threat III is 60% Rare, 40% Epic; Threat VI is all Mythical
        var three = ThreatBands.describe(tiers).get(2).getAsJsonObject().getAsJsonArray("mix");
        assertEquals("rare", three.get(0).getAsJsonObject().get("rarity").getAsString());
        assertEquals(60, three.get(0).getAsJsonObject().get("percent").getAsInt());
        assertEquals("epic", three.get(1).getAsJsonObject().get("rarity").getAsString());
        var six = ThreatBands.describe(tiers).get(5).getAsJsonObject().getAsJsonArray("mix");
        assertEquals(1, six.size());
        assertEquals("mythical", six.get(0).getAsJsonObject().get("rarity").getAsString());
    }

    @Test void creditsAndPayoutsAreTheGamesOwn() {
        for (var e : ThreatBands.describe(tiers)) {
            JsonObject b = e.getAsJsonObject();
            int band = b.get("band").getAsInt();
            EnemyTier tier = tiers.threat(band);
            assertEquals(tier.creditsFor(false), b.get("credits").getAsInt());
            assertEquals(tier.creditsFor(true), b.get("bossCredits").getAsInt());
            assertEquals(TowerCompletionBands.threatDust(band), b.get("dust").getAsInt());
            assertEquals(TowerCompletionBands.threatFacets(band), b.get("facets").getAsInt());
            assertEquals(TowerCompletionBands.threatCorePercent(band), b.get("corePercent").getAsInt());
        }
        var five = ThreatBands.describe(tiers).get(4).getAsJsonObject();
        assertTrue(five.get("bossUnique").getAsBoolean(), "Threat V's boss has a Unique");
        assertFalse(ThreatBands.describe(tiers).get(0).getAsJsonObject().get("bossUnique").getAsBoolean());
        assertEquals(0.0, ThreatBands.describe(tiers).get(1).getAsJsonObject().get("apex").getAsDouble(), "no Apex Shards below Threat III");
        assertEquals(0.3, ThreatBands.describe(tiers).get(2).getAsJsonObject().get("apex").getAsDouble(), 1e-9);
    }
}
