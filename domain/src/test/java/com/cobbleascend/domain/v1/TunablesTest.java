package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TunablesTest {
    @AfterEach void restoreDefaults() { Tunables.use(Tunables.defaults()); }

    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }

    @Test void theDefaultsAreTheApprovedNumbers() {
        var d = Tunables.defaults();
        assertEquals(20, d.budget().fullPerDay());
        assertEquals(25, d.budget().reducedDustPercent());
        assertEquals(3, d.withdrawalFeePercent());
        assertEquals(90, d.respecMarksPrice());
        assertEquals(3, d.attunementPerVictory());
        assertEquals(new Tunables.MiniChest(200, 1, 1, 1, 1, 50), d.miniChest());
        assertEquals(800, d.towerCompletion().dust());
    }

    @Test void theFileFormRoundTripsToTheSameConfiguration() {
        var problems = new ArrayList<String>();
        var again = Tunables.parse(Tunables.defaults().toJson(), problems);
        assertEquals(Tunables.defaults().toJson(), again.toJson());
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(Tunables.defaults().towerCompletion().tiers().size(), again.towerCompletion().tiers().size());
    }

    @Test void aPartialFileChangesOnlyWhatItNamesAndKeepsTheRest() {
        var problems = new ArrayList<String>();
        var t = Tunables.parse(json("{\"hourglassMarksPrice\": 120, \"dailyBudget\": {\"fullChestsPerDay\": 8}, \"towerCompletion\": {\"pile\": {\"dust\": 1000}}}"), problems);
        assertEquals(120, t.respecMarksPrice());
        assertEquals(8, t.budget().fullPerDay());
        assertEquals(25, t.budget().reducedDustPercent(), "the other half of the budget keeps its default");
        assertEquals(1000, t.towerCompletion().dust());
        assertEquals(6, t.towerCompletion().facets());
        assertEquals(3, t.withdrawalFeePercent());
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test void anInvalidValueKeepsItsDefaultAndIsNamed() {
        var problems = new ArrayList<String>();
        var t = Tunables.parse(json("{\"withdrawalFeePercent\": 90, \"hourglassMarksPrice\": 0, \"attunementPerVictory\": \"lots\", \"dailyBudget\": {\"reducedDustPercent\": 150}}"), problems);
        assertEquals(3, t.withdrawalFeePercent());
        assertEquals(90, t.respecMarksPrice());
        assertEquals(3, t.attunementPerVictory());
        assertEquals(25, t.budget().reducedDustPercent());
        assertEquals(4, problems.size(), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("withdrawalFeePercent")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("dailyBudget")));
    }

    @Test void aCrownTableThatCannotWorkFallsBackAsAWhole() {
        var problems = new ArrayList<String>();
        var zero = "{\"towerCompletion\": {\"tiers\": {\"gold\": {\"crownWeights\": {\"core\": 0, \"fragment_bundle\": 0, \"sigil\": 0, \"catalyst\": 0, \"armor\": 0, \"ticket\": 0, \"respec_rank\": 0, \"respec_unique\": 0}}}}}";
        var t = Tunables.parse(json(zero), problems);
        assertEquals(Tunables.defaults().towerCompletion().toJson(), t.towerCompletion().toJson());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("towerCompletion")), problems.toString());
        var misordered = new ArrayList<String>();
        Tunables.parse(json("{\"towerCompletion\": {\"tiers\": {\"silver\": {\"minScore\": 9}}}}"), misordered);
        assertFalse(misordered.isEmpty(), "tiers must keep their order");
    }

    @Test void unknownSettingsAreReportedButCommentsAreNot() {
        var problems = new ArrayList<String>();
        Tunables.parse(json("{\"_about\": \"hello\", \"hourglassPrice\": 5}"), problems);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("hourglassPrice"));
    }

    @Test void theLiveValuesReachTheCodeThatReadsThem() {
        var tuned = Tunables.parse(json("{\"withdrawalFeePercent\": 10, \"towerMiniChest\": {\"dust\": 5, \"facets\": 0, \"corePermille\": 1000}, \"towerCompletion\": {\"pile\": {\"dust\": 100}}}"), new ArrayList<>());
        Tunables.use(tuned);
        assertEquals(10, CurrencyItems.withdrawalFee(100), "the fee follows the setting");
        var chest = new TowerRewardBands(1.6, 1.08, 5, 10, 12, 50, 100, 75, 43).payout("e", new UUID(1, 1), 1, 5);
        assertEquals(5L, chest.get(MaterialId.RESONANCE_DUST));
        assertFalse(chest.containsKey(MaterialId.FACET), "a zero amount is not paid");
        assertTrue(chest.containsKey(MaterialId.ASCENSION_CORE), "a Core chance of 1000 permille always pays");
        assertEquals(100L, Tunables.current().towerCompletion().payout("e", new UUID(2, 2), 2, 0, false).materials().get(MaterialId.RESONANCE_DUST));
    }

    @Test void theZoneSettingsReadFromTheFileAndRoundTrip() {
        var problems = new ArrayList<String>();
        var t = Tunables.parse(json("{\"zones\": {\"defaultTier\": 2, \"dimensions\": {\"minecraft:the_end\": 0, \"minecraft:the_nether\": 4}, \"rarityTilt\": 0.4,"
                + " \"rarityWeights\": {\"6\": [1, 2, 3, 4, 5, 6]}}}"), problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(2, t.zones().defaultTier());
        assertEquals(0, t.zones().dimensions().get("minecraft:the_end"));
        assertEquals(4, t.zones().dimensions().get("minecraft:the_nether"));
        assertEquals(0.4, t.zones().rarityTilt());
        assertEquals(java.util.List.of(1, 2, 3, 4, 5, 6), t.zones().rarityWeights().get(6));
        assertEquals(t.zones(), Tunables.parse(t.toJson(), new ArrayList<>()).zones(), "what the file shows reads back as the same settings");
    }

    @Test void badZoneSettingsFallBackTogetherAndAreNamed() {
        for (String bad : new String[] {"{\"defaultTier\": 11}", "{\"dimensions\": {\"Not A Dimension\": 2}}", "{\"rarityTilt\": \"steep\"}",
                "{\"rarityWeights\": {\"3\": [1, 2]}}", "{\"rarityWeights\": {\"x\": [1, 1, 1, 1, 1, 1]}}"}) {
            var problems = new ArrayList<String>();
            var t = Tunables.parse(json("{\"zones\": " + bad + "}"), problems);
            assertEquals(ZoneSettings.DEFAULTS, t.zones(), bad);
            assertEquals(1, problems.size(), bad + " -> " + problems);
            assertTrue(problems.get(0).startsWith("zones:"), problems.get(0));
        }
    }

    @Test void theDefaultsAreEveryDimensionAtTierOneWithTheApprovedTilt() {
        var zones = Tunables.defaults().zones();
        assertEquals(1, zones.defaultTier());
        assertTrue(zones.dimensions().isEmpty());
        assertEquals(0.30, zones.rarityTilt());
        assertTrue(zones.rarityWeights().isEmpty());
    }

    @Test void threatIsOffByDefaultAndReadsBack() {
        assertFalse(Tunables.defaults().threat().enabled(), "Threat ships dark");
        var problems = new ArrayList<String>();
        var t = Tunables.parse(json("{\"threat\": {\"enabled\": true, \"fullCoreRollsPerDay\": 5}}"), problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertTrue(t.threat().enabled());
        assertEquals(5, t.threat().fullCoreRollsPerDay());
        assertEquals(ThreatSettings.DEFAULTS.fullApexPayoutsPerDay(), t.threat().fullApexPayoutsPerDay(), "an absent value keeps its default");
        assertEquals(t.threat(), Tunables.parse(t.toJson(), new ArrayList<>()).threat());
    }

    @Test void aBadThreatSectionFallsBackWhole() {
        for (var bad : List.of("{\"enabled\": \"yes\"}", "{\"reducedPercent\": 150}", "{\"fullCoreRollsPerDay\": -1}", "{\"enable\": true}")) {
            var problems = new ArrayList<String>();
            var t = Tunables.parse(json("{\"threat\": " + bad + "}"), problems);
            assertEquals(ThreatSettings.DEFAULTS, t.threat(), bad);
            assertEquals(1, problems.size(), bad + " -> " + problems);
        }
    }
}
