package com.ascensionlib.web;

import java.util.function.BiConsumer;
import net.minecraft.server.level.ServerPlayer;

/**
 * WebGUI itself, the fallback while servers move to Snobblemon GUI: the only class that names WebGUI's own types, so they are loaded only when this
 * backend was chosen. Goes away with WebGUI.
 */
final class WebGuiLink {
    static final String MOD_ID = "webgui";

    private WebGuiLink() {}

    static boolean canShow(ServerPlayer player) {
        return land.webgui.WebviewNetworking.clientKind(player) == land.webgui.WebviewNetworking.ClientKind.CURRENT;
    }

    static void open(ServerPlayer player, String page) {
        land.webgui.api.WebviewApi.openGui(player, "webgui://ascensionlib/" + page);
    }

    static void openHud(ServerPlayer player, String page) {
        land.webgui.api.WebviewApi.openHud(player, "webgui://ascensionlib/" + page);
    }

    static void emit(ServerPlayer player, String event, String json) {
        land.webgui.api.WebviewApi.emitToPage(player, event, json);
    }

    static void listen(String channel, BiConsumer<ServerPlayer, String> handler) {
        land.webgui.api.WebviewApi.onPageEvent(channel, handler);
    }

    static void reloadAssets() {
        land.webgui.server.WebviewAssets.reload();
    }
}
