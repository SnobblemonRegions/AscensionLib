package com.ascensionlib.web;

import com.ascensionlib.Profiler;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where this mod's screens are shown: opening a page for a player, pushing JSON events to it and receiving the messages it posts.
 *
 * <p>Snobblemon GUI ({@code snobblemon_server}) is used when it is installed: every page is a native Snobblemon page ({@link SnobbleLink}) drawn by
 * the Snobblemon Client. Otherwise WebGUI, the older web-page client mod, is used ({@link WebGuiLink}): every screen is then a page under
 * {@code web/ascensionlib/} in the jar, copied to WebGUI's {@code config/webgui/web/} folder at start. With neither, there are no screens.
 *
 * <p>Both are optional at runtime and reached only through those two classes. A page's messages come from the player's client, so every message is
 * bounded, parsed defensively and re-checked by the server exactly as a native packet would have been.
 */
public final class WebUi {
    private static final Logger LOG = LoggerFactory.getLogger("ascensionlib");
    private static final Gson GSON = new Gson();
    private static final int MAX_MESSAGE = 4096;
    private static boolean registered;

    /** What shows the screens, in order of preference. */
    enum Backend { SNOBBLE, WEBGUI, NONE }

    private static volatile Backend backend;

    private WebUi() {}

    /** Snobblemon GUI when installed, else WebGUI when installed, else nothing. Pure, for the tests. */
    static Backend choose(boolean snobbleInstalled, boolean webguiInstalled) {
        if (snobbleInstalled) return Backend.SNOBBLE;
        return webguiInstalled ? Backend.WEBGUI : Backend.NONE;
    }

    /** The backend, chosen once: Snobblemon GUI falls back to WebGUI (or nothing) if it is installed but its API cannot be bound. */
    static Backend backend() {
        Backend chosen = backend;
        if (chosen != null) return chosen;
        synchronized (WebUi.class) {
            if (backend != null) return backend;
            var loader = FabricLoader.getInstance();
            boolean webgui = loader.isModLoaded(WebGuiLink.MOD_ID);
            chosen = choose(loader.isModLoaded(SnobbleLink.MOD_ID), webgui);
            if (chosen == Backend.SNOBBLE) {
                try {
                    SnobbleLink.install();
                } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
                    LOG.warn("Snobblemon GUI is installed but its page API could not be bound; {}", webgui ? "using WebGUI instead" : "screens are off",
                            exception);
                    chosen = choose(false, webgui);
                }
            }
            backend = chosen;
            return chosen;
        }
    }

    /** Whether any screen can be shown on this server. */
    public static boolean available() { return backend() != Backend.NONE; }

    /** Publishes the bundled pages when the server has started. Call once at startup; a no-op without WebGUI. */
    public static synchronized void init() {
        if (registered) return;
        registered = true;
        switch (backend()) {
            case SNOBBLE -> LOG.info("AscensionLib's screens are shown by Snobblemon GUI (native pages; players need the Snobblemon Client).");
            // Only WebGUI serves the web pages; Snobblemon GUI has its own layouts and nothing needs copying.
            case WEBGUI -> ServerLifecycleEvents.SERVER_STARTED.register(server -> publish());
            case NONE -> LOG.info("Snobblemon GUI is not installed: AscensionLib's screens (craft, fusion, inspect, capture card) are unavailable. "
                    + "Install Snobblemon GUI on the server and the Snobblemon Client on each client.");
        }
    }

    /**
     * Opens {@code page} (a file under web/ascensionlib/) for the player. Returns false and tells the player why when they cannot see it.
     * A page that needs data asks for it with its own "ready" message once it has loaded.
     */
    public static boolean open(ServerPlayer player, String page) { return open(player, page, page, null); }

    /**
     * Opens a page of the AscensionDex that the Back button can return to: {@code key} names the page and its arguments, and {@code reopen}
     * opens it again the same way (see {@link PageNav}).
     */
    public static boolean open(ServerPlayer player, String page, String key, PageNav.Reopen reopen) {
        Backend b = backend();
        if (b == Backend.NONE) {
            player.sendSystemMessage(Component.literal("This screen needs Snobblemon GUI on the server."));
            return false;
        }
        if (!canShow(player)) {
            player.sendSystemMessage(Component.literal(b == Backend.SNOBBLE ? "This screen needs the Snobblemon Client mod on your game."
                    : "This screen is a web page: install the WebGUI mod on your client."));
            return false;
        }
        WebSprites.reset(player.getUUID());
        PageNav.opening(player.getUUID(), key, reopen, GuiPresence.isOpen(player.getUUID()));   // read before touch: was a page already up?
        GuiPresence.touch(player.getUUID());
        if (b == Backend.SNOBBLE) {
            if (!SnobbleLink.open(player, page)) LOG.warn("Snobblemon GUI has no page {} for {}", SnobbleLink.pageId(page), player.getGameProfile().getName());
        } else {
            WebGuiLink.open(player, page);
        }
        return true;
    }

    /** Opens a transparent overlay (HUD) page; the player can still play. */
    public static boolean openHud(ServerPlayer player, String page) {
        if (!canShow(player)) return false;
        WebSprites.reset(player.getUUID());
        if (backend() == Backend.SNOBBLE) return SnobbleLink.openHud(player, page);
        WebGuiLink.openHud(player, page);
        return true;
    }

    /** Whether this player's client can show our pages. */
    public static boolean canShow(ServerPlayer player) {
        return switch (backend()) {
            case SNOBBLE -> SnobbleLink.canShow(player);
            case WEBGUI -> WebGuiLink.canShow(player);
            case NONE -> false;
        };
    }

    /** WebGUI refuses a single event over 32 KB; stay under it with room for the framing. */
    private static final int MAX_EVENT = 30_000;

    /** Sends one JSON event to the player's open page. */
    public static void emit(ServerPlayer player, String event, Object payload) { emitRaw(player, event, GSON.toJson(payload)); }

    /** Sends an event whose JSON is already built; an event too large for WebGUI is logged and dropped, never truncated. */
    public static void emitRaw(ServerPlayer player, String event, String json) {
        Backend b = backend();
        if (b == Backend.NONE) return;
        if (json.length() > MAX_EVENT) {
            LOG.warn("Page event {} for {} is {} characters, over WebGUI's limit; not sent", event, player.getGameProfile().getName(), json.length());
            return;
        }
        Profiler.sample("emit." + event + ".chars", json.length());
        if (b == Backend.SNOBBLE) SnobbleLink.emit(player, event, json);
        else WebGuiLink.emit(player, event, json);
    }

    /** What a page's message handler receives: the player and the parsed JSON object the page posted. */
    public interface PageHandler { void handle(ServerPlayer player, JsonObject payload); }

    /**
     * Routes the page message with this {@code channel} to the handler on the server thread. A malformed or oversized message is dropped;
     * a failure in the handler is logged and never reaches the page.
     */
    public static void listen(String channel, PageHandler handler) {
        Backend b = backend();
        if (b == Backend.NONE) return;
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
        if (b == Backend.SNOBBLE) SnobbleLink.listen(channel, route);
        else WebGuiLink.listen(channel, route);
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

    /** WebGUI only: copies every file under the jar's web/ascensionlib/ to WebGUI's web folder, then asks WebGUI to rescan. */
    private static void publish() {
        try {
            var container = FabricLoader.getInstance().getModContainer("ascensionlib").orElse(null);
            if (container == null) return;
            var source = container.findPath("web/ascensionlib").orElse(null);
            if (source == null) return;
            Path target = FabricLoader.getInstance().getConfigDir().resolve("webgui").resolve("web").resolve("ascensionlib");
            Files.createDirectories(target);
            int copied = 0;
            try (var files = Files.walk(source)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    if (!Files.isRegularFile(file)) continue;
                    Path out = target.resolve(source.relativize(file).toString());
                    byte[] bytes = Files.readAllBytes(file);
                    if (Files.exists(out) && java.util.Arrays.equals(Files.readAllBytes(out), bytes)) continue;
                    Files.createDirectories(out.getParent());
                    Files.write(out, bytes);
                    copied++;
                }
            }
            WebGuiLink.reloadAssets();
            LOG.info("Published the AscensionLib pages to {} ({} updated)", target, copied);
        } catch (IOException | RuntimeException | LinkageError exception) {
            LOG.warn("Could not publish the AscensionLib web pages; the screens will not open", exception);
        }
    }
}
