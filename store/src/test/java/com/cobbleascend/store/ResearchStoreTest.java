package com.cobbleascend.store;

import static org.junit.jupiter.api.Assertions.*;

import com.cobbleascend.domain.v1.*;
import com.cobbleascend.domain.v1.Serums.Stat;
import com.cobbleascend.store.StoreException.Code;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Field Research in the store (docs/FIELD-RESEARCH-DESIGN.md, step 2): every change once, every refusal before anything is written. */
class ResearchStoreTest {
    private static final UUID AUTH = UUID.fromString("9d6bb263-d230-42d7-a9d0-ea4710df4bb5");
    private static final ResearchFamilies.Catalog CATALOG = ResearchFamilies.fromPreEvolutions(Map.of(
            "gible", "", "gabite", "gible", "garchomp", "gabite",
            "voltorb", "", "electrode", "voltorb",
            "ditto", "", "mewtwo", ""));
    private static final Serums.CaptureFacts WILD_GABITE = new Serums.CaptureFacts("gabite", false, false, false, Serums.CaptureKind.WILD);

    /** A clock the test moves by hand. */
    static final class HandClock extends Clock {
        volatile long millis = Instant.parse("2026-11-23T10:00:00Z").toEpochMilli();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public long millis() { return millis; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        void advance(long ms) { millis += ms; }
    }

    @TempDir Path dir;
    private final List<ProgressionStore> opened = new ArrayList<>();
    private final HandClock clock = new HandClock();
    private final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();

    @AfterEach void close() {
        for (var store : opened) try { store.close(); } catch (RuntimeException ignored) { }
    }

    private ProgressionStore open() { return open(Faults.NONE); }

    private ProgressionStore open(Faults faults) {
        var store = ProgressionStore.open(dir.resolve("progression.db"), RankedRules.defaults(), AUTH,
                ProgressionStore.Options.defaults().withRandom(new Random(1)).withClock(clock).withFaults(faults));
        opened.add(store);
        return store;
    }

    private static ResearchException.Reason refused(Runnable action) {
        return assertThrows(ResearchException.class, action::run).reason();
    }

    private static String detail(Runnable action) {
        return assertThrows(ResearchException.class, action::run).detail();
    }

    /** Research up to a level on Gible's family the honest way (grants spread over every stage). */
    private static void researchGible(ProgressionStore store, UUID player, int points) {
        int left = points;
        for (var species : List.of("gible", "gabite", "garchomp")) {
            int give = Math.min(left, CATALOG.shareOf(species));
            if (give > 0) store.grantResearch(UUID.randomUUID(), player, CATALOG, species, give, "test");
            left -= give;
        }
    }

    // ---- research and raids ----------------------------------------------------------------------------------------------------------

    @Test void aRaidWinPaysResearchAndOneSampleOnceHoweverOftenItIsReported() {
        var store = open();
        var boss = UUID.randomUUID().toString();
        var op = FieldResearch.operation("raid", boss, alice);
        var first = store.settleResearchRaid(op, alice, CATALOG, "Garchomp", FieldResearch.RaidTier.POWERHOUSE);
        var again = store.settleResearchRaid(op, alice, CATALOG, "garchomp", FieldResearch.RaidTier.POWERHOUSE);
        assertFalse(first.replayed());
        assertTrue(again.replayed());
        assertEquals(8, store.researchLedger(alice).pointsOf(CATALOG, "garchomp"));
        assertEquals(Map.of("garchomp", 1), store.researchItems(alice, FieldResearch.Item.BOSS_SAMPLE));
        // Another player in the same raid has an operation of their own.
        store.settleResearchRaid(FieldResearch.operation("raid", boss, bob), bob, CATALOG, "garchomp", FieldResearch.RaidTier.POWERHOUSE);
        assertEquals(8, store.researchLedger(bob).pointsOf(CATALOG, "garchomp"));
        assertEquals(8, store.researchLedger(alice).pointsOf(CATALOG, "garchomp"), "Bob's raid pays Alice nothing");
    }

    @Test void aRefusedGrantRecordsNothingSoItCanBeMadeAgain() {
        var store = open();
        var op = UUID.randomUUID();
        assertEquals(ResearchException.Reason.UNKNOWN_SPECIES, refused(() -> store.grantResearch(op, alice, CATALOG, "missingno", 10, "t")));
        assertEquals(ResearchException.Reason.BAD_AMOUNT, refused(() -> store.grantResearch(op, alice, CATALOG, "gible", 0, "t")));
        assertEquals(ResearchException.Reason.BAD_AMOUNT, refused(() -> store.grantResearch(op, alice, CATALOG, "gible", 5_000, "t")));
        assertFalse(store.hasOperation(op));
        store.grantResearch(op, alice, CATALOG, "gible", 10, "t");
        assertEquals(10, store.researchLedger(alice).pointsOf(CATALOG, "gible"));
    }

    @Test void aFullShareIsRecordedAndChangesNothing() {
        var store = open();
        store.grantResearch(UUID.randomUUID(), alice, CATALOG, "gible", 50, "t");
        var op = UUID.randomUUID();
        store.grantResearch(op, alice, CATALOG, "gible", 30, "t");
        assertTrue(store.hasOperation(op), "a retry replays instead of paying later, should the share ever grow");
        assertEquals(50, store.researchLedger(alice).pointsOf(CATALOG, "gible"));
    }

    @Test void theSameOperationForADifferentRequestIsRefused() {
        var store = open();
        var op = UUID.randomUUID();
        store.grantResearch(op, alice, CATALOG, "gible", 10, "t");
        var error = assertThrows(StoreException.class, () -> store.grantResearch(op, alice, CATALOG, "gible", 40, "t"));
        assertEquals(Code.OPERATION_REUSED, error.code());
        assertEquals(10, store.researchLedger(alice).pointsOf(CATALOG, "gible"));
    }

    // ---- Data Drives ------------------------------------------------------------------------------------------------------------------

    @Test void aDataDriveIsSpentOnceAndNeverOnAFullShare() {
        var store = open();
        assertEquals(ResearchException.Reason.NO_ITEM, refused(() -> store.useDataDrive(UUID.randomUUID(), alice, CATALOG, "gible")));
        store.grantResearchItem(UUID.randomUUID(), alice, CATALOG, FieldResearch.Item.DATA_DRIVE, "", 1);
        var op = UUID.randomUUID();
        store.useDataDrive(op, alice, CATALOG, "gible");
        store.useDataDrive(op, alice, CATALOG, "gible");
        assertEquals(10, store.researchLedger(alice).pointsOf(CATALOG, "gible"));
        assertTrue(store.researchItems(alice, FieldResearch.Item.DATA_DRIVE).isEmpty());
        store.grantResearchItem(UUID.randomUUID(), alice, CATALOG, FieldResearch.Item.DATA_DRIVE, "", 1);
        store.grantResearch(UUID.randomUUID(), alice, CATALOG, "gible", 40, "t");
        assertEquals(ResearchException.Reason.SHARE_FULL, refused(() -> store.useDataDrive(UUID.randomUUID(), alice, CATALOG, "gible")));
        assertEquals(Map.of("", 1), store.researchItems(alice, FieldResearch.Item.DATA_DRIVE), "the drive is kept");
    }

    @Test void itemCountsAreBounded() {
        var store = open();
        assertEquals(ResearchException.Reason.BAD_AMOUNT,
                refused(() -> store.grantResearchItem(UUID.randomUUID(), alice, CATALOG, FieldResearch.Item.BOSS_SAMPLE, "gible", 65)));
        assertEquals(ResearchException.Reason.UNKNOWN_SPECIES,
                refused(() -> store.grantResearchItem(UUID.randomUUID(), alice, CATALOG, FieldResearch.Item.BOSS_SAMPLE, "missingno", 1)));
    }

    // ---- Bench studies ----------------------------------------------------------------------------------------------------------------

    @Test void aStudyPaysOnceWhenItFinishesOnTheServerClock() {
        var store = open();
        var mon = UUID.randomUUID();
        store.startResearchStudy(UUID.randomUUID(), alice, CATALOG, BenchStudies.Kind.STANDARD, mon, "gabite", 0, UUID.randomUUID());
        assertEquals(Set.of(mon), store.pokemonInResearch());
        assertEquals(ResearchException.Reason.NOT_FINISHED, refused(() -> store.collectResearchStudy(alice, CATALOG)));
        clock.advance(8 * 3_600_000L - 1);
        assertEquals(ResearchException.Reason.NOT_FINISHED, refused(() -> store.collectResearchStudy(alice, CATALOG)));
        clock.advance(1);
        store.collectResearchStudy(alice, CATALOG);
        assertEquals(10, store.researchLedger(alice).pointsOf(CATALOG, "gabite"));
        assertEquals(ResearchException.Reason.NOTHING_TO_COLLECT, refused(() -> store.collectResearchStudy(alice, CATALOG)));
        assertEquals(10, store.researchLedger(alice).pointsOf(CATALOG, "gabite"), "a second collect pays nothing");
        assertTrue(store.pokemonInResearch().isEmpty());
    }

    @Test void oneStudyPerPlayerAndOnePerPokemon() {
        var store = open();
        var mon = UUID.randomUUID();
        store.startResearchStudy(UUID.randomUUID(), alice, CATALOG, BenchStudies.Kind.QUICK, mon, "gible", 0, UUID.randomUUID());
        assertEquals("PLAYER_BUSY", detail(() -> store.startResearchStudy(UUID.randomUUID(), alice, CATALOG, BenchStudies.Kind.QUICK,
                UUID.randomUUID(), "gible", 0, UUID.randomUUID())));
        assertEquals("POKEMON_BUSY", detail(() -> store.startResearchStudy(UUID.randomUUID(), bob, CATALOG, BenchStudies.Kind.QUICK,
                mon, "gible", 0, UUID.randomUUID())), "a Pokemon traded mid-study cannot be studied twice");
    }

    @Test void aFullShareIsRefusedBeforeAnyTimeIsSpent() {
        var store = open();
        store.grantResearch(UUID.randomUUID(), alice, CATALOG, "gible", 50, "t");
        assertEquals("SHARE_FULL", detail(() -> store.startResearchStudy(UUID.randomUUID(), alice, CATALOG, BenchStudies.Kind.STANDARD,
                UUID.randomUUID(), "gible", 0, UUID.randomUUID())));
        assertEquals("UNKNOWN_SPECIES", detail(() -> store.startResearchStudy(UUID.randomUUID(), alice, CATALOG, BenchStudies.Kind.STANDARD,
                UUID.randomUUID(), "missingno", 0, UUID.randomUUID())));
        assertTrue(store.researchStudy(alice).isEmpty());
    }

    @Test void aLaboratoryCatchStudiesInSixHours() {
        var store = open();
        store.startResearchStudy(UUID.randomUUID(), alice, CATALOG, BenchStudies.Kind.STANDARD, UUID.randomUUID(), "voltorb", 3, UUID.randomUUID());
        clock.advance(6 * 3_600_000L);
        store.collectResearchStudy(alice, CATALOG);
        assertEquals(10, store.researchLedger(alice).pointsOf(CATALOG, "voltorb"));
    }

    @Test void recallingPaysNothingAndFreesThePokemon() {
        var store = open();
        var mon = UUID.randomUUID();
        store.startResearchStudy(UUID.randomUUID(), alice, CATALOG, BenchStudies.Kind.STANDARD, mon, "gible", 0, UUID.randomUUID());
        clock.advance(7 * 3_600_000L);
        store.recallResearchStudy(UUID.randomUUID(), alice);
        clock.advance(3_600_000L);
        assertEquals(ResearchException.Reason.NOTHING_TO_COLLECT, refused(() -> store.collectResearchStudy(alice, CATALOG)));
        assertEquals(0, store.researchLedger(alice).pointsOf(CATALOG, "gible"));
        assertTrue(store.researchStudyOfPokemon(mon).isEmpty());
        assertEquals(ResearchException.Reason.NOTHING_TO_COLLECT, refused(() -> store.recallResearchStudy(UUID.randomUUID(), alice)));
    }

    @Test void anAnalysisSpendsItsSampleUpFrontAndRefusesAtOneHundredPercent() {
        var store = open();
        assertEquals("NO_BOSS_SAMPLE", detail(() -> store.startResearchStudy(UUID.randomUUID(), alice, CATALOG, BenchStudies.Kind.ANALYSIS,
                UUID.randomUUID(), "garchomp", 0, UUID.randomUUID())));
        store.grantResearchItem(UUID.randomUUID(), alice, CATALOG, FieldResearch.Item.BOSS_SAMPLE, "garchomp", 12);
        for (int i = 0; i < 10; i++) {
            store.startResearchStudy(UUID.randomUUID(), alice, CATALOG, BenchStudies.Kind.ANALYSIS, UUID.randomUUID(), "garchomp", 0, UUID.randomUUID());
            clock.advance(20 * 60_000L);
            store.collectResearchStudy(alice, CATALOG);
        }
        assertEquals(100, store.researchLedger(alice).bossPercentOf("garchomp"));
        assertEquals(Map.of("garchomp", 2), store.researchItems(alice, FieldResearch.Item.BOSS_SAMPLE));
        assertEquals("BOSS_RESEARCH_COMPLETE", detail(() -> store.startResearchStudy(UUID.randomUUID(), alice, CATALOG, BenchStudies.Kind.ANALYSIS,
                UUID.randomUUID(), "garchomp", 0, UUID.randomUUID())));
        assertEquals(Map.of("garchomp", 2), store.researchItems(alice, FieldResearch.Item.BOSS_SAMPLE), "no sample burnt on a refusal");
    }

    @Test void aRecalledAnalysisLosesItsSample() {
        var store = open();
        store.grantResearchItem(UUID.randomUUID(), alice, CATALOG, FieldResearch.Item.BOSS_SAMPLE, "garchomp", 1);
        store.startResearchStudy(UUID.randomUUID(), alice, CATALOG, BenchStudies.Kind.ANALYSIS, UUID.randomUUID(), "garchomp", 0, UUID.randomUUID());
        assertTrue(store.researchItems(alice, FieldResearch.Item.BOSS_SAMPLE).isEmpty());
        store.recallResearchStudy(UUID.randomUUID(), alice);
        assertEquals(0, store.researchLedger(alice).bossPercentOf("garchomp"));
    }

    // ---- serums -----------------------------------------------------------------------------------------------------------------------

    @Test void craftingNeedsTheResearchLevelAndPaysFromTheWallet() {
        var store = open();
        store.grant(UUID.randomUUID(), alice, Map.of(MaterialId.RESONANCE_DUST, 1_000L), "t");
        assertEquals("NEEDS_RESEARCH", detail(() -> store.craftSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.FLOOR, 1, CraftRequest.ANY_REVISION)));
        assertEquals(1_000, store.wallet(alice).balance(MaterialId.RESONANCE_DUST), "nothing taken for a refusal");
        researchGible(store, alice, 40);
        var outcome = store.craftSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.FLOOR, 2, CraftRequest.ANY_REVISION);
        assertEquals(880, outcome.wallet().balance(MaterialId.RESONANCE_DUST));
        assertEquals(2, outcome.wallet().balance(MaterialId.FLOOR_SERUM));
    }

    @Test void theDailyLimitHoldsAcrossCallsAndResetsAtUtcMidnight() {
        var store = open();
        store.grant(UUID.randomUUID(), alice, Map.of(MaterialId.RESONANCE_DUST, 10_000L), "t");
        researchGible(store, alice, 40);
        store.craftSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.FLOOR, 3, CraftRequest.ANY_REVISION);
        assertEquals("LIMIT_REACHED", detail(() -> store.craftSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.FLOOR, 3, CraftRequest.ANY_REVISION)));
        store.craftSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.FLOOR, 2, CraftRequest.ANY_REVISION);
        assertEquals("LIMIT_REACHED", detail(() -> store.craftSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.FLOOR, 1, CraftRequest.ANY_REVISION)));
        clock.millis = Instant.parse("2026-11-24T00:00:00Z").toEpochMilli();
        store.craftSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.FLOOR, 5, CraftRequest.ANY_REVISION);
        assertEquals(10, store.wallet(alice).balance(MaterialId.FLOOR_SERUM));
    }

    @Test void parallelCraftsNeverPassTheLimit() throws Exception {
        var store = open();
        store.grant(UUID.randomUUID(), alice, Map.of(MaterialId.RESONANCE_DUST, 100_000L), "t");
        researchGible(store, alice, 40);
        var pool = Executors.newFixedThreadPool(8);
        var made = new AtomicInteger();
        for (int i = 0; i < 40; i++) {
            pool.submit(() -> {
                try {
                    store.craftSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.FLOOR, 1, CraftRequest.ANY_REVISION);
                    made.incrementAndGet();
                } catch (ResearchException expected) {
                    // over the limit
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(5, made.get());
        assertEquals(5, store.wallet(alice).balance(MaterialId.FLOOR_SERUM));
        assertEquals(100_000 - 5 * 60, store.wallet(alice).balance(MaterialId.RESONANCE_DUST));
    }

    @Test void aShortWalletChangesNothingAndUsesNoLimit() {
        var store = open();
        store.grant(UUID.randomUUID(), alice, Map.of(MaterialId.RESONANCE_DUST, 100L), "t");
        researchGible(store, alice, 40);
        assertThrows(RuntimeException.class, () -> store.craftSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.FLOOR, 2, CraftRequest.ANY_REVISION));
        assertEquals(100, store.wallet(alice).balance(MaterialId.RESONANCE_DUST));
        long today = Serums.Kind.FLOOR.periodOf(clock.millis());
        assertEquals(0, store.researchCounter(alice, "floor_serum", today));
    }

    @Test void aStaleWalletIsRefused() {
        var store = open();
        store.grant(UUID.randomUUID(), alice, Map.of(MaterialId.RESONANCE_DUST, 1_000L), "t");
        researchGible(store, alice, 40);
        long shown = store.wallet(alice).revision();
        store.grant(UUID.randomUUID(), alice, Map.of(MaterialId.RESONANCE_DUST, 1L), "t");
        var error = assertThrows(StoreException.class, () -> store.craftSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.FLOOR, 1, shown));
        assertEquals(Code.STALE_WALLET, error.code());
    }

    @Test void primeNeedsASampleAndAMarkHasAWeeklyLimitAndSpendsFinishedSamplesFirst() {
        var store = open();
        store.grant(UUID.randomUUID(), alice, Map.of(MaterialId.RESONANCE_DUST, 10_000L, MaterialId.TOWER_MARK, 10L), "t");
        researchGible(store, alice, 200);
        assertEquals("NEEDS_BOSS_SAMPLE", detail(() -> store.craftSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.PRIME, 1, CraftRequest.ANY_REVISION)));
        store.grantResearchItem(UUID.randomUUID(), alice, CATALOG, FieldResearch.Item.BOSS_SAMPLE, "garchomp", 3);
        store.grantResearchItem(UUID.randomUUID(), alice, CATALOG, FieldResearch.Item.BOSS_SAMPLE, "electrode", 5);
        // Garchomp's boss research is complete, so its samples go first even though Electrode's are more numerous.
        for (int i = 0; i < 10; i++) {
            store.grantResearchItem(UUID.randomUUID(), alice, CATALOG, FieldResearch.Item.BOSS_SAMPLE, "garchomp", 1);
            store.startResearchStudy(UUID.randomUUID(), alice, CATALOG, BenchStudies.Kind.ANALYSIS, UUID.randomUUID(), "garchomp", 0, UUID.randomUUID());
            clock.advance(20 * 60_000L);
            store.collectResearchStudy(alice, CATALOG);
        }
        store.craftSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.PRIME, 3, CraftRequest.ANY_REVISION);
        assertEquals(Map.of("electrode", 5), store.researchItems(alice, FieldResearch.Item.BOSS_SAMPLE));
        assertEquals(7, store.wallet(alice).balance(MaterialId.TOWER_MARK));
        assertEquals("LIMIT_REACHED", detail(() -> store.craftSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.PRIME, 1, CraftRequest.ANY_REVISION)));
        clock.advance(7L * 86_400_000L);
        store.craftSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.PRIME, 1, CraftRequest.ANY_REVISION);
        assertEquals(4, store.wallet(alice).balance(MaterialId.PRIME_SERUM));
    }

    @Test void aCrashBeforeTheCommitLeavesNothingAndAfterItReplays() {
        var crash = new ProgressionStoreTest.CrashAt();
        var store = open(crash);
        store.grant(UUID.randomUUID(), alice, Map.of(MaterialId.RESONANCE_DUST, 1_000L), "t");
        researchGible(store, alice, 40);
        var op = UUID.randomUUID();
        for (var point : List.of(Faults.Point.AFTER_VALIDATION, Faults.Point.BEFORE_COMMIT)) {
            crash.armed = point;
            assertThrows(ProgressionStoreTest.SimulatedCrash.class,
                    () -> store.craftSerum(op, alice, CATALOG, Serums.Kind.FLOOR, 1, CraftRequest.ANY_REVISION));
            assertEquals(1_000, store.wallet(alice).balance(MaterialId.RESONANCE_DUST), point.name());
            assertEquals(0, store.wallet(alice).balance(MaterialId.FLOOR_SERUM), point.name());
        }
        crash.armed = Faults.Point.AFTER_COMMIT;
        assertThrows(ProgressionStoreTest.SimulatedCrash.class, () -> store.craftSerum(op, alice, CATALOG, Serums.Kind.FLOOR, 1, CraftRequest.ANY_REVISION));
        assertTrue(store.craftSerum(op, alice, CATALOG, Serums.Kind.FLOOR, 1, CraftRequest.ANY_REVISION).replayed());
        assertEquals(940, store.wallet(alice).balance(MaterialId.RESONANCE_DUST));
        assertEquals(1, store.researchCounter(alice, "floor_serum", Serums.Kind.FLOOR.periodOf(clock.millis())));
    }

    @Test void armingNeedsResearchOnThatFamilyAndTakesTheSerum() {
        var store = open();
        store.grant(UUID.randomUUID(), alice, Map.of(MaterialId.FOCUS_SERUM, 2L), "t");                // bought from someone
        assertEquals("NEEDS_RESEARCH", detail(() -> store.armSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.FOCUS, "gible",
                List.of(Stat.ATTACK, Stat.SPEED), false)), "a traded serum is useless without the research");
        researchGible(store, alice, 100);
        store.armSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.FOCUS, "gible", List.of(Stat.ATTACK, Stat.SPEED), false);
        assertEquals(1, store.wallet(alice).balance(MaterialId.FOCUS_SERUM));
        assertEquals("ALREADY_ARMED", detail(() -> store.armSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.FOCUS, "gible",
                List.of(Stat.HP, Stat.SPEED), false)));
        researchGible(store, bob, 100);
        assertEquals("DUPLICATE_STAT", detail(() -> store.armSerum(UUID.randomUUID(), bob, CATALOG, Serums.Kind.FOCUS, "gible",
                List.of(Stat.HP, Stat.HP), false)));
        assertEquals(Serums.Kind.FOCUS, store.armedSerums(alice).get("gible").kind());
    }

    @Test void armingWithoutTheSerumChangesNothing() {
        var store = open();
        researchGible(store, alice, 40);
        assertThrows(RuntimeException.class, () -> store.armSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.FLOOR, "gible", List.of(Stat.HP), false));
        assertTrue(store.armedSerums(alice).isEmpty());
    }

    @Test void disarmingReturnsTheSerumAndWorksForAVanishedFamily() {
        var store = open();
        store.grant(UUID.randomUUID(), alice, Map.of(MaterialId.FLOOR_SERUM, 1L), "t");
        researchGible(store, alice, 40);
        store.armSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.FLOOR, "gible", List.of(Stat.HP), false);
        var op = UUID.randomUUID();
        store.disarmSerum(op, alice, "gible");
        assertTrue(store.disarmSerum(op, alice, "gible").replayed(), "a doubled disarm returns one serum");
        assertEquals(1, store.wallet(alice).balance(MaterialId.FLOOR_SERUM));
        assertEquals(ResearchException.Reason.NOT_ARMED, refused(() -> store.disarmSerum(UUID.randomUUID(), alice, "gible")));
    }

    @Test void aCaptureUsesTheSerumOnceAndRecordsTheIvsToWrite() {
        var store = open();
        store.grant(UUID.randomUUID(), alice, Map.of(MaterialId.FLOOR_SERUM, 1L), "t");
        researchGible(store, alice, 40);
        store.armSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.FLOOR, "gible", List.of(Stat.SPEED), false);
        var mon = UUID.randomUUID();
        int[] ivs = {3, 7, 0, 12, 30, 19};
        var use = store.useArmedSerum(alice, CATALOG, WILD_GABITE, mon, ivs).orElseThrow();
        assertArrayEquals(new int[] {3, 7, 0, 12, 30, 20}, use.ivs());
        assertEquals(List.of(Stat.SPEED), use.raised());
        assertTrue(store.armedSerums(alice).isEmpty(), "used up");
        assertArrayEquals(use.ivs(), store.useArmedSerum(alice, CATALOG, WILD_GABITE, mon, ivs).orElseThrow().ivs(), "a repeated capture event is the same result");
        assertTrue(store.useArmedSerum(alice, CATALOG, WILD_GABITE, UUID.randomUUID(), ivs).isEmpty(), "the next capture has no serum");
        assertEquals(1, store.unprojectedSerumUses().size());
        store.markSerumUseProjected(mon);
        assertTrue(store.unprojectedSerumUses().isEmpty());
        assertEquals(Serums.Kind.FLOOR, store.serumUse(mon).orElseThrow().kind());
    }

    @Test void aCaptureTheSerumDoesNotFitKeepsItArmed() {
        var store = open();
        store.grant(UUID.randomUUID(), alice, Map.of(MaterialId.FLOOR_SERUM, 1L), "t");
        researchGible(store, alice, 40);
        store.armSerum(UUID.randomUUID(), alice, CATALOG, Serums.Kind.FLOOR, "gible", List.of(Stat.SPEED), false);
        int[] ivs = {1, 1, 1, 1, 1, 1};
        assertTrue(store.useArmedSerum(alice, CATALOG, new Serums.CaptureFacts("voltorb", false, false, false, Serums.CaptureKind.WILD), UUID.randomUUID(), ivs).isEmpty());
        assertTrue(store.useArmedSerum(alice, CATALOG, new Serums.CaptureFacts("gabite", false, false, false, Serums.CaptureKind.RAID), UUID.randomUUID(), ivs).isEmpty());
        assertTrue(store.useArmedSerum(bob, CATALOG, WILD_GABITE, UUID.randomUUID(), ivs).isEmpty(), "another player's catch never uses Alice's serum");
        assertEquals(1, store.armedSerums(alice).size());
    }

    @Test void researchSurvivesARestartWithoutASchemaBump() throws Exception {
        var store = open();
        store.grantResearch(UUID.randomUUID(), alice, CATALOG, "gible", 25, "t");
        store.grantResearchItem(UUID.randomUUID(), alice, CATALOG, FieldResearch.Item.BOSS_SAMPLE, "garchomp", 2);
        store.close();
        opened.remove(store);
        var again = open();
        assertEquals(25, again.researchLedger(alice).pointsOf(CATALOG, "gible"));
        assertEquals(Map.of("garchomp", 2), again.researchItems(alice, FieldResearch.Item.BOSS_SAMPLE));
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("progression.db"));
             var rows = connection.createStatement().executeQuery("SELECT value FROM meta WHERE key = 'schema_version'")) {
            assertTrue(rows.next());
            assertEquals(Integer.toString(ProgressionStore.SCHEMA_VERSION), rows.getString(1));
        }
    }
}
