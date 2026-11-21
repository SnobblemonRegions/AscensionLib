package com.ascensionlib.battle;

import com.ascensionlib.AscensionApi;
import com.ascensionlib.AscensionLib;
import com.ascensionlib.scout.ScoutEncounters;
import com.cobbleascend.domain.v1.BattleFx;
import com.cobbleascend.domain.v1.BattleFxPlanner;
import com.cobbleascend.domain.v1.CombatSnapshot;
import com.cobblemon.mod.common.api.battles.model.actor.ActorType;
import com.cobblemon.mod.common.api.battles.model.actor.EntityBackedBattleActor;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the {@code ascensionFx} format field for a battle that is about to start. Called by the Showdown host on the server
 * thread for every battle, just before it is handed to the simulator.
 *
 * <p>Fails closed for ascension and open for battles: whatever goes wrong (no running world, an unreadable profile, an
 * oversized payload, any exception) the battle starts with no effects and the log says why. Which Pokemon act, and from
 * what, is {@link BattleFxPlanner}; this class only describes the Cobblemon battle to it and writes the answer.
 */
public final class AscensionBattles {
    private static final Logger LOG = LoggerFactory.getLogger(AscensionLib.MOD_ID);
    static final String FIELD = "ascensionFx";

    private AscensionBattles() {}

    public static Map<String, String> fieldsFor(UUID battleId, List<UUID> playerIds) {
        if (BattleNormalization.isSuppressed(battleId)) return BattleNormalization.fieldsFor(battleId);
        try {
            var service = AscensionApi.service();
            if (service.isEmpty()) return Map.of();
            var battle = BattleRegistry.getBattle(battleId);
            if (battle == null) return Map.of();

            var actors = new ArrayList<BattleFxPlanner.Actor>();
            // A wild Pokemon and a trainer's Pokemon are rated from the Pokemon itself (its types and level), so keep them by their real uuid.
            var wildByUuid = new HashMap<UUID, Pokemon>();
            // Players' own Pokemon by id, so a Genome-Shifted Project Fusion bond fights with its Transcendent types traded.
            var ownedByUuid = new HashMap<UUID, Pokemon>();
            boolean profileZone = false;
            int zoneTier = 1;
            for (var actor : battle.getActors()) {
                // The dimension is the opponent's (a wild Pokemon's or a trainer's), not the player's; its tier rates the opponent. A dimension the
                // configuration turns off (tier 0) is native. No entity (a scripted battle) is native too.
                if (actor.getType() != ActorType.PLAYER && actor instanceof EntityBackedBattleActor<?> backed && backed.getEntity() != null) {
                    int tier = ProfileZones.tierOf(backed.getEntity().level().dimension().location());
                    if (tier >= 1) {
                        profileZone = true;
                        zoneTier = Math.max(zoneTier, tier);
                    }
                }
                var kind = actor.getType() == ActorType.PLAYER ? BattleFxPlanner.Kind.PLAYER
                        : actor.getType() == ActorType.WILD ? BattleFxPlanner.Kind.WILD : BattleFxPlanner.Kind.NPC;
                var combatants = new ArrayList<BattleFxPlanner.Combatant>();
                for (var member : actor.getPokemonList()) {
                    Pokemon original = member.getOriginalPokemon();
                    combatants.add(new BattleFxPlanner.Combatant(member.getUuid().toString(), original.getUuid()));
                    if (kind != BattleFxPlanner.Kind.PLAYER) wildByUuid.put(original.getUuid(), original);
                    else ownedByUuid.put(original.getUuid(), original);
                }
                actors.add(new BattleFxPlanner.Actor(kind, combatants));
            }

            final int tierForWild = zoneTier;
            boolean raid = "raid".equals(battle.getFormat().getBattleType().getName());
            var rules = service.get().rules();
            // PvP is native for both sides until there is data to balance it (the planner returns nothing for it).
            var armed = ScoutEncounters.armedEnemies(playerIds);
            Map<String, List<BattleFx.Effect>> plan = BattleFxPlanner.plan(actors, battle.isPvP(), raid, profileZone, armed,
                    pokemonId -> ownedByUuid.containsKey(pokemonId) ? service.get().snapshot(ownedByUuid.get(pokemonId)) : service.get().snapshot(pokemonId),
                    pokemonId -> Optional.ofNullable(wildByUuid.get(pokemonId)).map(wild -> {
                        var rating = service.get().previewWild(wild, tierForWild);
                        return new CombatSnapshot(CombatSnapshot.Source.ENEMY, pokemonId.toString(), rating.rarity(),
                                rules.catalogVersion(), rating.slots(), null);
                    }));

            // A Haunting acts wherever the ranked effects act: never in PvP, and only in a raid, an armed encounter or a profile zone.
            if (!battle.isPvP() && (raid || profileZone || armed != null)) {
                plan = BattleFxPlanner.withHauntings(plan, actors, pokemonId -> service.get().hauntingOf(pokemonId));
            }
            String payload = BattleFx.payload(plan, rules.base());
            if (payload == null) return Map.of();
            LOG.debug("Battle {}: ascension effects for {} Pokemon", battleId, plan.size());
            return Map.of(FIELD, payload);
        } catch (RuntimeException | LinkageError ex) {
            // Not Throwable: an OutOfMemoryError must not be swallowed here as if the battle could simply start without effects.
            LOG.error("Could not build ascension effects for battle {}; it starts without them", battleId, ex);
            return Map.of();
        }
    }
}
