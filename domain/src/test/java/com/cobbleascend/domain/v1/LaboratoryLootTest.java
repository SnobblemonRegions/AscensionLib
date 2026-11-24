package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LaboratoryLootTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test void aRetriedClaimRollsTheSame() {
        for (var container : LaboratoryLoot.Container.values())
            assertEquals(LaboratoryLoot.roll(container, "minecraft:overworld/10,40,-3", PLAYER),
                    LaboratoryLoot.roll(container, "minecraft:overworld/10,40,-3", PLAYER));
    }

    @Test void dustStaysInItsBandAndNoCoreOrApexEverDrops() {
        for (var container : LaboratoryLoot.Container.values()) {
            for (int i = 0; i < 5000; i++) {
                var pile = LaboratoryLoot.roll(container, "key-" + i, new UUID(i, i * 31L));
                long dust = pile.get(MaterialId.RESONANCE_DUST);
                assertTrue(dust >= container.dustMin() && dust <= container.dustMax(), container + " dust " + dust);
                assertFalse(pile.containsKey(MaterialId.ASCENSION_CORE));
                assertFalse(pile.containsKey(MaterialId.APEX_SHARD));
            }
        }
    }

    @Test void chancesLandNearTheApprovedRates() {
        int samples = 20000;
        int scouters = 0, lockerFacets = 0, terminalFragments = 0, sigils = 0;
        long terminalFacets = 0;
        for (int i = 0; i < samples; i++) {
            var player = new UUID(i * 7919L, ~i);
            if (LaboratoryLoot.roll(LaboratoryLoot.Container.SUPPLY, "s" + i, player).containsKey(MaterialId.SCOUTER)) scouters++;
            if (LaboratoryLoot.roll(LaboratoryLoot.Container.SPECIMEN, "l" + i, player).containsKey(MaterialId.FACET)) lockerFacets++;
            var terminal = LaboratoryLoot.roll(LaboratoryLoot.Container.TERMINAL, "t" + i, player);
            terminalFacets += terminal.get(MaterialId.FACET);
            if (terminal.containsKey(MaterialId.UNIQUE_FRAGMENT)) terminalFragments++;
            if (terminal.containsKey(MaterialId.ASCENSION_SIGIL)) sigils++;
        }
        assertEquals(0.20, scouters / (double) samples, 0.015);
        assertEquals(0.40, lockerFacets / (double) samples, 0.015);
        assertEquals(1.5, terminalFacets / (double) samples, 0.03);
        assertEquals(0.12, terminalFragments / (double) samples, 0.01);
        assertEquals(0.03, sigils / (double) samples, 0.006);
    }

    @Test void eachPlayerHasTheirOwnClaim() {
        var other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        assertNotEquals(LaboratoryLoot.claimOperation("k", PLAYER), LaboratoryLoot.claimOperation("k", other));
        assertNotEquals(LaboratoryLoot.claimOperation("k", PLAYER), LaboratoryLoot.claimOperation("k2", PLAYER));
        assertEquals(LaboratoryLoot.claimOperation("k", PLAYER), LaboratoryLoot.claimOperation("k", PLAYER));
    }

    @Test void containerIdsAreFrozen() {
        assertEquals(LaboratoryLoot.Container.SUPPLY, LaboratoryLoot.Container.fromId("supply"));
        assertEquals(LaboratoryLoot.Container.SPECIMEN, LaboratoryLoot.Container.fromId("specimen"));
        assertEquals(LaboratoryLoot.Container.TERMINAL, LaboratoryLoot.Container.fromId("terminal"));
        assertThrows(IllegalArgumentException.class, () -> LaboratoryLoot.Container.fromId("core"));
    }
}
