package com.ascensionlib.research;

import com.ascensionlib.AscensionApi;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.battles.BattleStartedEvent;
import com.cobblemon.mod.common.api.events.pokemon.PokemonSentEvent;
import com.cobblemon.mod.common.api.events.pokemon.ShoulderMountEvent;
import com.cobblemon.mod.common.api.events.pokemon.TradeEvent;
import com.cobblemon.mod.common.api.events.storage.ReleasePokemonEvent;
import com.cobblemon.mod.common.api.battles.model.actor.ActorType;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pokemon on the Research Bench (docs/FIELD-RESEARCH-DESIGN.md, decision 10): the real Pokemon stays in its owner's party or PC but cannot
 * battle, be sent out, ride a shoulder, be traded, released or fused until its study ends or it is recalled. The set mirrors the store's
 * {@code research_jobs} (loaded at start, updated on every start, collect and recall), so the event checks never touch the database.
 *
 * <p>These locks keep the Pokemon where the player expects it; they are not what stops research farming. Research belongs to the player and is
 * fixed when the study starts, and the store allows one study per player and one per Pokemon, so a Pokemon moved by a path these events do not
 * cover (an auction mod) copies nothing: its study simply ends unpaid when it is collected (see {@link FieldResearchRuntime#collect}).
 */
public final class ResearchLocks {
    private static final Logger LOG = LoggerFactory.getLogger("AscensionLib/Research");
    private static final Set<UUID> LOCKED = ConcurrentHashMap.newKeySet();
    public static final String REASON = "This Pokemon is on the Research Bench. Recall it or wait for the study to end.";

    private ResearchLocks() {}

    public static boolean locked(UUID pokemon) { return pokemon != null && LOCKED.contains(pokemon); }

    public static boolean locked(Pokemon pokemon) { return pokemon != null && locked(pokemon.getUuid()); }

    public static Optional<String> reason(Pokemon pokemon) { return locked(pokemon) ? Optional.of(REASON) : Optional.empty(); }

    static void lock(UUID pokemon) { LOCKED.add(pokemon); }

    static void unlock(UUID pokemon) { LOCKED.remove(pokemon); }

    /** Reloads the set from the store (server start). */
    static void reload() {
        LOCKED.clear();
        AscensionApi.store().ifPresent(store -> LOCKED.addAll(store.pokemonInResearch()));
    }

    static void clear() { LOCKED.clear(); }

    static void register() {
        CobblemonEvents.BATTLE_STARTED_PRE.subscribe((Consumer<BattleStartedEvent.Pre>) event -> {
            try {
                for (var actor : event.getBattle().getActors()) {
                    if (actor.getType() != ActorType.PLAYER) continue;
                    for (var member : actor.getPokemonList()) {
                        if (locked(member.getOriginalPokemon())) {
                            event.cancel();
                            event.setReason(Component.literal(member.getOriginalPokemon().getDisplayName(false).getString()
                                    + " is on the Research Bench: move it to your PC or recall it to battle.").withStyle(ChatFormatting.RED));
                            return;
                        }
                    }
                }
            } catch (RuntimeException failure) {
                LOG.warn("Research lock check failed for a battle start", failure);
            }
        });
        CobblemonEvents.POKEMON_SENT_PRE.subscribe((Consumer<PokemonSentEvent.Pre>) event -> {
            if (locked(event.getPokemon())) event.cancel();
        });
        CobblemonEvents.SHOULDER_MOUNT.subscribe((Consumer<ShoulderMountEvent>) event -> {
            if (locked(event.getPokemon())) event.cancel();
        });
        CobblemonEvents.TRADE_EVENT_PRE.subscribe((Consumer<TradeEvent.Pre>) event -> {
            if (locked(event.getTradeParticipant1Pokemon()) || locked(event.getTradeParticipant2Pokemon())) event.cancel();
        });
        CobblemonEvents.POKEMON_RELEASED_EVENT_PRE.subscribe((Consumer<ReleasePokemonEvent.Pre>) event -> {
            if (locked(event.getPokemon())) {
                event.cancel();
                event.getPlayer().sendSystemMessage(Component.literal(REASON).withStyle(ChatFormatting.RED));
            }
        });
    }
}
