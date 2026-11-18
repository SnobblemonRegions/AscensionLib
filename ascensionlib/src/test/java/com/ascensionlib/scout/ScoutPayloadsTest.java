package com.ascensionlib.scout;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScoutPayloadsTest {
    private static ScoutPayloads.Slot slot(String name, String theme, int value) {
        return new ScoutPayloads.Slot("prefix", name, 1, value, 1, 9, "", theme);
    }

    private static ScoutPayloads.Detail detail(ScoutPayloads.Slot... slots) {
        return new ScoutPayloads.Detail(50, List.of("fire"), List.of(1, 2, 3, 4, 5, 6), "epic", "", List.of(slots));
    }

    @Test void resonanceTiersFollowTheSlotThemes() {
        var d = detail(slot("Keen Edge", "Predator", 5), slot("Momentum", "Predator", 4), slot("Venomous", "Toxin", 3), slot("Type Focus", "", 6));
        assertEquals(List.of(new ScoutPayloads.Tier("Predator", 2, 15), new ScoutPayloads.Tier("Toxin", 1, 0)), d.resonance());
    }

    @Test void threeOrMorePiecesGiveTheTopTierAndNeutralSlotsCountForNothing() {
        var d = detail(slot("a", "Bulwark", 5), slot("b", "Bulwark", 5), slot("c", "Bulwark", 5), slot("d", "", 5));
        assertEquals(List.of(new ScoutPayloads.Tier("Bulwark", 3, 30)), d.resonance());
        assertEquals(List.of(), detail(slot("x", "", 5)).resonance());
    }

    @Test void aSlotWithNoRollDoesNotCountTowardsResonance() {
        assertEquals(List.of(new ScoutPayloads.Tier("Tempo", 1, 0)), detail(slot("a", "Tempo", 4), slot("b", "Tempo", 0)).resonance());
    }

    @Test void aTraitsUnitSaysWhatItsPercentMeasures() {
        assertEquals("damage", ScoutPayloads.Slot.unitOf("physical_force", "outgoingDamage"));
        assertEquals("less damage taken", ScoutPayloads.Slot.unitOf("type_ward", "incomingReduction"));
        assertEquals("healing", ScoutPayloads.Slot.unitOf("restorative", "healing"));
        assertEquals("chance", ScoutPayloads.Slot.unitOf("keen_edge", "mechanic"));
        assertEquals("Speed", ScoutPayloads.Slot.unitOf("swift_strike", "mechanic"), "Swift Strike raises Speed, not a chance");
        assertEquals("burn damage", ScoutPayloads.Slot.unitOf("smoldering", "residual"));
        assertEquals("", ScoutPayloads.Slot.unitOf("future_trait", "someNewChannel"), "an unknown channel shows no unit rather than a wrong one");
    }

    @Test void anEnemyRowHasNoUnit() {
        assertEquals("", slot("a", "", 4).unit());
    }
}
