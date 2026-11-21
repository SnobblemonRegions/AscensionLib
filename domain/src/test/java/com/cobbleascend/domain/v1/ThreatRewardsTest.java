package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Balance phase 1: tower completions by Threat, raid pay by tier and share, and the tower door's advice. */
class ThreatRewardsTest {
    private final TowerCompletionBands bands = TowerCompletionBands.DEFAULTS;

    @Test void threatTablesMatchTheSpec() {
        int[] dust = {100, 175, 250, 325, 400, 475, 550};
        int[] facets = {4, 5, 7, 8, 10, 11, 13};
        int[] cores = {32, 44, 56, 68, 80, 92, 95};
        for (int t = 1; t <= 7; t++) {
            assertEquals(dust[t - 1], TowerCompletionBands.threatDust(t), "dust at " + t);
            assertEquals(facets[t - 1], TowerCompletionBands.threatFacets(t), "facets at " + t);
            assertEquals(cores[t - 1], TowerCompletionBands.threatCorePercent(t), "core % at " + t);
        }
        assertEquals(TowerCompletionBands.threatDust(1), TowerCompletionBands.threatDust(0), "bands clamp");
        assertEquals(95, TowerCompletionBands.threatCorePercent(9));
    }

    @Test void aSilverThreatCompletionPaysItsBandAndRollsCoresAtTheBandChance() {
        int samples = 20000;
        for (int t : new int[] {1, 4, 7}) {
            long cores = 0;
            for (int i = 0; i < samples; i++) {
                var c = bands.payoutThreat("enc-" + i, new UUID(t, i), t, 2, 0, false, false, 25);
                assertEquals(TowerCompletionBands.Tier.SILVER, c.tier());
                assertEquals((long) TowerCompletionBands.threatDust(t), c.materials().get(MaterialId.RESONANCE_DUST));
                assertEquals((long) TowerCompletionBands.threatFacets(t), c.materials().get(MaterialId.FACET));
                cores += c.materials().getOrDefault(MaterialId.ASCENSION_CORE, 0L);
            }
            assertEquals(TowerCompletionBands.threatCorePercent(t) / 100.0, cores / (double) samples, 0.015, "Core rate at Threat " + t);
        }
    }

    @Test void theCrownNeverAddsASecondCoreAndACappedRollKeepsItsReducedShare() {
        int samples = 20000;
        long capped = 0;
        for (int i = 0; i < samples; i++) {
            var mythic = bands.payoutThreat("crown-" + i, new UUID(9, i), 4, 8, 0, true, false, 25);
            assertTrue(mythic.materials().getOrDefault(MaterialId.ASCENSION_CORE, 0L) <= 1, "one Core roll, never a crown Core on top");
            capped += bands.payoutThreat("cap-" + i, new UUID(8, i), 4, 2, 0, false, true, 25).materials().getOrDefault(MaterialId.ASCENSION_CORE, 0L);
        }
        assertEquals(0.68 * 0.25, capped / (double) samples, 0.01, "after the daily full rolls a Core is a quarter as likely");
    }

    @Test void threatCompletionsAreDeterministicPerEncounterAndPlayer() {
        var player = UUID.randomUUID();
        assertEquals(bands.payoutThreat("same", player, 5, 3, 1, true, false, 25), bands.payoutThreat("same", player, 5, 3, 1, true, false, 25));
    }

    @Test void raidPayFollowsTierShareAndCap() {
        int samples = 20000;
        for (var tier : RaidRewardBands.Tier.values()) {
            long apex = 0;
            for (int i = 0; i < samples; i++)
                apex += RaidRewardBands.pay("raid-" + i, new UUID(tier.ordinal(), i), tier, 0.25, 4, false, 25).materials().getOrDefault(MaterialId.APEX_SHARD, 0L);
            assertEquals(tier.apexHundredths / 100.0, apex / (double) samples, 0.03, "even split pays the tier's expected Apex: " + tier);
        }
        var legendary = RaidRewardBands.Tier.LEGENDARY;
        assertEquals(8, RaidRewardBands.pay("a", UUID.randomUUID(), legendary, 0.25, 4, false, 25).attunement());
        assertEquals(12, RaidRewardBands.pay("a", UUID.randomUUID(), legendary, 0.9, 4, false, 25).attunement(), "a carry is paid up to 1.5x");
        assertEquals(4, RaidRewardBands.pay("a", UUID.randomUUID(), legendary, 0.05, 4, false, 25).attunement(), "a light share is paid at least 0.5x");
        long capped = 0;
        for (int i = 0; i < samples; i++)
            capped += RaidRewardBands.pay("c-" + i, new UUID(77, i), RaidRewardBands.Tier.MYTHICAL, 0.25, 4, true, 25).materials().getOrDefault(MaterialId.APEX_SHARD, 0L);
        assertEquals(1.8 * 0.25, capped / (double) samples, 0.03, "past the daily full raids Apex is cut to a quarter");
        assertTrue(RaidRewardBands.tooSmall(0.02, 8), "2% of an 8-player raid is under a quarter of an even split");
        assertFalse(RaidRewardBands.tooSmall(0.05, 8), "5% of an 8-player raid is 0.4 of an even split");
        assertFalse(RaidRewardBands.tooSmall(0.05, 1), "a solo raider is never too small");
        assertEquals(1.0, RaidRewardBands.multiplier(Double.NaN, 4));
        assertEquals(RaidRewardBands.Tier.WORLD_BOSS, RaidRewardBands.Tier.fromId("world_boss"));
        assertThrows(IllegalArgumentException.class, () -> RaidRewardBands.Tier.fromId("epic"));
    }

    @Test void raidsPayFacetsAndCoresSoRaidOnlyPlayersCanPromote() {
        int samples = 20000;
        for (var tier : RaidRewardBands.Tier.values()) {
            long facets = 0, cores = 0;
            for (int i = 0; i < samples; i++) {
                var materials = RaidRewardBands.pay("rf-" + i, new UUID(tier.ordinal(), i), tier, 0.25, 4, false, 25).materials();
                facets += materials.getOrDefault(MaterialId.FACET, 0L);
                cores += materials.getOrDefault(MaterialId.ASCENSION_CORE, 0L);
            }
            assertEquals(tier.facetHundredths / 100.0, facets / (double) samples, 0.04, "even split pays the tier's expected Facets: " + tier);
            assertEquals(tier.corePermille / 1000.0, cores / (double) samples, 0.012, "even split rolls the tier's Core chance: " + tier);
        }
        long cut = 0;
        for (int i = 0; i < samples; i++)
            cut += RaidRewardBands.pay("cf-" + i, new UUID(5, i), RaidRewardBands.Tier.MYTHICAL, 0.25, 4, true, 25).materials().getOrDefault(MaterialId.FACET, 0L);
        assertEquals(RaidRewardBands.Tier.MYTHICAL.facetHundredths / 100.0 * 0.25, cut / (double) samples, 0.03,
                "past the daily full raids Facets are cut to a quarter too");
    }

    @Test void aHarderRaidTierPaysMoreOfEveryMaterial() {
        var tiers = RaidRewardBands.Tier.values();
        for (int i = 1; i < tiers.length; i++) {
            var lower = tiers[i - 1];
            var higher = tiers[i];
            assertTrue(higher.dust > lower.dust, higher + " pays more dust than " + lower);
            assertTrue(higher.corePermille > lower.corePermille, higher + " has a better Core chance than " + lower);
            assertTrue(higher.facetHundredths >= lower.facetHundredths, higher + " pays at least the Facets of " + lower);
            assertTrue(higher.apexHundredths > lower.apexHundredths, higher + " pays more Apex than " + lower);
        }
        assertTrue(RaidRewardBands.Tier.LEGENDARY.dust >= 2.5 * RaidRewardBands.Tier.STARTER.dust,
                "a Legendary win is worth well over a Starter win, so Starters are not the best farm");
    }

    @Test void theFirstRaidWinOfTheDayPaysEveryMaterialDoubleButNotAttunement() {
        for (var tier : RaidRewardBands.Tier.values()) {
            for (int i = 0; i < 200; i++) {
                var player = new UUID(3, i);
                var normal = RaidRewardBands.pay("fw-" + i, player, tier, 0.25, 4, false, 25);
                var first = RaidRewardBands.pay("fw-" + i, player, tier, 0.25, 4, false, 25, true);
                assertEquals(normal.materials().keySet(), first.materials().keySet(), "the same rolls, so the same kinds: " + tier);
                normal.materials().forEach((material, amount) ->
                        assertEquals(amount * RaidRewardBands.FIRST_WIN_MULTIPLIER, first.materials().get(material), material + " at " + tier));
                assertEquals(normal.attunement(), first.attunement(), "attunement is not doubled");
                assertEquals(normal, RaidRewardBands.pay("fw-" + i, player, tier, 0.25, 4, false, 25, false), "false is the ordinary pay");
            }
        }
    }

    @Test void highThreatTowerCompletionsMayPayAnApexShard() {
        int samples = 20000;
        for (int t : new int[] {1, 4, 6}) {
            long apex = 0;
            for (int i = 0; i < samples; i++)
                apex += bands.payoutThreat("ta-" + i, new UUID(t, i), t, 2, 0, false, false, 25).materials().getOrDefault(MaterialId.APEX_SHARD, 0L);
            assertEquals(TowerCompletionBands.threatApexPermille(t) / 1000.0, apex / (double) samples, 0.015, "Apex rate at Threat " + t);
        }
        assertEquals(0, TowerCompletionBands.threatApexPermille(2), "low bands pay no Apex");
        assertTrue(TowerCompletionBands.threatApexPermille(7) > TowerCompletionBands.threatApexPermille(4), "the ladder climbs");
    }

    @Test void doorAdviceIsTeamRarityPlusOne() {
        assertEquals(1, ThreatAdvice.recommended(0.0));
        assertEquals(1, ThreatAdvice.recommended(-1));
        assertEquals(3, ThreatAdvice.recommended(2.4));
        assertEquals(4, ThreatAdvice.recommended(2.5));
        assertEquals(6, ThreatAdvice.recommended(5.0));
        assertEquals(ThreatAdvice.Verdict.GREEN, ThreatAdvice.verdict(2.0, 3));
        assertEquals(ThreatAdvice.Verdict.YELLOW, ThreatAdvice.verdict(2.0, 4));
        assertEquals(ThreatAdvice.Verdict.RED, ThreatAdvice.verdict(2.0, 6));
    }
}
