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
    /**
     * One party slot. {@code id} is empty for an empty slot; {@code rarityId} is empty when the Pokemon has no profile. {@code attack} and {@code guard} are
     * the ranks (1-5) of its Attack and Guard traits; {@code attackSlots} and {@code guardSlots} how many its rarity allows, so the card can show the open ones.
     * {@code nextMilestone} (0: all reached), {@code attunement} and {@code attunementNeeded} (0 at Ascended) give the card its next goal.
     */
    public record Slot(int slot, String id, String name, String speciesId, int level, String rarityId, int pending, int hp, boolean fainted,
                       boolean shiny, boolean locked, List<String> types, List<Integer> attack, List<Integer> guard, int attackSlots, int guardSlots,
                       int nextMilestone, int attunement, int attunementNeeded) {}

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
                slots.add(new Slot(i, "", "", "", 0, "", 0, 0, false, false, false, List.of(), List.of(), List.of(), 0, 0, 0, 0, 0));
                continue;
            }
            var profile = service == null ? java.util.Optional.<com.cobbleascend.domain.v1.ProfileV1>empty() : service.canonical(pokemon);
            String name = pokemon.getDisplayName(false).getString();
            int max = Math.max(1, pokemon.getMaxHealth());
            slots.add(new Slot(i, pokemon.getUuid().toString(), name.length() > 40 ? name.substring(0, 40) : name,
                    pokemon.getSpecies().getResourceIdentifier().toString(), pokemon.getLevel(),
                    profile.map(p -> p.rarity().id()).orElse(""), profile.map(p -> p.pendingCredits()).orElse(0),
                    Math.max(0, Math.min(100, Math.round(100f * pokemon.getCurrentHealth() / max))), pokemon.getCurrentHealth() <= 0,
                    pokemon.getShiny(), CraftLocks.locked(pokemon),
                    java.util.stream.StreamSupport.stream(pokemon.getTypes().spliterator(), false).map(type -> type.getName()).toList(),
                    ranks(profile, com.cobbleascend.domain.v1.Category.PREFIX), ranks(profile, com.cobbleascend.domain.v1.Category.SUFFIX),
                    profile.map(p -> service.rules().slotCount(p.rarity(), com.cobbleascend.domain.v1.Category.PREFIX)).orElse(0),
                    profile.map(p -> service.rules().slotCount(p.rarity(), com.cobbleascend.domain.v1.Category.SUFFIX)).orElse(0),
                    // the card's next goal: the page picks the most useful one (an upgrade waiting, a promotion ready, the next milestone)
                    profile.map(com.ascensionlib.ProgressFacts::nextMilestone).orElse(0), profile.map(p -> p.attunement()).orElse(0),
                    profile.map(p -> com.ascensionlib.ProgressFacts.attunementNeeded(service.rules(), p)).orElse(0)));
        }
        return new View(slots);
    }

    private static List<Integer> ranks(java.util.Optional<com.cobbleascend.domain.v1.ProfileV1> profile, com.cobbleascend.domain.v1.Category category) {
        return profile.map(p -> p.ordinarySlots().stream().filter(slot -> slot.category() == category).map(slot -> slot.rank()).toList()).orElse(List.of());
    }

    static void send(ServerPlayer player) {
        var view = viewOf(player);
        WebSprites.feed(player, view.slots().stream().filter(s -> !s.id().isEmpty()).map(Slot::speciesId).toList());
        WebUi.emit(player, "partyView", view);
    }
}
