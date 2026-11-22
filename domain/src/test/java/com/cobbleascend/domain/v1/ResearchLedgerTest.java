package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ResearchLedgerTest {
    private final ResearchFamilies.Catalog catalog = ResearchFamiliesTest.catalog();

    @Test void aSpeciesNeverHoldsMoreThanItsShareAndTheRestIsReportedWasted() {
        var credit = ResearchLedger.EMPTY.credit(catalog, "gible", 40);
        assertEquals(40, credit.applied());
        credit = credit.ledger().credit(catalog, "gible", 40);
        assertEquals(10, credit.applied());
        assertEquals(30, credit.wasted());
        var full = credit.ledger().credit(catalog, "gible", 40);
        assertEquals(0, full.applied());
        assertEquals(40, full.wasted());
        assertSame(credit.ledger(), full.ledger(), "a full share changes nothing");
        assertEquals(50, full.ledger().familyPoints(catalog, "gible"));
    }

    @Test void farmingOneStageCannotMaxAFamily() {
        var ledger = ResearchLedger.EMPTY;
        for (int i = 0; i < 100; i++) ledger = ledger.credit(catalog, "garchomp", 40).ledger();
        assertEquals(90, ledger.familyPoints(catalog, "gible"));
        assertEquals(2, ledger.familyLevel(catalog, "gible"), "the final stage alone reaches level 2, never 3 or 4");
        ledger = ledger.credit(catalog, "gible", 50).ledger().credit(catalog, "gabite", 60).ledger();
        assertEquals(200, ledger.familyPoints(catalog, "gible"));
        assertEquals(4, ledger.familyLevel(catalog, "gible"));
        assertEquals(4, ledger.levelForSpecies(catalog, "gabite"));
    }

    @Test void levelsFollowTheThresholds() {
        var credit = ResearchLedger.EMPTY.credit(catalog, "electrode", 9);
        assertEquals(0, credit.levelAfter());
        credit = credit.ledger().credit(catalog, "electrode", 1);
        assertEquals(1, credit.levelAfter());
        assertTrue(credit.levelledUp());
    }

    @Test void badGrantsThrowAndChangeNothing() {
        assertThrows(IllegalArgumentException.class, () -> ResearchLedger.EMPTY.credit(catalog, "missingno", 10));
        assertThrows(IllegalArgumentException.class, () -> ResearchLedger.EMPTY.credit(catalog, "gible", 0));
        assertThrows(IllegalArgumentException.class, () -> ResearchLedger.EMPTY.credit(catalog, "gible", -5));
        assertThrows(IllegalArgumentException.class, () -> ResearchLedger.EMPTY.credit(catalog, "gible", FieldResearch.MAX_GRANT + 1));
        assertThrows(IllegalArgumentException.class, () -> ResearchLedger.EMPTY.credit(catalog, "gible", Integer.MIN_VALUE));
    }

    @Test void researchNeverGoesDownOrPastItsCapsUnderAnySequence() {
        var random = new Random(11);
        var species = List.copyOf(catalog.familyOfSpecies().keySet());
        var ledger = ResearchLedger.EMPTY;
        var previous = new HashMap<String, Integer>();
        for (int step = 0; step < 20_000; step++) {
            String s = species.get(random.nextInt(species.size()));
            if (random.nextInt(5) == 0) {
                if (ledger.bossPercentOf(s) < 100) ledger = ledger.analyze(catalog, s).ledger();
            } else {
                ledger = ledger.credit(catalog, s, 1 + random.nextInt(60)).ledger();
            }
            for (var family : catalog.families().keySet()) {
                int points = ledger.familyPoints(catalog, family);
                assertTrue(points >= previous.getOrDefault(family, 0), "a family meter never goes down");
                assertTrue(points <= ResearchFamilies.FAMILY_MAX);
                previous.put(family, points);
            }
            assertTrue(ledger.pointsOf(catalog, s) <= catalog.shareOf(s));
            assertTrue(ledger.bossPercentOf(s) >= 0 && ledger.bossPercentOf(s) <= 100);
        }
    }

    @Test void aShrunkCatalogCanNeverLeaveAMeterAboveItsMaximum() {
        // Stored points from an older catalog where Gible was a single-stage family (share 200).
        var stored = new ResearchLedger(Map.of("gible", 200, "gabite", 60, "garchomp", 90), Map.of());
        assertEquals(50, stored.pointsOf(catalog, "gible"));
        assertEquals(200, stored.familyPoints(catalog, "gible"));
        assertEquals(0, stored.roomFor(catalog, "gible"));
    }

    @Test void storedDataIsCleanedOnLoad() {
        var ledger = new ResearchLedger(Map.of("GIBLE", 30, "gabite", -4, "garchomp", 0), Map.of("Gible", 450, "voltorb", -1));
        assertEquals(30, ledger.pointsOf(catalog, "gible"));
        assertEquals(0, ledger.pointsOf(catalog, "gabite"));
        assertFalse(ledger.speciesPoints().containsKey("gabite"));
        assertEquals(100, ledger.bossPercentOf("gible"), "boss research is clamped to 100");
        assertEquals(0, ledger.bossPercentOf("voltorb"));
        var empty = new ResearchLedger(null, null);
        assertTrue(empty.speciesPoints().isEmpty() && empty.bossPercent().isEmpty());
    }

    @Test void caseVariantsOfTheSameSpeciesNeverDoubleCount() {
        var ledger = new ResearchLedger(Map.of("gible", 30, "Gible", 40, " GIBLE ", 20), Map.of());
        assertEquals(40, ledger.pointsOf(catalog, "gible"), "the largest stored value wins, never the sum");
    }

    @Test void analysisAddsTenPercentAndRefusesAtOneHundred() {
        var ledger = ResearchLedger.EMPTY;
        for (int i = 1; i <= 10; i++) {
            var a = ledger.analyze(catalog, "garchomp");
            assertEquals((i - 1) * 10, a.percentBefore());
            assertEquals(i * 10, a.percentAfter());
            ledger = a.ledger();
        }
        final var done = ledger;
        assertThrows(IllegalStateException.class, () -> done.analyze(catalog, "garchomp"), "an 11th sample would be burnt for nothing");
        assertThrows(IllegalArgumentException.class, () -> ResearchLedger.EMPTY.analyze(catalog, "missingno"));
        assertEquals(0, done.bossPercentOf("gible"), "boss research is per species, not per family (owner, decision 19)");
    }

    @Test void highestLevelLooksAcrossFamilies() {
        var ledger = ResearchLedger.EMPTY.credit(catalog, "electrode", 120).ledger().credit(catalog, "garchomp", 15).ledger();
        assertEquals(3, ledger.familyLevel(catalog, "voltorb"));
        assertEquals(1, ledger.familyLevel(catalog, "gible"));
        assertEquals(3, ledger.highestLevel(catalog));
        assertEquals(0, ResearchLedger.EMPTY.highestLevel(catalog));
    }
}
