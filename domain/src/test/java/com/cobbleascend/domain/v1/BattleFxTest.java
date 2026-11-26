package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BattleFxTest {
    private final RankedRules rules = RankedRules.defaults();
    private final EnemyGenerator generator = new EnemyGenerator(rules);
    private final EnemyTier tier = EnemyTiers.defaults().tier("trial_rank_3");

    private CombatSnapshot enemy(String id) {
        return generator.generate(id, 0, EnemySpec.regular(tier), List.of("water"));
    }

    @Test void everySlotBecomesOneEffectWithItsRolledPercent() {
        var snapshot = enemy("fx-1");
        var effects = BattleFx.effectsOf(snapshot);
        assertEquals(snapshot.slots().size(), effects.size());
        for (int i = 0; i < effects.size(); i++) {
            assertEquals(snapshot.slots().get(i).affixId(), effects.get(i).affixId());
            var slot = snapshot.slots().get(i);
            assertEquals(Resonance.apply(slot.affixId(), slot.rolledValue(), Resonance.pieces(snapshot.slots())), effects.get(i).percent());
        }
    }

    @Test void aUniqueIsSentAsOneMoreFlaggedEffect() {
        var plain = enemy("unique-0");
        var withUnique = new CombatSnapshot(plain.source(), plain.subjectId(), plain.rarity(), plain.catalogVersion(), plain.slots(), "ashen_heart");
        var effects = BattleFx.effectsOf(withUnique);
        assertEquals(plain.slots().size() + 1, effects.size());
        var last = effects.get(effects.size() - 1);
        assertEquals("ashen_heart", last.affixId());
        assertEquals(1, last.percent());
        assertNull(last.type());
        assertEquals(plain.slots().size(), BattleFx.effectsOf(plain).size(), "no Unique, no extra effect");
    }

    @Test void everyCatalogUniqueHasTextAndADrawback() {
        assertEquals(15, rules.uniques().size(), "the first seven and the second batch of eight");
        for (var unique : rules.uniques()) {
            assertFalse(unique.benefit().isBlank(), unique.id() + " needs benefit text");
            assertFalse(unique.drawback().isBlank(), unique.id() + " needs a drawback");
        }
    }

    @Test void aTypedSlotCarriesTheCapitalisedShowdownType() {
        for (int i = 0; i < 400; i++) {
            var snapshot = enemy("typed-" + i);
            for (int s = 0; s < snapshot.slots().size(); s++) {
                var slot = snapshot.slots().get(s);
                var effect = BattleFx.effectsOf(snapshot).get(s);
                if (slot.type() == null) assertNull(effect.type());
                else assertEquals(Character.toUpperCase(slot.type().charAt(0)) + slot.type().substring(1), effect.type());
            }
        }
    }

    @Test void payloadHasTheCompactShapeTheModuleReads() {
        var id = UUID.randomUUID().toString();
        var mons = new LinkedHashMap<String, List<BattleFx.Effect>>();
        mons.put(id, List.of(new BattleFx.Effect("type_focus", 7, "Water"), new BattleFx.Effect("iron_resolve", 5, null)));
        mons.put("empty-one", List.of());
        var json = JsonParser.parseString(BattleFx.payload(mons, rules.base())).getAsJsonObject();
        assertEquals(1, json.get("v").getAsInt());
        assertEquals(100, json.getAsJsonObject("caps").get("out").getAsInt());
        assertEquals(50, json.getAsJsonObject("caps").get("inc").getAsInt());
        assertEquals(50, json.getAsJsonObject("caps").get("heal").getAsInt());
        assertEquals(100, json.getAsJsonObject("caps").get("res").getAsInt());
        assertEquals(1, json.getAsJsonObject("mons").size(), "a Pokemon with no effects is left out");
        var first = json.getAsJsonObject("mons").getAsJsonArray(id).get(0).getAsJsonObject();
        assertEquals("type_focus", first.get("i").getAsString());
        assertEquals(7, first.get("p").getAsInt());
        assertEquals("Water", first.get("t").getAsString());
        assertFalse(json.getAsJsonObject("mons").getAsJsonArray(id).get(1).getAsJsonObject().has("t"));
    }

    @Test void noEffectsMeansNoPayload() {
        assertNull(BattleFx.payload(new LinkedHashMap<>(), rules.base()));
        var none = new LinkedHashMap<String, List<BattleFx.Effect>>();
        none.put("a", List.of());
        assertNull(BattleFx.payload(none, rules.base()));
    }

    @Test void aFullRaidFitsInOneFormatField() {
        var mons = new LinkedHashMap<String, List<BattleFx.Effect>>();
        for (int i = 0; i < 25; i++) mons.put(UUID.randomUUID().toString(), BattleFx.effectsOf(enemy("raid-" + i)));
        assertTrue(BattleFx.payload(mons, rules.base()).length() <= BattleFx.MAX_PAYLOAD_CHARS);
    }

    @Test void anOversizedPayloadIsRefusedRatherThanTruncated() {
        var mons = new LinkedHashMap<String, List<BattleFx.Effect>>();
        for (int i = 0; i < 400; i++) mons.put(UUID.randomUUID().toString(), List.of(new BattleFx.Effect("iron_resolve", 5, null)));
        assertThrows(IllegalStateException.class, () -> BattleFx.payload(mons, rules.base()));
    }
}
