package com.cobbleascend.store;

import static org.junit.jupiter.api.Assertions.*;

import com.cobbleascend.domain.Rarity;
import com.cobbleascend.domain.v1.*;
import com.cobbleascend.domain.v1.CraftException.Reason;
import com.cobbleascend.store.StoreException.Code;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgressionStoreTest {
    private static final UUID AUTH = UUID.fromString("9d6bb263-d230-42d7-a9d0-ea4710df4bb5");
    private static final String LEGACY = """
            {"schemaVersion":0,"profileId":"e2b02a9c-b02c-4414-8540-f25f60b48eef",
             "pokemonId":"d8927a6a-e60d-44e8-a052-9a4d8c282141","revision":3,"rarity":"RARE",
             "initialRarity":"COMMON","attunement":4,"origin":"wild_capture",
             "affixes":[{"id":"type_focus","type":"fire","value":7},
                        {"id":"physical_force","value":4},
                        {"id":"opening_guard","value":8}]}""";
    private static final UUID LEGACY_POKEMON = UUID.fromString("d8927a6a-e60d-44e8-a052-9a4d8c282141");

    static final class SimulatedCrash extends RuntimeException {
        SimulatedCrash(String message) { super(message); }
    }

    /** Throws once at the armed point, like a process dying there. */
    static final class CrashAt implements Faults {
        volatile Faults.Point armed;
        @Override public void at(Point point) {
            if (point == armed) { armed = null; throw new SimulatedCrash(point.name()); }
        }
    }

    @TempDir Path dir;
    private final RankedRules rules = RankedRules.defaults();
    private final RankedProgression progression = new RankedProgression(rules);
    private final List<ProgressionStore> opened = new ArrayList<>();
    private final List<String> types = List.of("fire", "flying");
    private final UUID player = UUID.randomUUID();

    @AfterEach void closeStores() {
        for (var store : opened) try { store.close(); } catch (RuntimeException ignored) { }
    }

    private Path file() { return dir.resolve("world").resolve("progression.db"); }

    private ProgressionStore open() { return open(rules, ProgressionStore.Options.defaults().withRandom(new Random(1))); }

    private ProgressionStore open(RankedRules forRules, ProgressionStore.Options options) {
        var store = ProgressionStore.open(file(), forRules, AUTH, options);
        opened.add(store);
        return store;
    }

    private ProfileV1 generate(UUID pokemon, Rarity rarity, int level) {
        return progression.create(pokemon, AUTH, rarity, Origin.of("wild_capture"), level, types, new Random(5));
    }

    private ProfileV1 acquire(ProgressionStore store, UUID pokemon, Rarity rarity, int level) {
        return store.acquire(UUID.randomUUID(), pokemon, () -> generate(pokemon, rarity, level)).profile();
    }

    private static Reason reasonOf(Runnable action) { return assertThrows(CraftException.class, action::run).reason(); }
    private static Code codeOf(Runnable action) { return assertThrows(StoreException.class, action::run).code(); }

    private RankedRules rulesWithSecondUnique() {
        var bands = new LinkedHashMap<String, List<RankBand>>();
        rules.affixes().forEach(a -> bands.put(a.id(), a.bands()));
        return new RankedRules(rules.base(), rules.catalogVersion(), bands,
                List.of(new UniqueDefinition("ashen_heart", "Ashen Heart", 1),
                        new UniqueDefinition("second_wind", "Second Wind", 1)));
    }

    // --- open / identity ------------------------------------------------------------------------------

    @Test void reopeningKeepsProfilesAndWalletsAcrossARestart() {
        var pokemon = UUID.randomUUID();
        UUID op = UUID.randomUUID();
        ProfileV1 saved;
        try (var store = ProgressionStore.open(file(), rules, AUTH)) {
            saved = store.acquire(UUID.randomUUID(), pokemon, () -> generate(pokemon, Rarity.EPIC, 30)).profile();
            store.grant(op, player, Map.of(MaterialId.RESONANCE_DUST, 40L), "test");
        }
        try (var store = ProgressionStore.open(file(), rules, AUTH)) {
            assertEquals(saved, store.profile(pokemon).orElseThrow());
            assertEquals(40, store.wallet(player).balance(MaterialId.RESONANCE_DUST));
            assertTrue(store.hasOperation(op));
            assertEquals(Set.of(10, 20, 30), store.awardedMilestones(saved.profileId()));
        }
    }

    @Test void theAuthorityCanBeReadWithoutOpeningTheStoreForWriting() {
        assertEquals(Optional.empty(), ProgressionStore.peekAuthority(file()));
        open().close();
        assertEquals(Optional.of(AUTH), ProgressionStore.peekAuthority(file()));
        assertFalse(ProgressionStore.driverVersion().isBlank());
    }

    @Test void theWildSecretIsCreatedOncePersistsAndIsPerWorld() throws Exception {
        byte[] first;
        try (var store = ProgressionStore.open(file(), rules, AUTH)) { first = store.wildSecret(); }
        assertEquals(32, first.length);
        try (var store = ProgressionStore.open(file(), rules, AUTH)) { assertArrayEquals(first, store.wildSecret()); }
        var other = dir.resolve("other").resolve("progression.db");
        try (var store = ProgressionStore.open(other, rules, AUTH)) { assertFalse(Arrays.equals(first, store.wildSecret())); }
        // A store created before the secret existed gets one on its next open, and keeps it afterwards.
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file().toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM meta WHERE key = 'wild_secret'");
        }
        byte[] regenerated;
        try (var store = ProgressionStore.open(file(), rules, AUTH)) { regenerated = store.wildSecret(); }
        assertEquals(32, regenerated.length);
        try (var store = ProgressionStore.open(file(), rules, AUTH)) { assertArrayEquals(regenerated, store.wildSecret()); }
    }

    @Test void wildCapturesThroughTheStoreMatchTheScouterPreview() {
        var store = open();
        var progression = new RankedProgression(rules);
        var random = new Random(4);
        for (int n = 0; n < 50; n++) {
            var pokemon = new UUID(random.nextLong(), random.nextLong());
            var preview = progression.rateWild(store.wildSecret(), pokemon, types, 33);
            var outcome = store.acquire(UUID.randomUUID(), pokemon, () -> progression.createWild(
                    store.wildSecret(), pokemon, AUTH, Origin.of("wild_capture"), 33, types));
            assertEquals(preview.rarity(), outcome.profile().rarity());
            assertEquals(preview.slots(), outcome.profile().ordinarySlots());
            assertEquals(preview.upgradesOnCapture(), outcome.profile().pendingCredits());
        }
    }

    @Test void aMissingStoreIsNeverSilentlyRecreatedWhenOneIsRequired() {
        var options = ProgressionStore.Options.defaults().withMode(ProgressionStore.OpenMode.REQUIRE_EXISTING);
        assertEquals(Code.STORE_MISSING, codeOf(() -> ProgressionStore.open(file(), rules, AUTH, options)));
        assertFalse(Files.exists(file()));
    }

    @Test void aStoreBelongingToAnotherAuthorityIsRefused() {
        open();
        assertEquals(Code.AUTHORITY_MISMATCH, codeOf(() -> ProgressionStore.open(file(), rules, UUID.randomUUID())));
    }

    @Test void aDifferentContentVersionRequiresAnExplicitMigration() {
        open().close();
        var bands = new LinkedHashMap<String, List<RankBand>>();
        rules.affixes().forEach(a -> bands.put(a.id(), a.bands()));
        var v2 = new RankedRules(rules.base(), 2, bands, List.of(new UniqueDefinition("ashen_heart", "Ashen Heart", 2)));
        assertEquals(Code.CATALOG_MISMATCH, codeOf(() -> ProgressionStore.open(file(), v2, AUTH)));
    }

    @Test void anUnknownFutureSchemaBlocksOpening() throws Exception {
        open().close();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file().toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE meta SET value = '6' WHERE key = 'schema_version'");
        }
        assertEquals(Code.STORE_SCHEMA_UNKNOWN, codeOf(() -> ProgressionStore.open(file(), rules, AUTH)));
    }

    @Test void aCorruptFileIsRefusedAndPreserved() throws Exception {
        Files.createDirectories(file().getParent());
        byte[] garbage = "this is not a sqlite database, just bytes".repeat(40).getBytes();
        Files.write(file(), garbage);
        assertThrows(StoreException.class, () -> ProgressionStore.open(file(), rules, AUTH));
        assertArrayEquals(garbage, Files.readAllBytes(file()), "A corrupt store is preserved for the operator, never reset");
    }

    // --- acquisition ----------------------------------------------------------------------------------

    @Test void acquisitionCommitsOnceAndRepeatedCallbacksResolveTheSameProfile() {
        var store = open();
        var pokemon = UUID.randomUUID();
        var op = UUID.randomUUID();
        var calls = new AtomicInteger();
        var first = store.acquire(op, pokemon, () -> { calls.incrementAndGet(); return generate(pokemon, Rarity.RARE, 37); });
        var second = store.acquire(op, pokemon, () -> { calls.incrementAndGet(); return generate(pokemon, Rarity.MYTHICAL, 37); });
        assertEquals(1, calls.get(), "The generator is not consulted again");
        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals(first.profile(), second.profile());
        assertEquals(Rarity.RARE, store.profile(pokemon).orElseThrow().rarity());
        assertEquals(Set.of(10, 20, 30), store.awardedMilestones(first.profile().profileId()));
    }

    @Test void aPokemonCannotGetASecondCanonicalProfileOrReuseAnOperationForAnother() {
        var store = open();
        var pokemon = UUID.randomUUID();
        var op = UUID.randomUUID();
        store.acquire(op, pokemon, () -> generate(pokemon, Rarity.COMMON, 1));
        var other = UUID.randomUUID();
        assertEquals(Code.DUPLICATE_POKEMON, codeOf(() -> store.acquire(UUID.randomUUID(), pokemon, () -> generate(pokemon, Rarity.MYTHICAL, 1))));
        assertEquals(Code.OPERATION_REUSED, codeOf(() -> store.acquire(op, other, () -> generate(other, Rarity.COMMON, 1))));
        assertTrue(store.profile(other).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.acquire(UUID.randomUUID(), other, () -> generate(UUID.randomUUID(), Rarity.COMMON, 1)));
        assertTrue(store.profile(other).isEmpty(), "A rejected acquisition creates no profile");
    }

    // --- wallet ---------------------------------------------------------------------------------------

    @Test void grantsAreExactlyOnceAndPerPlayer() {
        var store = open();
        var op = UUID.randomUUID();
        var amounts = Map.of(MaterialId.RESONANCE_DUST, 10L, MaterialId.FACET, 2L);
        var first = store.grant(op, player, amounts, "trial reward");
        var replay = store.grant(op, player, amounts, "trial reward");
        assertTrue(replay.replayed());
        assertEquals(first.wallet(), replay.wallet());
        assertEquals(10, store.wallet(player).balance(MaterialId.RESONANCE_DUST));
        assertEquals(1, store.wallet(player).revision());
        assertEquals(0, store.wallet(UUID.randomUUID()).revision());
        assertEquals(Code.OPERATION_REUSED, codeOf(() -> store.grant(op, player, Map.of(MaterialId.RESONANCE_DUST, 999L), "trial reward")));
        assertEquals(10, store.wallet(player).balance(MaterialId.RESONANCE_DUST));
        assertEquals(Reason.INVALID_AMOUNT, reasonOf(() -> store.grant(UUID.randomUUID(), player, Map.of(), "nothing")));
    }

    @Test void catalystAssemblyConsumesFragmentsOncePerOperation() {
        var store = open();
        store.grant(UUID.randomUUID(), player, Map.of(MaterialId.UNIQUE_FRAGMENT, 250L), "test");
        long revision = store.wallet(player).revision();
        var op = UUID.randomUUID();
        var assembled = store.assembleCatalyst(op, player, revision);
        assertEquals(150, assembled.wallet().balance(MaterialId.UNIQUE_FRAGMENT));
        assertEquals(1, assembled.wallet().balance(MaterialId.UNIQUE_CATALYST));
        assertEquals(assembled.wallet(), store.assembleCatalyst(op, player, revision).wallet());
        assertEquals(1, store.wallet(player).balance(MaterialId.UNIQUE_CATALYST));
        assertEquals(Code.STALE_WALLET, codeOf(() -> store.assembleCatalyst(UUID.randomUUID(), player, revision)));
        assertEquals(Reason.INSUFFICIENT_FUNDS, reasonOf(() -> store.assembleCatalyst(UUID.randomUUID(), UUID.randomUUID(), 0)));
    }

    // --- crafting -------------------------------------------------------------------------------------

    @Test void upgradeSpendsACreditNotMaterialsAndRecordsTheResult() {
        var store = open();
        var pokemon = UUID.randomUUID();
        var profile = acquire(store, pokemon, Rarity.UNCOMMON, 40);
        var op = UUID.randomUUID();
        var outcome = store.craft(CraftRequest.upgrade(op, player, pokemon, "prefix:0", profile.revision(), 0));
        assertEquals(2, outcome.profile().slot("prefix:0").orElseThrow().rank());
        assertEquals(profile.revision() + 1, outcome.profile().revision());
        assertEquals(1, outcome.creditsSpent());
        assertEquals(Map.of(), outcome.cost());
        assertEquals(outcome.profile(), store.profile(pokemon).orElseThrow());
        assertEquals(0, store.wallet(player).revision(), "A free upgrade does not touch the wallet");
        var replay = store.craft(CraftRequest.upgrade(op, player, pokemon, "prefix:0", profile.revision(), 0));
        assertTrue(replay.replayed());
        assertEquals(outcome.profile(), replay.profile());
        assertEquals(3, store.profile(pokemon).orElseThrow().pendingCredits(), "Replay spends no second credit");
    }

    @Test void replayReturnsTheOriginalSnapshotEvenAfterLaterChanges() {
        var store = open();
        var pokemon = UUID.randomUUID();
        var profile = acquire(store, pokemon, Rarity.UNCOMMON, 40);
        var firstOp = UUID.randomUUID();
        var first = store.craft(CraftRequest.upgrade(firstOp, player, pokemon, "prefix:0", profile.revision(), 0));
        store.craft(CraftRequest.upgrade(UUID.randomUUID(), player, pokemon, "suffix:0", first.profile().revision(), 0));
        var replay = store.craft(CraftRequest.upgrade(firstOp, player, pokemon, "prefix:0", profile.revision(), 0));
        assertEquals(first.profile(), replay.profile());
        assertEquals(3, store.profile(pokemon).orElseThrow().revision());
    }

    @Test void staleRevisionsAndReusedOperationIdsAreRejectedWithoutSideEffects() {
        var store = open();
        var pokemon = UUID.randomUUID();
        var profile = acquire(store, pokemon, Rarity.UNCOMMON, 40);
        var op = UUID.randomUUID();
        store.craft(CraftRequest.upgrade(op, player, pokemon, "prefix:0", profile.revision(), 0));
        assertEquals(Code.STALE_PROFILE, codeOf(() -> store.craft(
                CraftRequest.upgrade(UUID.randomUUID(), player, pokemon, "suffix:0", profile.revision(), 0))));
        assertEquals(Code.OPERATION_REUSED, codeOf(() -> store.craft(
                CraftRequest.upgrade(op, player, pokemon, "suffix:0", profile.revision(), 0))));
        assertEquals(Code.UNKNOWN_PROFILE, codeOf(() -> store.craft(
                CraftRequest.upgrade(UUID.randomUUID(), player, UUID.randomUUID(), "prefix:0", 1, 0))));
        assertEquals(2, store.profile(pokemon).orElseThrow().revision());
        assertEquals(1, store.profile(pokemon).orElseThrow().slot("suffix:0").orElseThrow().rank());
    }

    @Test void reforgeDebitsMaterialsAtomicallyAndChecksTheWalletRevision() {
        var store = open();
        var pokemon = UUID.randomUUID();
        var profile = acquire(store, pokemon, Rarity.RARE, 40);
        store.grant(UUID.randomUUID(), player, Map.of(MaterialId.RESONANCE_DUST, 100L, MaterialId.FACET, 5L), "test");
        var wallet = store.wallet(player);
        var outcome = store.craft(CraftRequest.reforge(UUID.randomUUID(), player, pokemon, "prefix:1", types,
                profile.revision(), wallet.revision()));
        assertEquals(Map.of(MaterialId.RESONANCE_DUST, 24L, MaterialId.FACET, 1L), outcome.cost());
        assertEquals(76, outcome.wallet().balance(MaterialId.RESONANCE_DUST));
        assertEquals(4, store.wallet(player).balance(MaterialId.FACET));
        assertEquals(wallet.revision() + 1, store.wallet(player).revision());
        assertEquals(profile.slot("prefix:1").orElseThrow().rank(), outcome.profile().slot("prefix:1").orElseThrow().rank());
        assertEquals(Code.STALE_WALLET, codeOf(() -> store.craft(CraftRequest.refine(UUID.randomUUID(), player, pokemon,
                "prefix:1", outcome.profile().revision(), wallet.revision()))));
    }

    @Test void insufficientFundsChangeNothingAndAllowARetryOfTheSameOperationAfterFunding() {
        var store = open();
        var pokemon = UUID.randomUUID();
        var profile = acquire(store, pokemon, Rarity.COMMON, 1);
        var op = UUID.randomUUID();
        var request = CraftRequest.refine(op, player, pokemon, "prefix:0", profile.revision(), 0);
        assertEquals(Reason.INSUFFICIENT_FUNDS, reasonOf(() -> store.craft(request)));
        assertFalse(store.hasOperation(op), "A refusal commits no operation record");
        assertEquals(profile, store.profile(pokemon).orElseThrow());
        assertEquals(0, store.wallet(player).revision());
        store.grant(UUID.randomUUID(), player, Map.of(MaterialId.RESONANCE_DUST, 12L), "test");
        var funded = CraftRequest.refine(op, player, pokemon, "prefix:0", profile.revision(), 1);
        var outcome = store.craft(funded);
        assertEquals(0, outcome.wallet().balance(MaterialId.RESONANCE_DUST));
    }

    @Test void perfectingDebitsOnlyDustStoresTheFloorReplaysByOperationAndRefusesBelowRankFive() {
        var store = open();
        var pokemon = UUID.randomUUID();
        var profile = acquire(store, pokemon, Rarity.RARE, 100);
        store.grant(UUID.randomUUID(), player, Map.of(MaterialId.RESONANCE_DUST, 500L, MaterialId.FACET, 7L), "test");
        assertEquals(Reason.NOT_MAX_RANK, reasonOf(() -> store.craft(CraftRequest.perfect(UUID.randomUUID(), player, pokemon, "prefix:0",
                profile.revision(), CraftRequest.ANY_REVISION))));
        var current = profile;
        for (int n = 0; n < 4; n++)
            current = store.craft(CraftRequest.upgrade(UUID.randomUUID(), player, pokemon, "prefix:0", current.revision(), CraftRequest.ANY_REVISION)).profile();
        final long staleRevision = current.revision();
        var before = current.slot("prefix:0").orElseThrow();
        var wallet = store.wallet(player);
        var op = UUID.randomUUID();
        var request = CraftRequest.perfect(op, player, pokemon, "prefix:0", current.revision(), wallet.revision());
        var outcome = store.craft(request);
        var progression = new RankedProgression(rules);
        long price = progression.perfectCost(before);
        var after = outcome.profile().slot("prefix:0").orElseThrow();
        assertEquals(progression.effectiveFloor(before) + 1, after.floor());
        assertEquals(500 - price, outcome.wallet().balance(MaterialId.RESONANCE_DUST));
        assertEquals(7, outcome.wallet().balance(MaterialId.FACET), "Perfecting touches nothing but dust");
        var replay = store.craft(request);
        assertTrue(replay.replayed());
        assertEquals(outcome.profile(), replay.profile());
        assertEquals(500 - price, store.wallet(player).balance(MaterialId.RESONANCE_DUST), "A replay does not charge twice");
        assertEquals(after, store.profile(pokemon).orElseThrow().slot("prefix:0").orElseThrow(), "The floor is what the store holds");
        assertEquals(Code.STALE_PROFILE, codeOf(() -> store.craft(CraftRequest.perfect(UUID.randomUUID(), player, pokemon, "prefix:0",
                staleRevision, CraftRequest.ANY_REVISION))));
        var refined = store.craft(CraftRequest.refine(UUID.randomUUID(), player, pokemon, "prefix:0", outcome.profile().revision(), CraftRequest.ANY_REVISION)).profile();
        assertEquals(after.floor(), refined.slot("prefix:0").orElseThrow().floor());
        assertTrue(refined.slot("prefix:0").orElseThrow().rolledValue() >= after.floor());
    }

    @Test void overreachSpendsDustAndFacetsOncePersistsTheOutcomeAndSealsTheSlot() {
        var store = open();
        var pokemon = UUID.randomUUID();
        var profile = acquire(store, pokemon, Rarity.RARE, 100);
        store.grant(UUID.randomUUID(), player, Map.of(MaterialId.RESONANCE_DUST, 5000L, MaterialId.FACET, 20L, MaterialId.TOWER_MARK, 10L), "test");
        var progression = new RankedProgression(rules);
        var current = profile;
        for (int n = 0; n < 4; n++)
            current = store.craft(CraftRequest.upgrade(UUID.randomUUID(), player, pokemon, "prefix:0", current.revision(), CraftRequest.ANY_REVISION)).profile();
        // raise the floor to the top so the value is at the band maximum
        while (true) {
            try { current = store.craft(CraftRequest.perfect(UUID.randomUUID(), player, pokemon, "prefix:0", current.revision(), CraftRequest.ANY_REVISION)).profile(); }
            catch (CraftException done) { break; }
        }
        var before = store.wallet(player);
        var op = UUID.randomUUID();
        var request = CraftRequest.overreach(op, player, pokemon, "prefix:0", current.revision(), before.revision());
        var outcome = store.craft(request);
        var slot = outcome.profile().slot("prefix:0").orElseThrow();
        assertTrue(slot.sealed());
        assertEquals(before.balance(MaterialId.RESONANCE_DUST) - 60, outcome.wallet().balance(MaterialId.RESONANCE_DUST));
        assertEquals(before.balance(MaterialId.FACET) - 2, outcome.wallet().balance(MaterialId.FACET));
        var replay = store.craft(request);
        assertTrue(replay.replayed());
        assertEquals(slot, replay.profile().slot("prefix:0").orElseThrow(), "A replay returns the same outcome");
        assertEquals(before.balance(MaterialId.FACET) - 2, store.wallet(player).balance(MaterialId.FACET), "A replay does not charge twice");
        assertEquals(slot, store.profile(pokemon).orElseThrow().slot("prefix:0").orElseThrow(), "The store holds the sealed slot");
        final var sealedRevision = outcome.profile().revision();
        assertEquals(Reason.SLOT_SEALED, reasonOf(() -> store.craft(CraftRequest.refine(UUID.randomUUID(), player, pokemon, "prefix:0", sealedRevision, CraftRequest.ANY_REVISION))));
        assertEquals(Reason.SLOT_SEALED, reasonOf(() -> store.craft(CraftRequest.overreach(UUID.randomUUID(), player, pokemon, "prefix:0", sealedRevision, CraftRequest.ANY_REVISION))));
    }

    @Test void promotionAndUniqueInstallationSpendTheirMaterials() {
        var store = open();
        var pokemon = UUID.randomUUID();
        var base = generate(pokemon, Rarity.COMMON, 1);
        var attuned = new ProfileV1(base.schemaVersion(), base.profileId(), base.pokemonId(), base.authorityId(), base.revision(),
                base.rarity(), base.initialRarity(), base.origin(), base.catalogVersion(), 3, base.highestLevelObserved(),
                base.awardedMilestones(), base.spentUpgradeCredits(), base.ordinarySlots(), null);
        store.acquire(UUID.randomUUID(), pokemon, () -> attuned);
        store.grant(UUID.randomUUID(), player, Map.of(MaterialId.RESONANCE_DUST, 20L, MaterialId.UNIQUE_CATALYST, 1L, MaterialId.TOWER_MARK, 5L), "test");
        var wallet = store.wallet(player);
        var promoted = store.craft(CraftRequest.promote(UUID.randomUUID(), player, pokemon, types, 1, wallet.revision()));
        assertEquals(Rarity.UNCOMMON, promoted.profile().rarity());
        assertEquals(0, promoted.wallet().balance(MaterialId.RESONANCE_DUST));

        var installOp = UUID.randomUUID();
        var installed = store.craft(CraftRequest.installUnique(installOp, player, pokemon, "ashen_heart",
                promoted.profile().revision(), promoted.wallet().revision()));
        assertEquals("ashen_heart", installed.profile().unique().uniqueId());
        assertEquals(installOp, installed.profile().unique().installedOperationId());
        assertEquals(0, store.wallet(player).balance(MaterialId.UNIQUE_CATALYST));
        assertEquals(Reason.SAME_UNIQUE, reasonOf(() -> store.craft(CraftRequest.replaceUnique(UUID.randomUUID(), player, pokemon,
                "ashen_heart", installed.profile().revision(), installed.wallet().revision()))));
        assertEquals(0, store.wallet(player).balance(MaterialId.UNIQUE_CATALYST), "A refused replacement spends nothing");
    }

    @Test void withThreatOnAnEpicPromotionAlsoSpendsApexShards() {
        var d = com.cobbleascend.domain.v1.Tunables.defaults();
        com.cobbleascend.domain.v1.Tunables.use(new com.cobbleascend.domain.v1.Tunables(d.budget(), d.withdrawalFeePercent(), d.respecMarksPrice(),
                d.attunementPerVictory(), d.towerCompletion(), d.miniChest(), d.zones(), new com.cobbleascend.domain.v1.ThreatSettings(true, 3, 4, 25)));
        try {
            var store = open();
            var pokemon = UUID.randomUUID();
            var base = generate(pokemon, Rarity.EPIC, 1);
            var attuned = new ProfileV1(base.schemaVersion(), base.profileId(), base.pokemonId(), base.authorityId(), base.revision(),
                    base.rarity(), base.initialRarity(), base.origin(), base.catalogVersion(), 50, base.highestLevelObserved(),
                    base.awardedMilestones(), base.spentUpgradeCredits(), base.ordinarySlots(), null);
            store.acquire(UUID.randomUUID(), pokemon, () -> attuned);
            store.grant(UUID.randomUUID(), player, Map.of(MaterialId.RESONANCE_DUST, 800L, MaterialId.FACET, 12L, MaterialId.ASCENSION_CORE, 2L,
                    MaterialId.APEX_SHARD, 3L), "test");
            assertEquals(Reason.INSUFFICIENT_FUNDS, reasonOf(() -> store.craft(CraftRequest.promote(UUID.randomUUID(), player, pokemon, types, 1,
                    store.wallet(player).revision()))), "3 Apex Shards are one short");
            store.grant(UUID.randomUUID(), player, Map.of(MaterialId.APEX_SHARD, 1L), "test");
            var promoted = store.craft(CraftRequest.promote(UUID.randomUUID(), player, pokemon, types, 1, store.wallet(player).revision()));
            assertEquals(Rarity.LEGENDARY, promoted.profile().rarity());
            assertEquals(0, promoted.wallet().balance(MaterialId.APEX_SHARD));
            assertEquals(0, promoted.wallet().balance(MaterialId.ASCENSION_CORE));
            assertEquals(0, promoted.wallet().balance(MaterialId.RESONANCE_DUST));
        } finally {
            com.cobbleascend.domain.v1.Tunables.use(com.cobbleascend.domain.v1.Tunables.defaults());
        }
    }

    @Test void attunementAwardsAdvanceTheProfileOnceAndUnlockPromotion() {
        var store = open();
        var pokemon = UUID.randomUUID();
        store.acquire(UUID.randomUUID(), pokemon, () -> generate(pokemon, Rarity.COMMON, 1));
        store.grant(UUID.randomUUID(), player, Map.of(MaterialId.RESONANCE_DUST, 20L), "test");
        assertEquals(Reason.INSUFFICIENT_ATTUNEMENT, reasonOf(() -> store.craft(CraftRequest.promote(UUID.randomUUID(), player, pokemon,
                types, 1, store.wallet(player).revision()))));

        var op = UUID.randomUUID();
        var first = store.craft(CraftRequest.awardAttunement(op, pokemon, 3, 1));
        assertEquals(3, first.profile().attunement());
        assertEquals(2, first.profile().revision());
        var again = store.craft(CraftRequest.awardAttunement(op, pokemon, 3, 1));
        assertTrue(again.replayed());
        assertEquals(3, store.profile(pokemon).orElseThrow().attunement(), "A repeated award does not add again");

        var promoted = store.craft(CraftRequest.promote(UUID.randomUUID(), player, pokemon, types, 2, store.wallet(player).revision()));
        assertEquals(Rarity.UNCOMMON, promoted.profile().rarity());
        assertEquals(3, promoted.profile().attunement(), "Attunement is lifetime and is not consumed");
    }

    @Test void installingAUniqueWithoutACatalystIsRefusedAndNothingChanges() {
        var store = open();
        var pokemon = UUID.randomUUID();
        var profile = acquire(store, pokemon, Rarity.COMMON, 1);
        var op = UUID.randomUUID();
        assertEquals(Reason.INSUFFICIENT_FUNDS, reasonOf(() -> store.craft(
                CraftRequest.installUnique(op, player, pokemon, "ashen_heart", profile.revision(), 0))));
        assertNull(store.profile(pokemon).orElseThrow().unique());
        assertFalse(store.hasOperation(op));
    }

    @Test void replacingAUniqueConsumesOneCatalystAndKeepsTheRest() {
        var multi = rulesWithSecondUnique();
        var store = open(multi, ProgressionStore.Options.defaults().withRandom(new Random(2)));
        var pokemon = UUID.randomUUID();
        var profile = acquire(store, pokemon, Rarity.EPIC, 20);
        store.grant(UUID.randomUUID(), player, Map.of(MaterialId.UNIQUE_CATALYST, 2L, MaterialId.TOWER_MARK, 5L), "test");
        var first = store.craft(CraftRequest.installUnique(UUID.randomUUID(), player, pokemon, "ashen_heart", profile.revision(), 1));
        var second = store.craft(CraftRequest.replaceUnique(UUID.randomUUID(), player, pokemon, "second_wind",
                first.profile().revision(), first.wallet().revision()));
        assertEquals("second_wind", second.profile().unique().uniqueId());
        assertEquals(0, second.wallet().balance(MaterialId.UNIQUE_CATALYST));
        assertEquals(profile.ordinarySlots(), second.profile().ordinarySlots());
    }

    @Test void levelObservationAwardsEachMilestoneExactlyOnce() {
        var store = open();
        var pokemon = UUID.randomUUID();
        var profile = acquire(store, pokemon, Rarity.COMMON, 9);
        var up = store.craft(CraftRequest.observeLevel(UUID.randomUUID(), pokemon, 31, profile.revision()));
        assertEquals(3, up.profile().pendingCredits());
        assertEquals(Set.of(10, 20, 30), store.awardedMilestones(profile.profileId()));
        var lowered = store.craft(CraftRequest.observeLevel(UUID.randomUUID(), pokemon, 5, up.profile().revision()));
        assertEquals(up.profile(), lowered.profile(), "Lowered level changes nothing");
        var restored = store.craft(CraftRequest.observeLevel(UUID.randomUUID(), pokemon, 31, up.profile().revision()));
        assertEquals(up.profile().revision(), restored.profile().revision());
        assertEquals(Set.of(10, 20, 30), store.awardedMilestones(profile.profileId()));
        var op = UUID.randomUUID();
        store.craft(CraftRequest.observeLevel(op, pokemon, 50, up.profile().revision()));
        store.craft(CraftRequest.observeLevel(op, pokemon, 50, up.profile().revision()));
        assertEquals(Set.of(10, 20, 30, 40, 50), store.awardedMilestones(profile.profileId()));
        assertEquals(5, store.profile(pokemon).orElseThrow().pendingCredits());
    }

    // --- recovery -------------------------------------------------------------------------------------

    private record Fixture(CrashAt crash, ProgressionStore store, UUID pokemon, ProfileV1 profile) {}

    private Fixture crashFixture() {
        var crash = new CrashAt();
        var store = open(rules, ProgressionStore.Options.defaults().withRandom(new Random(3)).withFaults(crash));
        var pokemon = UUID.randomUUID();
        var profile = acquire(store, pokemon, Rarity.RARE, 40);
        store.grant(UUID.randomUUID(), player, Map.of(MaterialId.RESONANCE_DUST, 100L, MaterialId.FACET, 3L), "test");
        return new Fixture(crash, store, pokemon, profile);
    }

    @Test void aCrashBeforeAnyWriteOrBeforeCommitChangesNothingAndTheRetryChargesOnce() {
        for (var point : List.of(Faults.Point.AFTER_VALIDATION, Faults.Point.BEFORE_COMMIT)) {
            var fixture = crashFixture();
            var wallet = fixture.store().wallet(player);
            var op = UUID.randomUUID();
            var request = CraftRequest.reforge(op, player, fixture.pokemon(), "prefix:0", types,
                    fixture.profile().revision(), wallet.revision());
            fixture.crash().armed = point;
            assertThrows(SimulatedCrash.class, () -> fixture.store().craft(request));
            fixture.store().close();

            var reopened = open();
            assertEquals(fixture.profile(), reopened.profile(fixture.pokemon()).orElseThrow(), point + " must not change the profile");
            assertEquals(wallet, reopened.wallet(player), point + " must not debit");
            assertFalse(reopened.hasOperation(op));
            assertEquals(Set.of(10, 20, 30, 40), reopened.awardedMilestones(fixture.profile().profileId()));

            var done = reopened.craft(request);
            assertFalse(done.replayed());
            assertEquals(76, reopened.wallet(player).balance(MaterialId.RESONANCE_DUST));
            assertEquals(2, reopened.profile(fixture.pokemon()).orElseThrow().revision());
            reopened.close();
            deleteStoreFiles();
        }
    }

    @Test void aCrashAfterCommitIsReplayedWithoutAnotherDebitOrRoll() {
        var fixture = crashFixture();
        var wallet = fixture.store().wallet(player);
        var op = UUID.randomUUID();
        var request = CraftRequest.reforge(op, player, fixture.pokemon(), "prefix:0", types,
                fixture.profile().revision(), wallet.revision());
        fixture.crash().armed = Faults.Point.AFTER_COMMIT;
        assertThrows(SimulatedCrash.class, () -> fixture.store().craft(request));
        fixture.store().close();

        var reopened = open();
        var committed = reopened.profile(fixture.pokemon()).orElseThrow();
        assertEquals(2, committed.revision());
        assertEquals(76, reopened.wallet(player).balance(MaterialId.RESONANCE_DUST));
        assertTrue(reopened.hasOperation(op));
        var replay = reopened.craft(request);
        assertTrue(replay.replayed());
        assertEquals(committed, replay.profile());
        assertEquals(76, reopened.wallet(player).balance(MaterialId.RESONANCE_DUST), "No second debit");
        assertEquals(committed, reopened.profile(fixture.pokemon()).orElseThrow(), "No second roll");
        assertEquals(List.of(committed), reopened.projectionBacklog().stream().filter(p -> p.revision() == 2).toList(),
                "The committed revision still needs projecting to Cobblemon");
    }

    private void deleteStoreFiles() {
        for (var suffix : List.of("", "-wal", "-shm")) {
            try { Files.deleteIfExists(Path.of(file() + suffix)); } catch (java.io.IOException ignored) { }
        }
    }

    @Test void projectionTrackingSurvivesRestartsAndNeverMovesBackwards() {
        var store = open();
        var pokemon = UUID.randomUUID();
        var profile = acquire(store, pokemon, Rarity.UNCOMMON, 40);
        assertEquals(List.of(profile), store.projectionBacklog());
        store.markProjected(profile.profileId(), profile.revision());
        assertEquals(List.of(), store.projectionBacklog());
        var next = store.craft(CraftRequest.upgrade(UUID.randomUUID(), player, pokemon, "prefix:0", profile.revision(), 0)).profile();
        assertEquals(List.of(next), store.projectionBacklog());
        store.markProjected(profile.profileId(), profile.revision());
        assertEquals(List.of(next), store.projectionBacklog(), "Marking an older revision does not clear the newer one");
        store.markProjected(profile.profileId(), next.revision());
        assertEquals(List.of(), store.projectionBacklog());
        store.close();
        assertEquals(List.of(), open().projectionBacklog());
    }

    @Test void concurrentDuplicateConfirmationsCommitExactlyOnce() throws Exception {
        var store = open();
        var pokemon = UUID.randomUUID();
        var profile = acquire(store, pokemon, Rarity.RARE, 40);
        store.grant(UUID.randomUUID(), player, Map.of(MaterialId.RESONANCE_DUST, 100L, MaterialId.FACET, 3L), "test");
        var op = UUID.randomUUID();
        var request = CraftRequest.reforge(op, player, pokemon, "prefix:0", types, profile.revision(), 1);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var futures = new ArrayList<Future<Outcome>>();
            for (int i = 0; i < 16; i++) futures.add(pool.submit(() -> store.craft(request)));
            int fresh = 0;
            Outcome first = null;
            for (var future : futures) {
                var outcome = future.get(30, TimeUnit.SECONDS);
                if (!outcome.replayed()) fresh++;
                if (first == null) first = outcome;
                assertEquals(first.profile(), outcome.profile());
            }
            assertEquals(1, fresh);
        } finally { pool.shutdownNow(); }
        assertEquals(76, store.wallet(player).balance(MaterialId.RESONANCE_DUST));
        assertEquals(2, store.profile(pokemon).orElseThrow().revision());
    }

    @Test void concurrentDifferentConfirmationsOnTheSameRevisionAllowOnlyOneWinner() throws Exception {
        var store = open();
        var pokemon = UUID.randomUUID();
        var profile = acquire(store, pokemon, Rarity.UNCOMMON, 40);
        var pool = Executors.newFixedThreadPool(8);
        var wins = new AtomicInteger();
        var stale = new AtomicInteger();
        try {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 12; i++) futures.add(pool.submit(() -> {
                try {
                    store.craft(CraftRequest.upgrade(UUID.randomUUID(), player, pokemon, "prefix:0", profile.revision(), 0));
                    wins.incrementAndGet();
                } catch (StoreException exception) {
                    if (exception.code() == Code.STALE_PROFILE) stale.incrementAndGet();
                }
            }));
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(1, wins.get());
        assertEquals(11, stale.get());
        assertEquals(2, store.profile(pokemon).orElseThrow().slot("prefix:0").orElseThrow().rank());
    }

    // --- schema-zero migration ------------------------------------------------------------------------

    @Test void schemaZeroMigrationImportsOnceAndRerunningIsANoOp() {
        var store = open();
        var op = UUID.randomUUID();
        var first = store.migrate(op, LEGACY_POKEMON, LEGACY, 37);
        var profile = first.profile();
        assertEquals(4, profile.revision());
        assertEquals(List.of("prefix:0", "prefix:1", "suffix:0"), profile.ordinarySlots().stream().map(OrdinarySlot::slotId).toList());
        assertEquals(Set.of(10, 20, 30), store.awardedMilestones(profile.profileId()));
        assertEquals(profile, store.profile(LEGACY_POKEMON).orElseThrow());

        var rerun = store.migrate(UUID.randomUUID(), LEGACY_POKEMON, LEGACY, 37);
        assertFalse(rerun.replayed(), "A new operation ID is a new operation, but it changes nothing");
        assertEquals(profile, rerun.profile());
        assertEquals(profile, store.profile(LEGACY_POKEMON).orElseThrow());
        assertEquals(Set.of(10, 20, 30), store.awardedMilestones(profile.profileId()));
        assertTrue(store.migrate(op, LEGACY_POKEMON, LEGACY, 37).replayed());

        assertEquals(Code.DUPLICATE_POKEMON, codeOf(() -> store.migrate(UUID.randomUUID(), LEGACY_POKEMON,
                LEGACY.replace("\"value\":8", "\"value\":9"), 37)));
        assertEquals(profile, store.profile(LEGACY_POKEMON).orElseThrow());
    }

    @Test void invalidOrInterruptedMigrationsLeaveNothingBehindAndCanBeRetried() {
        var crash = new CrashAt();
        var store = open(rules, ProgressionStore.Options.defaults().withFaults(crash));
        assertThrows(IllegalArgumentException.class, () -> store.migrate(UUID.randomUUID(), LEGACY_POKEMON,
                LEGACY.replace("\"value\":8", "\"value\":99"), 37));
        assertTrue(store.profile(LEGACY_POKEMON).isEmpty(), "Invalid source data is never repaired into a fresh roll");

        var op = UUID.randomUUID();
        crash.armed = Faults.Point.BEFORE_COMMIT;
        assertThrows(SimulatedCrash.class, () -> store.migrate(op, LEGACY_POKEMON, LEGACY, 37));
        store.close();
        var reopened = open();
        assertTrue(reopened.profile(LEGACY_POKEMON).isEmpty());
        assertFalse(reopened.hasOperation(op));
        var done = reopened.migrate(op, LEGACY_POKEMON, LEGACY, 37);
        assertEquals(1, reopened.projectionBacklog().size());
        assertEquals(done.profile(), reopened.profile(LEGACY_POKEMON).orElseThrow());
    }

    @Test void migratedProfilesCraftNormally() {
        var store = open();
        var profile = store.migrate(UUID.randomUUID(), LEGACY_POKEMON, LEGACY, 37).profile();
        var upgraded = store.craft(CraftRequest.upgrade(UUID.randomUUID(), player, LEGACY_POKEMON, "prefix:0", profile.revision(), 0));
        assertEquals(2, upgraded.profile().slot("prefix:0").orElseThrow().rank());
        assertEquals("fire", upgraded.profile().slot("prefix:0").orElseThrow().type());
    }

    // --- stored data protection -----------------------------------------------------------------------

    @Test void aTamperedRowIsPreservedAndBlocksUseInsteadOfBeingRepaired() throws Exception {
        var store = open();
        var pokemon = UUID.randomUUID();
        var profile = acquire(store, pokemon, Rarity.COMMON, 1);
        store.close();
        String tampered;
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file().toAbsolutePath());
             var statement = connection.createStatement()) {
            var rows = statement.executeQuery("SELECT body FROM profiles");
            rows.next();
            tampered = rows.getString(1).replace("\"rolledValue\":", "\"rolledValue\":9");
            rows.close();
            try (var update = connection.prepareStatement("UPDATE profiles SET body = ?")) {
                update.setString(1, tampered);
                update.executeUpdate();
            }
        }
        var reopened = open();
        assertEquals(Code.STORED_DATA_INVALID, codeOf(() -> reopened.profile(pokemon)));
        assertEquals(Code.STORED_DATA_INVALID, codeOf(() -> reopened.craft(
                CraftRequest.observeLevel(UUID.randomUUID(), pokemon, 50, profile.revision()))));
        reopened.close();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file().toAbsolutePath());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT body FROM profiles")) {
            rows.next();
            assertEquals(tampered, rows.getString(1), "The invalid row is left exactly as found for operator review");
        }
    }

    @Test void theDatabaseItselfRejectsDuplicateMilestonesAndNegativeBalances() throws Exception {
        var store = open();
        var pokemon = UUID.randomUUID();
        var profile = acquire(store, pokemon, Rarity.COMMON, 10);
        store.grant(UUID.randomUUID(), player, Map.of(MaterialId.FACET, 1L), "test");
        store.close();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file().toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO milestone_awards VALUES('" + profile.profileId() + "', 10, 'x')"));
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                    "UPDATE wallet_balances SET amount = -1 WHERE material_id = 'facet'"));
            assertThrows(java.sql.SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO profiles(profile_id, pokemon_id, revision, body) VALUES('z', '" + pokemon + "', 1, '{}')"));
        }
    }

    // --- fusion ---------------------------------------------------------------------------------------

    /** A ready host or donor: the ranks and attunement the fusion rules look at, set directly (acquire accepts any valid profile). */
    private ProfileV1 fusable(UUID pokemon, Rarity rarity, boolean spent, int attunement, String unique) {
        var base = progression.create(pokemon, AUTH, rarity, Origin.of("wild_capture"), 100, types, new Random(5));
        int[] spread = {5, 4, 3, 2, 1, 1};
        var slots = new ArrayList<OrdinarySlot>();
        int i = 0;
        for (var slot : base.ordinarySlots())
            {
            int rank = spent ? spread[i++] : 1;
            slots.add(new OrdinarySlot(slot.slotId(), slot.category(), rank, slot.affixId(), slot.parameters(),
                    rules.affix(slot.affixId()).band(rank).min(), slot.definitionVersion()));
        }
        int credits = slots.stream().mapToInt(slot -> slot.rank() - 1).sum();
        var awarded = new TreeSet<Integer>();
        for (int level = Milestones.STEP; awarded.size() < Math.max(credits, 10); level += Milestones.STEP) awarded.add(level);
        return new ProfileV1(1, base.profileId(), pokemon, AUTH, 1, rarity, base.initialRarity(), base.origin(), base.catalogVersion(),
                attunement, 100, awarded, credits, slots, new UniqueInstance(unique, 1, UUID.randomUUID()));
    }

    private FuseRequest fuseRequest(UUID op, UUID host, UUID donor, ProgressionStore store) {
        return new FuseRequest(op, player, host, donor, "charizard", "lucario", store.profile(host).orElseThrow().revision(),
                store.profile(donor).orElseThrow().revision(), store.wallet(player).revision());
    }

    private ProgressionStore fusionStore(UUID host, UUID donor) {
        var store = open();
        store.acquire(UUID.randomUUID(), host, () -> fusable(host, Rarity.MYTHICAL, true, 150, "ashen_heart"));
        store.acquire(UUID.randomUUID(), donor, () -> fusable(donor, Rarity.EPIC, false, 0, "rupture"));
        store.grant(UUID.randomUUID(), player, new java.util.EnumMap<>(FusionRules.cost()), "test");
        return store;
    }

    @Test void aFusionDebitsTheWalletRecordsTheFusionAndReplaysExactly() {
        var host = UUID.randomUUID(); var donor = UUID.randomUUID(); var op = UUID.randomUUID();
        var store = fusionStore(host, donor);
        var request = fuseRequest(op, host, donor, store);
        var first = store.fuse(request);
        assertEquals(FusionRules.cost(), first.cost());
        assertEquals(0, first.wallet().balance(MaterialId.RESONANCE_DUST));
        assertEquals(2, first.profile().revision(), "the host revision advanced");
        var fusion = store.fusion(host).orElseThrow();
        assertEquals("ashen_heart", fusion.hostUnique());
        assertEquals("rupture", fusion.donorUnique());
        assertEquals(donor, fusion.donorId());
        assertTrue(store.consumedBy(donor).isPresent());
        assertEquals(Set.of(host, donor), store.fusedPokemon(), "one read names every host and consumed donor");
        var replay = store.fuse(request);
        assertTrue(replay.replayed());
        assertEquals(first.profile(), replay.profile());
        assertEquals(0, store.wallet(player).balance(MaterialId.RESONANCE_DUST), "a replay does not debit twice");
    }

    @Test void aTranscendentCannotFuseAgainAndADonorIsOnlyConsumedOnce() {
        var host = UUID.randomUUID(); var donor = UUID.randomUUID(); var other = UUID.randomUUID();
        var store = fusionStore(host, donor);
        store.acquire(UUID.randomUUID(), other, () -> fusable(other, Rarity.EPIC, false, 0, "stormcaller"));
        store.grant(UUID.randomUUID(), player, new java.util.EnumMap<>(FusionRules.cost()), "again");
        store.fuse(fuseRequest(UUID.randomUUID(), host, donor, store));
        var again = fuseRequest(UUID.randomUUID(), host, other, store);
        assertEquals(Reason.ALREADY_TRANSCENDENT, reasonOf(() -> store.fuse(again)));
        assertTrue(store.fusion(other).isEmpty());
    }

    @Test void aRefusedFusionChangesNothing() {
        var host = UUID.randomUUID(); var donor = UUID.randomUUID();
        var store = fusionStore(host, donor);
        var stale = new FuseRequest(UUID.randomUUID(), player, host, donor, "charizard", "lucario", 9, 1, store.wallet(player).revision());
        assertEquals(Code.STALE_PROFILE, codeOf(() -> store.fuse(stale)));
        store.spend(UUID.randomUUID(), player, Map.of(MaterialId.RESONANCE_DUST, 1L), "make short");
        var short_ = fuseRequest(UUID.randomUUID(), host, donor, store);
        assertEquals(Reason.INSUFFICIENT_FUNDS, reasonOf(() -> store.fuse(short_)));
        assertTrue(store.fusion(host).isEmpty());
        assertEquals(1, store.profile(host).orElseThrow().revision());
        assertEquals(599, store.wallet(player).balance(MaterialId.RESONANCE_DUST));
    }

    @Test void aCrashBeforeCommitLeavesNoFusion() {
        var host = UUID.randomUUID(); var donor = UUID.randomUUID();
        var crash = new CrashAt();
        var store = open(rules, ProgressionStore.Options.defaults().withRandom(new Random(1)).withFaults(crash));
        store.acquire(UUID.randomUUID(), host, () -> fusable(host, Rarity.MYTHICAL, true, 150, "ashen_heart"));
        store.acquire(UUID.randomUUID(), donor, () -> fusable(donor, Rarity.EPIC, false, 0, "rupture"));
        store.grant(UUID.randomUUID(), player, new java.util.EnumMap<>(FusionRules.cost()), "test");
        var request = fuseRequest(UUID.randomUUID(), host, donor, store);
        crash.armed = Faults.Point.BEFORE_COMMIT;
        assertThrows(SimulatedCrash.class, () -> store.fuse(request));
        assertTrue(store.fusion(host).isEmpty());
        assertEquals(600, store.wallet(player).balance(MaterialId.RESONANCE_DUST));
        assertFalse(store.fuse(request).replayed(), "the retried operation commits once");
    }

    @Test void aSchemaOneStoreIsUpgradedInPlace() throws Exception {
        var pokemon = UUID.randomUUID();
        var before = open();
        acquire(before, pokemon, Rarity.RARE, 20);
        before.close();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file().toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE fusions");
            statement.executeUpdate("DROP TABLE vouchers");
            statement.executeUpdate("UPDATE meta SET value = '1' WHERE key = 'schema_version'");
        }
        var upgraded = open();
        assertTrue(upgraded.profile(pokemon).isPresent(), "existing rows survive");
        assertTrue(upgraded.fusion(pokemon).isEmpty());
        upgraded.close();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file().toAbsolutePath());
             var rows = connection.createStatement().executeQuery("SELECT value FROM meta WHERE key = 'schema_version'")) {
            assertTrue(rows.next());
            assertEquals("5", rows.getString(1));
        }
    }

    @Test void aSchemaTwoStoreGainsTheVouchersTableAndKeepsEverything() throws Exception {
        var pokemon = UUID.randomUUID();
        var before = open();
        acquire(before, pokemon, Rarity.RARE, 20);
        before.grant(UUID.randomUUID(), player, Map.of(MaterialId.FACET, 3L), "test");
        before.close();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file().toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE vouchers");
            statement.executeUpdate("UPDATE meta SET value = '2' WHERE key = 'schema_version'");
        }
        var upgraded = open();
        assertTrue(upgraded.profile(pokemon).isPresent());
        assertEquals(3, upgraded.wallet(player).balance(MaterialId.FACET));
        assertTrue(upgraded.undeliveredVouchers(player).isEmpty());
        upgraded.close();
    }

    // --- vouchers: wallet currency as tradable items -------------------------------------------------

    private ProgressionStore fundedStore() {
        var store = open();
        store.grant(UUID.randomUUID(), player, Map.of(MaterialId.RESONANCE_DUST, 500L, MaterialId.FACET, 10L), "test");
        return store;
    }

    @Test void aWithdrawalDebitsTheWalletAndOpensABatchInOneTransactionAndReplaysOnce() {
        var store = fundedStore();
        var op = UUID.randomUUID();
        var wallet = store.wallet(player);
        var minted = store.mintVoucher(op, player, MaterialId.RESONANCE_DUST, 120, wallet.revision());
        assertEquals(500 - 120 - 4, minted.wallet().balance(MaterialId.RESONANCE_DUST));
        var batch = ProgressionStore.voucherBatchId(op);
        var voucher = store.voucher(batch).orElseThrow();
        assertEquals(120, voucher.minted());
        assertEquals(120, voucher.outstanding());
        assertFalse(voucher.delivered());
        assertEquals(MaterialId.RESONANCE_DUST, voucher.material());
        assertEquals(List.of(voucher), store.undeliveredVouchers(player));
        assertTrue(store.mintVoucher(op, player, MaterialId.RESONANCE_DUST, 120, wallet.revision()).replayed());
        assertEquals(376, store.wallet(player).balance(MaterialId.RESONANCE_DUST), "A replay does not charge twice");
        store.markVoucherDelivered(batch);
        store.markVoucherDelivered(batch);
        assertTrue(store.undeliveredVouchers(player).isEmpty());
        assertTrue(store.voucher(batch).orElseThrow().delivered());
    }

    @Test void theFeeComesOutOfTheWalletButNotTheBatchAndIsRoundedUp() {
        assertEquals(0, CurrencyItems.withdrawalFee(0));
        assertEquals(1, CurrencyItems.withdrawalFee(1));
        assertEquals(1, CurrencyItems.withdrawalFee(33));
        assertEquals(2, CurrencyItems.withdrawalFee(34));
        assertEquals(3, CurrencyItems.withdrawalFee(100));
        var store = fundedStore();
        var op = UUID.randomUUID();
        store.mintVoucher(op, player, MaterialId.FACET, 9, CraftRequest.ANY_REVISION);
        assertEquals(0, store.wallet(player).balance(MaterialId.FACET) - (10 - 9 - 1), "9 plus a fee of 1");
        assertEquals(9, store.voucher(ProgressionStore.voucherBatchId(op)).orElseThrow().minted());
        // the balance of 10 cannot cover 10 plus its fee
        assertEquals(Reason.INSUFFICIENT_FUNDS, reasonOf(() -> fundedStore().mintVoucher(UUID.randomUUID(), player, MaterialId.FACET, 10, CraftRequest.ANY_REVISION)));
    }

    // ---- respec items ----

    private UUID investedPokemon(ProgressionStore store, UUID pokemon) {
        acquire(store, pokemon, Rarity.EPIC, 100);
        var profile = store.profile(pokemon).orElseThrow();
        var random = new java.util.Random(11);
        for (int guard = 0; guard < 40 && profile.pendingCredits() > 0; guard++) {
            var slot = profile.ordinarySlots().get(random.nextInt(profile.ordinarySlots().size()));
            if (slot.rank() >= 5) continue;
            profile = store.craft(CraftRequest.upgrade(UUID.randomUUID(), player, pokemon, slot.slotId(), profile.revision(), CraftRequest.ANY_REVISION)).profile();
        }
        return pokemon;
    }

    @Test void anHourglassIsSpentWithTheResetInOneTransactionAndAReplayChargesOnce() {
        var store = open();
        var pokemon = investedPokemon(store, UUID.randomUUID());
        store.grant(UUID.randomUUID(), player, Map.of(MaterialId.RANK_RESPEC, 2L, MaterialId.RESONANCE_DUST, 100L), "test");
        var before = store.profile(pokemon).orElseThrow();
        assertEquals(0, before.pendingCredits());
        var op = UUID.randomUUID();
        var done = store.craft(CraftRequest.respecRanks(op, player, pokemon, before.revision(), CraftRequest.ANY_REVISION));
        assertEquals(before.spentUpgradeCredits(), done.profile().pendingCredits());
        assertEquals(1, store.wallet(player).balance(MaterialId.RANK_RESPEC), "one Hourglass spent");
        assertEquals(100, store.wallet(player).balance(MaterialId.RESONANCE_DUST), "no dust is touched or refunded");
        assertTrue(store.craft(CraftRequest.respecRanks(op, player, pokemon, before.revision(), CraftRequest.ANY_REVISION)).replayed());
        assertEquals(1, store.wallet(player).balance(MaterialId.RANK_RESPEC), "a replay does not charge twice");
    }

    @Test void anHourglassWithoutTheItemOrOnASealedPokemonChangesNothing() {
        var store = open();
        var pokemon = investedPokemon(store, UUID.randomUUID());
        var before = store.profile(pokemon).orElseThrow();
        assertEquals(Reason.INSUFFICIENT_FUNDS, reasonOf(() -> store.craft(CraftRequest.respecRanks(UUID.randomUUID(), player, pokemon, before.revision(), CraftRequest.ANY_REVISION))));
        assertEquals(before, store.profile(pokemon).orElseThrow(), "no item, no change");
        // a nothing-to-undo Pokemon refuses and keeps the item
        var fresh = UUID.randomUUID();
        acquire(store, fresh, Rarity.RARE, 20);
        store.grant(UUID.randomUUID(), player, Map.of(MaterialId.RANK_RESPEC, 1L), "test");
        var profile = store.profile(fresh).orElseThrow();
        assertEquals(Reason.NOTHING_TO_RESPEC, reasonOf(() -> store.craft(CraftRequest.respecRanks(UUID.randomUUID(), player, fresh, profile.revision(), CraftRequest.ANY_REVISION))));
        assertEquals(1, store.wallet(player).balance(MaterialId.RANK_RESPEC), "a refusal keeps the Hourglass");
    }

    @Test void theMarksRouteTradesNinetyMarksForAnHourglassInOneTransaction() {
        var store = open();
        store.grant(UUID.randomUUID(), player, Map.of(MaterialId.TOWER_MARK, 100L), "test");
        var op = UUID.randomUUID();
        var price = (long) com.cobbleascend.domain.v1.Tunables.defaults().respecMarksPrice();
        var done = store.exchange(op, player, Map.of(MaterialId.TOWER_MARK, price), Map.of(MaterialId.RANK_RESPEC, 1L), "test", CraftRequest.ANY_REVISION);
        assertEquals(10, done.wallet().balance(MaterialId.TOWER_MARK));
        assertEquals(1, done.wallet().balance(MaterialId.RANK_RESPEC));
        assertTrue(store.exchange(op, player, Map.of(MaterialId.TOWER_MARK, price), Map.of(MaterialId.RANK_RESPEC, 1L), "test", CraftRequest.ANY_REVISION).replayed());
        assertEquals(10, store.wallet(player).balance(MaterialId.TOWER_MARK), "a replay does not charge twice");
        assertEquals(Reason.INSUFFICIENT_FUNDS, reasonOf(() -> store.exchange(UUID.randomUUID(), player, Map.of(MaterialId.TOWER_MARK, price), Map.of(MaterialId.RANK_RESPEC, 1L), "test", CraftRequest.ANY_REVISION)),
                "ten Marks cannot buy another");
        assertEquals(1, store.wallet(player).balance(MaterialId.RANK_RESPEC));
    }

    @Test void aTowerMarkIsBoundAndCanNeverBeWithdrawn() {
        var store = open();
        store.grant(UUID.randomUUID(), player, Map.of(MaterialId.TOWER_MARK, 5L), "test");
        var op = UUID.randomUUID();
        assertEquals(Reason.INVALID_AMOUNT, reasonOf(() -> store.mintVoucher(op, player, MaterialId.TOWER_MARK, 1, CraftRequest.ANY_REVISION)));
        assertFalse(store.hasOperation(op));
        assertEquals(5, store.wallet(player).balance(MaterialId.TOWER_MARK));
    }

    @Test void aWithdrawalRefusesOverdraftsBadAmountsAndAStaleWalletAndRecordsNothing() {
        var store = fundedStore();
        var op = UUID.randomUUID();
        var revision = store.wallet(player).revision();
        assertEquals(Reason.INSUFFICIENT_FUNDS, reasonOf(() -> store.mintVoucher(op, player, MaterialId.FACET, 11, revision)));
        assertFalse(store.hasOperation(op));
        assertTrue(store.voucher(ProgressionStore.voucherBatchId(op)).isEmpty());
        assertEquals(Reason.INVALID_AMOUNT, reasonOf(() -> store.mintVoucher(UUID.randomUUID(), player, MaterialId.FACET, 0, revision)));
        assertEquals(Reason.INVALID_AMOUNT, reasonOf(() -> store.mintVoucher(UUID.randomUUID(), player, MaterialId.FACET, ProgressionStore.MAX_MINT + 1, revision)));
        assertEquals(Code.STALE_WALLET, codeOf(() -> store.mintVoucher(UUID.randomUUID(), player, MaterialId.FACET, 1, revision + 5)));
        assertEquals(10, store.wallet(player).balance(MaterialId.FACET));
    }

    @Test void aRedemptionCreditsOnlyWhatIsOutstandingSoADuplicatedStackCannotInflateTheCurrency() {
        var store = fundedStore();
        var mintOp = UUID.randomUUID();
        store.mintVoucher(mintOp, player, MaterialId.RESONANCE_DUST, 100, CraftRequest.ANY_REVISION);
        var batch = ProgressionStore.voucherBatchId(mintOp);
        var thief = UUID.randomUUID();
        // an honest holder redeems part of the batch (a bearer instrument: it need not be the minter)
        var first = store.redeemVoucher(UUID.randomUUID(), thief, batch, 60);
        assertEquals(Map.of(MaterialId.RESONANCE_DUST, 60L), first.cost());
        assertEquals(60, store.wallet(thief).balance(MaterialId.RESONANCE_DUST));
        // a duplicated stack of 100 only finds 40 left
        var dupe = store.redeemVoucher(UUID.randomUUID(), player, batch, 100);
        assertEquals(Map.of(MaterialId.RESONANCE_DUST, 40L), dupe.cost());
        assertEquals(0, store.voucher(batch).orElseThrow().outstanding());
        // everything after that is worth nothing, and says so without an error
        var worthless = store.redeemVoucher(UUID.randomUUID(), player, batch, 64);
        assertTrue(worthless.cost().isEmpty());
        long total = store.wallet(player).balance(MaterialId.RESONANCE_DUST) + store.wallet(thief).balance(MaterialId.RESONANCE_DUST);
        assertEquals(500 - CurrencyItems.withdrawalFee(100), total, "The currency in existence is what it was before the withdrawal, less the fee");
    }

    @Test void aRedemptionReplaysByOperationAndAnUnknownBatchIsRefusedWithoutARecord() {
        var store = fundedStore();
        var mintOp = UUID.randomUUID();
        store.mintVoucher(mintOp, player, MaterialId.FACET, 4, CraftRequest.ANY_REVISION);
        var batch = ProgressionStore.voucherBatchId(mintOp);
        var op = UUID.randomUUID();
        var first = store.redeemVoucher(op, player, batch, 4);
        assertEquals(9, first.wallet().balance(MaterialId.FACET));
        var replay = store.redeemVoucher(op, player, batch, 4);
        assertTrue(replay.replayed());
        assertEquals(Map.of(MaterialId.FACET, 4L), replay.cost(), "A replay reports the same credit");
        assertEquals(9, store.wallet(player).balance(MaterialId.FACET), "A replay does not credit twice");
        var forged = UUID.randomUUID();
        var forgedOp = UUID.randomUUID();
        assertEquals(Code.UNKNOWN_VOUCHER, codeOf(() -> store.redeemVoucher(forgedOp, player, forged, 64)));
        assertFalse(store.hasOperation(forgedOp));
    }

    @Test void vouchersSurviveARestart() {
        var mintOp = UUID.randomUUID();
        try (var store = ProgressionStore.open(file(), rules, AUTH)) {
            store.grant(UUID.randomUUID(), player, Map.of(MaterialId.UNIQUE_FRAGMENT, 10L), "test");
            store.mintVoucher(mintOp, player, MaterialId.UNIQUE_FRAGMENT, 9, CraftRequest.ANY_REVISION);
        }
        try (var store = ProgressionStore.open(file(), rules, AUTH)) {
            var voucher = store.voucher(ProgressionStore.voucherBatchId(mintOp)).orElseThrow();
            assertEquals(9, voucher.outstanding());
            assertEquals(1, store.undeliveredVouchers(player).size());
        }
    }

    // --- Ascension Sigil ---------------------------------------------------------------------------

    @Test void aSigilIsSpentAndTheProfileCreatedInOneTransaction() {
        var store = open();
        var pokemon = UUID.randomUUID();
        store.grant(UUID.randomUUID(), player, Map.of(MaterialId.ASCENSION_SIGIL, 2L), "test");
        var op = UUID.randomUUID();
        var used = store.useSigil(op, player, pokemon, () -> generate(pokemon, Rarity.RARE, 20));
        assertEquals(1, used.wallet().balance(MaterialId.ASCENSION_SIGIL));
        assertEquals(Kind.USE_SIGIL, used.kind());
        assertTrue(store.profile(pokemon).isPresent());
        var replay = store.useSigil(op, player, pokemon, () -> { throw new AssertionError("a replay must not generate again"); });
        assertTrue(replay.replayed());
        assertEquals(1, store.wallet(player).balance(MaterialId.ASCENSION_SIGIL), "a replay does not charge again");
    }

    @Test void aSigilIsRefusedWithoutOneInTheWalletAndNothingIsCreated() {
        var store = open();
        var pokemon = UUID.randomUUID();
        var failure = assertThrows(CraftException.class, () ->
                store.useSigil(UUID.randomUUID(), player, pokemon, () -> generate(pokemon, Rarity.COMMON, 5)));
        assertEquals(CraftException.Reason.INSUFFICIENT_FUNDS, failure.reason());
        assertTrue(store.profile(pokemon).isEmpty());
    }

    @Test void aSigilIsNotSpentOnAPokemonThatAlreadyHasAProfile() {
        var store = open();
        var pokemon = UUID.randomUUID();
        acquire(store, pokemon, Rarity.COMMON, 5);
        store.grant(UUID.randomUUID(), player, Map.of(MaterialId.ASCENSION_SIGIL, 1L), "test");
        assertEquals(Code.DUPLICATE_POKEMON, codeOf(() ->
                store.useSigil(UUID.randomUUID(), player, pokemon, () -> generate(pokemon, Rarity.EPIC, 5))));
        assertEquals(1, store.wallet(player).balance(MaterialId.ASCENSION_SIGIL));
    }
}
