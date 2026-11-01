package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CurrencyItemsTest {
    /** Items that are safe to repurpose: they stack to 64, do nothing when used, and have a plain generated model a pack can override. */
    private static final Set<String> INERT_STACKABLE = Set.of("minecraft:glowstone_dust", "minecraft:amethyst_shard", "minecraft:heart_of_the_sea",
            "minecraft:echo_shard", "minecraft:nether_star", "minecraft:prismarine_crystals", "minecraft:nautilus_shell", "minecraft:disc_fragment_5", "minecraft:ghast_tear", "minecraft:prismarine_shard", "minecraft:phantom_membrane", "minecraft:shulker_shell");

    @Test void everyItemHasARarityThatExistsAndTheTiersAreOrderedByValue() {
        for (var spec : CurrencyItems.all().values()) {
            var rarity = CurrencyItems.rarity(spec.rarity());
            assertEquals(spec.rarity(), rarity.id(), spec.material().id() + " names a rarity that exists");
            assertFalse(rarity.label().isBlank());
            assertFalse(spec.kind().isBlank());
        }
        assertEquals("EPIC", CurrencyItems.rarity("epic").label());
        assertEquals("MYTHIC", CurrencyItems.rarity("mythical").label(), "the top tier reads Mythic, as on the pages");
        assertEquals("common", CurrencyItems.rarity("no-such-tier").id(), "an unknown tier falls back to common");
        assertEquals("common", CurrencyItems.all().get(MaterialId.RESONANCE_DUST).rarity(), "the everyday currency is common");
        assertEquals("legendary", CurrencyItems.all().get(MaterialId.UNIQUE_CATALYST).rarity(), "the rarest is legendary");
        assertTrue(java.util.List.of("common", "uncommon", "rare", "epic", "legendary", "mythical").contains(CurrencyItems.dex().rarity()));
    }

    @Test void everyMaterialHasAnItem() {
        for (var material : MaterialId.values()) {
            var spec = CurrencyItems.spec(material);
            assertNotNull(spec, material.id());
            assertFalse(spec.name().isBlank());
            assertFalse(spec.lore().isEmpty());
            assertTrue(spec.color() >= 0 && spec.color() <= 0xFFFFFF);
        }
        assertEquals(MaterialId.values().length, CurrencyItems.all().size());
    }

    @Test void baseItemsAreInertAndDistinctAndModelNumbersNeverCollide() {
        var bases = new HashSet<String>();
        var models = new HashSet<Integer>();
        for (var spec : CurrencyItems.all().values()) {
            assertTrue(INERT_STACKABLE.contains(spec.baseItem()), spec.baseItem() + " is not on the inert, stackable list");
            assertTrue(bases.add(spec.baseItem()), "Two currencies share " + spec.baseItem() + ": a pack override would need two models for one item");
            assertTrue(models.add(spec.model()), "Duplicate model number " + spec.model());
            assertTrue(models.add(spec.model() + CurrencyItems.VOUCHER_MODEL_OFFSET), "A voucher number collides: " + (spec.model() + CurrencyItems.VOUCHER_MODEL_OFFSET));
        }
    }

    @Test void aBatchIsACanonicalUuidAndAnythingElseIsNotAVoucher() {
        var id = UUID.randomUUID();
        assertEquals(id, CurrencyItems.parseBatch(id.toString()));
        assertNull(CurrencyItems.parseBatch("not-a-uuid"));
        assertNull(CurrencyItems.parseBatch(""));
        assertNull(CurrencyItems.parseBatch(null));
    }
}
