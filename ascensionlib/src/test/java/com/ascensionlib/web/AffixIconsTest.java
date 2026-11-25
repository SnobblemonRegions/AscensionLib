package com.ascensionlib.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.cobbleascend.domain.Rules;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The pages draw a modifier's icon from its display name (ui.js {@code A.affixIcon}); every modifier in the game must have one. */
class AffixIconsTest {
    /** The same rule as the page: lower-case, every character that is not a letter or digit becomes an underscore. */
    private static String iconId(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
    }

    @Test
    @DisplayName("every modifier's display name maps to its id and has a crest in the pages' icon folder, and so does the Unique one")
    void everyModifierHasACrest() {
        var rules = Rules.defaults();
        assertEquals(54, rules.affixes().size(), "a new modifier needs an icon in tools/affix_art.py: add it to PLAN, then run tools/build_resourcepack.py");
        for (var affix : rules.affixes()) {
            assertEquals(affix.id(), iconId(affix.name()), "the page derives the icon from the name");
            assertNotNull(getClass().getResource("/web/ascensionlib/icons/affix/" + affix.id() + ".png"), "no crest for " + affix.id());
        }
        assertNotNull(getClass().getResource("/web/ascensionlib/icons/affix/unique.png"));
    }

    @Test
    @DisplayName("a type-bound modifier is shown as \"Name (type)\", and has a crest in each of the 18 types' colours")
    void typeBoundModifiersHaveACrestPerType() {
        var rules = Rules.defaults();
        assertEquals(18, Rules.TYPES.size());
        int typed = 0;
        for (var affix : rules.affixes()) {
            if (affix.parameter() == null) continue;
            typed++;
            for (String type : Rules.TYPES) {
                assertNotNull(getClass().getResource("/web/ascensionlib/icons/affix/" + affix.id() + "_" + type + ".png"), "no crest for " + affix.id() + " " + type);
            }
        }
        assertEquals(4, typed, "Type Focus, Type Mastery, Type Ward and Type Bulwark");
    }
}
