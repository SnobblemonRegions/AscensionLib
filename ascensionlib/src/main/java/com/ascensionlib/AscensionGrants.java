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
