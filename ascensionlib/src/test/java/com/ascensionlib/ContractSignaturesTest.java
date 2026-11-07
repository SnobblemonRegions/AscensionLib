package com.ascensionlib;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * CobbleTowers and CobbleRaids reach these methods by reflection (the library is optional for them), so a changed name
 * or parameter list compiles here and fails silently there. This pins what they look up; change a signature only by
 * adding a new method.
 */
class ContractSignaturesTest {

    private static void assertContract(Class<?> owner, String name, Class<?> returns, Class<?>... parameters)
            throws NoSuchMethodException {
        var method = owner.getMethod(name, parameters);
        assertEquals(true, Modifier.isStatic(method.getModifiers()), name + " must stay static");
        assertEquals(returns, method.getReturnType(), name + " return type");
    }

    /** What CobbleTowers and CobbleRaids call to give a Pokemon a profile or spend its credits (Raids' rental drafts and Towers' rentals). */
    @Test void grantContractsMatchWhatRaidsAndTowersLookUp() throws Exception {
        assertContract(AscensionGrants.class, "upgradeRandomly", AscensionGrants.Result.class, com.cobblemon.mod.common.pokemon.Pokemon.class);
        assertContract(AscensionGrants.class, "grantWithRarity", AscensionGrants.Result.class, com.cobblemon.mod.common.pokemon.Pokemon.class, String.class);
        // Balance phase 3: the captured raid boss's rarity floor (CobbleRaids RaidCaptureSessionService).
        // Balance phase 3: the raid shop's Scouters and dust (CobbleRaids AscensionShopHook).
        assertContract(AscensionGrants.class, "grantMaterial", AscensionGrants.Result.class, java.util.UUID.class, java.util.UUID.class,
                String.class, long.class, String.class);
        assertContract(AscensionGrants.class, "grantWithRarityFloor", AscensionGrants.Result.class, com.cobblemon.mod.common.pokemon.Pokemon.class, String.class);
        // CobbleTowers' Mystery Bag pays its prize as vouchers.
        assertContract(AscensionGrants.class, "grantAsVouchers", AscensionGrants.Result.class, java.util.UUID.class, java.util.UUID.class, Map.class);
    }

    /** CobbleTowers asks this before opening a page of its own, so it never covers the completion reveal. */
    @Test void screenContractMatchesWhatTowersLooksUp() throws Exception {
        assertContract(AscensionScreens.class, "revealShowing", boolean.class, UUID.class);
    }

    @Test void rewardContractsMatchWhatTowersLooksUp() throws Exception {
        assertContract(AscensionRewards.class, "settleTowerBoss", Map.class,
                UUID.class, String.class, int.class, int.class, Collection.class);
        assertContract(AscensionRewards.class, "settleScouterDrops", Map.class,
                UUID.class, String.class, boolean.class, Collection.class);
        assertContract(AscensionRewards.class, "settle", Map.class,
                UUID.class, String.class, String.class, Map.class);
    }

    @Test void combatFxContractMatchesWhatDungeonsLooksUp() throws Exception {
        assertContract(AscensionCombatFx.class, "effectsOf", java.util.List.class, Object.class, int.class);
        assertContract(AscensionCombatFx.class, "caps", int[].class);
    }

    @Test void trialContractMatchesWhatTowersLooksUp() throws Exception {
        assertContract(AscensionRewards.class, "settleTrial", Map.class,
                UUID.class, String.class, int.class, Collection.class);
    }

    @Test void itemQuantityContractMatchesWhatRaidsAndTowersLookUp() throws Exception {
        assertContract(AscensionRewards.class, "scaleItemQuantity", int.class, UUID.class, int.class, String.class);
    }

    @Test void raidAttunementContractMatchesWhatRaidsLooksUp() throws Exception {
        assertContract(AscensionRewards.class, "settleRaidAttunement", int.class,
                String.class, String.class, Collection.class);
    }

    /** Balance phase 1: what CobbleTowers 1.1 and CobbleRaids 0.9 look up for the Threat system (each falls back to the older calls when absent). */
    @Test void threatContractsMatchWhatTowersAndRaidsLookUp() throws Exception {
        assertContract(AscensionRewards.class, "settleTowerCompletion", Map.class,
                UUID.class, String.class, int.class, int.class, int.class, boolean.class, Collection.class, int.class);
        assertContract(AscensionRewards.class, "settleTowerBoss", Map.class,
                UUID.class, String.class, int.class, int.class, Collection.class, int.class);
        assertContract(AscensionRewards.class, "towerCompletionExtras", Map.class,
                UUID.class, UUID.class, int.class, int.class, boolean.class, int.class);
        assertContract(AscensionRewards.class, "settleRaid", Map.class, String.class, String.class, String.class, Map.class);
        assertContract(AscensionEncounters.class, "threatEnabled", boolean.class);
        assertContract(AscensionEncounters.class, "recommendedThreat", int.class, Collection.class);
        assertContract(AscensionEncounters.class, "threatBands", String.class);
    }

    @Test void scoutingContractMatchesWhatTowersLooksUp() throws Exception {
        assertContract(AscensionEncounters.class, "declareEnemy", String.class,
                String.class, Collection.class, int.class, String.class, boolean.class, String.class, String.class,
                int.class);
        assertContract(AscensionEncounters.class, "end", void.class, String.class);
        assertContract(AscensionEncounters.class, "armBattle", void.class, Collection.class, String.class);
        assertContract(AscensionEncounters.class, "disarmBattle", void.class, Collection.class);
    }

    @Test void dungeonContractsMatchWhatAscensionDungeonsLooksUp() throws Exception {
        assertContract(AscensionZones.class, "contractVersion", int.class);
        assertContract(AscensionZones.class, "openZone", boolean.class, String.class, int.class);
        assertContract(AscensionZones.class, "closeZone", void.class, String.class);
        assertContract(AscensionZones.class, "zoneTier", int.class, String.class);
        assertContract(AscensionRewards.class, "settleDungeonRun", Map.class,
                UUID.class, String.class, int.class, int.class, boolean.class, Collection.class);
        assertContract(AscensionRewards.class, "settleTowerCompletion", Map.class,
                UUID.class, String.class, int.class, int.class, int.class, boolean.class, Collection.class);
        assertContract(AscensionRewards.class, "towerCompletionExtras", Map.class,
                UUID.class, UUID.class, int.class, int.class, boolean.class);
        assertContract(AscensionRewards.class, "towerCompletionPreview", Map.class);
        assertEquals(1, AscensionZones.contractVersion());
    }
}
