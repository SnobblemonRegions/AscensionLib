package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.cobbleascend.domain.Rarity;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A Project Fusion bond after Genome Shift: the head leads, so the Transcendent's offence and defence types trade places. */
class SwappedTypesTest {
    @Test void onlyTheTypesTradePlaces() {
        var fused = new CombatSnapshot.Fused(List.of("ashen_heart", "rupture"), 78, 60, "fire", "water");
        var swapped = fused.swappedTypes();
        assertEquals("water", swapped.hostType());
        assertEquals("fire", swapped.donorType());
        assertEquals(fused.uniqueIds(), swapped.uniqueIds());
        assertEquals(78, swapped.benefitPercent());
        assertEquals(60, swapped.drawbackPercent());
        assertEquals(fused, swapped.swappedTypes(), "shifting back restores it");
    }

    @Test void aSnapshotWithoutATranscendentIsUnchanged() {
        var plain = new CombatSnapshot(CombatSnapshot.Source.PLAYER, "p", Rarity.EPIC, 1, List.of(), null, null);
        assertSame(plain, plain.withSwappedTypes());
        var fused = new CombatSnapshot(CombatSnapshot.Source.PLAYER, "p", Rarity.MYTHICAL, 1, List.of(), null,
                new CombatSnapshot.Fused(List.of("ashen_heart", "rupture"), 78, 60, "fire", null));
        assertEquals(null, fused.withSwappedTypes().transcendent().hostType());
        assertEquals("fire", fused.withSwappedTypes().transcendent().donorType());
    }
}
