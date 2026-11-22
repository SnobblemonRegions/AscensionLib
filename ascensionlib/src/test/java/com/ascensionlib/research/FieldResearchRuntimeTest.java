package com.ascensionlib.research;

import static org.junit.jupiter.api.Assertions.*;

import com.cobbleascend.domain.v1.FieldResearch;
import org.junit.jupiter.api.Test;

class FieldResearchRuntimeTest {
    @Test void speciesIdsAreNormalisedWhateverAModPasses() {
        assertEquals("garchomp", ResearchCatalog.speciesId("cobblemon:garchomp"));
        assertEquals("garchomp", ResearchCatalog.speciesId("  Garchomp "));
        assertEquals("mr_mime", ResearchCatalog.speciesId("COBBLEMON:Mr_Mime"));
        assertNull(ResearchCatalog.speciesId("  "));
        assertNull(ResearchCatalog.speciesId(null));
        assertNull(ResearchCatalog.speciesId("cobblemon:"));
        assertEquals("mymod:fakemon", ResearchCatalog.speciesId("MyMod:Fakemon"), "another mod's species keeps its namespace");
        assertEquals("garchomp", ResearchCatalog.speciesId("cobblemon", "garchomp"));
        assertEquals("mymod:fakemon", ResearchCatalog.speciesId("mymod", "fakemon"));
        assertEquals("mr_mime", ResearchCatalog.speciesId("mr_mime"), "a bare id is Cobblemon's");
    }

    private static ResearchCatalog.Entry entry(String id, String pre, java.util.List<String> evolves, String... labels) {
        return new ResearchCatalog.Entry(id, pre, evolves, java.util.Set.of(labels));
    }

    @Test void aCatalogBuiltFromCobblemonDatapacksAndAddonsTogether() {
        var built = ResearchCatalog.build(java.util.List.of(
                entry("gible", null, java.util.List.of("gabite")),
                entry("gabite", "gible", java.util.List.of("garchomp")),
                entry("garchomp", "gabite", java.util.List.of()),
                // an addon line declared only from the parent side, with a bare name in one evolution result
                entry("mymod:sprout", null, java.util.List.of("sapling")),
                entry("mymod:sapling", null, java.util.List.of("mymod:elder")),
                entry("mymod:elder", null, java.util.List.of(), "legendary"),
                // an addon evolution of a Cobblemon species
                entry("mymod:aetheon", "eevee", java.util.List.of()),
                entry("eevee", null, java.util.List.of()),
                // the same bare name in two mods: never resolved by name, each is its own species
                entry("othermod:twin", null, java.util.List.of()),
                entry("mymod:twin", null, java.util.List.of())));
        var catalog = built.catalog();
        assertEquals("gible", catalog.familyOf("garchomp").orElseThrow().id());
        assertEquals("mymod:sprout", catalog.familyOf("mymod:elder").orElseThrow().id(), "parent-side links resolve bare names");
        assertEquals("eevee", catalog.familyOf("mymod:aetheon").orElseThrow().id());
        assertEquals(200, catalog.family("mymod:sprout").orElseThrow().shares().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(java.util.Optional.of("mymod:sapling"), ResearchCatalog.resolve("sapling", catalog::knows, built.byName()),
                "a bare name unique to one mod resolves to it");
        assertEquals(java.util.Optional.empty(), ResearchCatalog.resolve("twin", catalog::knows, built.byName()), "an ambiguous bare name resolves to nothing");
        assertEquals(java.util.Optional.of("garchomp"), ResearchCatalog.resolve("cobblemon:Garchomp", catalog::knows, built.byName()));
        assertEquals(java.util.Optional.empty(), ResearchCatalog.resolve("missingno", catalog::knows, built.byName()));
        assertEquals(java.util.Set.of("legendary"), built.labels().get("mymod:elder"));
    }

    @Test void displayNamesReadNaturally() {
        assertEquals("Mr Mime", FieldResearchRuntime.display("mr_mime"));
        assertEquals("Porygon Z", FieldResearchRuntime.display("porygon-z"));
        assertEquals("Garchomp", FieldResearchRuntime.display("garchomp"));
        assertEquals("?", FieldResearchRuntime.display(""));
        assertEquals("Fakemon", FieldResearchRuntime.display("mymod:fakemon"));
    }

    @Test void theRaidTierFollowsCobbleRaidsLevelsIncludingRenownedBosses() {
        // Starter 25 (renowned 35), powerhouse 50 (60), legendary and mythical 100 (110). Without species labels loaded, a level-100 boss is legendary.
        assertEquals(FieldResearch.RaidTier.STARTER, FieldResearchRuntime.tierOf("charmander", 25));
        assertEquals(FieldResearch.RaidTier.STARTER, FieldResearchRuntime.tierOf("charmander", 35));
        assertEquals(FieldResearch.RaidTier.POWERHOUSE, FieldResearchRuntime.tierOf("garchomp", 50));
        assertEquals(FieldResearch.RaidTier.POWERHOUSE, FieldResearchRuntime.tierOf("garchomp", 60));
        assertEquals(FieldResearch.RaidTier.LEGENDARY, FieldResearchRuntime.tierOf("mewtwo", 100));
        assertEquals(FieldResearch.RaidTier.LEGENDARY, FieldResearchRuntime.tierOf("mewtwo", 110));
    }

    @Test void nothingIsLockedUntilAStudyStarts() {
        assertFalse(ResearchLocks.locked(java.util.UUID.randomUUID()));
        assertFalse(ResearchLocks.locked((java.util.UUID) null));
        var mon = java.util.UUID.randomUUID();
        ResearchLocks.lock(mon);
        assertTrue(ResearchLocks.locked(mon));
        ResearchLocks.unlock(mon);
        assertFalse(ResearchLocks.locked(mon));
    }
}
