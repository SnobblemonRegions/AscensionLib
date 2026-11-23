package com.ascensionlib.battle;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;

/**
 * The tier of every dimension: how deep or dangerous it is (docs/ALL-WILD-PROFILES-DESIGN.md). A wild or trainer Pokemon there is rated at that tier
 * (see {@code RankedProgression.rateWild}), and the Pokemon a player captures there is rated at the same tier, so a Scouter preview equals the capture.
 * Tier 1 is the ordinary rarity table; higher tiers shift toward rarer. Tier 0 means profiles do not act there: wild and trainer fights are native and
 * captures get no profile. Raids and armed tower encounters are recognised by the battle itself and are unaffected by this list.
 *
 * <p>The tier comes from, in order: the owner's per-dimension setting in {@code config/ascensionlib.json} ({@code zones.dimensions}); a zone a mod
 * registered; the configuration's {@code zones.defaultTier} (1 unless changed). A mod adds a fixed dimension with {@link #allow} (tier 1) or
 * {@link #open}, and a dimension that exists only for a while (a dungeon run's level) with {@link #open} when it is created and {@link #close} when it
 * is deleted. The registered list is kept in memory and rebuilt at each start, so a mod must register again after every restart. Other mods reach this
 * through {@code AscensionZones}.
 */
public final class ProfileZones {
    /** The deepest tier a zone can have. */
    public static final int MAX_TIER = 10;

    /** CobbleTowers' dimension; its encounters are armed anyway, so this is only a safety net. */
    public static final ResourceLocation TOWER = ResourceLocation.fromNamespaceAndPath("cobbletowers", "tower");

    private static final Map<ResourceLocation, Integer> ZONES = new ConcurrentHashMap<>();

    static {
        ZONES.put(TOWER, 1);
    }

    private ProfileZones() {}

    /** Adds a dimension as a zone of tier 1. */
    public static void allow(ResourceLocation dimension) { open(dimension, 1); }

    /** Adds or changes a zone. Throws for a null dimension or a tier outside 1..{@link #MAX_TIER}. */
    public static void open(ResourceLocation dimension, int tier) {
        java.util.Objects.requireNonNull(dimension, "dimension");
        if (tier < 1 || tier > MAX_TIER) throw new IllegalArgumentException("Zone tier must be 1-" + MAX_TIER);
        ZONES.put(dimension, tier);
    }

    /** Removes a registered zone; safe when it is not one. The tower dimension cannot be closed. */
    public static void close(ResourceLocation dimension) {
        if (dimension != null && !dimension.equals(TOWER)) ZONES.remove(dimension);
    }

    /** The dimension's tier: 0 when profiles do not act there (or there is no dimension), otherwise 1 to {@link #MAX_TIER}. */
    public static int tierOf(ResourceLocation dimension) {
        if (dimension == null) return 0;
        var zones = com.cobbleascend.domain.v1.Tunables.current().zones();
        Integer configured = zones.dimensions().get(dimension.toString());
        if (configured != null) return configured;
        Integer registered = ZONES.get(dimension);
        return registered != null ? registered : zones.defaultTier();
    }

    /** The tier a mod registered for the dimension, ignoring the configuration, or 0 when none did. */
    public static int registeredTierOf(ResourceLocation dimension) {
        return dimension == null ? 0 : ZONES.getOrDefault(dimension, 0);
    }

    public static boolean isZone(ResourceLocation dimension) { return tierOf(dimension) > 0; }

    /** The persistent-data key that fixes a Pokemon's own tier (set on Laboratory guards when they spawn). Never rename: captured Pokemon carry it. */
    public static final String POKEMON_TIER_KEY = "ascensionlib_zone_tier";

    /**
     * The tier a particular wild or trainer Pokemon is rated at: its dimension's tier, raised to the tier marked on the Pokemon itself (a
     * Laboratory guard keeps the tier it spawned with wherever it wanders, so its Scouter preview, battle and capture agree). A dimension the
     * configuration turns off (tier 0) stays off whatever the mark says.
     */
    public static int tierOf(com.cobblemon.mod.common.pokemon.Pokemon pokemon, ResourceLocation dimension) {
        int base = tierOf(dimension);
        if (base < 1 || pokemon == null) return base;
        int marked = pokemon.getPersistentData().getInt(POKEMON_TIER_KEY);
        return Math.max(base, Math.min(marked, MAX_TIER));
    }

    public static Map<ResourceLocation, Integer> zones() { return Collections.unmodifiableMap(ZONES); }
}
