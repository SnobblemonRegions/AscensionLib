package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.cobbleascend.domain.Rarity;
import com.cobbleascend.domain.v1.BattleFxPlanner.Actor;
import com.cobbleascend.domain.v1.BattleFxPlanner.Combatant;
import com.cobbleascend.domain.v1.BattleFxPlanner.Kind;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Balance phase 1: the rarity aura rides first in each profiled Pokemon's battle effects, only while the Threat system is on. */
class RarityAuraTest {
    private final RankedRules rules = RankedRules.defaults();
    private final EnemyGenerator generator = new EnemyGenerator(rules);
    private final Combatant mine = new Combatant("mine", UUID.nameUUIDFromBytes("mine".getBytes()));
    private final Combatant foe = new Combatant("foe", UUID.nameUUIDFromBytes("foe".getBytes()));

    @AfterEach void restoreDefaults() { Tunables.use(Tunables.defaults()); }

    private static void threat(boolean on) {
        var d = Tunables.defaults();
        Tunables.use(new Tunables(d.budget(), d.withdrawalFeePercent(), d.respecMarksPrice(), d.attunementPerVictory(), d.towerCompletion(),
                d.miniChest(), d.zones(), new ThreatSettings(on, 3, 4, 25)));
    }

    private CombatSnapshot of(String tierId, String id) {
        return generator.generate(id, 0, EnemySpec.regular(EnemyTiers.defaults().tier(tierId)), List.of("fire"));
    }

    @Test void theShippedAuraClimbsFromNothingToAQuarter() {
        assertEquals(0, RarityAura.percent(Rarity.COMMON));
        assertEquals(13, RarityAura.percent(Rarity.EPIC));
        assertEquals(26, RarityAura.percent(Rarity.MYTHICAL));
        int last = -1;
        for (var rarity : Rarity.values()) { assertTrue(RarityAura.percent(rarity) >= last); last = RarityAura.percent(rarity); }
    }

    @Test void aBadAuraTableIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RarityAura.parse(JsonParser.parseString("{\"auraPercent\":{\"rare\":20,\"epic\":10}}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> RarityAura.parse(JsonParser.parseString("{\"auraPercent\":{\"mythical\":150}}").getAsJsonObject()));
        assertEquals(0, RarityAura.parse(JsonParser.parseString("{}").getAsJsonObject()).get(Rarity.MYTHICAL), "no table, no aura");
    }

    @Test void auraIsSentOnlyWithThreatOnAndAlwaysFirst() {
        var actors = List.of(new Actor(Kind.PLAYER, List.of(mine)), new Actor(Kind.WILD, List.of(foe)));
        var player = of("threat_6", "player");
        var enemy = of("threat_3", "enemy");
        threat(false);
        var off = BattleFxPlanner.plan(actors, false, true, false, List.of(enemy), id -> Optional.of(player), id -> Optional.empty());
        assertTrue(off.values().stream().flatMap(List::stream).noneMatch(e -> e.affixId().equals(RarityAura.EFFECT_ID)), "no aura with Threat off");
        threat(true);
        var on = BattleFxPlanner.plan(actors, false, true, false, List.of(enemy), id -> Optional.of(player), id -> Optional.of(enemy));
        assertEquals(RarityAura.EFFECT_ID, on.get("mine").get(0).affixId());
        assertEquals(RarityAura.percent(player.rarity()), on.get("mine").get(0).percent());
        assertEquals(RarityAura.EFFECT_ID, on.get("foe").get(0).affixId(), "the enemy fights with its own rarity's aura");
        assertEquals(RarityAura.percent(enemy.rarity()), on.get("foe").get(0).percent());
        assertEquals(off.get("mine").size() + 1, on.get("mine").size(), "the aura is one more effect, the rest unchanged");
        assertNotNull(BattleFx.payload(on, rules.base()), "the payload still fits");
    }

    @Test void aCommonPokemonGetsNoAuraEffect() {
        threat(true);
        assertNull(RarityAura.effectFor(Rarity.COMMON));
        assertNotNull(RarityAura.effectFor(Rarity.UNCOMMON));
    }
}
