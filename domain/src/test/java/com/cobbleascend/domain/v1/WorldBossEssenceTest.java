package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorldBossEssenceTest {
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    @Test void everyoneAboveTheThresholdEarnsTheSameFlatAmount() {
        var damage = new LinkedHashMap<UUID, Float>();
        damage.put(a, 900f);
        damage.put(b, 50f);
        damage.put(c, 20f);       // the average is 323.3, so the bar is 16.2
        var pay = WorldBossEssence.payouts(damage);
        assertEquals(Map.of(a, 2L, b, 2L, c, 2L), pay);
    }

    @Test void nothingIsPaidOnceTheEventIsOver() {
        // Spooky Essence is Halloween's currency: a World Boss (or the Daily Boss) beaten after the window pays none of it
        var damage = Map.of(a, 900f, b, 50f);
        assertTrue(WorldBossEssence.payouts(damage, false).isEmpty());
        assertEquals(WorldBossEssence.payouts(damage), WorldBossEssence.payouts(damage, true), "during the event it pays as before");
    }

    @Test void aPlayerBelowTheThresholdEarnsNothing() {
        var pay = WorldBossEssence.payouts(Map.of(a, 990f, b, 9f));   // the average is 499.5, so the bar is 25
        assertEquals(Map.of(a, 2L), pay);
    }

    @Test void noDamageOrBadDamagePaysNobody() {
        assertTrue(WorldBossEssence.payouts(Map.of(a, 0f)).isEmpty());
        assertTrue(WorldBossEssence.payouts(Map.of(a, Float.NaN)).isEmpty());
        assertTrue(WorldBossEssence.payouts(Map.of(a, Float.POSITIVE_INFINITY)).isEmpty());
        assertTrue(WorldBossEssence.payouts(Map.of()).isEmpty());
        assertEquals(Map.of(b, 2L), WorldBossEssence.payouts(Map.of(a, 0f, b, 5f)), "a player who dealt nothing is not counted in the average");
    }

    @Test void thereIsNoCapOnHowManyPlayersEarn() {
        var damage = new LinkedHashMap<UUID, Float>();
        for (int i = 0; i < 500; i++) damage.put(UUID.randomUUID(), 100f);     // each of 500 players dealt 0.2% of the damage
        assertEquals(500, WorldBossEssence.payouts(damage).size(), "an even crowd of any size is paid; a fixed 1% bar would pay nobody");
    }

    @Test void theOperationIdIsStablePerBossAndPlayerAndDiffersOtherwise() {
        var boss = UUID.randomUUID();
        assertEquals(WorldBossEssence.operationId(boss, a), WorldBossEssence.operationId(boss, a));
        assertNotEquals(WorldBossEssence.operationId(boss, a), WorldBossEssence.operationId(boss, b));
        assertNotEquals(WorldBossEssence.operationId(boss, a), WorldBossEssence.operationId(UUID.randomUUID(), a));
    }
}
