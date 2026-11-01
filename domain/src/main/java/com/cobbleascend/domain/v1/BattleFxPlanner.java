package com.cobbleascend.domain.v1;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Decides which Pokemon of a battle act with which ranked effects (docs/BATTLE-ADAPTER-DESIGN.md, section 1). Pure: the
 * Cobblemon battle is described by {@link Actor}s, so every rule is a unit test with no Minecraft in reach.
 *
 * <ul>
 *   <li><b>PvP</b>: nothing acts, ever.</li>
 *   <li><b>Everywhere but a dimension switched off</b>: a raid, an armed encounter, or a profile zone. Every dimension is a zone (tier 1 unless the
 *       configuration or a mod says otherwise); only a dimension set to tier 0 is native for both sides.</li>
 *   <li><b>A player's Pokemon</b> act from their stored profile; a Pokemon with no profile does not act.</li>
 *   <li><b>An armed encounter</b> (a tower opponent or floor boss, a raid boss) assigns its declared enemy snapshots, in
 *       order, to the non-player Pokemon, so what a Scouter revealed is what fights.</li>
 *   <li><b>A wild Pokemon or a trainer's Pokemon</b> (no armed encounter, not a raid) acts from its derived rating, the one a Scouter previews and
 *       a capture awards, at the zone's tier.</li>
 * </ul>
 */
public final class BattleFxPlanner {
    private BattleFxPlanner() {}

    public enum Kind { PLAYER, WILD, NPC }

    /**
     * One Pokemon in a battle. {@code effectedId} is the uuid Cobblemon packs into the team (what the simulator sees, a
     * clone's in a raid); {@code originalId} is the real Pokemon, whose profile or wild rating applies.
     */
    public record Combatant(String effectedId, UUID originalId) {}

    public record Actor(Kind kind, List<Combatant> pokemon) {
        public Actor { pokemon = List.copyOf(pokemon); }
    }

    /**
     * @param actors         every actor in battle order
     * @param pvp            two or more players fighting each other
     * @param raid           a raid-format battle, whose boss is never rated as a wild Pokemon
     * @param profileZone    the wild or trainer opponent stands in a dimension where profiles act (every dimension unless the
     *                       configuration turns it off); with no raid, no armed encounter and no zone, nothing acts on either side
     * @param armed          the declared enemies of an encounter armed for this battle, or {@code null} when none is armed;
     *                       an armed encounter with no enemies means "explicitly native"
     * @param playerSnapshot a player's Pokemon's stored profile as a snapshot, by original uuid
     * @param wildSnapshot   a wild or trainer Pokemon's derived rating as a snapshot, empty when it cannot be rated
     * @return effects per packed uuid; Pokemon with none are absent
     */
    public static Map<String, List<BattleFx.Effect>> plan(List<Actor> actors, boolean pvp, boolean raid,
                                                          boolean profileZone, List<CombatSnapshot> armed,
                                                          Function<UUID, Optional<CombatSnapshot>> playerSnapshot,
                                                          Function<UUID, Optional<CombatSnapshot>> wildSnapshot) {
        var result = new LinkedHashMap<String, List<BattleFx.Effect>>();
        if (pvp) return result;
        // Profiles act in a raid, an armed encounter (tower) or a profile zone. Every dimension is a zone unless the configuration turns it off
        // (default tier 0), so an ordinary wild or trainer fight is rated everywhere; a fight in a dimension that is off is fully native for both sides.
        if (armed == null && !raid && !profileZone) return result;
        var enemies = new ArrayList<Combatant>();
        for (var actor : actors) {
            for (var combatant : actor.pokemon()) {
                switch (actor.kind()) {
                    case PLAYER -> playerSnapshot.apply(combatant.originalId())
                            .ifPresent(snapshot -> put(result, combatant, snapshot));
                    case WILD -> enemies.add(combatant);
                    case NPC -> enemies.add(combatant);
                }
            }
        }
        if (armed != null) {
            for (int i = 0; i < Math.min(armed.size(), enemies.size()); i++) put(result, enemies.get(i), armed.get(i));
        } else if (!raid) {
            // A wild Pokemon and a trainer's Pokemon are rated the same way: from the Pokemon itself and the zone's tier.
            for (var combatant : enemies) wildSnapshot.apply(combatant.originalId()).ifPresent(snapshot -> put(result, combatant, snapshot));
        }
        return result;
    }

    /**
     * A plan with each player's Pokemon's Haunting added (docs/HALLOWEEN-EVENT-DESIGN.md). Call it only when profiles act in this battle (not PvP, and a raid,
     * an armed encounter or a profile zone): a Haunting follows the same rule as every other effect. The Haunting goes first in the Pokemon's list so
     * the payload's effect limit can never cut it off, and a Pokemon whose only effect is a Haunting still appears. An id this build does not know
     * (a newer world) is skipped.
     *
     * @param hauntingOf a player's Pokemon's Haunting id by its original uuid, empty when it has none
     */
    public static Map<String, List<BattleFx.Effect>> withHauntings(Map<String, List<BattleFx.Effect>> plan, List<Actor> actors,
                                                                  Function<UUID, Optional<String>> hauntingOf) {
        var result = new LinkedHashMap<String, List<BattleFx.Effect>>();
        plan.forEach((id, effects) -> result.put(id, List.copyOf(effects)));
        for (var actor : actors) {
            if (actor.kind() != Kind.PLAYER) continue;
            for (var combatant : actor.pokemon()) {
                var haunting = hauntingOf.apply(combatant.originalId()).flatMap(Hauntings::byId);
                if (haunting.isEmpty()) continue;
                var effects = new ArrayList<BattleFx.Effect>();
                effects.add(new BattleFx.Effect(haunting.get().id(), Hauntings.effectPercent(haunting.get().id()), null));
                effects.addAll(result.getOrDefault(combatant.effectedId(), List.of()));
                result.put(combatant.effectedId(), effects);
            }
        }
        return result;
    }

    private static void put(Map<String, List<BattleFx.Effect>> result, Combatant combatant, CombatSnapshot snapshot) {
        var effects = new java.util.ArrayList<BattleFx.Effect>();
        // The rarity aura goes first so the module's per-Pokemon effect limit can never cut it off (only sent with Threat on).
        var aura = RarityAura.effectFor(snapshot.rarity());
        if (aura != null) effects.add(aura);
        effects.addAll(BattleFx.effectsOf(snapshot));
        if (!effects.isEmpty()) result.put(combatant.effectedId(), effects);
    }
}
