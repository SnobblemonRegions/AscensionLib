package com.cobbleascend.store;

import static org.junit.jupiter.api.Assertions.*;

import com.cobbleascend.domain.v1.DailyRewardBudget;
import com.cobbleascend.domain.v1.MaterialId;
import com.cobbleascend.domain.v1.RankedRules;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Balance phase 1: the daily Threat counters ride along with a budgeted grant, once per operation, in their own table. */
class ThreatCountersTest {
    @TempDir Path dir;
    private ProgressionStore store;

    @AfterEach void close() { if (store != null) store.close(); }

    private ProgressionStore open() {
        store = ProgressionStore.open(dir.resolve("world").resolve("progression.db"), RankedRules.defaults(),
                UUID.fromString("9d6bb263-d230-42d7-a9d0-ea4710df4bb5"), ProgressionStore.Options.defaults().withRandom(new Random(1)));
        return store;
    }

    @Test void aCountedGrantPaysAndCountsOnceEvenWhenRetried() {
        var s = open();
        var player = UUID.randomUUID();
        var op = UUID.randomUUID();
        assertEquals(0, s.threatUsedToday(player, "apex"));
        var paid = s.grantBudgeted(op, player, Map.of(MaterialId.APEX_SHARD, 2L, MaterialId.RESONANCE_DUST, 60L), "raid test", DailyRewardBudget.DEFAULTS, "apex");
        assertEquals(2L, s.wallet(player).balance(MaterialId.APEX_SHARD));
        assertEquals(1, s.threatUsedToday(player, "apex"));
        assertEquals(1, s.budgetUsedToday(player), "it is still a chest for the dust budget");
        var again = s.grantBudgeted(op, player, Map.of(MaterialId.APEX_SHARD, 2L, MaterialId.RESONANCE_DUST, 60L), "raid test", DailyRewardBudget.DEFAULTS, "apex");
        assertTrue(again.replayed());
        assertEquals(2L, s.wallet(player).balance(MaterialId.APEX_SHARD), "a retry pays nothing more");
        assertEquals(1, s.threatUsedToday(player, "apex"), "and counts nothing more");
        assertEquals(0, s.threatUsedToday(player, "core_roll"), "counters are separate");
    }

    @Test void anUncountedGrantLeavesTheCountersAloneAndCountsSurviveAReopen() {
        var s = open();
        var player = UUID.randomUUID();
        s.grantBudgeted(UUID.randomUUID(), player, Map.of(MaterialId.RESONANCE_DUST, 10L), "chest", DailyRewardBudget.DEFAULTS, null);
        assertEquals(0, s.threatUsedToday(player, "core_roll"));
        s.grantBudgeted(UUID.randomUUID(), player, Map.of(MaterialId.FACET, 1L), "completion", DailyRewardBudget.DEFAULTS, "core_roll");
        s.close();
        store = null;
        assertEquals(1, open().threatUsedToday(player, "core_roll"));
    }

    @Test void aSettlementCountsEachPaidPlayer() {
        var s = open();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        var rewards = new EncounterRewards(s);
        var settlement = new EncounterRewards.Settlement("raid-boss-1", EncounterRewards.EncounterOutcome.VICTORY, "raid", java.util.List.of(
                new EncounterRewards.Payout(a, Map.of(MaterialId.APEX_SHARD, 1L)), new EncounterRewards.Payout(b, Map.of(MaterialId.APEX_SHARD, 2L))));
        rewards.settleBudgeted(settlement, DailyRewardBudget.DEFAULTS, "apex");
        assertEquals(1, s.threatUsedToday(a, "apex"));
        assertEquals(1, s.threatUsedToday(b, "apex"));
        assertEquals(2L, s.wallet(b).balance(MaterialId.APEX_SHARD));
    }
}
