package com.cobbleascend.domain.v1;

import java.util.UUID;

/**
 * Research Bench studies (docs/FIELD-RESEARCH-DESIGN.md, decision 10 and follow-up 3). A player puts one of their Pokemon on the Bench; the
 * real Pokemon stays in their party or PC, locked, while the Bench's capsule shows a display copy. A study ends on the server clock; recalling
 * the Pokemon early ends it with nothing paid.
 *
 * <p><b>Rules that close exploits.</b>
 * <ul>
 *   <li>One study per player at a time, and a Pokemon can be in at most one study (anyone's): the store enforces both with unique keys, these
 *       checks are the friendly refusal.</li>
 *   <li>The reward is fixed when the study starts (species, kind, points, end time), so evolving, renaming or trading tricks mid-study change
 *       nothing; the Pokemon is locked anyway.</li>
 *   <li>A study is paid once, by its job's operation ({@link #payOperation}); a second "collect" replays.</li>
 *   <li>A study whose species share is already full is refused at the start, so a player never waits eight hours for nothing; a Boss Sample
 *       analysis is refused at 100% before the sample is consumed.</li>
 *   <li>Time is the server's clock only. A clock that jumps backwards simply makes the study wait longer; nothing is paid early, and an end time
 *       that would overflow is refused.</li>
 * </ul>
 */
public final class BenchStudies {
    private BenchStudies() {}

    /** The persistent-data key Laboratories put on a guard (never renamed); a guard caught at this tier or above studies faster. */
    public static final String ZONE_TIER_KEY = "ascensionlib_zone_tier";
    public static final int LAB_TIER = 3;

    /** The kinds of study. Points come from {@link FieldResearch.Source}; an analysis pays boss research instead. */
    public enum Kind {
        QUICK(20L * 60_000, 20L * 60_000, FieldResearch.Source.BENCH_QUICK.points),
        STANDARD(8L * 3_600_000, 6L * 3_600_000, FieldResearch.Source.BENCH_STANDARD.points),
        ANALYSIS(20L * 60_000, 20L * 60_000, 0);

        /** How long the study takes, and how long for a Pokemon caught in a Laboratory. */
        public final long durationMillis, labDurationMillis;
        public final int points;

        Kind(long durationMillis, long labDurationMillis, int points) {
            this.durationMillis = durationMillis;
            this.labDurationMillis = labDurationMillis;
            this.points = points;
        }

        public long durationFor(int zoneTier) { return zoneTier >= LAB_TIER ? labDurationMillis : durationMillis; }
    }

    /** Why a study cannot start, or {@link StartCheck#OK}. */
    public enum StartCheck { OK, PLAYER_BUSY, POKEMON_BUSY, UNKNOWN_SPECIES, SHARE_FULL, BOSS_RESEARCH_COMPLETE, NO_BOSS_SAMPLE, BAD_CLOCK }

    /**
     * What a study needs to know to start. {@code speciesRoom} is how many points the species can still take ({@link ResearchLedger#roomFor});
     * {@code bossPercent} and {@code samplesOfSpecies} matter only for an analysis.
     */
    public record Request(Kind kind, UUID player, UUID pokemon, String species, boolean speciesKnown, int speciesRoom, int bossPercent,
                          int samplesOfSpecies, int zoneTier, boolean playerHasActiveStudy, boolean pokemonInAStudy, long nowMillis) {}

    public static StartCheck startCheck(Request r) {
        if (r.playerHasActiveStudy()) return StartCheck.PLAYER_BUSY;
        if (r.pokemonInAStudy()) return StartCheck.POKEMON_BUSY;
        if (!r.speciesKnown() || ResearchFamilies.normalise(r.species()) == null) return StartCheck.UNKNOWN_SPECIES;
        if (r.nowMillis() <= 0 || r.nowMillis() > Long.MAX_VALUE - r.kind().durationMillis) return StartCheck.BAD_CLOCK;
        if (r.kind() == Kind.ANALYSIS) {
            if (r.bossPercent() >= FieldResearch.BOSS_MAX_PERCENT) return StartCheck.BOSS_RESEARCH_COMPLETE;
            if (r.samplesOfSpecies() < 1) return StartCheck.NO_BOSS_SAMPLE;
        } else if (r.speciesRoom() <= 0) {
            return StartCheck.SHARE_FULL;
        }
        return StartCheck.OK;
    }

    /** A running study as stored ({@code research_jobs}): everything it pays is fixed here. */
    public record Study(UUID jobId, UUID player, UUID pokemon, String species, Kind kind, long startedMillis, long endsMillis) {
        public Study {
            if (jobId == null || player == null || pokemon == null || kind == null) throw new IllegalArgumentException("Incomplete study");
            species = ResearchFamilies.normalise(species);
            if (species == null) throw new IllegalArgumentException("A study needs a species");
            if (endsMillis <= startedMillis) throw new IllegalArgumentException("A study must end after it starts");
        }

        /** Done when the server clock has reached the end time. */
        public boolean finished(long nowMillis) { return nowMillis >= endsMillis; }

        public long remainingMillis(long nowMillis) { return Math.max(0, endsMillis - nowMillis); }
    }

    /** Starts a study (after {@link #startCheck} said OK). The job ID is new; the end time comes from the server clock. */
    public static Study start(Request r, UUID jobId) {
        if (startCheck(r) != StartCheck.OK) throw new IllegalStateException("Study refused: " + startCheck(r));
        return new Study(jobId, r.player(), r.pokemon(), r.species(), r.kind(), r.nowMillis(), r.nowMillis() + r.kind().durationFor(r.zoneTier()));
    }

    /** The operation that pays a finished study, once. */
    public static UUID payOperation(Study study) {
        return FieldResearch.operation("bench-" + study.kind().name().toLowerCase(java.util.Locale.ROOT), study.jobId().toString(), study.player());
    }
}
