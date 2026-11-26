package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScoutViewTest {
    private final RankedRules rules = RankedRules.defaults();
    private final EnemyGenerator generator = new EnemyGenerator(rules);
    private final EnemyTier tier = EnemyTiers.defaults().tier("trial_rank_2");

    @Test void viewListsEverySlotWithItsRankAndRoll() {
        var enemy = generator.generate("enc-1", 0, EnemySpec.regular(tier), List.of("water"));
        var view = ScoutView.of(enemy, rules);
        assertEquals(enemy.rarity().id(), view.rarityId());
        assertEquals(enemy.slots().size(), view.slots().size());
        for (int i = 0; i < view.slots().size(); i++) {
            var slot = enemy.slots().get(i);
            var line = view.slots().get(i);
            assertEquals(slot.category().id(), line.category());
            assertEquals(rules.affix(slot.affixId()).base().name(), line.name());
            assertEquals(slot.rank(), line.rank());
            assertEquals(slot.rolledValue(), line.rolledValue());
            assertEquals(slot.affixId(), line.affixId(), "the row names its catalog id, so its text can be looked up");
            assertEquals(slot.type() == null ? "" : slot.type(), line.type(), "a type-bound modifier carries its chosen type");
        }
        assertEquals("", view.uniqueName());
    }

    @Test void aTypeBoundModifierSaysWhichTypeInItsTextAndName() {
        assertEquals("Your moves of the Fire type deal more damage.", ScoutView.condition(rules.affix("type_focus").base().condition(), "fire"));
        assertEquals("You take less damage from moves of the Water type.", ScoutView.condition(rules.affix("type_ward").base().condition(), "water"));
        assertEquals("Your contact moves deal more damage.", ScoutView.condition(rules.affix("brawler").base().condition(), ""), "untyped text is unchanged");
        assertEquals("Type Focus (fire)", ScoutView.displayName("Type Focus", "fire"));
        assertEquals("Brawler", ScoutView.displayName("Brawler", ""));
        for (var affix : rules.affixes()) {
            if (affix.base().parameter() == null) continue;
            assertTrue(affix.base().condition().contains("the chosen type"), affix.id() + "'s text must name the chosen type, or the type never shows");
        }
    }

    @Test void aBossUniqueIsShownByName() {
        var unique = rules.uniques().iterator().next();
        var boss = generator.generate("enc-2", 0, EnemySpec.boss(EnemyTiers.defaults().tier("boss"), unique.id()),
                List.of("fire"));
        assertEquals(unique.name(), ScoutView.of(boss, rules).uniqueName());
    }
}
