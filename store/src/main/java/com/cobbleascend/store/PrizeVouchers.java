package com.cobbleascend.store;

import com.cobbleascend.domain.v1.MaterialId;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * A prize handed out by another mod (CobbleTowers' Mystery Bag), paid as the physical-rewards path pays everything else: the prize is credited to the
 * wallet, then moved out of it into voucher batches the player right-clicks to deposit (or trades). The two steps are separate store transactions with
 * operation IDs derived from the caller's {@code key}, so a retry with the same key replays what already happened and never pays twice, and a crash
 * between the two leaves the prize in the wallet (never lost, never doubled) until the retry finishes the job. Materials that cannot be vouchers
 * (the bound Tower Mark) stay in the wallet.
 */
public final class PrizeVouchers {

    private PrizeVouchers() {}

    /**
     * @param key a stable id for this one prize (the bag's id); the same key always means the same prize
     * @return what ended up as vouchers; the rest of the prize (bound materials) is in the wallet
     */
    public static Map<MaterialId, Long> grantAsVouchers(ProgressionStore store, String key, UUID playerId, Map<MaterialId, Long> prize) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("a prize needs a key");
        var paid = new EnumMap<MaterialId, Long>(MaterialId.class);
        prize.forEach((material, amount) -> {
            if (amount != null && amount > 0) paid.put(material, amount);
        });
        if (paid.isEmpty()) return Map.of();
        store.grant(operation("grant", key), playerId, paid, "prize");
        return store.issueRewardVouchers(operation("vouchers", key), playerId, paid);
    }

    private static UUID operation(String step, String key) {
        return UUID.nameUUIDFromBytes(("prize-" + step + "|" + key).getBytes(StandardCharsets.UTF_8));
    }
}
