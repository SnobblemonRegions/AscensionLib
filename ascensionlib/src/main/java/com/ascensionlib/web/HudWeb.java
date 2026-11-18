package com.ascensionlib.web;

import com.ascensionlib.AscensionApi;
import com.ascensionlib.scout.CaptureRevealPayload;
import com.ascensionlib.scout.InspectWeb;
import com.ascensionlib.scout.ScoutPayloads;
import com.cobblemon.mod.common.Cobblemon;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The persistent battle overlay ({@code hud.html}): scouted enemies, the player's party and battle-effect badges. Its interact key is
 * managed by WebGUI. Capture cards use {@link CaptureWeb}'s transparent GUI instead, which unlocks the cursor automatically.
 * A HUD click only asks the server to scout (the Scouter rules decide) or to open an existing screen.
 */
public final class HudWeb {
    /** Players whose overlay has loaded and can take events, and those asked to open it that have not reported in yet. */
    private static final Set<UUID> OPEN = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> OPENING = ConcurrentHashMap.newKeySet();
    /** The bleeds last pushed to each player, re-sent when the overlay finishes loading. */
    private static final Map<UUID, List<Views.BleedView>> BLEEDS = new ConcurrentHashMap<>();
    /** The Momentum stacks last pushed to each player, re-sent the same way. */
    private static final Map<UUID, List<Views.MomentumView>> MOMENTUM = new ConcurrentHashMap<>();

    /**
     * Players whose overlay work had to wait because a full-screen page was open (see {@link GuiPresence}): WebGUI's openHud destroys an open GUI
     * page, and the overlay's own close would close it too. {@link #flush} picks the work up once the page is gone.
     */
    private static final Set<UUID> DEFERRED = ConcurrentHashMap.newKeySet();

    private HudWeb() {}

    public static void register() {
        WebUi.listen("ascension_hud_ready", (player, o) -> {
            OPEN.add(player.getUUID());
            OPENING.remove(player.getUUID());
            resync(player);
        });
        WebUi.listen("ascension_hud_closed", (player, o) -> {
            OPEN.remove(player.getUUID());
            OPENING.remove(player.getUUID());
            // Closed by something outside us: bring it back now, not at the next capture. Only a few times a session, so an overlay that keeps
            // closing can never open, flash and close in a loop.
            if (REOPENS.merge(player.getUUID(), 1, Integer::sum) <= MAX_REOPENS) reopenAt.put(player.getUUID(), tickNow + REOPEN_TICKS);
        });
        // The overlay never closes itself any more. Opening it is what flashes grey on the client (a new browser view paints before the page is
        // transparent), and that must never happen at a capture, so it is opened once after login and kept. The page's idle message is ignored.
        WebUi.listen("ascension_hud_idle", (player, o) -> { });
        CaptureWeb.register();
        GuiPresence.register();
        ServerTickEvents.END_SERVER_TICK.register(HudWeb::flush);
        ServerTickEvents.END_SERVER_TICK.register(HudWeb::openDue);
    }

    /** Ticks after login before the overlay is opened (the client needs a moment to finish joining), and after an unexpected close before it is reopened. */
    private static final int OPEN_AFTER_JOIN_TICKS = 100;
    private static final int REOPEN_TICKS = 100;
    /** Players whose overlay is to be opened, and the server tick it is due at. */
    private static final Map<UUID, Integer> reopenAt = new ConcurrentHashMap<>();
    private static final int MAX_REOPENS = 3;
    private static final Map<UUID, Integer> REOPENS = new ConcurrentHashMap<>();
    private static int tickNow;

    /** A player has joined: the overlay opens a few seconds later, while nothing is happening, rather than at their first capture. */
    public static void joined(ServerPlayer player) {
        reopenAt.put(player.getUUID(), tickNow + OPEN_AFTER_JOIN_TICKS);
    }

    private static void openDue(MinecraftServer server) {
        tickNow = server.getTickCount();
        if (reopenAt.isEmpty() || tickNow % 10 != 0) return;
        for (var entry : reopenAt.entrySet()) {
            if (entry.getValue() > tickNow) continue;
            var player = server.getPlayerList().getPlayer(entry.getKey());
            reopenAt.remove(entry.getKey());
            if (player == null || !WebUi.canShow(player)) continue;
            ensureOpen(player);
        }
    }

    /** Re-sends the bleed and Momentum strips, the scouting panel and the team. */
    private static void resync(ServerPlayer player) {
        var bleeds = BLEEDS.get(player.getUUID());
        if (bleeds != null && !bleeds.isEmpty()) WebUi.emit(player, "hudBleed", bleeds);
        var momentum = MOMENTUM.get(player.getUUID());
        if (momentum != null && !momentum.isEmpty()) WebUi.emit(player, "hudEffects", new Views.EffectsView(momentum, List.of()));
        var state = com.ascensionlib.scout.ScoutEncounters.stateFor(player.getUUID());
        WebUi.emit(player, "hudScout", Views.state(state));
        if (!state.entries().isEmpty()) sendTeam(player);
    }

    /** Twice a second: gives the overlay back to players whose full-screen page has closed, by opening it or re-sending what it missed. */
    private static void flush(MinecraftServer server) {
        if (DEFERRED.isEmpty() || server.getTickCount() % 10 != 0) return;
        for (UUID id : DEFERRED) {
            var player = server.getPlayerList().getPlayer(id);
            if (player == null) { DEFERRED.remove(id); continue; }
            if (GuiPresence.isOpen(id)) continue;
            DEFERRED.remove(id);
            if (OPEN.contains(id)) resync(player); else ensureOpen(player);
        }
    }

    /** Forgets what is held for a player who left. */
    public static void forget(UUID player) {
        OPEN.remove(player);
        OPENING.remove(player);
        CaptureWeb.forget(player);
        reopenAt.remove(player);
        REOPENS.remove(player);
        DEFERRED.remove(player);
        GuiPresence.forget(player);
        PageNav.forget(player);
        BLEEDS.remove(player);
        MOMENTUM.remove(player);
        com.ascensionlib.battle.BleedWatch.forget(player);
        com.ascensionlib.battle.Participation.forget(player);
    }

    private static void ensureOpen(ServerPlayer player) {
        if (GuiPresence.isOpen(player.getUUID())) { DEFERRED.add(player.getUUID()); GuiPresence.logHeld(player.getUUID()); return; }
        if (!OPEN.contains(player.getUUID()) && !OPENING.contains(player.getUUID()) && WebUi.openHud(player, "hud.html")) OPENING.add(player.getUUID());
    }

    /** Compatibility entrypoint for the capture GUI. False when the client needs the chat fallback. */
    public static boolean reveal(ServerPlayer player, CaptureRevealPayload payload) {
        return CaptureWeb.reveal(player, payload);
    }

    /** The scouting state changed for this player: refresh the panel, opening the overlay when an encounter begins. */
    public static void pushScout(ServerPlayer player, ScoutPayloads.State state) {
        InspectWeb.onScout(player, state);
        if (!WebUi.canShow(player)) return;
        if (state.entries().isEmpty() && !OPEN.contains(player.getUUID())) return;
        if (!state.entries().isEmpty()) ensureOpen(player);
        if (live(player)) {
            WebUi.emit(player, "hudScout", Views.state(state));
            if (!state.entries().isEmpty()) sendTeam(player);
        }
    }

    /** The bleeds in this player's battle changed: show the badge strip, opening the overlay for the first one. An empty list clears it. */
    public static void pushBleed(ServerPlayer player, List<Views.BleedView> bleeds) {
        if (bleeds.isEmpty()) BLEEDS.remove(player.getUUID()); else BLEEDS.put(player.getUUID(), List.copyOf(bleeds));
        if (!WebUi.canShow(player)) return;
        if (bleeds.isEmpty() && !OPEN.contains(player.getUUID())) return;
        if (!bleeds.isEmpty()) ensureOpen(player);
        if (live(player)) WebUi.emit(player, "hudBleed", bleeds);
    }

    /**
     * Momentum stacks or a Wardstone/Ensnaring proc in this player's battle: show the strip, opening the overlay for the first one. Stacks stay until
     * an update without them; a proc is flashed once and not kept (an overlay that is still loading misses it, the stacks are re-sent).
     */
    public static void pushEffects(ServerPlayer player, Views.EffectsView effects) {
        if (effects.momentum().isEmpty()) MOMENTUM.remove(player.getUUID()); else MOMENTUM.put(player.getUUID(), List.copyOf(effects.momentum()));
        if (!WebUi.canShow(player)) return;
        boolean any = !effects.momentum().isEmpty() || !effects.procs().isEmpty();
        if (!any && !OPEN.contains(player.getUUID())) return;
        if (any) ensureOpen(player);
        if (live(player)) WebUi.emit(player, "hudEffects", effects);
    }

    /** Whether the overlay is loaded and in front, so an event sent now reaches it (a full-screen page in front would receive it instead). */
    private static boolean live(ServerPlayer player) {
        if (!OPEN.contains(player.getUUID())) return false;
        if (!GuiPresence.isOpen(player.getUUID())) return true;
        DEFERRED.add(player.getUUID());   // re-sent by flush() once the page closes
        return false;
    }

    private static void sendTeam(ServerPlayer player) {
        var team = team(player);
        WebSprites.feed(player, team.stream().map(Views.TeamView::speciesId).toList());
        WebUi.emit(player, "hudTeam", team);
    }

    private static List<Views.TeamView> team(ServerPlayer player) {
        var service = AscensionApi.service().orElse(null);
        var out = new ArrayList<Views.TeamView>();
        for (var pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
            if (pokemon.isBattleClone()) continue;
            String rarity = service == null ? "" : service.canonical(pokemon).map(p -> p.rarity().id()).orElse("");
            String name = pokemon.getDisplayName(false).getString();
            out.add(new Views.TeamView(pokemon.getUuid().toString(), name.length() > 60 ? name.substring(0, 60) : name,
                    pokemon.getSpecies().getResourceIdentifier().toString(), rarity, pokemon.getLevel()));
        }
        return out;
    }
}
