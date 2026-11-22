package com.cobbleascend.domain.v1;

import static org.junit.jupiter.api.Assertions.*;

import com.cobbleascend.domain.v1.BenchStudies.Kind;
import com.cobbleascend.domain.v1.BenchStudies.Request;
import com.cobbleascend.domain.v1.BenchStudies.StartCheck;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BenchStudiesTest {
    private static final UUID PLAYER = new UUID(1, 2), MON = new UUID(3, 4);
    private static final long NOW = 1_800_000_000_000L;

    private static Request request(Kind kind) {
        return new Request(kind, PLAYER, MON, "gabite", true, 60, 0, 1, 0, false, false, NOW);
    }

    @Test void anOrdinaryStudyStartsAndEndsOnTheServerClock() {
        assertEquals(StartCheck.OK, BenchStudies.startCheck(request(Kind.STANDARD)));
        var study = BenchStudies.start(request(Kind.STANDARD), UUID.randomUUID());
        assertEquals(NOW + 8 * 3_600_000L, study.endsMillis());
        assertFalse(study.finished(study.endsMillis() - 1));
        assertTrue(study.finished(study.endsMillis()));
        assertEquals(0, study.remainingMillis(study.endsMillis() + 5));
        assertFalse(study.finished(NOW - 3_600_000L), "a clock that jumps backwards only makes the study wait");
        assertEquals(10, Kind.STANDARD.points);
        assertEquals(3, Kind.QUICK.points);
    }

    @Test void aLaboratoryCatchStudiesFaster() {
        var lab = new Request(Kind.STANDARD, PLAYER, MON, "porygon", true, 50, 0, 0, 3, false, false, NOW);
        assertEquals(NOW + 6 * 3_600_000L, BenchStudies.start(lab, UUID.randomUUID()).endsMillis());
        var notQuite = new Request(Kind.STANDARD, PLAYER, MON, "porygon", true, 50, 0, 0, 2, false, false, NOW);
        assertEquals(NOW + 8 * 3_600_000L, BenchStudies.start(notQuite, UUID.randomUUID()).endsMillis());
        assertEquals(20 * 60_000L, Kind.QUICK.durationFor(3), "the quick study is already short");
    }

    @Test void refusalsComeBeforeAnyTimeIsSpent() {
        assertEquals(StartCheck.PLAYER_BUSY, BenchStudies.startCheck(new Request(Kind.QUICK, PLAYER, MON, "gabite", true, 60, 0, 0, 0, true, false, NOW)));
        assertEquals(StartCheck.POKEMON_BUSY, BenchStudies.startCheck(new Request(Kind.QUICK, PLAYER, MON, "gabite", true, 60, 0, 0, 0, false, true, NOW)));
        assertEquals(StartCheck.UNKNOWN_SPECIES, BenchStudies.startCheck(new Request(Kind.QUICK, PLAYER, MON, "missingno", false, 0, 0, 0, 0, false, false, NOW)));
        assertEquals(StartCheck.UNKNOWN_SPECIES, BenchStudies.startCheck(new Request(Kind.QUICK, PLAYER, MON, " ", true, 60, 0, 0, 0, false, false, NOW)));
        assertEquals(StartCheck.SHARE_FULL, BenchStudies.startCheck(new Request(Kind.STANDARD, PLAYER, MON, "gabite", true, 0, 0, 0, 0, false, false, NOW)),
                "eight hours for nothing is refused up front");
        assertEquals(StartCheck.BAD_CLOCK, BenchStudies.startCheck(new Request(Kind.QUICK, PLAYER, MON, "gabite", true, 60, 0, 0, 0, false, false, 0)));
        assertEquals(StartCheck.BAD_CLOCK, BenchStudies.startCheck(new Request(Kind.STANDARD, PLAYER, MON, "gabite", true, 60, 0, 0, 0, false, false, Long.MAX_VALUE - 5)),
                "an end time that would overflow is refused");
        assertThrows(IllegalStateException.class, () -> BenchStudies.start(new Request(Kind.QUICK, PLAYER, MON, "gabite", true, 60, 0, 0, 0, true, false, NOW), UUID.randomUUID()));
    }

    @Test void anAnalysisNeedsASampleAndRefusesAtOneHundredPercent() {
        assertEquals(StartCheck.OK, BenchStudies.startCheck(new Request(Kind.ANALYSIS, PLAYER, MON, "garchomp", true, 0, 90, 1, 0, false, false, NOW)),
                "an analysis does not care about the species' share");
        assertEquals(StartCheck.BOSS_RESEARCH_COMPLETE, BenchStudies.startCheck(new Request(Kind.ANALYSIS, PLAYER, MON, "garchomp", true, 0, 100, 3, 0, false, false, NOW)));
        assertEquals(StartCheck.NO_BOSS_SAMPLE, BenchStudies.startCheck(new Request(Kind.ANALYSIS, PLAYER, MON, "garchomp", true, 0, 10, 0, 0, false, false, NOW)));
    }

    @Test void aStudyPaysOnceByItsJob() {
        var job = UUID.randomUUID();
        var study = BenchStudies.start(request(Kind.QUICK), job);
        assertEquals(BenchStudies.payOperation(study), BenchStudies.payOperation(BenchStudies.start(request(Kind.QUICK), job)));
        assertNotEquals(BenchStudies.payOperation(study), BenchStudies.payOperation(BenchStudies.start(request(Kind.QUICK), UUID.randomUUID())));
    }

    @Test void aStudyRecordIsValidated() {
        assertThrows(IllegalArgumentException.class, () -> new BenchStudies.Study(UUID.randomUUID(), PLAYER, MON, "gabite", Kind.QUICK, NOW, NOW));
        assertThrows(IllegalArgumentException.class, () -> new BenchStudies.Study(UUID.randomUUID(), PLAYER, MON, "  ", Kind.QUICK, NOW, NOW + 1));
        assertThrows(IllegalArgumentException.class, () -> new BenchStudies.Study(null, PLAYER, MON, "gabite", Kind.QUICK, NOW, NOW + 1));
        assertEquals("gabite", new BenchStudies.Study(UUID.randomUUID(), PLAYER, MON, " Gabite ", Kind.QUICK, NOW, NOW + 1).species());
    }
}
