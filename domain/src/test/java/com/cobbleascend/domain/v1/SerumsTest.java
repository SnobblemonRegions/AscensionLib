package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.cobbleascend.domain.v1.Serums.ArmCheck;
import com.cobbleascend.domain.v1.Serums.CaptureFacts;
import com.cobbleascend.domain.v1.Serums.CaptureKind;
import com.cobbleascend.domain.v1.Serums.CraftCheck;
import com.cobbleascend.domain.v1.Serums.Kind;
import com.cobbleascend.domain.v1.Serums.Stat;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SerumsTest {
    private final ResearchFamilies.Catalog catalog = ResearchFamiliesTest.catalog();
    private static final CaptureFacts WILD_GABITE = new CaptureFacts("gabite", false, false, false, CaptureKind.WILD);
    private static final int[] LOW = {3, 7, 0, 12, 30, 19};

    @Test void idsAndNumbersMatchTheDesign() {
        assertEquals("floor_serum", Kind.FLOOR.id);
        assertEquals("focus_serum", Kind.FOCUS.id);
        assertEquals("prime_serum", Kind.PRIME.id);
        assertEquals(Kind.PRIME, Kind.fromId("prime_serum"));
        assertThrows(IllegalArgumentException.class, () -> Kind.fromId("mega_serum"));
        assertEquals(60L, Kind.FLOOR.cost.get(MaterialId.RESONANCE_DUST));
        assertEquals(3L, Kind.FOCUS.cost.get(MaterialId.FACET));
        assertEquals(1L, Kind.PRIME.cost.get(MaterialId.TOWER_MARK));
        assertEquals(1, Kind.PRIME.bossSamples);
        assertEquals(5, Kind.FLOOR.limit);
        assertEquals(Serums.Period.WEEK, Kind.PRIME.period);
        for (var kind : Kind.values()) {
            assertFalse(kind.cost.containsKey(MaterialId.ASCENSION_CORE), "serums never cost a Core");
            assertTrue(kind.cost.values().stream().allMatch(v -> v > 0));
        }
    }

    @Test void craftingNeedsResearchAndRespectsLimits() {
        assertEquals(CraftCheck.OK, Serums.craftCheck(Kind.FLOOR, 2, 0, 0, 5));
        assertEquals(CraftCheck.NEEDS_RESEARCH, Serums.craftCheck(Kind.FLOOR, 1, 0, 0, 1));
        assertEquals(CraftCheck.NEEDS_RESEARCH, Serums.craftCheck(Kind.PRIME, 3, 0, 9, 1));
        assertEquals(CraftCheck.LIMIT_REACHED, Serums.craftCheck(Kind.FLOOR, 4, 4, 0, 2));
        assertEquals(CraftCheck.LIMIT_REACHED, Serums.craftCheck(Kind.FOCUS, 4, 2, 0, 1));
        assertEquals(CraftCheck.LIMIT_REACHED, Serums.craftCheck(Kind.FLOOR, 4, -1, 0, 1), "a corrupt negative count is refused, not trusted");
        assertEquals(CraftCheck.BAD_QUANTITY, Serums.craftCheck(Kind.FLOOR, 4, 0, 0, 0));
        assertEquals(CraftCheck.BAD_QUANTITY, Serums.craftCheck(Kind.FLOOR, 4, 0, 0, 6));
        assertEquals(CraftCheck.BAD_QUANTITY, Serums.craftCheck(Kind.FLOOR, 4, 0, 0, Integer.MIN_VALUE));
        assertEquals(CraftCheck.NEEDS_BOSS_SAMPLE, Serums.craftCheck(Kind.PRIME, 4, 0, 1, 2));
        assertEquals(CraftCheck.OK, Serums.craftCheck(Kind.PRIME, 4, 1, 2, 2));
        assertEquals(CraftCheck.LIMIT_REACHED, Serums.craftCheck(Kind.PRIME, 4, Integer.MAX_VALUE, 9, 1), "no overflow past the limit");
    }

    @Test void costScalesWithQuantity() {
        assertEquals(300L, Serums.costOf(Kind.FLOOR, 5).get(MaterialId.RESONANCE_DUST));
        assertEquals(6L, Serums.costOf(Kind.FOCUS, 2).get(MaterialId.FACET));
        assertThrows(IllegalArgumentException.class, () -> Serums.costOf(Kind.FLOOR, 0));
        assertThrows(IllegalArgumentException.class, () -> Serums.costOf(Kind.FOCUS, 3));
    }

    @Test void armingNeedsResearchOnThatFamilyAndAValidChoice() {
        assertEquals(ArmCheck.OK, Serums.armCheck(Kind.FOCUS, catalog, "gible", List.of(Stat.ATTACK, Stat.SPEED), 3, false, false));
        assertEquals(ArmCheck.NEEDS_RESEARCH, Serums.armCheck(Kind.FOCUS, catalog, "gible", List.of(Stat.ATTACK, Stat.SPEED), 2, false, false),
                "a bought serum is useless without the research (owner follow-up 1)");
        assertEquals(ArmCheck.UNKNOWN_FAMILY, Serums.armCheck(Kind.FLOOR, catalog, "missingno", List.of(Stat.HP), 4, false, false));
        assertEquals(ArmCheck.WRONG_STAT_COUNT, Serums.armCheck(Kind.FOCUS, catalog, "gible", List.of(Stat.ATTACK), 4, false, false));
        assertEquals(ArmCheck.WRONG_STAT_COUNT, Serums.armCheck(Kind.FLOOR, catalog, "gible", List.of(), 4, false, false));
        assertEquals(ArmCheck.WRONG_STAT_COUNT, Serums.armCheck(Kind.FLOOR, catalog, "gible", null, 4, false, false));
        assertEquals(ArmCheck.WRONG_STAT_COUNT, Serums.armCheck(Kind.FLOOR, catalog, "gible", Arrays.asList((Stat) null), 4, false, false));
        assertEquals(ArmCheck.DUPLICATE_STAT, Serums.armCheck(Kind.FOCUS, catalog, "gible", List.of(Stat.SPEED, Stat.SPEED), 4, false, false));
        assertEquals(ArmCheck.ALREADY_ARMED, Serums.armCheck(Kind.FLOOR, catalog, "gible", List.of(Stat.HP), 4, true, false));
        assertEquals(ArmCheck.EXCLUDED_FAMILY, Serums.armCheck(Kind.FLOOR, catalog, "ditto", List.of(Stat.HP), 4, false, true));
        assertEquals(Stat.DEFENCE, Stat.fromId("defense"));
        assertEquals(Stat.SPECIAL_DEFENCE, Stat.fromId("special defense"));
    }

    private static Serums.Armed armed(Kind kind, String family, Stat... stats) { return new Serums.Armed(kind, family, List.of(stats)); }

    @Test void aFloorRaisesOnlyWhatIsBelowItAndNeverLowersAnything() {
        var out = Serums.apply(armed(Kind.FLOOR, "gible", Stat.SPEED), catalog, WILD_GABITE, 2, LOW, 1);
        assertTrue(out.applied());
        assertArrayEquals(new int[] {3, 7, 0, 12, 30, 20}, out.ivs());
        assertEquals(List.of(Stat.SPEED), out.raised());
        var high = Serums.apply(armed(Kind.FLOOR, "gible", Stat.SPECIAL_DEFENCE), catalog, WILD_GABITE, 2, LOW, 1);
        assertTrue(high.applied(), "an eligible capture uses the serum up even when the stat was already high");
        assertArrayEquals(LOW, high.ivs());
        assertEquals("already_met", high.reason());
    }

    @Test void focusRaisesTwoChosenStats() {
        var out = Serums.apply(armed(Kind.FOCUS, "gible", Stat.ATTACK, Stat.SPEED), catalog, WILD_GABITE, 3, LOW, 1);
        assertArrayEquals(new int[] {3, 25, 0, 12, 30, 25}, out.ivs());
    }

    @Test void primeSetsTheChosenStatAndOneRandomOtherToThirtyOne() {
        var out = Serums.apply(armed(Kind.PRIME, "gible", Stat.ATTACK), catalog, WILD_GABITE, 4, LOW, 99);
        int[] ivs = out.ivs();
        assertEquals(31, ivs[Stat.ATTACK.ordinal()]);
        long perfect = Arrays.stream(ivs).filter(v -> v == 31).count();
        assertEquals(2, perfect);
        assertEquals(2, out.raised().size());
        assertNotEquals(Stat.ATTACK, out.raised().get(1));
        assertArrayEquals(ivs, Serums.apply(armed(Kind.PRIME, "gible", Stat.ATTACK), catalog, WILD_GABITE, 4, LOW, 99).ivs(),
                "a retried capture with the same seed writes the same IVs");
    }

    @Test void primeNeverWastesItsRandomStatOnAPerfectOne() {
        int[] mostlyPerfect = {31, 31, 31, 31, 31, 4};
        for (long seed = 0; seed < 50; seed++) {
            var out = Serums.apply(armed(Kind.PRIME, "gible", Stat.HP), catalog, WILD_GABITE, 4, mostlyPerfect, seed);
            assertArrayEquals(new int[] {31, 31, 31, 31, 31, 31}, out.ivs());
        }
        var allPerfect = Serums.apply(armed(Kind.PRIME, "gible", Stat.HP), catalog, WILD_GABITE, 4, new int[] {31, 31, 31, 31, 31, 31}, 3);
        assertArrayEquals(new int[] {31, 31, 31, 31, 31, 31}, allPerfect.ivs());
    }

    @Test void theRandomStatIsSpreadAcrossTheOthers() {
        var counts = new int[6];
        for (long seed = 0; seed < 6_000; seed++) {
            var out = Serums.apply(armed(Kind.PRIME, "gible", Stat.HP), catalog, WILD_GABITE, 4, new int[6], seed);
            counts[out.raised().get(1).ordinal()]++;
        }
        assertEquals(0, counts[Stat.HP.ordinal()]);
        for (int i = 1; i < 6; i++) assertEquals(1_200, counts[i], 150, "stat " + Stat.values()[i]);
    }

    @Test void nothingAppliesToExcludedCaptures() {
        var serum = armed(Kind.FLOOR, "gible", Stat.HP);
        for (var kind : List.of(CaptureKind.RAID, CaptureKind.GIFT, CaptureKind.EGG, CaptureKind.OTHER)) {
            var out = Serums.apply(serum, catalog, new CaptureFacts("gabite", false, false, false, kind), 4, LOW, 1);
            assertFalse(out.applied(), kind.name());
            assertArrayEquals(LOW, out.ivs());
        }
        assertFalse(Serums.apply(serum, catalog, new CaptureFacts("gabite", true, false, false, CaptureKind.WILD), 4, LOW, 1).applied());
        assertFalse(Serums.apply(serum, catalog, new CaptureFacts("gabite", false, true, false, CaptureKind.WILD), 4, LOW, 1).applied());
        assertFalse(Serums.apply(serum, catalog, new CaptureFacts("gabite", false, false, true, CaptureKind.WILD), 4, LOW, 1).applied());
        var ditto = Serums.apply(armed(Kind.FLOOR, "ditto", Stat.HP), catalog, new CaptureFacts("Ditto", false, false, false, CaptureKind.WILD), 4, LOW, 1);
        assertFalse(ditto.applied(), "Ditto never takes a serum, it would spread IVs into every egg group");
        assertFalse(Serums.apply(serum, catalog, null, 4, LOW, 1).applied());
    }

    @Test void aSerumOnlyFitsItsOwnFamilyAndIsRecheckedAtTheCapture() {
        var serum = armed(Kind.FOCUS, "gible", Stat.HP, Stat.SPEED);
        assertEquals("other_family", Serums.apply(serum, catalog, new CaptureFacts("voltorb", false, false, false, CaptureKind.WILD), 4, LOW, 1).reason());
        assertEquals("other_family", Serums.apply(serum, catalog, new CaptureFacts("missingno", false, false, false, CaptureKind.WILD), 4, LOW, 1).reason());
        assertEquals("needs_research", Serums.apply(serum, catalog, WILD_GABITE, 2, LOW, 1).reason());
        assertTrue(Serums.apply(serum, catalog, new CaptureFacts("GARCHOMP", false, false, false, CaptureKind.WILD), 3, LOW, 1).applied(),
                "any species in the family, any case");
    }

    @Test void badInputIsClampedOrRefusedNeverTrusted() {
        var serum = armed(Kind.FLOOR, "gible", Stat.HP);
        var out = Serums.apply(serum, catalog, WILD_GABITE, 4, new int[] {-9, 99, 15, 15, 15, 15}, 1);
        assertArrayEquals(new int[] {20, 31, 15, 15, 15, 15}, out.ivs(), "values outside 0..31 are clamped before anything else");
        assertEquals("bad_ivs", Serums.apply(serum, catalog, WILD_GABITE, 4, new int[5], 1).reason());
        assertEquals("bad_ivs", Serums.apply(serum, catalog, WILD_GABITE, 4, null, 1).reason());
        var tampered = new Serums.Armed(Kind.FOCUS, "gible", List.of(Stat.HP));
        assertEquals("bad_choice", Serums.apply(tampered, catalog, WILD_GABITE, 4, LOW, 1).reason(), "a stored serum with the wrong stat count does nothing");
        var duplicate = new Serums.Armed(Kind.FOCUS, "gible", List.of(Stat.HP, Stat.HP));
        assertEquals("bad_choice", Serums.apply(duplicate, catalog, WILD_GABITE, 4, LOW, 1).reason());
    }

    @Test void theOutcomeCannotBeChangedFromOutside() {
        int[] input = LOW.clone();
        var out = Serums.apply(armed(Kind.FLOOR, "gible", Stat.HP), catalog, WILD_GABITE, 4, input, 1);
        input[0] = 0;
        out.ivs()[0] = 0;
        assertEquals(20, out.ivs()[0]);
    }

    @Test void craftLimitsCountByUtcDayOrWeek() {
        long t = java.time.Instant.parse("2026-11-22T23:59:59Z").toEpochMilli();
        assertNotEquals(Kind.FLOOR.periodOf(t), Kind.FLOOR.periodOf(t + 1000), "a new UTC day");
        assertNotEquals(Kind.PRIME.periodOf(t), Kind.PRIME.periodOf(t + 1000), "Sunday to Monday is a new week");
        assertEquals(Kind.PRIME.periodOf(t + 1000), Kind.PRIME.periodOf(t + 6 * 86_400_000L), "Monday to Sunday is one week");
    }

    @Test void seedsAreStablePerCapture() {
        var pokemon = UUID.randomUUID();
        var op = UUID.randomUUID();
        assertEquals(Serums.seed(pokemon, op), Serums.seed(pokemon, op));
        assertNotEquals(Serums.seed(pokemon, op), Serums.seed(UUID.randomUUID(), op));
    }
}
