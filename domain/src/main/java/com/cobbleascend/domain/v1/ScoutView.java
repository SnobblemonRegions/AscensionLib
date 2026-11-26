package com.cobbleascend.domain.v1;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What a scouted enemy shows: its rarity, modifiers and Unique as display text and numbers. Built only after a
 * reveal, so an unscouted enemy has nothing to leak. Never carries IVs, EVs or moves.
 *
 * @param uniqueName the Unique's display name, or empty when the enemy has none
 */
public record ScoutView(String rarityId, String uniqueName, List<SlotLine> slots) {
    /**
     * One modifier row: {@code category} is {@code prefix} or {@code suffix}; {@code affixId} is the catalog id; {@code type} is the chosen type of a
     * type-bound modifier (Type Focus, Ward, Mastery, Bulwark: lower case, as the catalog stores it) and empty for every other.
     */
    public record SlotLine(String category, String name, int rank, int rolledValue, String theme, String affixId, String type) {
        public SlotLine {
            affixId = affixId == null ? "" : affixId;
            type = type == null ? "" : type;
        }

        public SlotLine(String category, String name, int rank, int rolledValue, String theme) { this(category, name, rank, rolledValue, theme, "", ""); }

        /** A line with no theme (neutral affix). */
        public SlotLine(String category, String name, int rank, int rolledValue) { this(category, name, rank, rolledValue, ""); }
    }

    /**
     * A modifier's player text with its chosen type filled in: the catalog says "the chosen type" for the four type-bound modifiers, which tells a
     * player nothing about this Pokemon. "Your moves of the chosen type deal more damage." with type fire reads "Your moves of the Fire type deal ...".
     */
    public static String condition(String catalogText, String type) {
        String text = catalogText == null ? "" : catalogText;
        if (type == null || type.isBlank()) return text;
        String shown = type.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + type.substring(1).toLowerCase(java.util.Locale.ROOT);
        return text.replace("the chosen type", "the " + shown + " type");
    }

    /** A modifier's display name with its chosen type, "Type Focus (fire)", as the capture and inspect rows already show it. */
    public static String displayName(String name, String type) {
        return type == null || type.isBlank() ? name : name + " (" + type + ")";
    }

    public ScoutView {
        Objects.requireNonNull(rarityId);
        Objects.requireNonNull(uniqueName);
        slots = List.copyOf(slots);
    }

    public static ScoutView of(CombatSnapshot snapshot, RankedRules rules) {
        var lines = new ArrayList<SlotLine>();
        for (var slot : snapshot.slots()) {
            lines.add(new SlotLine(slot.category().id(), rules.affix(slot.affixId()).base().name(), slot.rank(),
                    slot.rolledValue(), Resonance.themeOf(slot.affixId()).map(Resonance.Theme::display).orElse(""), slot.affixId(), slot.type()));
        }
        String unique = snapshot.uniqueId() == null ? ""
                : rules.unique(snapshot.uniqueId()).map(UniqueDefinition::name).orElse(snapshot.uniqueId());
        return new ScoutView(snapshot.rarity().id(), unique, lines);
    }
}
