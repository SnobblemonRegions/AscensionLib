package com.ascensionlib.dex;

import com.ascensionlib.AscensionApi;
import com.ascensionlib.web.WebUi;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tells Snobblemon GUI the rarity of every Pokemon in each player's party, so the Snobblemon Client can mark it on Cobblemon's party bar and Summary
 * screen (a small sparkle, and a card on hover). Only the rarity id goes out; the bridge decides how it looks.
 *
 * <p>Checked every {@link #EVERY} ticks per player and sent only when it changed (a capture, a swap, a promotion), so a quiet party costs one map
 * lookup per Pokemon every two seconds and no network traffic. Off when the bridge has no {@code partyRarity} (an older Snobblemon GUI).
 */
public final class PartyRarityFeed {
    static final int EVERY = 40;
    private static final Map<UUID, String> SENT = new ConcurrentHashMap<>();
    private static int tick;

    private PartyRarityFeed() {}

    public static void tick(MinecraftServer server) {
        if (++tick % EVERY != 0 || !WebUi.partyRarityAvailable()) return;
        var service = AscensionApi.service().orElse(null);
        if (service == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Map<UUID, String> party = new LinkedHashMap<>();
            for (Pokemon pokemon : Cobblemon.INSTANCE.getStorage().getParty(player)) {
                if (pokemon == null) continue;
                service.canonical(pokemon).ifPresent(profile -> party.put(pokemon.getUuid(), profile.rarity().id()));
            }
            String json = json(party);
            if (json.equals(SENT.get(player.getUUID()))) continue;
            SENT.put(player.getUUID(), json);
            WebUi.partyRarity(player, json);
        }
    }

    /** A player left: their next join sends the party again. */
    public static void forget(UUID player) {
        SENT.remove(player);
    }

    /** {"<pokemon uuid>": "<rarity id>", ...} in party order; Pokemon with no profile are left out. */
    static String json(Map<UUID, String> party) {
        JsonObject o = new JsonObject();
        party.forEach((id, rarity) -> o.addProperty(id.toString(), rarity));
        return o.toString();
    }
}
