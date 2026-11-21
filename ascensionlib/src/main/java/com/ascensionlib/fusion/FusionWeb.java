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

    /**
     * Players whose page was opened by a Project Fusion Fuser, with the donor (the Fuser's head) it holds: the page shows that one pairing
     * and its choices (Transcend, or a plain fusion), not the pickers. See {@code compat.bond.FuserBond}.
     */
    private static final Map<UUID, String> AT_FUSER = new ConcurrentHashMap<>();

    /** Forgets the page state of a player who left. */
    public static void forget(UUID player) { PENDING.remove(player); AT_FUSER.remove(player); }

    /** Opens the page for the pairing a Fuser sent: body as host, head as donor. */
    public static void openAtFuser(ServerPlayer player, String bodyId, String headId) {
        PENDING.put(player.getUUID(), bodyId);
        AT_FUSER.put(player.getUUID(), headId);
        WebUi.open(player, "fusion.html");
    }

    /** The donor of the Fuser pairing this player's page shows, or null when the page was not opened by a Fuser. */
    static String fuserDonor(ServerPlayer player) { return AT_FUSER.get(player.getUUID()); }

    private static com.ascensionlib.web.CalmPrefs intro;
    private static java.nio.file.Path introFile;

    /**
     * Who has dismissed the Fuser page's short intro, kept beside the world like the calm-effects choice (a per-player yes, stored only when
     * yes), so it shows once per player, on any device, and never again after "Got it".
     */
    private static synchronized com.ascensionlib.web.CalmPrefs introOf(net.minecraft.server.MinecraftServer server) {
        var file = server == null ? null : server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("ascensionlib-fuser-intro.json");
        if (intro == null || (file != null && !file.equals(introFile))) { intro = new com.ascensionlib.web.CalmPrefs(file); introFile = file; }
        return intro;
    }

    public static boolean introSeen(ServerPlayer player) { return introOf(player.getServer()).get(player.getUUID()); }

    /** The Fuser took the pairing: the page has nothing more to offer for it. */
    static void fuserDone(ServerPlayer player) { AT_FUSER.remove(player.getUUID()); }

    /** After a Swap at the Fuser: the new host and donor. */
    static void fuserSwapped(ServerPlayer player, String bodyId, String headId) {
        PENDING.put(player.getUUID(), bodyId);
        AT_FUSER.put(player.getUUID(), headId);
    }

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
                        num(o, "hostRevision"), num(o, "donorRevision"), num(o, "walletRevision"), WebUi.bool(o, "plain"))));
        // The Fuser intro's "Got it": never shown to this player again.
        WebUi.listen("ascension_fusion_intro_seen", (player, o) -> {
            if (com.ascensionlib.net.RateLimit.allow(player.getUUID(), "fusion.intro", 2, 1)) introOf(player.getServer()).set(player.getUUID(), true);
        });
        // At a Fuser, Swap trades the Fuser's head and body.
        WebUi.listen("ascension_fusion_fuser_swap", (player, o) -> {
            if (!com.ascensionlib.net.RateLimit.allow(player.getUUID(), "fusion.preview", 8, 4)) return;
            FusionNet.fuserSwap(player);
        });
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
        AT_FUSER.remove(player.getUUID());
        WebUi.open(player, "fusion.html");
    }

    static void feed(ServerPlayer player, java.util.Collection<String> species) { com.ascensionlib.web.WebSprites.feed(player, species); }

    /** Sends one JSON event to the player's open page. */
    static void emit(ServerPlayer player, String event, Object payload) { WebUi.emit(player, event, payload); }
}
