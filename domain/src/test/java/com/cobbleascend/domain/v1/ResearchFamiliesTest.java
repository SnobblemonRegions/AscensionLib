package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class ResearchFamiliesTest {
    /** A small dex: chains of one, two and three stages, a three-way branch, a branch in the middle and Eevee's eight. */
    static Map<String, String> dex() {
        var pre = new LinkedHashMap<String, String>();
        pre.put("ditto", null);
        pre.put("voltorb", null); pre.put("electrode", "voltorb");
        pre.put("gible", null); pre.put("gabite", "gible"); pre.put("garchomp", "gabite");
        pre.put("wurmple", null); pre.put("silcoon", "wurmple"); pre.put("cascoon", "wurmple");
        pre.put("beautifly", "silcoon"); pre.put("dustox", "cascoon");
        pre.put("eevee", null);
        for (var e : List.of("vaporeon", "jolteon", "flareon", "espeon", "umbreon", "leafeon", "glaceon", "sylveon")) pre.put(e, "eevee");
        pre.put("magnemite", null); pre.put("magneton", "magnemite"); pre.put("magnezone", "magneton");
        pre.put("porygon", null); pre.put("porygon2", "porygon"); pre.put("porygonz", "porygon2");
        pre.put("klink", null); pre.put("klang", "klink"); pre.put("klinklang", "klang");
        pre.put("mewtwo", null);
        return pre;
    }

    static ResearchFamilies.Catalog catalog() { return ResearchFamilies.fromPreEvolutions(dex()); }

    @Test void stageWeightsAlwaysSumToAHundredAndFavourLaterStages() {
        for (int n = 1; n <= 8; n++) {
            int[] w = ResearchFamilies.stageWeights(n);
            assertEquals(n, w.length);
            int sum = 0;
            for (int i = 0; i < n; i++) {
                sum += w[i];
                if (i > 0) assertTrue(w[i] >= w[i - 1], "later stages are worth at least as much, n=" + n);
            }
            assertEquals(100, sum, "n=" + n);
        }
        assertArrayEquals(new int[] {25, 30, 45}, ResearchFamilies.stageWeights(3));
        assertArrayEquals(new int[] {40, 60}, ResearchFamilies.stageWeights(2));
        assertThrows(IllegalArgumentException.class, () -> ResearchFamilies.stageWeights(0));
    }

    @Test void everyFamilySumsToExactlyTwoHundred() {
        var c = catalog();
        for (var f : c.families().values()) {
            int sum = f.shares().values().stream().mapToInt(Integer::intValue).sum();
            assertEquals(ResearchFamilies.FAMILY_MAX, sum, f.id());
        }
    }

    @Test void sharesAreWeightedTowardLaterStages() {
        var c = catalog();
        assertEquals(50, c.shareOf("gible"));
        assertEquals(60, c.shareOf("gabite"));
        assertEquals(90, c.shareOf("garchomp"));
        assertEquals(80, c.shareOf("voltorb"));
        assertEquals(120, c.shareOf("electrode"));
        assertEquals(200, c.shareOf("ditto"));
        assertEquals(80, c.shareOf("eevee"));
        // Eevee's 120 split eight ways: 15 each.
        assertEquals(15, c.shareOf("sylveon"));
        // Wurmple: 50, then 60 split between Silcoon and Cascoon, then 90 split between Beautifly and Dustox.
        assertEquals(50, c.shareOf("wurmple"));
        assertEquals(30, c.shareOf("silcoon") );
        assertEquals(45, c.shareOf("dustox"));
        assertEquals(0, c.shareOf("missingno"));
    }

    @Test void manyBranchesWithASmallShareStillSumExactly() {
        var pre = new HashMap<String, String>();
        pre.put("root", null);
        for (int i = 0; i < 130; i++) pre.put("leaf" + i, "root");                 // 120 points over 130 leaves: most get 0, the last gets the rest
        var f = ResearchFamilies.fromPreEvolutions(pre).family("root").orElseThrow();
        assertEquals(200, f.shares().values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(f.shares().values().stream().allMatch(v -> v >= 0));
    }

    @Test void theCatalogDoesNotDependOnInputOrder() {
        var entries = new ArrayList<>(dex().entrySet());
        var expected = catalog();
        var random = new Random(7);
        for (int round = 0; round < 20; round++) {
            Collections.shuffle(entries, random);
            var shuffled = new LinkedHashMap<String, String>();
            for (var e : entries) shuffled.put(e.getKey(), e.getValue());
            var c = ResearchFamilies.fromPreEvolutions(shuffled);
            assertEquals(expected.families(), c.families());
            assertEquals(expected.familyOfSpecies(), c.familyOfSpecies());
        }
    }

    @Test void everySpeciesIsInExactlyOneFamily() {
        var c = catalog();
        int counted = 0;
        for (var f : c.families().values()) counted += f.species().size();
        assertEquals(c.familyOfSpecies().size(), counted);
        assertEquals("gible", c.familyOf("garchomp").orElseThrow().id());
        assertEquals("gible", c.familyOf("  GaRChomp ").orElseThrow().id(), "ids are case- and space-insensitive");
        assertTrue(c.familyOf("missingno").isEmpty());
    }

    @Test void oddDataNeverThrowsAndIsReported() {
        var pre = new HashMap<String, String>();
        pre.put("a", "b"); pre.put("b", "c"); pre.put("c", "a");                     // a cycle
        pre.put("d", "a");                                                         // hangs below the cycle
        pre.put("lonely", "not_a_species");                                        // unknown pre-evolution
        pre.put("selfie", "selfie");                                               // its own pre-evolution
        pre.put("  ", "x");                                                        // blank key: ignored
        var c = ResearchFamilies.fromPreEvolutions(pre);
        assertTrue(c.knows("a") && c.knows("b") && c.knows("c") && c.knows("d") && c.knows("lonely") && c.knows("selfie"));
        assertFalse(c.knows("not_a_species"));
        for (var f : c.families().values()) assertEquals(200, f.shares().values().stream().mapToInt(Integer::intValue).sum(), f.id());
        assertEquals("lonely", c.familyOf("lonely").orElseThrow().id());
        assertEquals("selfie", c.familyOf("selfie").orElseThrow().id());
        assertEquals("a", c.familyOf("d").orElseThrow().id(), "a species below a cycle hangs from the cycle member it evolves from");
        assertEquals(2, c.problems().size(), c.problems().toString());
    }

    @Test void evolutionsDeclaredOnTheParentAloneStillMakeAFamily() {
        // An addon that only lists "evolutions" on the parent, never "preEvolution" on the child.
        var pre = new HashMap<String, String>();
        for (var s : List.of("mymod:sprout", "mymod:sapling", "mymod:elder")) pre.put(s, null);
        var evolves = Map.of("mymod:sprout", List.of("mymod:sapling"), "mymod:sapling", List.of("mymod:elder"));
        var c = ResearchFamilies.fromLinks(pre, evolves);
        assertEquals("mymod:sprout", c.familyOf("mymod:elder").orElseThrow().id());
        assertEquals(3, c.family("mymod:sprout").orElseThrow().stages().size());
        assertEquals(90, c.shareOf("mymod:elder"));
        assertTrue(c.problems().isEmpty(), c.problems().toString());
    }

    @Test void aDeclaredPreEvolutionWinsOverAConflictingListing() {
        var pre = new HashMap<String, String>();
        pre.put("a", null); pre.put("b", null); pre.put("c", "a");
        var c = ResearchFamilies.fromLinks(pre, Map.of("b", List.of("c")));
        assertEquals("a", c.familyOf("c").orElseThrow().id());
        assertEquals(1, c.problems().size(), c.problems().toString());
    }

    @Test void twoParentsListingTheSameChildJoinTheSmallestAndAreReported() {
        var pre = new HashMap<String, String>();
        pre.put("x", null); pre.put("y", null); pre.put("z", null);
        var c = ResearchFamilies.fromLinks(pre, Map.of("y", List.of("z"), "x", List.of("z")));
        assertEquals("x", c.familyOf("z").orElseThrow().id());
        assertEquals(1, c.problems().size());
    }

    @Test void linksToSpeciesTheServerDoesNotHaveAreIgnoredSoAFamilyCanAlwaysBeFilled() {
        // Gible's line where Garchomp is not implemented (or its mod is missing): the family is Gible and Gabite only, worth 200 between them.
        var pre = new HashMap<String, String>();
        pre.put("gible", null); pre.put("gabite", "gible");
        var c = ResearchFamilies.fromLinks(pre, Map.of("gabite", List.of("garchomp")));
        assertFalse(c.knows("garchomp"));
        assertEquals(200, c.shareOf("gible") + c.shareOf("gabite"));
        assertEquals(1, c.problems().size());
        // A child whose declared pre-evolution is missing becomes a root of its own family.
        var orphan = ResearchFamilies.fromLinks(Map.of("garchomp", "gabite"), Map.of());
        assertEquals(200, orphan.shareOf("garchomp"));
    }

    @Test void namespacedSpeciesNeverShareResearchWithCobblemonsOfTheSameName() {
        var pre = new HashMap<String, String>();
        pre.put("pikachu", null); pre.put("mymod:pikachu", null);
        var c = ResearchFamilies.fromLinks(pre, Map.of());
        assertNotEquals(c.familyOf("pikachu").orElseThrow().id(), c.familyOf("mymod:pikachu").orElseThrow().id());
    }

    @Test void crossNamespaceEvolutionsJoinOneFamily() {
        var pre = new HashMap<String, String>();
        pre.put("eevee", null); pre.put("mymod:aetheon", "eevee"); pre.put("vaporeon", "eevee");
        var c = ResearchFamilies.fromLinks(pre, Map.of());
        assertEquals("eevee", c.familyOf("mymod:aetheon").orElseThrow().id());
        assertEquals(60, c.shareOf("mymod:aetheon"), "120 for the second stage, split between two evolutions");
    }
}
