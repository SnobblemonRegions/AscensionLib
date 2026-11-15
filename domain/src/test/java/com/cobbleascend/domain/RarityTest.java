package com.cobbleascend.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RarityTest {
    @Test void topTiersReadAsRadiantAndAscendedSoTheyAreNotCobblemonSpeciesCategories() {
        assertEquals("Common", Rarity.COMMON.displayName());
        assertEquals("Epic", Rarity.EPIC.displayName());
        assertEquals("Radiant", Rarity.LEGENDARY.displayName());
        assertEquals("Ascended", Rarity.MYTHICAL.displayName());
    }

    @Test void renamingTheDisplayNameNeverChangesTheStoredId() {
        assertEquals("legendary", Rarity.LEGENDARY.id());
        assertEquals("mythical", Rarity.MYTHICAL.id());
        assertEquals(Rarity.MYTHICAL, Rarity.fromId("mythical"));
    }
}
