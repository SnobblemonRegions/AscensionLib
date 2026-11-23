package com.ascensionlib;

import com.cobbleascend.domain.v1.BattleFx;
import com.cobbleascend.domain.v1.CombatSnapshot;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read-only contract for a mod that fights in real time instead of through Cobblemon's turn-based Showdown battle (ascensionDungeons).
 * It says which affixes a Pokemon holds; the caller applies them with its own damage maths. Signatures use only {@code java.*} and
 * {@code Object}, so a caller can reach it by reflection when the library is optional for it. Call on the server thread.
 */
public final class AscensionCombatFx {
    private AscensionCombatFx() {}

    /**
     * The affixes a Pokemon fights with, one string per effect in slot order: {@code affixId|percent|type} (type empty when the
     * affix has none, otherwise lower case such as {@code water}). A player's Pokemon uses its committed profile; any other Pokemon is
     * rated as a wild one at {@code tier} (the rating a Scouter previews), so call it for dungeon Pokemon with the zone's tier.
     *
     * @param pokemon a {@code com.cobblemon.mod.common.pokemon.Pokemon}
     * @param tier    1..10, used only for a Pokemon without an owner
     * @return the effects, empty when it has none, no running world, or {@code pokemon} is not a Pokemon
     */
    public static List<String> effectsOf(Object pokemon, int tier) {
        var out = new ArrayList<String>();
        if (!(pokemon instanceof Pokemon p)) return out;
        var service = AscensionApi.service();
        if (service.isEmpty()) return out;
        Optional<CombatSnapshot> snapshot;
        if (p.isPlayerOwned()) {
            snapshot = service.get().snapshot(p);
        } else {
            var rating = service.get().previewWild(p, Math.max(1, Math.min(10, tier)));
            snapshot = Optional.of(new CombatSnapshot(CombatSnapshot.Source.ENEMY, p.getUuid().toString(), rating.rarity(),
                    service.get().rules().catalogVersion(), rating.slots(), null));
        }
        if (snapshot.isEmpty()) return out;
        for (var effect : BattleFx.effectsOf(snapshot.get())) {
            out.add(effect.affixId() + "|" + effect.percent() + "|" + (effect.type() == null ? "" : effect.type().toLowerCase(java.util.Locale.ROOT)));
        }
        return out;
    }

    /** The per-channel ceilings, in percent: {@code [outgoingDamage, incomingReduction, healing]}. */
    public static int[] caps() {
        var rules = AscensionApi.rules();
        if (rules.isEmpty()) return new int[] {100, 50, 50};
        var base = rules.get().base();
        return new int[] {base.cap("outgoingDamage"), base.cap("incomingReduction"), base.cap("healing")};
    }
}
