package com.cobbleascend.domain.v1;

import com.cobbleascend.domain.Rarity;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Who may fuse and what it costs (docs/TRANSCENDENT-POWERS.md, owner-approved 2026-10-09). The left Pokemon (host) is kept and
 * becomes the Transcendent; the right one (donor) is consumed. Pure rules: the store applies the change atomically, this only
 * decides whether the request is allowed and names the price. Whether a Pokemon already is a Transcendent is not part of schema 1,
 * so the caller passes it in.
 */
public final class FusionRules {
    public static final int DUST = 600;
    public static final int FACETS = 30;
    public static final int CORES = 10;
    public static final int CATALYSTS = 1;
    /** Tower Marks: proof of a personal Tower clear, so the top rung cannot be bought (docs/TOWER-COMPLETION-REWARDS.md). */
    public static final int MARKS = 5;
    /** Apex Shards a Fusion also costs while the Threat system is on (balance phase 1). */
    public static final int APEX = 12;
    /** Lifetime attunement the host must have earned. */
    public static final int HOST_ATTUNEMENT = 150;
    /**
     * Upgrade credits the host must have spent: all it can ever earn (one per ten levels). The first proposal, every slot at rank V,
     * needs 24 credits on a Mythical's six slots and could never be met, so the owner chose "all credits spent" on 2026-10-09.
     */
    public static final int HOST_CREDITS = Milestones.MAX_LEVEL / Milestones.STEP;

    private FusionRules() {}

    public static Map<MaterialId, Long> cost() {
        var cost = new EnumMap<MaterialId, Long>(MaterialId.class);
        cost.put(MaterialId.RESONANCE_DUST, (long) DUST);
        cost.put(MaterialId.FACET, (long) FACETS);
        cost.put(MaterialId.ASCENSION_CORE, (long) CORES);
        cost.put(MaterialId.UNIQUE_CATALYST, (long) CATALYSTS);
        cost.put(MaterialId.TOWER_MARK, (long) MARKS);
        if (Tunables.current().threat().enabled()) cost.put(MaterialId.APEX_SHARD, (long) APEX);
        return java.util.Collections.unmodifiableMap(cost);
    }

    /** One requirement and how far a Pokemon is towards it ({@code have} of {@code need}; 0 of 1 or 1 of 1 for a yes-or-no rule). */
    public record Requirement(String id, String label, boolean met, int have, int need) {}

    /**
     * How close one Pokemon is to each role, rule by rule, so a screen can show progress before the player reaches a Fuser. The same rules
     * as {@link #check} that concern one Pokemon alone; the pairing rules (different Uniques, the price) are judged at the Fuser.
     */
    public record Readiness(boolean transcendent, List<Requirement> host, List<Requirement> donor) {
        public Readiness { host = List.copyOf(host); donor = List.copyOf(donor); }
        public boolean hostReady() { return !transcendent && host.stream().allMatch(Requirement::met); }
        public boolean donorReady() { return !transcendent && donor.stream().allMatch(Requirement::met); }
    }

    public static Readiness readiness(ProfileV1 profile, boolean isTranscendent) {
        boolean unique = profile.unique() != null;
        boolean mythic = profile.rarity() == Rarity.MYTHICAL;
        boolean epic = profile.rarity().ordinal() >= Rarity.EPIC.ordinal();
        int credits = Math.min(profile.spentUpgradeCredits(), HOST_CREDITS);
        int attunement = (int) Math.min(profile.attunement(), HOST_ATTUNEMENT);
        var host = List.of(
                new Requirement("ascended", "Ascended", mythic, mythic ? 1 : 0, 1),
                new Requirement("credits", "Upgrade credits spent", credits >= HOST_CREDITS, credits, HOST_CREDITS),
                new Requirement("attunement", "Attunement", attunement >= HOST_ATTUNEMENT, attunement, HOST_ATTUNEMENT),
                new Requirement("unique", "Holds a Unique", unique, unique ? 1 : 0, 1));
        var donor = List.of(
                new Requirement("epic", "Epic or better", epic, epic ? 1 : 0, 1),
                new Requirement("unique", "Holds a Unique", unique, unique ? 1 : 0, 1));
        return new Readiness(isTranscendent, host, donor);
    }

    /**
     * Throws a {@link CraftException} naming the first rule broken: not the same Pokemon; the host is Mythical with every upgrade
     * credit spent and holds a Unique; the donor is Epic or better and holds a different Unique; neither is a Transcendent already;
     * the host has the lifetime attunement; the wallet covers the price.
     */
    public static void check(ProfileV1 host, boolean hostIsTranscendent, ProfileV1 donor, boolean donorIsTranscendent, MaterialWallet wallet) {
        if (host.pokemonId().equals(donor.pokemonId()))
            throw new CraftException(CraftException.Reason.FUSION_SAME_POKEMON, "A Pokemon cannot be fused with itself");
        if (hostIsTranscendent || donorIsTranscendent)
            throw new CraftException(CraftException.Reason.ALREADY_TRANSCENDENT, "A Transcendent can be neither host nor donor again");
        if (host.rarity() != Rarity.MYTHICAL)
            throw new CraftException(CraftException.Reason.FUSION_HOST_RARITY, "The host must be Ascended");
        if (host.spentUpgradeCredits() < HOST_CREDITS)
            throw new CraftException(CraftException.Reason.FUSION_HOST_RANKS, "The host must have spent all " + HOST_CREDITS + " upgrade credits");
        if (donor.rarity().ordinal() < Rarity.EPIC.ordinal())
            throw new CraftException(CraftException.Reason.FUSION_DONOR_RARITY, "The donor must be Epic or better");
        if (host.unique() == null || donor.unique() == null)
            throw new CraftException(CraftException.Reason.NO_UNIQUE, "Both Pokemon must hold a Unique");
        if (host.unique().uniqueId().equals(donor.unique().uniqueId()))
            throw new CraftException(CraftException.Reason.SAME_UNIQUE, "The two Uniques must differ");
        if (host.attunement() < HOST_ATTUNEMENT)
            throw new CraftException(CraftException.Reason.INSUFFICIENT_ATTUNEMENT, "The host needs " + HOST_ATTUNEMENT + " lifetime attunement");
        if (!wallet.canAfford(cost()))
            throw new CraftException(CraftException.Reason.INSUFFICIENT_FUNDS, "The wallet cannot cover the fusion price");
    }
}
