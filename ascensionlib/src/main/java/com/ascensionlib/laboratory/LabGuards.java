package com.ascensionlib.laboratory;

import com.ascensionlib.battle.ProfileZones;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.entity.SpawnEvent;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Laboratory guards: a wild Pokemon that spawns inside a laboratory's pieces is marked with {@link Laboratories#GUARD_TIER} in its persistent
 * data, so it is rated at that tier in a battle, by a Scouter and when caught ({@link ProfileZones#tierOf(com.cobblemon.mod.common.pokemon.Pokemon,
 * net.minecraft.resources.ResourceLocation)}), even after it wanders out. The lab-themed species come from {@code spawn_pool_world/laboratory.json};
 * anything else that happens to spawn inside is a guard too.
 */
public final class LabGuards {
    private static final Logger LOG = LoggerFactory.getLogger("AscensionLib/Laboratory");

    private LabGuards() {}

    public static void register() {
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe((Consumer<SpawnEvent<PokemonEntity>>) event -> {
            try {
                mark(event.getEntity());
            } catch (RuntimeException failure) {
                LOG.warn("Could not check a spawn against the laboratories", failure);
            }
        });
    }

    static void mark(PokemonEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return;
        var pokemon = entity.getPokemon();
        if (pokemon.getOwnerUUID() != null) return;
        if (!Laboratories.isInside(level, entity.blockPosition())) return;
        var data = pokemon.getPersistentData();
        if (data.getInt(ProfileZones.POKEMON_TIER_KEY) < Laboratories.GUARD_TIER) data.putInt(ProfileZones.POKEMON_TIER_KEY, Laboratories.GUARD_TIER);
    }
}
