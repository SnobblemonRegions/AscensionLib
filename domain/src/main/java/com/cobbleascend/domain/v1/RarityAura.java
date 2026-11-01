package com.cobbleascend.domain.v1;

import com.cobbleascend.domain.Rarity;
import com.cobbleascend.domain.Rules;
import com.google.gson.JsonObject;
import java.util.EnumMap;
import java.util.Map;

/**
 * The rarity aura (balance phase 1, docs/BALANCE-THREAT.md): while the Threat system is on, every Pokemon with a profile fights with a flat
 * bonus to Attack, Sp. Atk, Defense and Sp. Def by its rarity, so a Mythic team is about twice as strong as a Common one and each rarity is
 * worth about one Threat band. The percents are design data ({@code balance.json} "auraPercent"); the battle module applies them outside
 * the affix channel caps. Enemies get the same aura from their own rarity.
 */
public final class RarityAura {
    /** The wire id of the aura effect in the battle payload. */
    public static final String EFFECT_ID = "aura";

    private static volatile Map<Rarity, Integer> shipped;

    private RarityAura() {}

    /** The aura percents in {@code balance}; all zero when it has no {@code auraPercent} table. Each is 0-100 and never falls as rarity rises. */
    public static Map<Rarity, Integer> parse(JsonObject balance) {
        var out = new EnumMap<Rarity, Integer>(Rarity.class);
        var table = balance.has("auraPercent") ? balance.getAsJsonObject("auraPercent") : null;
        int last = 0;
        for (var rarity : Rarity.values()) {
            int value = table != null && table.has(rarity.id()) ? table.get(rarity.id()).getAsInt() : 0;
            if (value < 0 || value > 100) throw new IllegalArgumentException("auraPercent." + rarity.id() + " must be 0-100");
            if (value < last) throw new IllegalArgumentException("auraPercent must not fall as rarity rises");
            last = value;
            out.put(rarity, value);
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /** The shipped percent for a rarity. */
    public static int percent(Rarity rarity) {
        var table = shipped;
        if (table == null) shipped = table = parse(Rules.resourceJson("balance.json"));
        return table.get(rarity);
    }

    /** The aura effect for a Pokemon of this rarity, or null when the Threat system is off or the rarity has none. */
    public static BattleFx.Effect effectFor(Rarity rarity) {
        if (!Tunables.current().threat().enabled()) return null;
        int p = percent(rarity);
        return p > 0 ? new BattleFx.Effect(EFFECT_ID, p, null) : null;
    }
}
