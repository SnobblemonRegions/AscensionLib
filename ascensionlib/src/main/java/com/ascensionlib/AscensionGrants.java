package com.ascensionlib;

import com.cobbleascend.domain.Rarity;
import com.cobbleascend.domain.v1.Origin;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The contract for mods that hand a player a Pokemon outside capture and hatching (a shop, a reward). Signatures use
 * only {@code java.*} and Cobblemon's {@link Pokemon}. CobbleRaids reaches {@link #grantWithRarity} by reflection
 * because the library is optional for it, so the method name, parameter types and the {@link Result#GRANTED} name
 * are a contract: change them only with a new method, never in place.
 */
public final class AscensionGrants {
    private static final Logger LOG = LoggerFactory.getLogger(AscensionLib.MOD_ID);

    public enum Result {
        /** The Pokemon has a profile at the requested rarity (already had one from an earlier call: unchanged). */
        GRANTED,
        /** No running world, or progression is disabled for it. Nothing was written. */
        DISABLED,
        /** The rarity id is not one of {@link #RARITY_IDS}. Nothing was written. */
        UNKNOWN_RARITY,
        /** The store refused or the Pokemon is not an owned party/PC Pokemon; see the server log. */
        FAILED
    }

    /** The rarity ids a caller may name, lower case, weakest first. */
    public static final java.util.List<String> RARITY_IDS = java.util.Arrays.stream(Rarity.values())
            .map(Rarity::id).toList();

    private AscensionGrants() {}

    /**
     * For a Pokemon lent out with a profile (a rental draft): awards any level milestones it has reached, then spends every
     * pending upgrade credit on randomly chosen slots, one rank each. {@link Result#GRANTED} when the Pokemon has a profile
     * afterwards (nothing left to spend is fine). New method; the contract of {@link #grantWithRarity} is unchanged.
     */
    public static Result upgradeRandomly(Pokemon pokemon) {
        var service = AscensionApi.service();
        if (service.isEmpty()) return Result.DISABLED;
        try {
            return service.get().autoUpgrade(pokemon).isPresent() ? Result.GRANTED : Result.FAILED;
        } catch (RuntimeException exception) {
            LOG.error("Could not upgrade Pokemon {} at random", pokemon.getUuid(), exception);
            return Result.FAILED;
        }
    }

    /** Balance phase 3: what another mod may sell for its own currency through {@link #grantMaterial}. Never Cores or Apex Shards. */
    public static final java.util.Set<String> SELLABLE = java.util.Set.of("scouter", "resonance_dust");

    /**
     * Balance phase 3, for CobbleRaids' raid shop: credits {@code amount} of a {@link #SELLABLE} material to a player's wallet.
     * {@code operationId} makes a retry safe (the same purchase is never credited twice). {@link Result#UNKNOWN_RARITY} answers
     * a material that is not sellable (the enum is the contract, so no new constant). Online or offline players alike.
     */
    public static Result grantMaterial(java.util.UUID operationId, java.util.UUID player, String materialId, long amount, String reason) {
        String key = materialId == null ? "" : materialId.trim().toLowerCase(Locale.ROOT);
        if (!SELLABLE.contains(key) || amount <= 0 || amount > 100_000) return Result.UNKNOWN_RARITY;
        var service = AscensionApi.service();
        if (service.isEmpty()) return Result.DISABLED;
        try {
            service.get().store().grant(operationId, player, java.util.Map.of(com.cobbleascend.domain.v1.MaterialId.fromId(key), amount),
                    reason == null ? "external_shop" : reason);
            return Result.GRANTED;
        } catch (RuntimeException exception) {
            LOG.error("Could not credit {} x{} to {}", key, amount, player, exception);
            return Result.FAILED;
        }
    }

    /**
     * For a prize another mod hands out (CobbleTowers' Mystery Bag): the materials are credited to the player's wallet and moved into voucher items the
     * player right-clicks to deposit (or trades); a material that cannot be a voucher (the bound Tower Mark) stays in the wallet. The items reach the
     * player within a few seconds (the library hands waiting vouchers over every few seconds, and at login). {@code prizeId} makes a retry safe: the same
     * prize is never paid twice. {@link Result#UNKNOWN_RARITY} answers an unknown material id or an amount outside 1 to 2304 (it names no rarity here).
     * New method; the contract of {@link #grantMaterial} is unchanged.
     * @param materials material id (such as {@code resonance_dust}) to amount
     */
    public static Result grantAsVouchers(java.util.UUID prizeId, java.util.UUID player, java.util.Map<String, Long> materials) {
        if (prizeId == null || player == null || materials == null || materials.isEmpty()) return Result.UNKNOWN_RARITY;
        var paid = new java.util.EnumMap<com.cobbleascend.domain.v1.MaterialId, Long>(com.cobbleascend.domain.v1.MaterialId.class);
        for (var entry : materials.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim().toLowerCase(Locale.ROOT);
            Long amount = entry.getValue();
            if (amount == null || amount <= 0 || amount > com.cobbleascend.store.ProgressionStore.MAX_MINT) return Result.UNKNOWN_RARITY;
            try {
                paid.merge(com.cobbleascend.domain.v1.MaterialId.fromId(key), amount, Long::sum);
            } catch (IllegalArgumentException unknown) {
                return Result.UNKNOWN_RARITY;
            }
        }
        var service = AscensionApi.service();
        if (service.isEmpty()) return Result.DISABLED;
        try {
            com.cobbleascend.store.PrizeVouchers.grantAsVouchers(service.get().store(), prizeId.toString(), player, paid);
            return Result.GRANTED;
        } catch (RuntimeException exception) {
            LOG.error("Could not pay prize {} to {} as vouchers", prizeId, player, exception);
            return Result.FAILED;
        }
    }

    /**
     * Balance phase 3, for a captured raid boss: gives an owned Pokemon a profile whose rarity is rolled from the normal
     * table but is at least {@code floorRarityId}. Call right after the Pokemon is added to the player's party or PC, on
     * the server thread, so it is profiled before the ordinary gift path would roll it. Safe to repeat: a Pokemon that
     * already has a profile keeps it. CobbleRaids reaches this by reflection and falls back to the ordinary gift roll
     * when it is missing.
     */
    public static Result grantWithRarityFloor(Pokemon pokemon, String floorRarityId) {
        Rarity floor;
        try {
            floor = Rarity.fromId(floorRarityId.trim().toLowerCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            return Result.UNKNOWN_RARITY;
        }
        var service = AscensionApi.service();
        if (service.isEmpty()) return Result.DISABLED;
        try {
            service.get().acquireWithRarityFloor(pokemon, Origin.of("external_grant"), floor);
            return Result.GRANTED;
        } catch (RuntimeException exception) {
            LOG.error("Could not grant a profile of at least {} to Pokemon {}", floor.id(), pokemon.getUuid(), exception);
            return Result.FAILED;
        }
    }

    /**
     * Gives an owned Pokemon a profile at an operator-chosen rarity: the rarity is never rolled, its modifier slots
     * are. Call after the Pokemon has been added to the player's party or PC, on the server thread. Safe to repeat:
     * a Pokemon that already has a profile keeps it.
     */
    public static Result grantWithRarity(Pokemon pokemon, String rarityId) {
        Rarity rarity;
        try {
            rarity = Rarity.fromId(rarityId.trim().toLowerCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            return Result.UNKNOWN_RARITY;
        }
        var service = AscensionApi.service();
        if (service.isEmpty()) return Result.DISABLED;
        try {
            service.get().acquireWithRarity(pokemon, Origin.of("external_grant"), rarity);
            return Result.GRANTED;
        } catch (RuntimeException exception) {
            LOG.error("Could not grant a {} profile to Pokemon {}", rarity.id(), pokemon.getUuid(), exception);
            return Result.FAILED;
        }
    }
}
