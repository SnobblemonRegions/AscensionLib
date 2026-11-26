package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.cobbleascend.domain.v1.Transcendence.Pulse;
import com.cobbleascend.domain.v1.Transcendence.Twist;
import com.google.gson.JsonParser;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Transcendent power tables (docs/TRANSCENDENT-POWERS.md): 21 signatures, 25 motif twists, 25 riders, 80 bespoke recipe twists. */
class TranscendentPowersTest {
    private static final Transcendence BOOK = Transcendence.defaults();
    private static final LoreCatalog LORE = LoreCatalog.defaults();
    private static final RankedRules RULES = RankedRules.defaults();
    private static final String A = "ashen_heart";
    private static final String B = "last_breath";

    @Test void thereIsASignatureWithAPulseAndTextsForEveryPair() {
        assertEquals(105, BOOK.signatures().size(), "21 hand-written and 84 composed, one per pair of the 15 Uniques");
        var pulses = new HashSet<Pulse>();
        for (var signature : BOOK.signatures().values()) {
            pulses.add(signature.pulse());
            assertFalse(signature.core().isBlank(), signature.id() + " needs a core");
            assertFalse(signature.drawback().isBlank(), signature.id() + " needs a drawback (exactly one, in its own words)");
        }
        assertTrue(pulses.containsAll(List.of(Pulse.HIT, Pulse.STRUCK, Pulse.KO, Pulse.TICK)), "pulses in use: " + pulses);
    }

    @Test void everyMotifHasATwistAndARider() {
        assertEquals(LORE.motifs().keySet(), BOOK.motifTwists().keySet());
        assertEquals(LORE.motifs().keySet(), BOOK.motifRiders().keySet());
        var names = new HashSet<String>();
        for (var twist : BOOK.motifTwists().values()) {
            assertEquals(Twist.Source.MOTIF, twist.source());
            assertTrue(twist.effects().size() >= 1 && twist.effects().size() <= 2);
            assertTrue(names.add(twist.name()), "twist names are distinct: " + twist.name());
        }
        for (var rider : BOOK.motifRiders().values()) {
            assertNotNull(RULES.affix(rider.affix()), rider.affix() + " is an existing affix");
            assertTrue(rider.value() >= 1 && rider.value() <= 20);
        }
    }

    @Test void everyCuratedRecipeHasItsOwnBespokeTwist() {
        assertEquals(80, BOOK.pairRecipeCount() + BOOK.groupRecipeCount());
        var recipeTwists = new HashSet<String>();
        int checked = 0;
        for (var lore : LORE.allSpecies().values()) {
            for (var other : List.of("kyogre", "groudon", "kanto", "pikachu")) {
                if (!LORE.species(other).isPresent()) continue;
                var result = BOOK.resolve(lore.species(), A, other, B);
                if (result.recipeId() != null) {
                    assertEquals(Twist.Source.RECIPE, result.twist().source(), result.recipeId() + " has a bespoke twist");
                    recipeTwists.add(result.recipeId());
                    checked++;
                }
            }
        }
        assertTrue(checked > 0);
    }

    @Test void aCuratedRecipeReplacesTheMotifTwistAndADerivedOneUsesTheHostMotif() {
        var curated = BOOK.resolve("kyogre", A, "groudon", B);
        assertEquals("Cataclysm", curated.twist().name());
        assertEquals(Twist.Source.RECIPE, curated.twist().source());
        var derived = BOOK.resolve("charizard", A, "pikachu", B);
        assertNull(derived.recipeId());
        assertEquals(Twist.Source.MOTIF, derived.twist().source());
        assertEquals(BOOK.motifTwists().get(LORE.species("charizard").orElseThrow().primaryMotif()), derived.twist());
        var hostMotif = BOOK.resolve("charizard", A, "pikachu", B).twist().name();
        var swapped = BOOK.resolve("pikachu", A, "charizard", B).twist().name();
        assertNotEquals(hostMotif, swapped, "the host chooses the twist");
    }

    @Test void theDonorChoosesTheRiderAndTheHostDoesNot() {
        var tide = BOOK.resolve("charizard", A, "gyarados", B).rider();
        assertEquals("restorative", tide.affix(), "Gyarados is TIDE");
        var ember = BOOK.resolve("gyarados", A, "charizard", B).rider();
        assertEquals("smoldering", ember.affix(), "Charizard is EMBER");
    }

    @Test void theSignatureFollowsThePairOfUniquesNotWhoHoldsWhich() {
        assertEquals("phoenix_cinder", BOOK.resolve("charizard", A, "gyarados", B).signature().id());
        assertEquals("phoenix_cinder", BOOK.resolve("charizard", B, "gyarados", A).signature().id());
    }

    @Test void thePulseOperationVocabularyIsValidatedAndRoundTrips() {
        var parsed = PulseOp.fromJson(JsonParser.parseString("{\"op\":\"status\",\"status\":\"brn\",\"chance\":30}").getAsJsonObject());
        assertEquals(new PulseOp("status", Map.of("status", "brn", "chance", 30)), parsed);
        assertEquals(parsed, PulseOp.fromJson(parsed.toJson()));
        assertThrows(IllegalArgumentException.class, () -> new PulseOp("explode", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new PulseOp("heal", Map.of("pct", 99)));
        assertThrows(IllegalArgumentException.class, () -> new PulseOp("heal", Map.of("pct", 4, "extra", 1)));
        assertThrows(IllegalArgumentException.class, () -> new PulseOp("status", Map.of("status", "slp", "chance", 30)));
        assertThrows(IllegalArgumentException.class, () -> new PulseOp("selfStage", Map.of("stat", "spe", "delta", 1)));
        assertThrows(IllegalArgumentException.class, () -> new PulseOp("foeStage", Map.of("stat", "spe", "delta", 1)));
        new PulseOp("mimic", Map.of());
    }

    @Test void aResolvedTransientCarriesEverythingTheBattleNeeds() {
        var fused = BOOK.resolve("charizard", A, "gyarados", B).toFused();
        assertEquals("phoenix_cinder", fused.signature());
        assertFalse(fused.twist().isEmpty());
        assertEquals("restorative", fused.riderAffix());
        assertEquals(8, fused.riderValue());
        assertEquals("fire", fused.hostType());
        assertEquals("water", fused.donorType());
    }

    @Test void everyPossibleFusionOfTheSampleBuildsAValidSnapshotPayload() {
        var ids = RULES.uniques().stream().map(UniqueDefinition::id).sorted().toList();
        int built = 0;
        for (int i = 0; i < ids.size(); i++) {
            for (int j = 0; j < ids.size(); j++) {
                if (i == j) continue;
                for (var pair : List.of(List.of("charizard", "gyarados"), List.of("kyogre", "groudon"), List.of("mew", "mewtwo"), List.of("eevee", "vaporeon"))) {
                    var fused = BOOK.resolve(pair.get(0), ids.get(i), pair.get(1), ids.get(j)).toFused();
                    var snapshot = new CombatSnapshot(CombatSnapshot.Source.PLAYER, "p", com.cobbleascend.domain.Rarity.COMMON, 1,
                            List.of(new OrdinarySlot("prefix:0", Category.PREFIX, 1, "executioner", Map.of(), 8, 1)), null, fused);
                    RULES.validate(snapshot);
                    var payload = BattleFx.payload(Map.of("u", BattleFx.effectsOf(snapshot)), RULES.base());
                    assertTrue(payload.length() < 1000, "payload " + payload.length());
                    built++;
                }
            }
        }
        assertEquals(15 * 14 * 4, built);
    }

    @Test void everyEffectDescribesItselfWithTheNumbersAtTheBenefitShare() {
        assertEquals("heals 4% of its max HP", new PulseOp("heal", java.util.Map.of("pct", 4)).describe(100));
        assertEquals("heals 2% of its max HP", new PulseOp("heal", java.util.Map.of("pct", 4)).describe(50));
        assertEquals("15% chance to inflict a burn", new PulseOp("status", java.util.Map.of("status", "brn", "chance", 30)).describe(50));
        assertEquals("lowers the foe's Speed by 1", new PulseOp("foeStage", java.util.Map.of("stat", "spe", "delta", -1)).describe(100));
        var all = Transcendence.shared();
        for (var twist : all.motifTwists().values())
            for (var op : twist.effects()) assertFalse(op.describe(80).isBlank() || op.describe(80).equals(op.op()), op.op());
    }
}
