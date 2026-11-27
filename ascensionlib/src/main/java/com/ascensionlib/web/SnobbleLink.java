package com.ascensionlib.web;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Snobblemon GUI (SnobbleGUIOverhaul's server mod, {@code snobblemon_server}) shown directly: every page of this mod is a native Snobblemon page,
 * {@code ascensionlib_<page>}, drawn by the Snobblemon Client. Bound by reflection to the bridge's public {@code SnobbleUi}, so this mod neither
 * compiles against it nor needs it at runtime; nothing here runs unless {@link #install()} succeeded.
 *
 * <p>Each call does what WebGUI's compat jar ({@code WebguiCompat}, which stood in for WebGUI with the same bridge) did with the same request, so the
 * pages behave exactly as they did on a server running that jar.
 */
final class SnobbleLink {
    static final String MOD_ID = "snobblemon_server";
    private static final String BRIDGE_CLASS = "net.snobblemon.server.SnobbleUi";

    private static MethodHandle available;
    private static MethodHandle open;
    private static MethodHandle openHud;
    private static MethodHandle isHudLayout;
    private static MethodHandle openPage;
    private static MethodHandle hudPage;
    private static MethodHandle emitJson;
    private static MethodHandle onMessage;

    private SnobbleLink() {}

    /** Binds every method or none: throws when the bridge is missing one, so the caller can fall back. */
    static void install() throws ReflectiveOperationException {
        Class<?> api = Class.forName(BRIDGE_CLASS);
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle a = lookup.findStatic(api, "available", MethodType.methodType(boolean.class, ServerPlayer.class));
        MethodHandle o = lookup.findStatic(api, "open", MethodType.methodType(boolean.class, ServerPlayer.class, String.class));
        MethodHandle h = lookup.findStatic(api, "openHud", MethodType.methodType(boolean.class, ServerPlayer.class, String.class));
        MethodHandle l = lookup.findStatic(api, "isHudLayout", MethodType.methodType(boolean.class, String.class));
        MethodHandle p = lookup.findStatic(api, "openPage", MethodType.methodType(String.class, ServerPlayer.class));
        MethodHandle u = lookup.findStatic(api, "hudPage", MethodType.methodType(String.class, ServerPlayer.class));
        MethodHandle e = lookup.findStatic(api, "emitJson", MethodType.methodType(void.class, ServerPlayer.class, String.class, String.class, String.class));
        MethodHandle m = lookup.findStatic(api, "onMessage", MethodType.methodType(void.class, String.class, BiConsumer.class));
        available = a; open = o; openHud = h; isHudLayout = l; openPage = p; hudPage = u; emitJson = e; onMessage = m;
    }

    /** Whether this player's game can show a page: it has the Snobblemon Client. */
    static boolean canShow(ServerPlayer player) {
        try {
            return (boolean) available.invokeExact(player);
        } catch (Throwable ex) {
            return false;
        }
    }

    /** Opens the native page for {@code page} (a file name such as {@code craft.html}); false when it could not be shown. */
    static boolean open(ServerPlayer player, String page) {
        try {
            return (boolean) open.invokeExact(player, pageId(page));
        } catch (Throwable ex) {
            return false;
        }
    }

    /** Opens {@code page} as an overlay when the bridge has it as one, otherwise as a full page (as the compat jar did). */
    static boolean openHud(ServerPlayer player, String page) {
        String id = pageId(page);
        try {
            boolean overlay = (boolean) isHudLayout.invokeExact(id);
            return overlay ? (boolean) openHud.invokeExact(player, id) : (boolean) open.invokeExact(player, id);
        } catch (Throwable ex) {
            return false;
        }
    }

    /** Delivers an event to the player's open page: a {@code hud...} event to their overlay when one is up. Dropped when nothing is open. */
    static void emit(ServerPlayer player, String event, String json) {
        try {
            String hud = (String) hudPage.invokeExact(player);
            String page = event.startsWith("hud") && hud != null ? hud : (String) openPage.invokeExact(player);
            if (page == null) return;
            emitJson.invokeExact(player, page, event, json);
        } catch (Throwable ex) {
            // A cosmetic update that could not be delivered; the page asks again on its next "ready".
        }
    }

    /** Routes a page's messages on {@code channel} to {@code handler}. */
    static void listen(String channel, BiConsumer<ServerPlayer, String> handler) {
        try {
            onMessage.invoke(channel, handler);
        } catch (Throwable ex) {
            throw new IllegalStateException("Could not register page channel " + channel + " with Snobblemon GUI", ex);
        }
    }

    /**
     * The bridge's page id for one of this mod's pages: {@code craft.html} is {@code ascensionlib_craft}. The same rule as the compat jar's UrlMap
     * applied to {@code webgui://ascensionlib/<page>}: query and fragment dropped, {@code .html} dropped, each part lower case and limited to
     * {@code a-z 0-9 _ -}, parts joined by {@code _}, a repeated part and a trailing {@code index} dropped.
     */
    static String pageId(String page) {
        String u = "ascensionlib/" + (page == null ? "" : page.trim());
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int h = u.indexOf('#');
        if (h >= 0) u = u.substring(0, h);
        List<String> parts = new ArrayList<>();
        for (String seg : u.split("/")) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) continue;
            if (seg.endsWith(".html")) seg = seg.substring(0, seg.length() - 5);
            seg = seg.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
            if (seg.isEmpty()) continue;
            if (!parts.isEmpty() && parts.get(parts.size() - 1).equals(seg)) continue;
            parts.add(seg);
        }
        if (parts.size() > 1 && parts.get(parts.size() - 1).equals("index")) parts.remove(parts.size() - 1);
        return String.join("_", parts);
    }
}
