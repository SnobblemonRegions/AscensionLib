package com.cobbleascend.sim;

import com.cobbleascend.domain.v1.BenchStudies;
import com.cobbleascend.domain.v1.FieldResearch;
import com.cobbleascend.domain.v1.MaterialId;
import com.cobbleascend.domain.v1.ResearchFamilies;
import com.cobbleascend.domain.v1.ResearchLedger;
import com.cobbleascend.domain.v1.Serums;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Field Research in the balance sim (docs/FIELD-RESEARCH-DESIGN.md), off unless {@code -PsimResearch=true}. Every rule comes from the shipped
 * domain code: family shares ({@link ResearchFamilies}), credits and levels ({@link ResearchLedger}), Bench study points ({@link BenchStudies}),
 * raid research by tier ({@link FieldResearch.RaidTier}), serum gates, costs and craft limits ({@link Serums}). What a server would decide is a
 * stated model in {@link Simulation.Model}: how many Bench studies a player runs, how many labs they search, how many serums they use.
 *
 * <p>Research itself mints no material: the only flows are the serums' costs leaving the ledger, so this measures the sink. It draws from a
 * random stream of its own, so switching it on never changes the base economy's rolls (spending can still change when a player affords a
 * promotion, which is the effect being measured).
 */
final class ResearchSink {
    /** The synthetic dex: three-stage families, the first {@code raidFamilies} of which have a raid boss (their final stage). */
    final ResearchFamilies.Catalog catalog;
    private final List<String> bossSpecies = new ArrayList<>();
    private final List<String> familyIds;
    private final Simulation.Model model;
    private final Random random;
    private final Map<UUID, State> states = new HashMap<>();
    final Stats stats = new Stats();

    /** One player's research. */
    static final class State {
        ResearchLedger ledger = ResearchLedger.EMPTY;
        final Map<String, Integer> samples = new HashMap<>();
        /** Crafts this UTC day by kind, and Prime crafts this week. */
        final int[] craftedToday = new int[Serums.Kind.values().length];
        int primeThisWeek;
        long week = -1;
        int focus;                                                                          // index into familyIds being studied
        int reachedLevels;                                                                  // bit l set once the highest level reached l

        boolean reached(int level) { return (reachedLevels & (1 << level)) != 0; }

        void mark(int level) { reachedLevels |= 1 << level; }
    }

    /** What research did over a run. */
    static final class Stats {
        final long[] crafted = new long[Serums.Kind.values().length];
        long dust, facets, marks, samplesSpent, analyses, quickStudies, standardStudies, raidGrants, labGrants, wasted;
        final Set<UUID> researchers = new HashSet<>();
        /** Players whose highest family level reached 2, 3 and 4, and the day each first did. */
        final Map<Integer, List<Integer>> firstDayAtLevel = new HashMap<>();
        int maxCraftsSeenInADay, maxPrimeSeenInAWeek;
        long familiesAtLevel4, bossComplete;
    }

    ResearchSink(Simulation.Model model, long seed) {
        this.model = model;
        this.random = new Random(seed * 53 + 11);
        var pre = new HashMap<String, String>();
        for (int f = 0; f < model.researchFamilies; f++) {
            pre.put("f" + f + "a", null);
            pre.put("f" + f + "b", "f" + f + "a");
            pre.put("f" + f + "c", "f" + f + "b");
            if (f < model.raidFamilies) bossSpecies.add("f" + f + "c");
        }
        catalog = ResearchFamilies.fromPreEvolutions(pre);
        familyIds = List.copyOf(catalog.families().keySet());
    }

    void enrol(SimPlayer player) {
        if (random.nextDouble() < model.researchParticipation) {
            var state = new State();
            state.focus = random.nextInt(familyIds.size());
            states.put(player.id, state);
            stats.researchers.add(player.id);
        }
    }

    boolean researcher(SimPlayer player) { return states.containsKey(player.id); }

    /** The species in the player's focus family with room left (earliest stage first), moving to the next family once it is full. */
    private String focusSpecies(State state) {
        for (int tries = 0; tries < familyIds.size(); tries++) {
            var family = catalog.family(familyIds.get(state.focus)).orElseThrow();
            for (var stage : family.stages()) for (var s : stage) if (state.ledger.roomFor(catalog, s) > 0) return s;
            state.focus = (state.focus + 1) % familyIds.size();
        }
        return null;
    }

    /** The earliest species of a family with room left, or null when the family is full. */
    private String roomIn(State state, String familyId) {
        for (var stage : catalog.family(familyId).orElseThrow().stages()) for (var s : stage) if (state.ledger.roomFor(catalog, s) > 0) return s;
        return null;
    }

    private void credit(State state, String species, int points) {
        if (species == null) return;
        int scaled = Math.max(1, (int) Math.round(points * model.researchGrantPercent / 100.0));
        var credit = state.ledger.credit(catalog, species, scaled);
        state.ledger = credit.ledger();
        stats.wasted += credit.wasted();
    }

    /** A won raid: research for the boss's species by tier and one Boss Sample of it (a raid boss drawn from the raid families). */
    void raidWon(SimPlayer player, FieldResearch.RaidTier tier) {
        var state = states.get(player.id);
        if (state == null || bossSpecies.isEmpty()) return;
        String boss = bossSpecies.get(random.nextInt(bossSpecies.size()));
        credit(state, boss, tier.research);
        state.samples.merge(boss, 1, Integer::sum);
        stats.raidGrants++;
    }

    /** A play day: the Bench (one overnight study, quick studies or analyses through the session), Laboratories, then serums. */
    void day(SimPlayer player, int day, double sessionMinutes, Ledger ledger) {
        var state = states.get(player.id);
        if (state == null) return;
        java.util.Arrays.fill(state.craftedToday, 0);
        long week = Math.floorDiv(day + 3, 7);
        if (week != state.week) { state.week = week; state.primeThisWeek = 0; }
        if (!player.playedToday) return;
        // The overnight study.
        String species = focusSpecies(state);
        if (species != null) { credit(state, species, BenchStudies.Kind.STANDARD.points); stats.standardStudies++; }
        // Quick studies and analyses through the session (one job at a time, twenty minutes each, as often as the player tends the Bench).
        int jobs = (int) Math.floor(sessionMinutes / 60.0 * model.benchJobsPerHour);
        for (int j = 0; j < jobs; j++) {
            String sample = null;
            for (var entry : state.samples.entrySet())
                if (entry.getValue() > 0 && state.ledger.bossPercentOf(entry.getKey()) < FieldResearch.BOSS_MAX_PERCENT) { sample = entry.getKey(); break; }
            if (sample != null) {
                state.samples.merge(sample, -1, Integer::sum);
                state.ledger = state.ledger.analyze(catalog, sample).ledger();
                stats.analyses++;
                stats.samplesSpent++;
            } else {
                String s = focusSpecies(state);
                if (s == null) break;
                credit(state, s, BenchStudies.Kind.QUICK.points);
                stats.quickStudies++;
            }
        }
        // Laboratories: a Data Drive (to the focus) and, in four labs of nine, a Specimen Locker (a guard species: the sim's first family).
        if (random.nextDouble() < model.labsPerWeek / 7.0) {
            credit(state, focusSpecies(state), FieldResearch.Source.LAB_DATA_DRIVE.points);
            if (random.nextDouble() < 4.0 / 9.0) credit(state, roomIn(state, familyIds.get(0)), FieldResearch.Source.LAB_LOCKER.points);
            stats.labGrants++;
        }
        craft(player, state, day, ledger);
        int level = state.ledger.highestLevel(catalog);
        for (int l = 2; l <= level; l++) {
            var days = stats.firstDayAtLevel.computeIfAbsent(l, k -> new ArrayList<>());
            if (!state.reached(l)) { days.add(day + 1); state.mark(l); }
        }
    }

    /**
     * Serums for the day's wild catches: the best kind the player can make (Prime, then Focus, then Floor), within the domain's craft limits
     * and keeping a reserve of each material for promotions, one per catch the model says they would use one on.
     */
    private void craft(SimPlayer player, State state, int day, Ledger ledger) {
        int level = state.ledger.highestLevel(catalog);
        for (int use = 0; use < model.serumUsesPerDay; use++) {
            Serums.Kind made = null;
            for (var kind : new Serums.Kind[] {Serums.Kind.PRIME, Serums.Kind.FOCUS, Serums.Kind.FLOOR}) {
                int crafted = kind == Serums.Kind.PRIME ? state.primeThisWeek : state.craftedToday[kind.ordinal()];
                int samples = state.samples.values().stream().mapToInt(Integer::intValue).sum();
                if (Serums.craftCheck(kind, level, crafted, samples, 1) != Serums.CraftCheck.OK) continue;
                if (!affords(player, Serums.costOf(kind, 1))) continue;
                made = kind;
                break;
            }
            if (made == null) return;
            for (var cost : Serums.costOf(made, 1).entrySet()) ledger.burn(player, day, cost.getKey(), cost.getValue());
            for (int n = 0; n < made.bossSamples; n++) {
                // Prime takes any species' sample: the player spends one already analyzed to 100%, else any.
                String spend = null;
                for (var entry : state.samples.entrySet()) if (entry.getValue() > 0) { spend = entry.getKey(); if (state.ledger.bossPercentOf(spend) >= 100) break; }
                state.samples.merge(spend, -1, Integer::sum);
                stats.samplesSpent++;
            }
            if (made == Serums.Kind.PRIME) state.primeThisWeek++;
            else state.craftedToday[made.ordinal()]++;
            stats.crafted[made.ordinal()]++;
            var cost = Serums.costOf(made, 1);
            stats.dust += cost.getOrDefault(MaterialId.RESONANCE_DUST, 0L);
            stats.facets += cost.getOrDefault(MaterialId.FACET, 0L);
            stats.marks += cost.getOrDefault(MaterialId.TOWER_MARK, 0L);
            int today = 0;
            for (int c : state.craftedToday) today += c;
            stats.maxCraftsSeenInADay = Math.max(stats.maxCraftsSeenInADay, today);
            stats.maxPrimeSeenInAWeek = Math.max(stats.maxPrimeSeenInAWeek, state.primeThisWeek);
        }
    }

    private boolean affords(SimPlayer player, Map<MaterialId, Long> cost) {
        long dust = player.wallet.getOrDefault(MaterialId.RESONANCE_DUST, 0L) - model.researchReserveDust;
        long facets = player.wallet.getOrDefault(MaterialId.FACET, 0L) - model.researchReserveFacets;
        long marks = player.wallet.getOrDefault(MaterialId.TOWER_MARK, 0L) - model.researchReserveMarks;
        return dust >= cost.getOrDefault(MaterialId.RESONANCE_DUST, 0L) && facets >= cost.getOrDefault(MaterialId.FACET, 0L)
                && marks >= cost.getOrDefault(MaterialId.TOWER_MARK, 0L);
    }

    /** End-of-run checks every research state must pass; null when they all hold. */
    String check() {
        for (var entry : states.entrySet()) {
            var ledger = entry.getValue().ledger;
            for (var family : familyIds) {
                int points = ledger.familyPoints(catalog, family);
                if (points < 0 || points > ResearchFamilies.FAMILY_MAX) return entry.getKey() + " family " + family + " at " + points;
            }
            for (var s : catalog.familyOfSpecies().keySet()) {
                if (ledger.pointsOf(catalog, s) > catalog.shareOf(s)) return entry.getKey() + " " + s + " over its share";
                if (ledger.bossPercentOf(s) > 100) return entry.getKey() + " " + s + " boss research over 100";
            }
            for (var n : entry.getValue().samples.values()) if (n < 0) return entry.getKey() + " holds negative Boss Samples";
        }
        if (stats.maxCraftsSeenInADay > Serums.Kind.FLOOR.limit + Serums.Kind.FOCUS.limit) return "more crafts in a day than the limits allow";
        if (stats.maxPrimeSeenInAWeek > Serums.Kind.PRIME.limit) return "more Prime crafts in a week than the limit";
        return null;
    }

    /** Totals for the report, computed at the end. */
    void finish() {
        for (var state : states.values()) {
            for (var family : familyIds) if (state.ledger.familyLevel(catalog, family) >= 4) stats.familiesAtLevel4++;
            for (var boss : bossSpecies) if (state.ledger.bossPercentOf(boss) >= 100) stats.bossComplete++;
        }
    }

    int researchers() { return states.size(); }
}
