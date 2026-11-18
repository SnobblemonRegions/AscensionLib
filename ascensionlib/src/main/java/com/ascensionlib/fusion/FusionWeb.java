package com.ascensionlib.fusion;

import static com.ascensionlib.web.WebUi.id;
import static com.ascensionlib.web.WebUi.num;

import com.ascensionlib.web.WebUi;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;

/**
 * The fusion screen's page channels ({@code fusion.html}): it opens the page, answers what the page asks and pushes the server's answers
 * back as JSON events. The page is code the player can edit, so the server re-checks ownership, the rules, the price and the revisions.
 */
public final class FusionWeb {
    /** The host each player's page should show once it reports that it has loaded. */
    private static final Map<UUID, String> PENDING = new ConcurrentHashMap<>();

    private FusionWeb() {}

    /** Forgets the page state of a player who left. */
    public static void forget(UUID player) { PENDING.remove(player); }

    public static boolean available() { return WebUi.available(); }

    static void register() {
        WebUi.listen("ascension_fusion_ready", (player, o) -> {
            var host = PENDING.get(player.getUUID());
            if (host != null) FusionNet.sendCandidates(player, host);
        });
        WebUi.listen("ascension_fusion_pick", (player, o) -> {
            if (!com.ascensionlib.net.RateLimit.allow(player.getUUID(), "fusion.pick", 6, 3)) return;
            FusionNet.sendPick(player, WebUi.bool(o, "hostRole"), id(o, "hostId"), id(o, "donorId"));
        });
        WebUi.listen("ascension_fusion_preview", (player, o) -> {
            if (!com.ascensionlib.net.RateLimit.allow(player.getUUID(), "fusion.preview", 8, 4)) return;
            FusionNet.sendDetail(player, id(o, "hostId"), id(o, "donorId"));
        });
        WebUi.listen("ascension_fusion_confirm", (player, o) ->
                FusionNet.confirm(player, new FusionNet.ConfirmRequest(id(o, "operationId"), id(o, "hostId"), id(o, "donorId"),
                        num(o, "hostRevision"), num(o, "donorRevision"), num(o, "walletRevision"))));
        // After a fusion the page asks for the host again, to show what it became.
        WebUi.listen("ascension_fusion_refresh", (player, o) -> {
            if (!com.ascensionlib.net.RateLimit.allow(player.getUUID(), "fusion.open", 4, 2)) return;
            String host = id(o, "hostId");
            if (!host.isEmpty()) { PENDING.put(player.getUUID(), host); FusionNet.sendCandidates(player, host); }
        });
    }

    /** Opens the page for this player with {@code hostId} in the host chamber. */
    public static void open(ServerPlayer player, String hostId) {
        PENDING.put(player.getUUID(), hostId);
        WebUi.open(player, "fusion.html", "fusion:" + hostId, p -> {
            var host = com.ascensionlib.craft.CraftNet.findOwned(p, hostId).orElse(null);
            if (host == null) return false;
            FusionNet.openFor(p, host);
            return true;
        });
    }

    static void feed(ServerPlayer player, java.util.Collection<String> species) { com.ascensionlib.web.WebSprites.feed(player, species); }

    /** Sends one JSON event to the player's open page. */
    static void emit(ServerPlayer player, String event, Object payload) { WebUi.emit(player, event, payload); }
}
