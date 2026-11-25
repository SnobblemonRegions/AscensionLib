package com.cobbleascend.domain;

import java.util.Locale;

public enum Rarity {
    COMMON, UNCOMMON, RARE, EPIC, LEGENDARY, MYTHICAL;

    /** The stored id ("legendary", "mythical"): saved profiles, payloads and CSS classes use it, so it never changes. */
    public String id() { return name().toLowerCase(Locale.ROOT); }
    public static Rarity fromId(String id) { return valueOf(id.toUpperCase(Locale.ROOT)); }

    /**
     * The name players see. The top two tiers are shown as Awakened and Radiant (owner decisions 2026-11-15 and 2026-11-25) so they cannot be read as
     * Cobblemon's Legendary and Mythical species. Keep in step with A.rarityName in ui.js and RARITY_NAMES in tools/build_guide.py.
     */
    public String displayName() {
        return switch (this) {
            case COMMON -> "Common";
            case UNCOMMON -> "Uncommon";
            case RARE -> "Rare";
            case EPIC -> "Epic";
            case LEGENDARY -> "Awakened";
            case MYTHICAL -> "Radiant";
        };
    }
}
