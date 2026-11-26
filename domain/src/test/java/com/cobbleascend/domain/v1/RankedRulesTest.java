package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.cobbleascend.domain.Rarity;
import com.cobbleascend.domain.Rules;
import com.google.gson.JsonParser;
import java.util.*;
import org.junit.jupiter.api.Test;

class RankedRulesTest {
    private final RankedRules rules = RankedRules.defaults();
    private final RankedProgression progression = new RankedProgression(rules);

    private Map<String, List<RankBand>> defaultBands() {
        var bands = new LinkedHashMap<String, List<RankBand>>();
        rules.affixes().forEach(a -> bands.put(a.id(), a.bands()));
        return bands;
    }

    private static List<RankBand> bands(int... pairs) {
        var list = new ArrayList<RankBand>();
        for (int i = 0; i < pairs.length; i += 2) list.add(new RankBand(pairs[i], pairs[i + 1]));
        return list;
    }

    private ProfileV1 sample(Rarity rarity) {
        return progression.create(UUID.randomUUID(), UUID.randomUUID(), rarity, Origin.of("admin"), 40,
                List.of("fire"), new Random(7));
    }

    /** Rebuilds a profile with replaced mutable parts, bypassing transitions to craft invalid states. */
    static ProfileV1 rebuild(ProfileV1 p, int catalogVersion, Set<Integer> awarded, int highest, int spent,
                             List<OrdinarySlot> slots, UniqueInstance unique) {
        return new ProfileV1(p.schemaVersion(), p.profileId(), p.pokemonId(), p.authorityId(), p.revision(),
                p.rarity(), p.initialRarity(), p.origin(), catalogVersion, p.attunement(), highest, awarded,
                spent, slots, unique);
    }

    private static ProfileV1 withSlots(ProfileV1 p, List<OrdinarySlot> slots) {
        return rebuild(p, p.catalogVersion(), p.awardedMilestones(), p.highestLevelObserved(),
                slots.stream().mapToInt(s -> s.rank() - 1).sum(), slots, p.unique());
    }

    @Test void defaultCatalogDefinesFiveIncreasingBandsPerAffixAndMatchesSchemaZeroRankOne() {
        assertEquals(54, rules.affixes().size());
        for (var affix : rules.affixes()) {
            assertEquals(5, affix.bands().size());
            assertEquals(new RankBand(affix.base().min(), affix.base().max()), affix.band(1),
                    "Rank I must equal the prototype range so schema-zero rolls stay valid: " + affix.id());
            for (int rank = 2; rank <= 5; rank++) assertTrue(affix.band(rank).min() > affix.band(rank - 1).max());
        }
        assertEquals(bands(4, 8, 9, 12, 13, 16, 17, 20, 21, 24), rules.affix("type_focus").bands(),
                "Fire Focus matches the specification example");
        assertEquals(List.of("ashen_heart", "last_breath", "creeping_venom", "stormcaller", "rupture", "titans_heart", "triple_seven",
                "crescendo", "nightfang", "iron_maiden", "tailwind_heart", "huntmark", "martyrs_gift", "aegis_oath", "fickle_fortune"),
                rules.uniques().stream().map(UniqueDefinition::id).toList());
        assertEquals(1, rules.catalogVersion());
    }

    @Test void powerhouseAffixesAreMuchRarerAndStrongerThanTheirStandardPeers() {
        var powerhouses = List.of("type_mastery", "overwhelming_force", "iron_resolve", "type_bulwark", "giant_slayer", "pressure_point");
        for (var id : powerhouses) {
            var affix = rules.affix(id);
            assertTrue(affix.weight() <= 8, id + " must be rare");
            var standard = rules.affixes().stream()
                    .filter(a -> a.weight() == 100 && a.base().channel().equals(affix.base().channel())).toList();
            double standardMean = standard.stream().mapToDouble(a -> (a.band(1).min() + a.band(1).max()) / 2.0).average().orElseThrow();
            double mean = (affix.band(1).min() + affix.band(1).max()) / 2.0;
            assertTrue(mean > standardMean, id + " rank I must beat the standard average of " + standardMean);
        }
        assertEquals(new RankBand(51, 60), rules.affix("type_mastery").band(5));
        assertEquals(6, rules.affixes().stream().filter(a -> a.weight() <= 8).count(), "Only the six powerhouses are rare (Giant Slayer and Pressure Point joined, owner 2026-11-25)");
        assertTrue(rules.affixes().stream().allMatch(a -> a.weight() >= 3));
    }

    @Test void channelCapsLeaveRoomForEveryRankVAffix() {
        assertEquals(100, rules.base().cap("outgoingDamage"));
        assertEquals(50, rules.base().cap("incomingReduction"));
        assertEquals(50, rules.base().cap("healing"));
        assertEquals(100, rules.base().cap("residual"));
        for (var affix : rules.affixes()) {
            assertTrue(affix.band(5).max() <= rules.base().cap(affix.base().channel()),
                    affix.id() + " rank V would be clipped by its own channel cap");
        }
    }

    private static com.google.gson.JsonObject resource(String name) {
        try (var reader = new java.io.InputStreamReader(
                RankedRulesTest.class.getResourceAsStream("/cobbleascend/" + name), java.nio.charset.StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test void channelCapsAreBoundedAndReductionCannotApproachInvulnerability() {
        var json = resource("balance.json");
        var catalog = resource("affixes.json");
        json.getAsJsonObject("capsPercent").addProperty("incomingReduction", 95);
        assertThrows(IllegalArgumentException.class, () -> Rules.parse(json, catalog));
        json.getAsJsonObject("capsPercent").addProperty("incomingReduction", 50);
        json.getAsJsonObject("capsPercent").addProperty("outgoingDamage", 101);
        assertThrows(IllegalArgumentException.class, () -> Rules.parse(json, catalog));
    }

    @Test void everyFamilyOfferedBySlotCategoryStillFillsAMythic() {
        for (var category : Category.values()) {
            long families = rules.affixes().stream().filter(a -> a.category() == category).map(RankedAffix::family).distinct().count();
            assertTrue(families >= rules.slotCount(Rarity.MYTHICAL, category));
        }
        assertEquals(2, rules.affixes().stream().filter(a -> a.family().equals("type_offense")).count(),
                "Type Mastery shares Type Focus's family so they never coexist");
        assertEquals(2, rules.affixes().stream().filter(a -> a.family().equals("type_defense")).count());
    }

    @Test void incompleteOrMisorderedCatalogsAreRejected() {
        var base = rules.base();
        var missing = defaultBands();
        missing.remove("type_focus");
        assertThrows(IllegalArgumentException.class, () -> new RankedRules(base, 1, missing, List.of()));

        var extra = defaultBands();
        extra.put("not_an_affix", bands(1, 2, 3, 4, 5, 6, 7, 8, 9, 10));
        assertThrows(IllegalArgumentException.class, () -> new RankedRules(base, 1, extra, List.of()));

        var four = defaultBands();
        four.put("type_focus", bands(4, 8, 9, 12, 13, 16, 17, 20));
        assertThrows(IllegalArgumentException.class, () -> new RankedRules(base, 1, four, List.of()));

        var touching = defaultBands();
        touching.put("type_focus", bands(4, 8, 8, 12, 13, 16, 17, 20, 21, 24));
        assertThrows(IllegalArgumentException.class, () -> new RankedRules(base, 1, touching, List.of()));

        var reversed = defaultBands();
        reversed.put("type_focus", bands(21, 24, 17, 20, 13, 16, 9, 12, 4, 8));
        assertThrows(IllegalArgumentException.class, () -> new RankedRules(base, 1, reversed, List.of()));

        assertThrows(IllegalArgumentException.class, () -> new RankBand(5, 4));
        assertThrows(IllegalArgumentException.class, () -> new RankBand(0, 101));
        assertThrows(IllegalArgumentException.class, () -> new RankedRules(base, 0, defaultBands(), List.of()));
    }

    @Test void uniqueDefinitionsMustBeUniqueAndMatchTheCatalogVersion() {
        var base = rules.base();
        var ashen = new UniqueDefinition("ashen_heart", "Ashen Heart", 1);
        assertThrows(IllegalArgumentException.class, () -> new RankedRules(base, 1, defaultBands(), List.of(ashen, ashen)));
        assertThrows(IllegalArgumentException.class, () -> new RankedRules(base, 1, defaultBands(),
                List.of(new UniqueDefinition("ashen_heart", "Ashen Heart", 2))));
        assertThrows(IllegalArgumentException.class, () -> new UniqueDefinition("Bad Id", "Name", 1));
    }

    @Test void catalogJsonIsParsedStrictly() {
        var base = Rules.defaults();
        assertThrows(IllegalArgumentException.class, () -> RankedRules.parse(base,
                JsonParser.parseString("{\"schemaVersion\":2,\"catalogVersion\":1,\"ranks\":{},\"uniques\":[]}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> RankedRules.parse(base,
                JsonParser.parseString("{\"schemaVersion\":1,\"catalogVersion\":1,\"ranks\":{},\"uniques\":[],\"surprise\":1}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> RankedRules.parse(base,
                JsonParser.parseString("{\"schemaVersion\":1,\"catalogVersion\":1,\"ranks\":{\"type_focus\":[[1,2,3]]},\"uniques\":[]}").getAsJsonObject()));
        assertThrows(IllegalArgumentException.class, () -> RankedRules.parse(base,
                JsonParser.parseString("{\"schemaVersion\":1,\"catalogVersion\":1,\"ranks\":{},\"uniques\":[]}").getAsJsonObject()),
                "Missing rank tables for released affixes");
    }

    @Test void validateRejectsEveryCatalogViolation() {
        var profile = sample(Rarity.RARE);
        rules.validate(profile);
        var slots = profile.ordinarySlots();
        var first = slots.getFirst();

        assertThrows(IllegalArgumentException.class, () -> rules.validate(rebuild(profile, 2,
                profile.awardedMilestones(), profile.highestLevelObserved(), 0, slots, null)));

        var outOfBand = new OrdinarySlot(first.slotId(), first.category(), first.rank(), first.affixId(),
                first.parameters(), rules.affix(first.affixId()).band(1).max() + 1, first.definitionVersion());
        assertThrows(IllegalArgumentException.class, () -> rules.validate(withSlots(profile, replaceFirst(slots, outOfBand))));

        var staleVersion = new OrdinarySlot(first.slotId(), first.category(), first.rank(), first.affixId(),
                first.parameters(), first.rolledValue(), first.definitionVersion() + 1);
        assertThrows(IllegalArgumentException.class, () -> rules.validate(withSlots(profile, replaceFirst(slots, staleVersion))));

        var wrongCategory = new OrdinarySlot(first.slotId(), first.category(), 1, "healthy_guard", Map.of(), 4, 1);
        assertThrows(IllegalArgumentException.class, () -> rules.validate(withSlots(profile, replaceFirst(slots, wrongCategory))));

        var unknownAffix = new OrdinarySlot(first.slotId(), first.category(), 1, "mystery", Map.of(), 4, 1);
        assertThrows(IllegalArgumentException.class, () -> rules.validate(withSlots(profile, replaceFirst(slots, unknownAffix))));

        var typedWithoutType = new OrdinarySlot(first.slotId(), first.category(), 1, "type_focus", Map.of(), 5, 1);
        assertThrows(IllegalArgumentException.class, () -> rules.validate(withSlots(profile, replaceFirst(slots, typedWithoutType))));
        var badType = new OrdinarySlot(first.slotId(), first.category(), 1, "type_focus", Map.of("type", "shadow"), 5, 1);
        assertThrows(IllegalArgumentException.class, () -> rules.validate(withSlots(profile, replaceFirst(slots, badType))));
        var untypedWithType = new OrdinarySlot(first.slotId(), first.category(), 1, "physical_force", Map.of("type", "fire"), 4, 1);
        assertThrows(IllegalArgumentException.class, () -> rules.validate(withSlots(profile, replaceFirst(slots, untypedWithType))));

        var second = slots.get(1);
        var duplicateFamily = new ArrayList<>(slots);
        duplicateFamily.set(0, new OrdinarySlot("prefix:0", Category.PREFIX, 1, "physical_force", Map.of(), 4, 1));
        duplicateFamily.set(1, new OrdinarySlot(second.slotId(), Category.PREFIX, 1, "special_force", Map.of(), 4, 1));
        assertThrows(IllegalArgumentException.class, () -> rules.validate(withSlots(profile, duplicateFamily)));

        var tooFew = slots.subList(0, slots.size() - 1);
        assertThrows(IllegalArgumentException.class, () -> rules.validate(withSlots(profile, tooFew)));

        var unknownUnique = new UniqueInstance("missing", 1, UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> rules.validate(rebuild(profile, 1,
                profile.awardedMilestones(), profile.highestLevelObserved(), 0, slots, unknownUnique)));
        var staleUnique = new UniqueInstance("ashen_heart", 9, UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> rules.validate(rebuild(profile, 1,
                profile.awardedMilestones(), profile.highestLevelObserved(), 0, slots, staleUnique)));
    }

    private static List<OrdinarySlot> replaceFirst(List<OrdinarySlot> slots, OrdinarySlot replacement) {
        var copy = new ArrayList<>(slots);
        copy.set(0, replacement);
        return copy;
    }

    @Test void profileStructureRejectsBrokenAccounting() {
        var profile = sample(Rarity.UNCOMMON);
        var slots = profile.ordinarySlots();
        var awarded = profile.awardedMilestones();
        assertEquals(4, awarded.size() - profile.spentUpgradeCredits());

        assertThrows(IllegalArgumentException.class, () -> rebuild(profile, 1, awarded, 40, 1, slots, null),
                "Spent credits must equal invested ranks");
        assertThrows(IllegalArgumentException.class, () -> rebuild(profile, 1, Set.of(10, 20, 30, 40, 50), 40, 0, slots, null),
                "Milestone above highest observed level");
        assertThrows(IllegalArgumentException.class, () -> rebuild(profile, 1, Set.of(15), 40, 0, slots, null));
        assertThrows(IllegalArgumentException.class, () -> rebuild(profile, 1, Set.of(110), 100, 0, slots, null));
        assertThrows(IllegalArgumentException.class, () -> rebuild(profile, 1, awarded, 101, 0, slots, null));
        assertThrows(IllegalArgumentException.class, () -> rebuild(profile, 1, awarded, 0, 0, slots, null));

        var upgradedWithoutCredit = new ArrayList<>(slots);
        var s = upgradedWithoutCredit.getFirst();
        upgradedWithoutCredit.set(0, new OrdinarySlot(s.slotId(), s.category(), 5, s.affixId(), s.parameters(), s.rolledValue(), 1));
        assertThrows(IllegalArgumentException.class, () -> rebuild(profile, 1, Set.of(10), 40, 4, upgradedWithoutCredit, null),
                "Four credits cannot be spent from a single award");

        var gap = new ArrayList<>(slots);
        var suffix = gap.get(1);
        gap.set(1, new OrdinarySlot("suffix:1", Category.SUFFIX, 1, suffix.affixId(), suffix.parameters(), suffix.rolledValue(), 1));
        assertThrows(IllegalArgumentException.class, () -> withSlots(profile, gap), "Slot IDs must be contiguous");

        var duplicate = new ArrayList<>(slots);
        duplicate.add(duplicate.getFirst());
        assertThrows(IllegalArgumentException.class, () -> withSlots(profile, duplicate));

        assertThrows(IllegalArgumentException.class, () -> new OrdinarySlot("prefix:0", Category.SUFFIX, 1, "x", Map.of(), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new OrdinarySlot("unique", Category.PREFIX, 1, "x", Map.of(), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new OrdinarySlot("prefix:0", Category.PREFIX, 6, "x", Map.of(), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new OrdinarySlot("prefix:0", Category.PREFIX, 0, "x", Map.of(), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ProfileV1(2, profile.profileId(), profile.pokemonId(),
                profile.authorityId(), 1, Rarity.COMMON, Rarity.COMMON, Origin.of("admin"), 1, 0, 1, Set.of(), 0, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new ProfileV1(1, profile.profileId(), profile.pokemonId(),
                profile.authorityId(), 1, Rarity.COMMON, Rarity.RARE, Origin.of("admin"), 1, 0, 1, Set.of(), 0, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> Origin.of("mystery"));
    }

    @Test void profileCannotBeMutatedThroughCallerCollections() {
        var profile = sample(Rarity.COMMON);
        var slots = new ArrayList<>(profile.ordinarySlots());
        var awarded = new TreeSet<>(profile.awardedMilestones());
        var copy = rebuild(profile, 1, awarded, 40, 0, slots, null);
        slots.clear();
        awarded.clear();
        assertEquals(1, copy.ordinarySlots().size());
        assertEquals(4, copy.pendingCredits());
        assertThrows(UnsupportedOperationException.class, () -> copy.ordinarySlots().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.awardedMilestones().clear());
    }
}
