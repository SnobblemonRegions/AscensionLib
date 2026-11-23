package com.ascensionlib.scout;

import com.ascensionlib.ProfileService;
import com.ascensionlib.compat.FusionNbt;
import com.cobbleascend.domain.v1.FusionRules;
import com.cobbleascend.domain.v1.Transcendence;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * The Transcendence panel of the owner's Inspect page ({@code inspectTranscend}): what this Pokemon became, what a Project Fusion fusion
 * carries inside it, or how close it is to being a Transcendence host or donor, rule by rule, so the player sees the road before they
 * reach a Fuser. Read only; the Fuser and the store decide.
 */
public final class TranscendView {
    private TranscendView() {}

    /** The head a fusion carries: its name, species, and (when it has one) its rarity and Unique. */
    public record HeadView(String name, String speciesId, String rarityId, String uniqueName) {}

    /**
     * {@code transcendentName} is empty unless this Pokemon is a Transcendent; {@code bonded} means a Fuser bond, never separable;
     * {@code fusion} means a Project Fusion fusion (with {@code head}); {@code atFuser} means Transcendence is made at a Fuser on this server.
     */
    public record Info(String pokemonId, boolean hasProfile, String transcendentName, boolean bonded, boolean fusion, HeadView head,
                       List<FusionRules.Requirement> host, List<FusionRules.Requirement> donor, boolean hostReady, boolean donorReady, boolean atFuser,
                       List<Hint> recipes) {}

    /** A named recipe this species belongs to, with a partner that completes it (by display name). */
    public record Hint(String recipeName, String partnerName) {}

    public static Info of(ProfileService service, Pokemon pokemon, boolean atFuser) {
        var id = pokemon.getUuid();
        var head = FusionNbt.headOf(pokemon).map(h -> headView(service, h)).orElse(null);
        var profile = service.canonical(pokemon);
        if (profile.isEmpty())
            return new Info(id.toString(), false, "", false, head != null, head, List.of(), List.of(), false, false, atFuser, List.of());
        var fusion = service.store().fusion(id);
        String name = "";
        if (fusion.isPresent()) {
            try {
                name = Transcendence.shared().resolve(fusion.get().hostSpecies(), fusion.get().hostUnique(), fusion.get().donorSpecies(),
                        fusion.get().donorUnique()).name();
            } catch (RuntimeException unresolved) {
                name = "Transcendent";
            }
        }
        boolean bonded = service.bondOf(id).filter(b -> b.hostId().equals(id)).isPresent();
        var readiness = FusionRules.readiness(profile.get(), service.isFused(id));
        // Named recipes are a lead for collectors: only for a Pokemon that can still be fused, at most four.
        var hints = name.isEmpty() && head == null
                ? Transcendence.shared().recipesFor(ProfileService.speciesIdOf(pokemon), 2).stream().limit(4)
                        .map(h -> new Hint(h.recipeName(), speciesName("cobblemon:" + h.partnerSpecies()))).toList()
                : List.<Hint>of();
        return new Info(id.toString(), true, name, bonded, head != null, head, readiness.host(), readiness.donor(),
                readiness.hostReady(), readiness.donorReady(), atFuser, hints);
    }

    private static HeadView headView(ProfileService service, FusionNbt.Head head) {
        String species = head.speciesId();
        String name = head.nickname().isBlank() ? speciesName(species) : head.nickname();
        var profile = service.store().profile(head.id());
        String rarity = profile.map(p -> p.rarity().id()).orElse("");
        String unique = profile.map(p -> p.unique() == null ? "" : service.rules().unique(p.unique().uniqueId()).map(u -> u.name()).orElse(p.unique().uniqueId()))
                .orElse("");
        return new HeadView(name, species, rarity, unique);
    }

    private static String speciesName(String speciesId) {
        try {
            var species = PokemonSpecies.INSTANCE.getByIdentifier(ResourceLocation.parse(speciesId));
            return species == null ? speciesId : species.getName();
        } catch (RuntimeException unknown) {
            return speciesId;
        }
    }
}
