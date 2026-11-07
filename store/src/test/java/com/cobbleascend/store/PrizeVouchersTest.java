package com.cobbleascend.store;

import static org.junit.jupiter.api.Assertions.*;

import com.cobbleascend.domain.v1.MaterialId;
import com.cobbleascend.domain.v1.RankedRules;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A prize from another mod (CobbleTowers' Mystery Bag): credited to the wallet and moved into vouchers, once per key, never twice. */
class PrizeVouchersTest {
    @TempDir Path dir;
    private ProgressionStore store;

    @AfterEach void close() { if (store != null) store.close(); }

    private ProgressionStore open() {
        store = ProgressionStore.open(dir.resolve("world").resolve("progression.db"), RankedRules.defaults(),
                UUID.fromString("9d6bb263-d230-42d7-a9d0-ea4710df4bb5"), ProgressionStore.Options.defaults().withRandom(new Random(1)));
        return store;
    }

    private long outstanding(UUID player, MaterialId material) {
        return store.undeliveredVouchers(player).stream().filter(v -> v.material() == material).mapToLong(v -> v.outstanding()).sum();
    }

    @Test void aTradablePrizeBecomesVouchersAndTheWalletIsNetZero() {
        var s = open();
        var player = UUID.randomUUID();
        var issued = PrizeVouchers.grantAsVouchers(s, "bag-1", player, Map.of(MaterialId.RESONANCE_DUST, 50L));
        assertEquals(Map.of(MaterialId.RESONANCE_DUST, 50L), issued);
        assertEquals(0L, s.wallet(player).balance(MaterialId.RESONANCE_DUST), "the prize is in items, not the wallet");
        assertEquals(50L, outstanding(player, MaterialId.RESONANCE_DUST));
    }

    @Test void aRetryOfTheSameKeyPaysNothingMore() {
        var s = open();
        var player = UUID.randomUUID();
        PrizeVouchers.grantAsVouchers(s, "bag-1", player, Map.of(MaterialId.UNIQUE_CATALYST, 1L));
        var again = PrizeVouchers.grantAsVouchers(s, "bag-1", player, Map.of(MaterialId.UNIQUE_CATALYST, 1L));
        assertEquals(Map.of(MaterialId.UNIQUE_CATALYST, 1L), again, "the retry reports the same prize");
        assertEquals(1L, outstanding(player, MaterialId.UNIQUE_CATALYST), "but nothing more was issued");
        assertEquals(0L, s.wallet(player).balance(MaterialId.UNIQUE_CATALYST));
    }

    @Test void aBoundMaterialStaysInTheWalletAndIsNotAVoucher() {
        var s = open();
        var player = UUID.randomUUID();
        var issued = PrizeVouchers.grantAsVouchers(s, "bag-2", player, Map.of(MaterialId.TOWER_MARK, 1L));
        assertTrue(issued.isEmpty(), "a Tower Mark cannot be a tradable item");
        assertEquals(1L, s.wallet(player).balance(MaterialId.TOWER_MARK));
        assertEquals(0L, outstanding(player, MaterialId.TOWER_MARK));
    }

    @Test void twoBagsAreTwoSeparatePrizes() {
        var s = open();
        var player = UUID.randomUUID();
        PrizeVouchers.grantAsVouchers(s, "bag-a", player, Map.of(MaterialId.RESONANCE_DUST, 40L));
        PrizeVouchers.grantAsVouchers(s, "bag-b", player, Map.of(MaterialId.RESONANCE_DUST, 60L));
        assertEquals(100L, outstanding(player, MaterialId.RESONANCE_DUST));
    }

    @Test void aVoucherFromAPrizeRedeemsInFullAndOnlyOnce() {
        var s = open();
        var player = UUID.randomUUID();
        PrizeVouchers.grantAsVouchers(s, "bag-3", player, Map.of(MaterialId.FACET, 1L));
        var voucher = s.undeliveredVouchers(player).stream().filter(v -> v.material() == MaterialId.FACET).findFirst().orElseThrow();
        s.markVoucherDelivered(voucher.batchId());
        s.redeemVoucher(UUID.randomUUID(), player, voucher.batchId(), 1);
        assertEquals(1L, s.wallet(player).balance(MaterialId.FACET), "depositing gives back the whole prize");
        s.redeemVoucher(UUID.randomUUID(), player, voucher.batchId(), 1);
        assertEquals(1L, s.wallet(player).balance(MaterialId.FACET), "a duplicated stack credits nothing");
    }

    @Test void nonPositiveAmountsAreIgnoredAndAnEmptyPrizeIssuesNothing() {
        var s = open();
        var player = UUID.randomUUID();
        assertTrue(PrizeVouchers.grantAsVouchers(s, "bag-4", player, Map.of()).isEmpty());
        assertTrue(PrizeVouchers.grantAsVouchers(s, "bag-4", player, Map.of(MaterialId.FACET, 0L)).isEmpty());
        assertTrue(s.undeliveredVouchers(player).isEmpty());
    }

    @Test void aBlankKeyIsRefused() {
        var s = open();
        assertThrows(IllegalArgumentException.class, () -> PrizeVouchers.grantAsVouchers(s, " ", UUID.randomUUID(), Map.of(MaterialId.FACET, 1L)));
    }
}
