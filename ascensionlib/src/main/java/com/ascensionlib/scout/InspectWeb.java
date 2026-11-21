package com.ascensionlib.scout;

import com.ascensionlib.net.RateLimit;
import com.ascensionlib.web.Views;
import com.ascensionlib.web.WebUi;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The inspection screen ({@code inspect.html}): one of the player's own Pokemon (full detail, with an Upgrade button) or the enemies of
 * the encounter they are in (with the Scouter). The page names what it wants to see; the server decides what it may show: an owned
 * Pokemon only after {@link OwnedInspectNet#findForInspection} confirms it is theirs, an enemy only as far as the player has scouted it.
 */
public final class InspectWeb {
    public enum Kind { OWNED, ENEMY, WILD }

    private record Target(Kind kind, String id) {}

    /** What each player's inspect page should show once it reports that it has loaded. */
    private static final Map<UUID, Target> PENDING = new ConcurrentHashMap<>();

    private InspectWeb() {}

    public static void register() {
        WebUi.listen("ascension_inspect_ready", (player, o) -> {
            if (RateLimit.allow(player.getUUID(), "inspect.owned", 8, 4)) sendCurrent(player);
        });
        WebUi.listen("ascension_inspect_open", (player, o) -> {
            if (!RateLimit.allow(player.getUUID(), "inspect.open", 4, 2)) return;
            open(player, WebUi.str(o, "kind", 8).equals("enemy") ? Kind.ENEMY : Kind.OWNED, WebUi.id(o, "id"));
        });
        // Spend an Ascension Sigil on one of the player's own Pokemon that has no profile. The store decides everything; this only asks.
        WebUi.listen("ascension_inspect_sigil", (player, o) -> {
            if (!RateLimit.allow(player.getUUID(), "inspect.sigil", 3, 1)) return;
            String id = WebUi.id(o, "pokemonId");
            try {
                var pokemon = OwnedInspectNet.findForInspection(player, UUID.fromString(id));
                var service = com.ascensionlib.AscensionApi.service();
                if (pokemon.isEmpty() || service.isEmpty()) throw new IllegalArgumentException("That Pokemon is not yours");
                var profile = service.get().useSigil(player, pokemon.get());
                player.displayClientMessage(Component.literal("[Ascend] " + pokemon.get().getDisplayName(false).getString() + " is now "
                        + profile.rarity().displayName()
                        + "."), false);
            } catch (IllegalArgumentException | IllegalStateException refused) {
                player.displayClientMessage(Component.literal("[Ascend] " + refused.getMessage()), true);
            } catch (RuntimeException failed) {
                player.displayClientMessage(Component.literal("[Ascend] The Sigil could not be used right now. You were not charged."), true);
            }
            sendCurrent(player);
        });
        WebUi.listen("ascension_inspect_use", (player, o) -> {
            if (!RateLimit.allow(player.getUUID(), "scout.use", 4, 2)) return;
            String message = ScoutEncounters.use(player.getUUID(), WebUi.str(o, "encounterId", 100), WebUi.str(o, "subjectId", 100));
            player.displayClientMessage(Component.literal("[Scout] " + message), true);
        });
    }

    /** Opens the inspect page. {@code id} is a Pokemon id for {@link Kind#OWNED} and ignored for {@link Kind#ENEMY}. */
    public static boolean open(ServerPlayer player, Kind kind, String id) {
        PENDING.put(player.getUUID(), new Target(kind, kind == Kind.OWNED ? id : ""));
        return WebUi.open(player, "inspect.html");
    }

    private static void sendCurrent(ServerPlayer player) {
        var target = PENDING.get(player.getUUID());
        if (target == null) return;
        if (target.kind() == Kind.OWNED) {
            var result = OwnedInspectNet.resultFor(player, target.id());
            com.ascensionlib.web.WebSprites.feed(player, java.util.List.of(result.extra().speciesId()));
            WebUi.emit(player, "inspectOwned", Views.owned(result, com.ascensionlib.AscensionApi.store()
                    .map(store -> store.wallet(player.getUUID()).balance(com.cobbleascend.domain.v1.MaterialId.ASCENSION_SIGIL)).orElse(0L)));
            emitTranscend(player, target.id());
        }
        else if (target.kind() == Kind.WILD) emitWild(player);
        else WebUi.emit(player, "inspectScout", Views.state(ScoutEncounters.stateFor(player.getUUID())));
    }

    /** The Transcendence panel for one of the player's own Pokemon (see {@link TranscendView}); nothing when it cannot be found. */
    private static void emitTranscend(ServerPlayer player, String pokemonId) {
        var service = com.ascensionlib.AscensionApi.service().orElse(null);
        if (service == null) return;
        UUID id;
        try { id = UUID.fromString(pokemonId); } catch (IllegalArgumentException e) { return; }
        OwnedInspectNet.findForInspection(player, id).ifPresent(pokemon -> {
            var info = TranscendView.of(service, pokemon, com.ascensionlib.fusion.FusionNet.madeAtFuser());
            if (info.head() != null) com.ascensionlib.web.WebSprites.feed(player, java.util.List.of(info.head().speciesId()));
            WebUi.emit(player, "inspectTranscend", info);
        });
    }

    private static void emitWild(ServerPlayer player) {
        var state = ScoutEncounters.wildStateFor(player.getUUID());
        com.ascensionlib.web.WebSprites.feed(player, state.entries().stream().map(ScoutPayloads.Entry::speciesId).toList());
        WebUi.emit(player, "inspectScout", Views.state(state));
    }

    /** The player's wild session changed (a reveal): refresh their inspect page when that is what it shows. */
    public static void refreshWild(ServerPlayer player) {
        var target = PENDING.get(player.getUUID());
        if (target != null && target.kind() == Kind.WILD) emitWild(player);
    }

    /** A scouting change for this player: refreshes the enemy view when that is what their inspect page shows. */
    public static void onScout(ServerPlayer player, ScoutPayloads.State state) {
        var target = PENDING.get(player.getUUID());
        if (target != null && target.kind() == Kind.ENEMY) {
            com.ascensionlib.web.WebSprites.feed(player, state.entries().stream().map(ScoutPayloads.Entry::speciesId).toList());
            WebUi.emit(player, "inspectScout", Views.state(state));
        }
    }

    /** After an upgrade the owner's page asks again so it shows the new rolls. */
    public static void forget(UUID player) { PENDING.remove(player); }
}
