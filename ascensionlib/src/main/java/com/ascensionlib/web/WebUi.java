package com.ascensionlib.web;

import com.ascensionlib.Profiler;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where this mod's screens are shown: opening a page for a player, pushing JSON events to it and receiving the messages it posts.
 *
 * <p>Every screen is a native page of Snobblemon GUI ({@code snobblemon_server}), drawn by the Snobblemon Client and reached through
 * {@link SnobbleLink}. Snobblemon GUI is optional at runtime: without it there are no screens, and nothing here touches it. A page's messages come
 * from the player's client, so every message is bounded, parsed defensively and re-checked by the server exactly as a native packet would have been.
 */
public final class WebUi {
    private static final Logger LOG = LoggerFactory.getLogger("ascensionlib");
    private static final Gson GSON = new Gson();
    private static final int MAX_MESSAGE = 4096;
    private static boolean registered;
    /** Whether Snobblemon GUI is installed and its page API bound; decided once. */
    private static volatile Boolean bound;

    private WebUi() {}

    private static boolean bound() {
        Boolean b = bound;
        if (b != null) return b;
        synchronized (WebUi.class) {
            if (bound != null) return bound;
            boolean ok = false;
            if (FabricLoader.getInstance().isModLoaded(SnobbleLink.MOD_ID)) {
                try {
                    SnobbleLink.install();
                    ok = true;
                } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
                    LOG.warn("Snobblemon GUI is installed but its page API could not be bound; screens are off", exception);
                }
            }
            bound = ok;
            return ok;
        }
    }

    /** Whether any screen can be shown on this server. */
    public static boolean available() { return bound(); }

    /** Whether the bridge takes party rarities for the Snobblemon Client's party marks (Snobblemon GUI 2.21+). */
    public static boolean partyRarityAvailable() { return bound() && SnobbleLink.hasPartyRarity(); }

    /** Sends this player's party rarities ({"<pokemon uuid>": "<rarity id>"}) to the bridge; nothing when it cannot take them. */
    public static void partyRarity(ServerPlayer player, String json) {
        if (partyRarityAvailable()) SnobbleLink.partyRarity(player, json);
    }

    /** Says at startup where the screens come from. Call once. */
    public static synchronized void init() {
        if (registered) return;
        registered = true;
        if (bound()) LOG.info("AscensionLib's screens are shown by Snobblemon GUI (native pages; players need the Snobblemon Client).");
        else LOG.info("Snobblemon GUI is not installed: AscensionLib's screens (craft, fusion, inspect, capture card) are unavailable. "
                + "Install Snobblemon GUI on the server and the Snobblemon Client on each client.");
    }

    /**
     * Opens {@code page} (a page name such as {@code craft.html}, the native page {@code ascensionlib_craft}) for the player. Returns false and tells
     * the player why when they cannot see it. A page that needs data asks for it with its own "ready" message once it has opened.
     */
    public static boolean open(ServerPlayer player, String page) { return open(player, page, page, null); }

    /**
     * Opens a page of the AscensionDex that the Back button can return to: {@code key} names the page and its arguments, and {@code reopen}
     * opens it again the same way (see {@link PageNav}).
     */
    public static boolean open(ServerPlayer player, String page, String key, PageNav.Reopen reopen) {
        if (!bound()) {
            player.sendSystemMessage(Component.literal("This screen needs Snobblemon GUI on the server."));
            return false;
        }
        if (!canShow(player)) {
            player.sendSystemMessage(Component.literal("This screen needs the Snobblemon Client mod on your game."));
            return false;
        }
        WebSprites.reset(player.getUUID());
        PageNav.opening(player.getUUID(), key, reopen, GuiPresence.isOpen(player.getUUID()));   // read before touch: was a page already up?
        GuiPresence.touch(player.getUUID());
        if (!SnobbleLink.open(player, page)) LOG.warn("Snobblemon GUI has no page {} for {}", SnobbleLink.pageId(page), player.getGameProfile().getName());
        return true;
    }

    /** Opens a transparent overlay (HUD) page; the player can still play. */
    public static boolean openHud(ServerPlayer player, String page) {
        if (!canShow(player)) return false;
        WebSprites.reset(player.getUUID());
        return SnobbleLink.openHud(player, page);
    }

    /** Whether this player's client can show our pages (it has the Snobblemon Client). */
    public static boolean canShow(ServerPlayer player) {
        return bound() && SnobbleLink.canShow(player);
    }

    /** The most one page event may carry: far above any page's needs, so a runaway payload is caught rather than sent. */
    private static final int MAX_EVENT = 30_000;

    /** Sends one JSON event to the player's open page. */
    public static void emit(ServerPlayer player, String event, Object payload) { emitRaw(player, event, GSON.toJson(payload)); }

    /** Sends an event whose JSON is already built; an oversized event is logged and dropped, never truncated. */
    public static void emitRaw(ServerPlayer player, String event, String json) {
        if (!bound()) return;
        if (json.length() > MAX_EVENT) {
            LOG.warn("Page event {} for {} is {} characters, over the limit; not sent", event, player.getGameProfile().getName(), json.length());
            return;
        }
        Profiler.sample("emit." + event + ".chars", json.length());
        SnobbleLink.emit(player, event, json);
    }

    /** What a page's message handler receives: the player and the parsed JSON object the page posted. */
    public interface PageHandler { void handle(ServerPlayer player, JsonObject payload); }

    /**
     * Routes the page message with this {@code channel} to the handler on the server thread. A malformed or oversized message is dropped;
     * a failure in the handler is logged and never reaches the page.
     */
    public static void listen(String channel, PageHandler handler) {
        if (!bound()) return;
        java.util.function.BiConsumer<ServerPlayer, String> route = (player, raw) -> {
            var server = player.getServer();
            if (server == null || raw == null || raw.length() > MAX_MESSAGE) return;
            long queued = Profiler.start();
            server.execute(() -> {
                // "wait" is how long the message sat in the server thread's queue (a busy tick shows here); "run" is the handler itself.
                Profiler.stop("page." + channel + ".wait", queued);
                long started = Profiler.start();
                try {
                    JsonElement parsed = JsonParser.parseString(raw);
                    if (parsed.isJsonObject()) handler.handle(player, parsed.getAsJsonObject());
                } catch (JsonParseException | IllegalStateException | UnsupportedOperationException ignored) {
                    // A malformed message from a page is dropped, never an error for the server.
                } catch (RuntimeException exception) {
                    LOG.error("Page request {} failed for {}", channel, player.getGameProfile().getName(), exception);
                }
                Profiler.stop("page." + channel + ".run", started);
            });
        };
        SnobbleLink.listen(channel, route);
    }

    /** A bounded string field, or "" when absent or too long. */
    public static String str(JsonObject o, String key, int max) {
        var value = o.get(key);
        if (value == null || !value.isJsonPrimitive()) return "";
        String text = value.getAsString();
        return text.length() > max ? "" : text;
    }

    /** An id field (a UUID or a short slot id). */
    public static String id(JsonObject o, String key) { return str(o, key, 36); }

    /** A numeric field, or -1 when absent or not a number. */
    public static long num(JsonObject o, String key) {
        var value = o.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return -1;
        return value.getAsLong();
    }

    public static boolean bool(JsonObject o, String key) {
        var value = o.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

}
