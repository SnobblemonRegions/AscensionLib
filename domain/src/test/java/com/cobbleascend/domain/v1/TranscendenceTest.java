package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.cobbleascend.domain.v1.Transcendence.Harmony;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TranscendenceTest {
    private static final Transcendence BOOK = Transcendence.defaults();
    private static final LoreCatalog LORE = LoreCatalog.defaults();
    private static final String A = "ashen_heart";
    private static final String B = "last_breath";

    @Test void everyCobblemonSpeciesHasResearchedLore() {
        assertEquals(1025, LORE.allSpecies().size());
        var dexes = new HashSet<Integer>();
        for (var lore : LORE.allSpecies().values()) {
            assertTrue(dexes.add(lore.dex()), "duplicate dex " + lore.dex());
            assertFalse(lore.basis().isBlank(), lore.species() + " needs a basis note");
            assertTrue(lore.basis().length() <= 120, lore.species() + " basis is long: " + lore.basis().length());
            assertEquals(lore.motifs().size(), new HashSet<>(lore.motifs()).size(), lore.species() + " repeats a motif");
        }
        for (int dex = 1; dex <= 1025; dex++) assertTrue(dexes.contains(dex), "missing dex " + dex);
    }

    @Test void everyMotifIsUsedAndRelationsAreConsistent() {
        var used = new HashSet<String>();
        for (var lore : LORE.allSpecies().values()) used.addAll(lore.motifs());
        assertEquals(LORE.motifs().keySet(), used, "an unused motif has no reason to exist");
        for (var a : LORE.motifs().keySet()) {
            for (var b : LORE.motifs().keySet()) {
                assertEquals(LORE.isKindred(a, b), LORE.isKindred(b, a));
                assertEquals(LORE.isOpposed(a, b), LORE.isOpposed(b, a));
                assertFalse(LORE.isKindred(a, b) && LORE.isOpposed(a, b), a + " and " + b + " cannot be both kindred and opposed");
            }
            assertFalse(LORE.isOpposed(a, a));
        }
    }

    @Test void thereIsOneBaseForEveryPairOfUniques() {
        assertEquals(21, BOOK.baseCount());
        var rules = RankedRules.defaults();
        var ids = rules.uniques().stream().map(UniqueDefinition::id).sorted().toList();
        var names = new HashSet<String>();
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                var result = BOOK.resolve("pikachu", ids.get(i), "bulbasaur", ids.get(j));
                assertEquals(List.of(ids.get(i), ids.get(j)), result.uniqueIds(), "unique ids are sorted");
                assertTrue(names.add(result.id()), "base ids are distinct: " + result.id());
            }
        }
    }

    @Test void sameInputsAlwaysGiveTheSameResult() {
        assertEquals(BOOK.resolve("gyarados", A, "pikachu", B), BOOK.resolve("gyarados", A, "pikachu", B));
        assertEquals(BOOK.resolve("gyarados", A, "pikachu", B), BOOK.resolve("gyarados", B, "pikachu", A),
                "which Unique is on which Pokemon does not change the result");
    }

    @Test void theLeftPokemonShapesTheOffenceAndTheRightTheDefence() {
        var forward = BOOK.resolve("charizard", A, "gyarados", B);
        assertEquals("fire", forward.hostType());
        assertEquals("water", forward.donorType());
        var reverse = BOOK.resolve("gyarados", A, "charizard", B);
        assertEquals("water", reverse.hostType());
        assertEquals("fire", reverse.donorType());
        assertNotEquals(forward.name(), reverse.name(), "the host adjective differs, so the name does");
    }

    @Test void aDerivedNameIsHostAdjectiveBaseAndDonorNoun() {
        var result = BOOK.resolve("charizard", A, "gyarados", B);
        var host = LORE.species("charizard").orElseThrow();
        var donor = LORE.species("gyarados").orElseThrow();
        assertEquals(LORE.motif(host.primaryMotif()).adjective() + " Phoenix Cinder of the " + LORE.motif(donor.primaryMotif()).noun(), result.name());
        assertNull(result.recipeId());
        assertTrue(result.blurb().contains("Charizard") && result.blurb().contains("Gyarados"));
    }

    @Test void sameEvolutionaryFamilyIsLineage() {
        var result = BOOK.resolve("charizard", A, "charmander", B);
        assertEquals(Harmony.LINEAGE, result.harmony());
        assertEquals("Phoenix Cinder of the Bloodline", result.name());
        assertEquals(Harmony.LINEAGE, BOOK.resolve("pikachu", A, "pikachu", B).harmony(), "two of the same species are family");
        assertEquals(Harmony.LINEAGE, BOOK.resolve("eevee", A, "vaporeon", B).harmony());
    }

    @Test void aCuratedPairWorksInEitherOrder() {
        var forward = BOOK.resolve("kyogre", A, "groudon", B);
        var reverse = BOOK.resolve("groudon", A, "kyogre", B);
        assertEquals("primordial_clash", forward.recipeId());
        assertEquals(forward.recipeId(), reverse.recipeId());
        assertEquals(Harmony.OPPOSED, forward.harmony());
        assertEquals("Phoenix Cinder of the Primordial Clash", forward.name());
        assertEquals("water", forward.hostType());
        assertEquals("ground", reverse.hostType(), "the host still shapes the offence");
    }

    @Test void aCuratedPairBeatsTheGroupBothBelongTo() {
        assertEquals("primordial_clash", BOOK.resolve("kyogre", A, "groudon", B).recipeId());
        assertEquals("weather_pact", BOOK.resolve("kyogre", A, "rayquaza", B).recipeId(), "no pair recipe, so the group applies");
    }

    @Test void aGroupAppliesToAnyTwoMembersButNotToOneAndAStranger() {
        assertEquals("kanto_triad", BOOK.resolve("venusaur", A, "blastoise", B).recipeId());
        assertEquals("kanto_triad", BOOK.resolve("charizard", A, "venusaur", B).recipeId());
        assertNull(BOOK.resolve("venusaur", A, "pikachu", B).recipeId());
        assertNull(BOOK.resolve("venusaur", A, "venusaur", B).recipeId(), "the same species twice is lineage, not a group");
    }

    @Test void theResearchedParadoxLinksAreCurated() {
        assertEquals("elder_tusk", BOOK.resolve("greattusk", A, "donphan", B).recipeId());
        assertEquals("foo_fighter", BOOK.resolve("ironmoth", A, "volcarona", B).recipeId());
        assertEquals("mecha_kaiju", BOOK.resolve("tyranitar", A, "ironthorns", B).recipeId());
    }

    @Test void scalesRiseWithRiskAndFallWithFit() {
        var lineage = BOOK.scale(Harmony.LINEAGE);
        var pure = BOOK.scale(Harmony.PURE);
        var kindred = BOOK.scale(Harmony.KINDRED);
        var neutral = BOOK.scale(Harmony.NEUTRAL);
        var opposed = BOOK.scale(Harmony.OPPOSED);
        assertTrue(lineage.benefitPercent() >= pure.benefitPercent() && pure.benefitPercent() >= kindred.benefitPercent()
                && kindred.benefitPercent() >= neutral.benefitPercent(), "better fit keeps more of the benefit");
        assertTrue(lineage.drawbackPercent() <= pure.drawbackPercent() && pure.drawbackPercent() <= kindred.drawbackPercent()
                && kindred.drawbackPercent() <= neutral.drawbackPercent(), "better fit takes less of the drawback");
        assertTrue(opposed.benefitPercent() > lineage.benefitPercent() && opposed.drawbackPercent() > neutral.drawbackPercent(),
                "an opposed fusion is volatile: the most benefit and the most drawback");
        for (var harmony : Harmony.values()) {
            var s = BOOK.scale(harmony);
            // Two benefits at this share must beat one Unique alone, and stay under the sum of both.
            assertTrue(s.benefitPercent() * 2 > 100 && s.benefitPercent() < 100, harmony + " benefit share must sit between one and two");
        }
    }

    @Test void everyPairOfSpeciesResolvesAndEveryHarmonyOccurs() {
        var seen = new EnumMap<Harmony, Integer>(Harmony.class);
        var all = new ArrayList<>(LORE.allSpecies().keySet());
        for (var host : all) {
            for (var donor : all) {
                var result = BOOK.resolve(host, A, donor, B);
                assertFalse(result.name().isBlank());
                assertTrue(result.benefitPercent() > 0 && result.drawbackPercent() > 0);
                seen.merge(result.harmony(), 1, Integer::sum);
            }
        }
        for (var harmony : Harmony.values()) assertTrue(seen.getOrDefault(harmony, 0) > 0, harmony + " never occurs");
        // A guard against a recipe book that collapses to one outcome: NEUTRAL must not be the overwhelming majority.
        int total = all.size() * all.size();
        assertTrue(seen.get(Harmony.NEUTRAL) < total * 0.7, "neutral share: " + seen.get(Harmony.NEUTRAL) + " of " + total);
    }

    @Test void invalidInputsAreRefusedWithAStableReason() {
        var sameUnique = assertThrows(CraftException.class, () -> BOOK.resolve("pikachu", A, "bulbasaur", A));
        assertEquals(CraftException.Reason.SAME_UNIQUE, sameUnique.reason());
        var unknownUnique = assertThrows(CraftException.class, () -> BOOK.resolve("pikachu", A, "bulbasaur", "nope"));
        assertEquals(CraftException.Reason.UNKNOWN_UNIQUE, unknownUnique.reason());
        var unknownSpecies = assertThrows(CraftException.class, () -> BOOK.resolve("missingno", A, "bulbasaur", B));
        assertEquals(CraftException.Reason.UNKNOWN_SPECIES, unknownSpecies.reason());
        assertEquals(CraftException.Reason.UNKNOWN_SPECIES,
                assertThrows(CraftException.class, () -> BOOK.resolve("pikachu", A, "missingno", B)).reason());
    }

    @Test void curatedRecipeIdsAreUniqueAndEveryGroupNamesRealSpecies() {
        assertTrue(BOOK.pairRecipeCount() >= 40, "pair recipes: " + BOOK.pairRecipeCount());
        assertTrue(BOOK.groupRecipeCount() >= 30, "group recipes: " + BOOK.groupRecipeCount());
        Set<String> ids = new HashSet<>();
        for (var lore : LORE.allSpecies().values()) {
            for (var other : List.of("pikachu", "mew")) {
                var result = BOOK.resolve(lore.species(), A, other, B);
                if (result.recipeId() != null) ids.add(result.recipeId());
            }
        }
        assertFalse(ids.isEmpty());
    }

    @Test void recipeHintsNameThePartnersThatCompleteANamedRecipe() {
        var kyogre = BOOK.recipesFor("kyogre", 3);
        assertTrue(kyogre.stream().anyMatch(h -> h.partnerSpecies().equals("groudon")), "Kyogre's pair recipe names Groudon");
        assertTrue(kyogre.stream().noneMatch(h -> h.partnerSpecies().equals("kyogre")), "a species is never its own partner");
        assertTrue(BOOK.recipesFor("not_a_species", 3).isEmpty());
        for (var species : new String[] {"bulbasaur", "pikachu", "eevee", "gyarados"})
            BOOK.recipesFor(species, 2).forEach(h -> assertFalse(h.recipeName().isBlank()));
    }
}
