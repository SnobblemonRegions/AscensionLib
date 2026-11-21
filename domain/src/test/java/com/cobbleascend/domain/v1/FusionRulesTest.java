package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.cobbleascend.domain.Rarity;
import com.cobbleascend.domain.v1.CraftException.Reason;
import java.util.*;
import org.junit.jupiter.api.Test;

class FusionRulesTest {
    private final RankedProgression progression = new RankedProgression(RankedRules.defaults());
    private final MaterialWallet rich = MaterialWallet.EMPTY.credit(FusionRules.cost());

    private ProfileV1 profile(Rarity rarity, boolean maxed, int attunement, String unique) {
        var base = progression.create(UUID.randomUUID(), UUID.randomUUID(), rarity, Origin.of("admin"), 40, List.of("fire"), new Random(7));
        var slots = new ArrayList<OrdinarySlot>();
        int[] spread = {5, 4, 3, 2, 1, 1};   // ten credits spent over six slots
        int i = 0;
        for (var s : base.ordinarySlots())
            slots.add(new OrdinarySlot(s.slotId(), s.category(), maxed ? spread[i++] : 1, s.affixId(), s.parameters(), s.rolledValue(), s.definitionVersion()));
        int spent = slots.stream().mapToInt(s -> s.rank() - 1).sum();
        var awarded = new TreeSet<Integer>();
        for (int level = Milestones.STEP; awarded.size() < spent; level += Milestones.STEP) awarded.add(level);
        return new ProfileV1(1, base.profileId(), base.pokemonId(), base.authorityId(), 1, rarity, base.initialRarity(), base.origin(),
                base.catalogVersion(), attunement, Milestones.MAX_LEVEL, awarded, spent, slots,
                unique == null ? null : new UniqueInstance(unique, 1, UUID.randomUUID()));
    }

    private ProfileV1 host() { return profile(Rarity.MYTHICAL, true, 150, "ashen_heart"); }
    private ProfileV1 donor() { return profile(Rarity.EPIC, false, 0, "rupture"); }

    private Reason refused(ProfileV1 host, boolean hostT, ProfileV1 donor, boolean donorT, MaterialWallet wallet) {
        return assertThrows(CraftException.class, () -> FusionRules.check(host, hostT, donor, donorT, wallet)).reason();
    }

    @Test void aQualifiedPairWithTheMaterialsIsAllowed() {
        FusionRules.check(host(), false, donor(), false, rich);
    }

    @Test void theCostIsTheApprovedOne() {
        var cost = FusionRules.cost();
        assertEquals(600L, cost.get(MaterialId.RESONANCE_DUST));
        assertEquals(30L, cost.get(MaterialId.FACET));
        assertEquals(10L, cost.get(MaterialId.ASCENSION_CORE));
        assertEquals(1L, cost.get(MaterialId.UNIQUE_CATALYST));
        assertThrows(UnsupportedOperationException.class, () -> cost.put(MaterialId.FACET, 1L));
    }

    @Test void theHostMustBeMythicalWithEveryUpgradeCreditSpent() {
        assertEquals(Reason.FUSION_HOST_RARITY, refused(profile(Rarity.LEGENDARY, true, 150, "ashen_heart"), false, donor(), false, rich));
        assertEquals(Reason.FUSION_HOST_RANKS, refused(profile(Rarity.MYTHICAL, false, 150, "ashen_heart"), false, donor(), false, rich));
    }

    @Test void theDonorMustBeEpicOrBetter() {
        assertEquals(Reason.FUSION_DONOR_RARITY, refused(host(), false, profile(Rarity.RARE, false, 0, "rupture"), false, rich));
        FusionRules.check(host(), false, profile(Rarity.MYTHICAL, false, 0, "rupture"), false, rich);
    }

    @Test void bothNeedDifferentUniques() {
        assertEquals(Reason.NO_UNIQUE, refused(host(), false, profile(Rarity.EPIC, false, 0, null), false, rich));
        assertEquals(Reason.NO_UNIQUE, refused(profile(Rarity.MYTHICAL, true, 150, null), false, donor(), false, rich));
        assertEquals(Reason.SAME_UNIQUE, refused(host(), false, profile(Rarity.EPIC, false, 0, "ashen_heart"), false, rich));
    }

    @Test void aTranscendentIsNeitherHostNorDonorAgain() {
        assertEquals(Reason.ALREADY_TRANSCENDENT, refused(host(), true, donor(), false, rich));
        assertEquals(Reason.ALREADY_TRANSCENDENT, refused(host(), false, donor(), true, rich));
    }

    @Test void theHostNeedsLifetimeAttunementAndTheWalletTheMaterials() {
        assertEquals(Reason.INSUFFICIENT_ATTUNEMENT, refused(profile(Rarity.MYTHICAL, true, 149, "ashen_heart"), false, donor(), false, rich));
        assertEquals(Reason.INSUFFICIENT_FUNDS, refused(host(), false, donor(), false, MaterialWallet.EMPTY));
    }

    @Test void aPokemonCannotBeFusedWithItself() {
        var h = host();
        assertEquals(Reason.FUSION_SAME_POKEMON, refused(h, false, h, false, rich));
    }

    @Test void readinessShowsProgressTowardsEachRole() {
        var ready = FusionRules.readiness(host(), false);
        assertTrue(ready.hostReady());
        assertTrue(ready.donorReady());
        var young = FusionRules.readiness(profile(Rarity.MYTHICAL, false, 120, null), false);
        assertFalse(young.hostReady());
        assertFalse(young.donorReady(), "no Unique");
        var credits = young.host().stream().filter(r -> r.id().equals("credits")).findFirst().orElseThrow();
        assertEquals(FusionRules.HOST_CREDITS, credits.need());
        assertFalse(credits.met());
        var attunement = young.host().stream().filter(r -> r.id().equals("attunement")).findFirst().orElseThrow();
        assertEquals(120, attunement.have());
        assertEquals(FusionRules.HOST_ATTUNEMENT, attunement.need());
        var epicDonor = FusionRules.readiness(donor(), false);
        assertFalse(epicDonor.hostReady());
        assertTrue(epicDonor.donorReady());
        var transcendent = FusionRules.readiness(host(), true);
        assertFalse(transcendent.hostReady(), "a Transcendent is neither host nor donor again");
        assertFalse(transcendent.donorReady());
    }
}
