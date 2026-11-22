package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FieldResearchTest {
    private static final UUID ALICE = new UUID(1, 1), BOB = new UUID(2, 2);

    @Test void levelBoundaries() {
        assertEquals(0, FieldResearch.levelOf(0));
        assertEquals(0, FieldResearch.levelOf(9));
        assertEquals(1, FieldResearch.levelOf(10));
        assertEquals(2, FieldResearch.levelOf(40));
        assertEquals(3, FieldResearch.levelOf(100));
        assertEquals(3, FieldResearch.levelOf(199));
        assertEquals(4, FieldResearch.levelOf(200));
        assertEquals(0, FieldResearch.levelOf(-50));
    }

    @Test void raidCatchBonusDoublesAtMostAndNeverPassesTheCap() {
        // CobbleRaids' current odds: base 8/6/4/2%, perfect play 12/10/7/4%.
        assertEquals(0.16, FieldResearch.raidCatchChance(0.08, 100), 1e-12);
        assertEquals(0.24, FieldResearch.raidCatchChance(0.12, 100), 1e-12);
        assertEquals(0.08, FieldResearch.raidCatchChance(0.04, 100), 1e-12);
        assertEquals(0.12 * 1.5, FieldResearch.raidCatchChance(0.12, 50), 1e-12);
        assertEquals(0.25, FieldResearch.raidCatchChance(0.20, 100), 1e-12, "the cap binds");
        assertEquals(0.12, FieldResearch.raidCatchChance(0.12, 0), 1e-12);
    }

    @Test void raidCatchBonusNeverLowersAChanceAndSurvivesBadInput() {
        assertEquals(0.40, FieldResearch.raidCatchChance(0.40, 100), 1e-12, "a base above the cap is the server's choice: research leaves it");
        assertEquals(1.0, FieldResearch.raidCatchChance(5.0, 100), 1e-12);
        assertEquals(0.0, FieldResearch.raidCatchChance(Double.NaN, 100));
        assertEquals(0.0, FieldResearch.raidCatchChance(-0.1, 100));
        assertEquals(0.24, FieldResearch.raidCatchChance(0.12, 9_999), 1e-12, "boss research is clamped to 100%");
        assertEquals(0.12, FieldResearch.raidCatchChance(0.12, -40), 1e-12);
        for (int p = 0; p <= 100; p++) {
            for (double base = 0.001; base < 1; base += 0.013) {
                double chance = FieldResearch.raidCatchChance(base, p);
                assertTrue(chance >= base - 1e-12, "never lower");
                assertTrue(chance <= Math.max(base, FieldResearch.RAID_CATCH_CAP) + 1e-12, "never past the cap");
            }
        }
        assertEquals(2.0, FieldResearch.raidCatchMultiplier(100));
        assertEquals(2.0, FieldResearch.raidCatchMultiplier(400));
        assertEquals(1.0, FieldResearch.raidCatchMultiplier(-3));
    }

    @Test void operationsAreStablePerEventAndDistinctPerPlayer() {
        var boss = UUID.randomUUID().toString();
        assertEquals(FieldResearch.operation("raid", boss, ALICE), FieldResearch.operation("raid", boss, ALICE));
        assertNotEquals(FieldResearch.operation("raid", boss, ALICE), FieldResearch.operation("raid", boss, BOB));
        assertNotEquals(FieldResearch.operation("raid", boss, ALICE), FieldResearch.operation("lab-drive", boss, ALICE));
        assertNotEquals(FieldResearch.operation("raid", "a|b", ALICE), FieldResearch.operation("raid|a", "b", ALICE),
                "a delimiter inside a key cannot forge another event's operation"
                        + " (kinds are fixed constants in code, so only keys are external)");
        assertThrows(IllegalArgumentException.class, () -> FieldResearch.operation("", boss, ALICE));
        assertThrows(IllegalArgumentException.class, () -> FieldResearch.operation("raid", " ", ALICE));
        assertThrows(IllegalArgumentException.class, () -> FieldResearch.operation("raid", boss, null));
    }

    @Test void raidTiersPayByTier() {
        assertEquals(5, FieldResearch.RaidTier.fromId("starter").research);
        assertEquals(13, FieldResearch.RaidTier.fromId(" Mythical ").research);
        assertThrows(IllegalArgumentException.class, () -> FieldResearch.RaidTier.fromId("boss"));
    }

    @Test void beaconRules() {
        var full = new FieldResearch.BeaconStanding(ALICE, 4, 100, 0);
        assertEquals(FieldResearch.BeaconCheck.OK, FieldResearch.beaconCheck(List.of(full)));
        assertEquals(FieldResearch.BeaconCheck.NO_PARTICIPANTS, FieldResearch.beaconCheck(List.of()));
        assertEquals(FieldResearch.BeaconCheck.NEEDS_FAMILY_LEVEL_4, FieldResearch.beaconCheck(List.of(new FieldResearch.BeaconStanding(ALICE, 3, 100, 0))));
        assertEquals(FieldResearch.BeaconCheck.NEEDS_FULL_BOSS_RESEARCH, FieldResearch.beaconCheck(List.of(new FieldResearch.BeaconStanding(ALICE, 4, 90, 0))));
        assertEquals(FieldResearch.BeaconCheck.DAILY_LIMIT, FieldResearch.beaconCheck(List.of(new FieldResearch.BeaconStanding(ALICE, 4, 100, 1))));
        // A group: everyone must have the species fully researched (owner, decision 12 and follow-up 2).
        assertEquals(FieldResearch.BeaconCheck.OK, FieldResearch.beaconCheck(List.of(full, new FieldResearch.BeaconStanding(BOB, 4, 100, 5))),
                "only the owner spends a Beacon, so only the owner's daily count matters");
        assertEquals(FieldResearch.BeaconCheck.MEMBER_NOT_RESEARCHED,
                FieldResearch.beaconCheck(List.of(full, new FieldResearch.BeaconStanding(BOB, 4, 99, 0))));
        assertEquals(FieldResearch.BeaconCheck.MEMBER_NOT_RESEARCHED, FieldResearch.beaconCheck(List.of(full, full)), "a player listed twice");
        assertTrue(FieldResearch.fullyResearched(4, 100));
        assertFalse(FieldResearch.fullyResearched(3, 100));
    }

    @Test void lockerSpeciesIsStableAndAlwaysAKnownGuard() {
        var catalog = ResearchFamiliesTest.catalog();
        for (int i = 0; i < 200; i++) {
            String key = "minecraft:overworld/" + i + ",40,9";
            var species = FieldResearch.lockerSpecies(key, ALICE, catalog).orElseThrow();
            assertEquals(species, FieldResearch.lockerSpecies(key, ALICE, catalog).orElseThrow());
            assertTrue(FieldResearch.LAB_GUARD_SPECIES.contains(species) && catalog.knows(species));
        }
        var none = ResearchFamilies.fromPreEvolutions(java.util.Map.of("gible", ""));
        assertTrue(FieldResearch.lockerSpecies("k", ALICE, none).isEmpty());
    }

    @Test void daysAndWeeksAreUtcAndWeeksStartOnMonday() {
        long sunday = LocalDate.of(2026, 11, 22).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long monday = sunday + 86_400_000L;
        assertEquals(FieldResearch.utcWeek(sunday - 1), FieldResearch.utcWeek(sunday), "Saturday and Sunday share a week");
        assertEquals(FieldResearch.utcWeek(sunday) + 1, FieldResearch.utcWeek(monday), "Monday starts a new week");
        assertEquals(FieldResearch.utcDay(sunday) + 1, FieldResearch.utcDay(monday));
        assertEquals(FieldResearch.utcDay(monday), FieldResearch.utcDay(monday + 86_399_999L));
        assertEquals(-1, FieldResearch.utcDay(-1), "before 1970 still floors");
    }
}
