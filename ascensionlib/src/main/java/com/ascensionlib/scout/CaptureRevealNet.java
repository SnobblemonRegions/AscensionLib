package com.ascensionlib.scout;

import com.ascensionlib.ProfileService;
import com.cobbleascend.domain.v1.ProfileV1;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.ArrayList;
import java.util.stream.StreamSupport;
import net.minecraft.server.level.ServerPlayer;

/** The capture reveal: built only by the server, shown only to the owner, only after the save. */
public final class CaptureRevealNet {
    private CaptureRevealNet() {}

    /** What the owner may see of their own Pokemon: rarity, Unique, rolled modifiers, base stats. Never IVs, EVs or moves. */
    static ScoutPayloads.Detail detailOf(Pokemon p, ProfileService service, ProfileV1 value) {
        return new ScoutPayloads.Detail(p.getLevel(),
                StreamSupport.stream(p.getTypes().spliterator(), false).map(type -> type.getName()).toList(),
                ScoutEncounters.baseStats(p.getSpecies()), value.rarity().id(),
                service.uniqueLabel(value),
                value.ordinarySlots().stream().map(slot -> slotOf(service, slot)).toList());
    }

    /** One owned modifier row: its rolled value, the band that rank rolls in, and what it does (catalog text, nothing secret). */
    public static ScoutPayloads.Slot slotOf(ProfileService service, com.cobbleascend.domain.v1.OrdinarySlot slot) {
        var affix = service.rules().affix(slot.affixId());
        var band = affix.band(slot.rank());
        String type = slot.type();
        String condition = affix.base().condition() == null ? "" : affix.base().condition();
        return new ScoutPayloads.Slot(slot.category().id(), affix.base().name() + (type == null ? "" : " (" + type + ")"),
                slot.rank(), slot.rolledValue(), Math.max(band.min(), slot.floor()), band.max(), condition,
                com.cobbleascend.domain.v1.Resonance.themeOf(slot.affixId()).map(com.cobbleascend.domain.v1.Resonance.Theme::display).orElse(""), slot.sealed(),
                ScoutPayloads.Slot.unitOf(slot.affixId(), affix.base().channel()));
    }

    /** Shows a committed profile in the transparent capture GUI. False when the client cannot show web pages. */
    public static boolean send(ServerPlayer player, Pokemon pokemon, ProfileService service, ProfileV1 profile) {
        return send(player, pokemon, service, profile, profile.profileId().toString());
    }

    /** An operator explicitly asked to replay the card; normal acquisition callbacks keep the stable profile identity. */
    public static boolean preview(ServerPlayer player, Pokemon pokemon, ProfileService service, ProfileV1 profile) {
        return send(player, pokemon, service, profile, java.util.UUID.randomUUID().toString());
    }

    private static boolean send(ServerPlayer player, Pokemon pokemon, ProfileService service, ProfileV1 profile, String revealId) {
        if (!player.getUUID().equals(pokemon.getOwnerUUID())) return false;
        return com.ascensionlib.web.HudWeb.reveal(player, new CaptureRevealPayload(pokemon.getUuid().toString(),
                pokemon.getDisplayName(false).getString(), pokemon.getSpecies().getResourceIdentifier().toString(),
                new ArrayList<>(pokemon.getAspects()), detailOf(pokemon, service, profile), revealId));
    }
}
