package com.cobbleascend.domain.v1;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Theme resonance (docs/DEPTH-DESIGN.md, part B). Every themed affix belongs to exactly one theme; a Pokemon holding two
 * affixes of one theme has them multiplied by {@link #TWO_PIECES}, three or more by {@link #THREE_PIECES}. Affixes with no
 * theme are neutral and are never changed. Pure arithmetic on rolled values, applied where the battle payload is built, so the
 * simulator, the channel caps and the stored profile are untouched. Multipliers are provisional until a balance pass.
 */
public final class Resonance {
    public static final int TWO_PIECES_PERCENT = 115;
    public static final int THREE_PIECES_PERCENT = 130;

    public enum Theme {
        TEMPO("Tempo"), TOXIN("Toxin"), PREDATOR("Predator"), BULWARK("Bulwark"), VITALITY("Vitality");

        private final String display;

        Theme(String display) { this.display = display; }

        public String display() { return display; }
    }

    private static final Map<String, Theme> THEMES = new LinkedHashMap<>();

    static {
        theme(Theme.TEMPO, "opening_strike", "opening_guard", "swift_strike", "bracing_entry", "ensnaring", "ambush", "fresh_legs", "watchful");
        theme(Theme.TOXIN, "smoldering", "venomous", "rending", "defiant_force", "status_guard", "searing_touch", "venom_touch", "purging", "insulated");
        theme(Theme.PREDATOR, "executioner", "last_stand", "super_effective_force", "keen_edge", "momentum", "giant_slayer", "relentless",
                "exploit_weakness", "armorbreak", "pressure_point");
        theme(Theme.BULWARK, "iron_resolve", "type_bulwark", "physical_bulwark", "special_bulwark", "resilient_hide", "stubborn", "thorns",
                "stalwart", "unyielding", "resolute", "hardening");
        theme(Theme.VITALITY, "restorative", "triumphant", "healthy_force", "healthy_guard", "steadfast_guard", "wardstone", "siphon");
    }

    private static void theme(Theme theme, String... affixIds) {
        for (var id : affixIds) THEMES.put(id, theme);
    }

    private Resonance() {}

    /** The theme of an affix, or empty for a neutral one. */
    public static Optional<Theme> themeOf(String affixId) {
        return Optional.ofNullable(THEMES.get(affixId));
    }

    /** Every themed affix id and its theme, for tests and displays. */
    public static Map<String, Theme> themes() {
        return Map.copyOf(THEMES);
    }

    /** The multiplier in percent for a count of same-theme slots: 100 below two, 115 for two, 130 for three or more. */
    public static int multiplierPercent(int pieces) {
        if (pieces >= 3) return THREE_PIECES_PERCENT;
        if (pieces == 2) return TWO_PIECES_PERCENT;
        return 100;
    }

    /** How many slots, with a positive roll, each theme has in these slots. */
    public static Map<Theme, Integer> pieces(List<OrdinarySlot> slots) {
        var counts = new LinkedHashMap<Theme, Integer>();
        for (var slot : slots) {
            if (slot.rolledValue() <= 0) continue;
            themeOf(slot.affixId()).ifPresent(theme -> counts.merge(theme, 1, Integer::sum));
        }
        return counts;
    }

    /** The rolled value after resonance, rounded half up; neutral affixes and lone pieces are returned unchanged. */
    public static int apply(String affixId, int rolledValue, Map<Theme, Integer> pieces) {
        var theme = THEMES.get(affixId);
        if (theme == null) return rolledValue;
        int percent = multiplierPercent(pieces.getOrDefault(theme, 0));
        return percent == 100 ? rolledValue : (rolledValue * percent + 50) / 100;
    }
}
