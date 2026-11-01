package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.cobbleascend.domain.Rarity;
import com.google.gson.JsonParser;
import java.util.*;
import org.junit.jupiter.api.Test;

class EnemyGeneratorTest {
    private final RankedRules rules = RankedRules.defaults();
    private final EnemyGenerator generator = new EnemyGenerator(rules);
    private final EnemyTiers tiers = EnemyTiers.defaults();
    private final List<String> types = List.of("water", "dark");

    private static EnemyTiers parse(String json) { return EnemyTiers.parse(JsonParser.parseString(json).getAsJsonObject()); }

    @Test void defaultTiersLoadAndAreValid() {
        assertEquals(11, tiers.all().size(), "four legacy tiers and seven Threat bands");
        assertEquals(8, tiers.tier("trial_rank_3").rankCredits());
        assertEquals(List.of(Rarity.LEGENDARY, Rarity.MYTHICAL),
                tiers.tier("boss").rarities().stream().map(EnemyTier.RarityWeight::rarity).toList());
        assertThrows(IllegalArgumentException.class, () -> tiers.tier("missing"));
    }

    @Test void malformedTierConfigurationIsRejected() {
        String base = "{\"schemaVersion\":1,\"tiers\":[%s]}";
        assertThrows(IllegalArgumentException.class, () -> parse(base.formatted("")), "No tiers");
        assertThrows(IllegalArgumentException.class, () -> parse("{\"schemaVersion\":2,\"tiers\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> parse(base.formatted(
                "{\"id\":\"a\",\"rankCredits\":11,\"rarities\":{\"common\":1}}")), "More credits than a player can earn");
        assertThrows(IllegalArgumentException.class, () -> parse(base.formatted(
                "{\"id\":\"a\",\"rankCredits\":1,\"rarities\":{}}")));
        assertThrows(IllegalArgumentException.class, () -> parse(base.formatted(
                "{\"id\":\"a\",\"rankCredits\":1,\"rarities\":{\"common\":0}}")));
        assertThrows(IllegalArgumentException.class, () -> parse(base.formatted(
                "{\"id\":\"a\",\"rankCredits\":1,\"rarities\":{\"mythic\":5}}")), "Unknown rarity ID");
        assertThrows(IllegalArgumentException.class, () -> parse(base.formatted(
                "{\"id\":\"A b\",\"rankCredits\":1,\"rarities\":{\"common\":5}}")));
        assertThrows(IllegalArgumentException.class, () -> parse(base.formatted(
                "{\"id\":\"a\",\"rankCredits\":1,\"rarities\":{\"common\":5}},{\"id\":\"a\",\"rankCredits\":1,\"rarities\":{\"rare\":5}}")));
        assertThrows(IllegalArgumentException.class, () -> parse(base.formatted(
                "{\"id\":\"a\",\"rankCredits\":1,\"rarities\":{\"common\":5},\"extra\":1}")));
    }

    @Test void generationIsDeterministicAndIndependentOfCallOrder() {
        var spec = EnemySpec.regular(tiers.tier("trial_rank_2"));
        var forward = new ArrayList<CombatSnapshot>();
        for (int i = 0; i < 50; i++) forward.add(generator.generate("trial-7f3a", i, spec, types));
        var reverse = new ArrayList<CombatSnapshot>();
        for (int i = 49; i >= 0; i--) reverse.add(generator.generate("trial-7f3a", i, spec, types));
        Collections.reverse(reverse);
        assertEquals(forward, reverse);
        assertEquals(forward.get(10).contentHash(), new EnemyGenerator(RankedRules.defaults())
                .generate("trial-7f3a", 10, spec, types).contentHash(), "A different generator instance agrees");
        assertTrue(forward.stream().map(CombatSnapshot::contentHash).distinct().count() > 40,
                "Different enemy indexes give different enemies");
        int identical = 0;
        for (int i = 0; i < 50; i++)
            if (forward.get(i).slots().equals(generator.generate("trial-9c01", i, spec, types).slots())) identical++;
        assertTrue(identical < 5, "A different encounter ID gives different enemies");
    }

    @Test void everyGeneratedEnemyFollowsThePlayerRules() {
        for (var tier : tiers.all()) {
            var spec = EnemySpec.regular(tier);
            var allowed = new HashSet<Rarity>();
            tier.rarities().forEach(w -> allowed.add(w.rarity()));
            for (int i = 0; i < 1500; i++) {
                var enemy = generator.generate("sweep-" + tier.id(), i, spec, types);
                rules.validate(enemy);
                assertEquals(CombatSnapshot.Source.ENEMY, enemy.source());
                assertTrue(allowed.contains(enemy.rarity()), "Rarity outside the tier's table");
                assertEquals(enemy.rarity().ordinal() + 1, enemy.slots().size());
                int capacity = enemy.slots().size() * (RankedAffix.RANKS - 1);
                int invested = enemy.slots().stream().mapToInt(s -> s.rank() - 1).sum();
                assertEquals(Math.min(tier.rankCredits(), capacity), invested, "Credits are spent exactly, up to capacity");
                if (!tier.unique()) assertNull(enemy.uniqueId(), "Regular enemies never get a Unique unless their tier grants one");
                for (var slot : enemy.slots()) {
                    assertTrue(rules.affix(slot.affixId()).band(slot.rank()).contains(slot.rolledValue()));
                    if (slot.affixId().equals("type_focus") || slot.affixId().equals("type_mastery"))
                        assertTrue(types.contains(slot.type()), "Species-typed affixes follow the enemy's own types");
                }
            }
        }
    }

    @Test void rarityFollowsTheTierWeights() {
        var tier = tiers.tier("trial_rank_1");
        var spec = EnemySpec.regular(tier);
        var counts = new EnumMap<Rarity, Integer>(Rarity.class);
        int samples = 20000;
        for (int i = 0; i < samples; i++) counts.merge(generator.generate("weights", i, spec, types).rarity(), 1, Integer::sum);
        for (var weight : tier.rarities())
            assertEquals(samples * weight.weight() / (double) tier.totalWeight(), counts.get(weight.rarity()), samples * 0.02,
                    weight.rarity().id());
        assertEquals(3, counts.size());
    }

    @Test void harderTiersProduceStrongerEnemiesOnAverage() {
        double previous = -1;
        for (var id : List.of("trial_rank_1", "trial_rank_2", "trial_rank_3", "boss")) {
            var spec = EnemySpec.regular(tiers.tier(id));
            double total = 0;
            int samples = 2000;
            for (int i = 0; i < samples; i++) {
                for (var slot : generator.generate("scaling-" + id, i, spec, types).slots()) total += slot.rolledValue();
            }
            assertTrue(total / samples > previous, id + " should out-scale the previous tier");
            previous = total / samples;
        }
    }

    @Test void unusedCreditsAreDroppedWhenEverySlotIsAtRankFive() {
        var tier = new EnemyTier("all_in", List.of(new EnemyTier.RarityWeight(Rarity.COMMON, 1)), 10);
        var enemy = generator.generate("one-slot", 0, EnemySpec.regular(tier), types);
        assertEquals(1, enemy.slots().size());
        assertEquals(5, enemy.slots().getFirst().rank());
        var none = new EnemyTier("no_ranks", List.of(new EnemyTier.RarityWeight(Rarity.MYTHICAL, 1)), 0);
        assertTrue(generator.generate("flat", 3, EnemySpec.regular(none), types).slots().stream().allMatch(s -> s.rank() == 1));
    }

    @Test void onlyBossesCarryAFixedDeclaredUnique() {
        var boss = EnemySpec.boss(tiers.tier("boss"), "ashen_heart");
        for (int i = 0; i < 100; i++) {
            var enemy = generator.generate("raid-boss", i, boss, types);
            assertEquals("ashen_heart", enemy.uniqueId());
            rules.validate(enemy);
        }
        assertThrows(IllegalArgumentException.class, () -> new EnemySpec(tiers.tier("trial_rank_1"), false, "ashen_heart"));
        assertThrows(IllegalArgumentException.class, () -> generator.generate("raid-boss", 0,
                EnemySpec.boss(tiers.tier("boss"), "not_a_unique"), types));
        assertNull(generator.generate("raid-boss", 0, EnemySpec.boss(tiers.tier("boss"), null), types).uniqueId());
    }

    @Test void invalidRequestsAreRefused() {
        var spec = EnemySpec.regular(tiers.tier("trial_rank_1"));
        assertThrows(IllegalArgumentException.class, () -> generator.generate("", 0, spec, types));
        assertThrows(IllegalArgumentException.class, () -> generator.generate("x".repeat(129), 0, spec, types));
        assertThrows(IllegalArgumentException.class, () -> generator.generate("ok", -1, spec, types));
        assertThrows(IllegalArgumentException.class, () -> generator.generate("ok", 0, spec, List.of("shadow")),
                "A species type outside the standard list is not silently accepted");
        assertThrows(IllegalArgumentException.class, () -> generator.generate("ok", 0, spec, List.of()));
    }

    @Test void playerAndEnemySnapshotsShareOneContractAndStayFrozen() {
        var progression = new RankedProgression(rules);
        var profile = progression.create(UUID.randomUUID(), UUID.randomUUID(), Rarity.EPIC, Origin.of("wild_capture"), 60, types, new Random(2));
        var player = CombatSnapshot.ofProfile(profile);
        rules.validate(player);
        assertEquals(CombatSnapshot.Source.PLAYER, player.source());
        assertEquals(profile.ordinarySlots(), player.slots());
        assertEquals(player.contentHash(), CombatSnapshot.ofProfile(profile).contentHash());

        var withUnique = progression.installUnique(profile, "ashen_heart", UUID.randomUUID()).profile();
        assertEquals("ashen_heart", CombatSnapshot.ofProfile(withUnique).uniqueId());
        assertNotEquals(player.contentHash(), CombatSnapshot.ofProfile(withUnique).contentHash());
        var upgraded = progression.upgrade(profile, "prefix:0", new Random(3)).profile();
        assertNotEquals(player.contentHash(), CombatSnapshot.ofProfile(upgraded).contentHash());

        assertThrows(UnsupportedOperationException.class, () -> player.slots().clear());
        var mutable = new ArrayList<>(player.slots());
        var copy = new CombatSnapshot(CombatSnapshot.Source.PLAYER, "x", Rarity.EPIC, 1, mutable, null);
        mutable.clear();
        assertEquals(player.slots().size(), copy.slots().size());
        assertThrows(IllegalArgumentException.class, () -> new CombatSnapshot(CombatSnapshot.Source.ENEMY, " ", Rarity.COMMON, 1, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new CombatSnapshot(CombatSnapshot.Source.ENEMY, "ok", Rarity.COMMON, 1, List.of(), "Bad Id"));
    }

    @Test void snapshotValidationRejectsTamperingAndTooManyRanks() {
        var enemy = generator.generate("tamper", 1, EnemySpec.regular(tiers.tier("trial_rank_3")), types);
        var slot = enemy.slots().getFirst();
        var outOfBand = new ArrayList<>(enemy.slots());
        outOfBand.set(0, new OrdinarySlot(slot.slotId(), slot.category(), slot.rank(), slot.affixId(), slot.parameters(), 100, slot.definitionVersion()));
        assertThrows(IllegalArgumentException.class, () -> rules.validate(new CombatSnapshot(
                CombatSnapshot.Source.ENEMY, "tamper#1", enemy.rarity(), 1, outOfBand, null)));
        assertThrows(IllegalArgumentException.class, () -> rules.validate(new CombatSnapshot(
                CombatSnapshot.Source.ENEMY, "tamper#1", enemy.rarity(), 2, enemy.slots(), null)), "Wrong content version");
        assertThrows(IllegalArgumentException.class, () -> rules.validate(new CombatSnapshot(
                CombatSnapshot.Source.ENEMY, "tamper#1", Rarity.COMMON, 1, enemy.slots(), null)), "Slots do not match rarity");
        assertThrows(IllegalArgumentException.class, () -> rules.validate(new CombatSnapshot(
                CombatSnapshot.Source.ENEMY, "tamper#1", enemy.rarity(), 1, enemy.slots(), "unknown_power")));
    }

    // ---- Threat bands (balance phase 1) ----

    @Test void threatBandsClimbInRarityAndCredits() {
        int lastCredits = -1;
        double lastRarity = -1;
        for (int band = 1; band <= 7; band++) {
            var tier = tiers.threat(band);
            assertEquals("threat_" + band, tier.id());
            assertTrue(tier.rankCredits() >= lastCredits, "credits never fall from one band to the next");
            double meanRarity = tier.rarities().stream().mapToDouble(w -> w.rarity().ordinal() * w.weight()).sum() / tier.totalWeight();
            assertTrue(meanRarity >= lastRarity, "rarity never falls from one band to the next");
            lastCredits = tier.rankCredits();
            lastRarity = meanRarity;
        }
        assertEquals("threat_1", tiers.threat(0).id(), "bands clamp low");
        assertEquals("threat_7", tiers.threat(12).id(), "bands clamp high");
    }

    @Test void legacyIdsMapToThreatOnlyWhenSwitchedOn() {
        assertEquals("boss", tiers.resolve("boss", false).id());
        assertEquals("trial_rank_2", tiers.resolve("trial_rank_2", false).id());
        assertEquals("threat_6", tiers.resolve("boss", true).id());
        assertEquals("threat_2", tiers.resolve("trial_rank_2", true).id());
        assertEquals("threat_4", tiers.resolve("threat_4", true).id(), "a Threat ID is itself");
        assertThrows(IllegalArgumentException.class, () -> parse(
                "{\"schemaVersion\":1,\"tiers\":[{\"id\":\"a\",\"rankCredits\":1,\"rarities\":{\"common\":1}}],\"threatAliases\":{\"a\":\"missing\"}}"),
                "an alias to an unknown tier is rejected");
    }

    @Test void bossesSpendTheirBonusButNeverMoreThanTen() {
        var band3 = tiers.threat(3);
        var band6 = tiers.threat(6);
        assertEquals(7, band3.creditsFor(true));
        assertEquals(5, band3.creditsFor(false));
        assertEquals(10, band6.creditsFor(true), "already at a player's ten");
        for (int i = 0; i < 300; i++) {
            var boss = generator.generate("boss-credits", i, EnemySpec.boss(band3, null), types);
            int capacity = boss.slots().size() * (RankedAffix.RANKS - 1);
            assertEquals(Math.min(7, capacity), boss.slots().stream().mapToInt(s -> s.rank() - 1).sum());
            rules.validate(boss);
        }
    }

    @Test void uniquesComeFromTheTierAndSkipExcludedOnes() {
        var excluding = new EnemyGenerator(rules, tiers.uniqueExclusions());
        var band4 = tiers.threat(4);
        var band5 = tiers.threat(5);
        var band7 = tiers.threat(7);
        var seen = new HashSet<String>();
        for (int i = 0; i < 400; i++) {
            assertNull(excluding.generate("u", i, EnemySpec.boss(band4, null), types).uniqueId(), "below Threat V a boss has no Unique");
            assertNull(excluding.generate("u", i, EnemySpec.regular(band5), types).uniqueId(), "a regular Threat V enemy has no Unique");
            var boss = excluding.generate("u", i, EnemySpec.boss(band5, null), types);
            assertNotNull(boss.uniqueId(), "a Threat V boss has a Unique");
            assertFalse(tiers.uniqueExclusions().contains(boss.uniqueId()), "777 is never drawn for an enemy");
            seen.add(boss.uniqueId());
            assertNotNull(excluding.generate("u", i, EnemySpec.regular(band7), types).uniqueId(), "every Threat VII enemy has a Unique");
        }
        assertEquals(rules.uniques().size() - tiers.uniqueExclusions().size(), seen.size(), "every other Unique turns up");
        assertEquals("rupture", excluding.generate("u", 1, EnemySpec.boss(band5, "rupture"), types).uniqueId(),
                "a Unique the encounter names wins over the tier's draw");
    }

    @Test void threatSevenEnemiesArePerfected() {
        var band7 = tiers.threat(7);
        for (int i = 0; i < 200; i++) {
            var enemy = generator.generate("perfect", i, EnemySpec.regular(band7), types);
            rules.validate(enemy);
            for (var slot : enemy.slots()) {
                int top = rules.affix(slot.affixId()).band(slot.rank()).max();
                assertEquals(top, slot.rolledValue());
                assertEquals(slot.rank() == RankedAffix.RANKS ? top : 0, slot.floor(), "only a rank V slot carries a floor");
            }
        }
    }

    @Test void anOverrideFileReplacesTheShippedTiersOrFallsBackWhole() throws Exception {
        var dir = java.nio.file.Files.createTempDirectory("tiers");
        var file = dir.resolve("ascensionlib-enemy-tiers.json");
        var problems = new ArrayList<String>();
        assertEquals(11, EnemyTiers.loadOrDefaults(file, problems).all().size(), "no file: the shipped tiers");
        java.nio.file.Files.writeString(file, "{\"schemaVersion\":1,\"tiers\":[{\"id\":\"threat_1\",\"rankCredits\":2,\"rarities\":{\"common\":1}}]}");
        assertEquals(2, EnemyTiers.loadOrDefaults(file, problems).tier("threat_1").rankCredits(), "a valid file wins");
        assertTrue(problems.isEmpty());
        java.nio.file.Files.writeString(file, "{\"schemaVersion\":1,\"tiers\":[{\"id\":\"threat_1\",\"rankCredits\":99,\"rarities\":{\"common\":1}}]}");
        assertEquals(11, EnemyTiers.loadOrDefaults(file, problems).all().size(), "a bad file falls back to the shipped tiers");
        assertEquals(1, problems.size());
    }
}
