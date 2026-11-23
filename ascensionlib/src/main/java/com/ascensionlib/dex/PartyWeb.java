package com.ascensionlib.dex;

import com.ascensionlib.AscensionApi;
import com.ascensionlib.CraftLocks;
import com.ascensionlib.net.RateLimit;
import com.ascensionlib.web.WebSprites;
import com.ascensionlib.web.WebUi;
import com.cobblemon.mod.common.Cobblemon;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;

/**
 * The party page ({@code party.html}), opened by the AscensionDex: the player's six party slots with each Pokemon's picture, level, rarity, health and
 * pending upgrades, and a button to inspect or upgrade the one they pick. The page only asks; picking sends the same messages the other pages already use
 * ({@code ascension_inspect_open}, {@code ascension_craft_open}), so the server's ownership checks are the same ones.
 */
public final class PartyWeb {
    /** One party slot. {@code id} is empty for an empty slot; {@code rarityId} is empty when the Pokemon has no profile. */
    public record Slot(int slot, String id, String name, String speciesId, int level, String rarityId, int pending, int hp, boolean fainted,
                       boolean shiny, boolean locked) {}

    public record View(List<Slot> slots) {}

    private PartyWeb() {}

    public static void register() {
        WebUi.listen("ascension_party_ready", (player, o) -> {
            if (RateLimit.allow(player.getUUID(), "party.view", 6, 3)) send(player);
        });
    }

    public static boolean open(ServerPlayer player) { return WebUi.open(player, "party.html", "party", PartyWeb::open); }

    static View viewOf(ServerPlayer player) {
        var service = AscensionApi.service().orElse(null);
        var party = Cobblemon.INSTANCE.getStorage().getParty(player);
        var slots = new ArrayList<Slot>();
        for (int i = 0; i < 6; i++) {
            var pokemon = party.get(i);
            if (pokemon == null || pokemon.isBattleClone()) {
                slots.add(new Slot(i, "", "", "", 0, "", 0, 0, false, false, false));
                continue;
            }
            var profile = service == null ? java.util.Optional.<com.cobbleascend.domain.v1.ProfileV1>empty() : service.canonical(pokemon);
            String name = pokemon.getDisplayName(false).getString();
            int max = Math.max(1, pokemon.getMaxHealth());
            slots.add(new Slot(i, pokemon.getUuid().toString(), name.length() > 40 ? name.substring(0, 40) : name,
                    pokemon.getSpecies().getResourceIdentifier().toString(), pokemon.getLevel(),
                    profile.map(p -> p.rarity().id()).orElse(""), profile.map(p -> p.pendingCredits()).orElse(0),
                    Math.max(0, Math.min(100, Math.round(100f * pokemon.getCurrentHealth() / max))), pokemon.getCurrentHealth() <= 0,
                    pokemon.getShiny(), CraftLocks.locked(pokemon)));
        }
        return new View(slots);
    }

    static void send(ServerPlayer player) {
        var view = viewOf(player);
        WebSprites.feed(player, view.slots().stream().filter(s -> !s.id().isEmpty()).map(Slot::speciesId).toList());
        WebUi.emit(player, "partyView", view);
    }
}
