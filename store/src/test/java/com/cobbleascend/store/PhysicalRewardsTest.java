package com.cobbleascend.store;

import static org.junit.jupiter.api.Assertions.*;

import com.cobbleascend.domain.v1.DailyRewardBudget;
import com.cobbleascend.domain.v1.MaterialId;
import com.cobbleascend.domain.v1.RankedRules;
import com.cobbleascend.store.EncounterRewards.EncounterOutcome;
import com.cobbleascend.store.EncounterRewards.Payout;
import com.cobbleascend.store.EncounterRewards.Settlement;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Physical rewards: a payout is moved into undelivered voucher batches, once, with no withdrawal fee, and redeems back in full. */
class PhysicalRewardsTest {
    @TempDir Path dir;
    private ProgressionStore store;

    @AfterEach void close() { if (store != null) store.close(); }

    private ProgressionStore open() {
        store = ProgressionStore.open(dir.resolve("world").resolve("progression.db"), RankedRules.defaults(),
                UUID.fromString("9d6bb263-d230-42d7-a9d0-ea4710df4bb5"), ProgressionStore.Options.defaults().withRandom(new Random(1)));
        return store;
    }

    private static Settlement win(String id, UUID player, Map<MaterialId, Long> materials) {
        return new Settlement(id, EncounterOutcome.VICTORY, "raid_threat", List.of(new Payout(player, materials)));
    }

    @Test void aPhysicalPayoutBecomesVouchersOnceAndRedeemsInFull() {
        var s = open();
        var rewards = new EncounterRewards(s);
        rewards.physicalRewards(() -> true);
        var player = UUID.randomUUID();
        var pay = Map.of(MaterialId.APEX_SHARD, 2L, MaterialId.RESONANCE_DUST, 60L);
        rewards.settleBudgeted(win("raid-1", player, pay), DailyRewardBudget.DEFAULTS, "apex");
        assertEquals(0L, s.wallet(player).balance(MaterialId.APEX_SHARD), "the reward is in items, not the wallet");
        var waiting = s.undeliveredVouchers(player);
        assertEquals(2, waiting.size());
        assertEquals(2L, waiting.stream().filter(v -> v.material() == MaterialId.APEX_SHARD).findFirst().orElseThrow().outstanding());

        rewards.settleBudgeted(win("raid-1", player, pay), DailyRewardBudget.DEFAULTS, "apex");
        assertEquals(2, s.undeliveredVouchers(player).size(), "a retried settlement issues nothing more");

        var apex = waiting.stream().filter(v -> v.material() == MaterialId.APEX_SHARD).findFirst().orElseThrow();
        s.markVoucherDelivered(apex.batchId());
        s.redeemVoucher(UUID.randomUUID(), player, apex.batchId(), 2);
        assertEquals(2L, s.wallet(player).balance(MaterialId.APEX_SHARD), "no withdrawal fee: depositing gives back the whole reward");
        s.redeemVoucher(UUID.randomUUID(), player, apex.batchId(), 2);
        assertEquals(2L, s.wallet(player).balance(MaterialId.APEX_SHARD), "a duplicated stack credits nothing");
    }

    @Test void offKeepsTheWalletPathAndBoundMaterialsStayInTheWallet() {
        var s = open();
        var rewards = new EncounterRewards(s);
        var player = UUID.randomUUID();
        rewards.settle(win("t-1", player, Map.of(MaterialId.RESONANCE_DUST, 100L)));
        assertEquals(100L, s.wallet(player).balance(MaterialId.RESONANCE_DUST), "physical rewards off: straight into the wallet");
        assertTrue(s.undeliveredVouchers(player).isEmpty());

        rewards.physicalRewards(() -> true);
        rewards.settle(win("t-2", player, Map.of(MaterialId.TOWER_MARK, 3L, MaterialId.FACET, 4L)));
        assertEquals(3L, s.wallet(player).balance(MaterialId.TOWER_MARK), "a bound Tower Mark is never a voucher");
        assertEquals(0L, s.wallet(player).balance(MaterialId.FACET));
        assertEquals(1, s.undeliveredVouchers(player).size());
    }
}
