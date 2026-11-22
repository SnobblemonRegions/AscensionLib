package com.cobbleascend.domain.v1;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * One player's Field Research, as stored (tables {@code research_awards}, owner decision 17): points per species and boss research per species.
 * Immutable; every change returns a new ledger and what it did, for the store to write in one transaction.
 *
 * <p>Invariants: points are never negative and a species never holds more than its share (excess is reported as wasted and not stored), so a
 * family meter never exceeds {@link ResearchFamilies#FAMILY_MAX}; boss research stays within 0 to {@link FieldResearch#BOSS_MAX_PERCENT}. Research
 * is never spent (decision 4), so nothing here lowers a value. A family's points are always recomputed from the species, capped by the
 * <i>current</i> shares, so a catalog that changes (a Cobblemon update adds an evolution) can never leave a meter above its maximum.
 */
public record ResearchLedger(Map<String, Integer> speciesPoints, Map<String, Integer> bossPercent) {
    public static final ResearchLedger EMPTY = new ResearchLedger(Map.of(), Map.of());

    public ResearchLedger {
        speciesPoints = clean(speciesPoints, Integer.MAX_VALUE);
        bossPercent = clean(bossPercent, FieldResearch.BOSS_MAX_PERCENT);
    }

    private static Map<String, Integer> clean(Map<String, Integer> raw, int max) {
        var out = new TreeMap<String, Integer>();
        if (raw != null) {
            raw.forEach((species, value) -> {
                String id = ResearchFamilies.normalise(species);
                if (id != null && value != null && value > 0) out.merge(id, Math.min(value, max), (a, b) -> Math.min(max, Math.max(a, b)));
            });
        }
        return Collections.unmodifiableMap(out);
    }

    /** Points the species holds toward its family, capped at its current share. */
    public int pointsOf(ResearchFamilies.Catalog catalog, String species) {
        String id = ResearchFamilies.normalise(species);
        return Math.min(speciesPoints.getOrDefault(id, 0), catalog.shareOf(id));
    }

    /** The family meter: the sum of every member's capped points, 0 to {@link ResearchFamilies#FAMILY_MAX}. */
    public int familyPoints(ResearchFamilies.Catalog catalog, String familyId) {
        var family = catalog.family(familyId);
        if (family.isEmpty()) return 0;
        int sum = 0;
        for (var species : family.get().species()) sum += Math.min(speciesPoints.getOrDefault(species, 0), family.get().shareOf(species));
        return Math.min(sum, ResearchFamilies.FAMILY_MAX);
    }

    public int familyLevel(ResearchFamilies.Catalog catalog, String familyId) {
        return FieldResearch.levelOf(familyPoints(catalog, familyId));
    }

    /** The level of the family the species belongs to; 0 for an unknown species. */
    public int levelForSpecies(ResearchFamilies.Catalog catalog, String species) {
        return catalog.familyOf(species).map(f -> familyLevel(catalog, f.id())).orElse(0);
    }

    /** The highest level the player holds on any family (crafting a serum needs its level on at least one, see {@link Serums}). */
    public int highestLevel(ResearchFamilies.Catalog catalog) {
        int best = 0;
        var families = new java.util.HashSet<String>();
        for (var species : speciesPoints.keySet()) catalog.familyOf(species).ifPresent(f -> families.add(f.id()));
        for (var family : families) best = Math.max(best, familyLevel(catalog, family));
        return best;
    }

    /** How many more points the species can take before its share is full. */
    public int roomFor(ResearchFamilies.Catalog catalog, String species) {
        return Math.max(0, catalog.shareOf(species) - pointsOf(catalog, species));
    }

    public int bossPercentOf(String species) { return bossPercent.getOrDefault(ResearchFamilies.normalise(species), 0); }

    /** What a credit did: the new ledger, the points that counted, the points wasted against a full share, and the family's level before and after. */
    public record Credit(ResearchLedger ledger, int applied, int wasted, int levelBefore, int levelAfter) {
        public boolean levelledUp() { return levelAfter > levelBefore; }
    }

    /**
     * Adds research to a species. Throws for an unknown species or an amount outside 1..{@link FieldResearch#MAX_GRANT} (the caller validates
     * first; a throw means a bug, and nothing is written). A full share is not an error: the points are reported as wasted and the ledger is
     * returned unchanged.
     */
    public Credit credit(ResearchFamilies.Catalog catalog, String species, int amount) {
        String id = ResearchFamilies.normalise(species);
        var family = catalog.familyOf(id).orElseThrow(() -> new IllegalArgumentException("Unknown species for research: " + species));
        if (amount < 1 || amount > FieldResearch.MAX_GRANT) throw new IllegalArgumentException("Research grant out of range: " + amount);
        int before = familyLevel(catalog, family.id());
        int room = roomFor(catalog, id);
        int applied = Math.min(room, amount);
        if (applied == 0) return new Credit(this, 0, amount, before, before);
        var points = new TreeMap<>(speciesPoints);
        points.put(id, pointsOf(catalog, id) + applied);
        var next = new ResearchLedger(points, bossPercent);
        return new Credit(next, applied, amount - applied, before, next.familyLevel(catalog, family.id()));
    }

    /** The result of analyzing one Boss Sample. */
    public record Analysis(ResearchLedger ledger, int percentBefore, int percentAfter) {}

    /**
     * Analyzes one Boss Sample of the species: +{@link FieldResearch#BOSS_PERCENT_PER_SAMPLE}%, capped at 100. Throws when the species is already
     * at 100%, so the caller refuses before consuming a sample (a sample is never burnt for nothing).
     */
    public Analysis analyze(ResearchFamilies.Catalog catalog, String species) {
        String id = ResearchFamilies.normalise(species);
        if (!catalog.knows(id)) throw new IllegalArgumentException("Unknown species for boss research: " + species);
        int before = bossPercentOf(id);
        if (before >= FieldResearch.BOSS_MAX_PERCENT) throw new IllegalStateException("Boss research on " + id + " is already complete");
        int after = Math.min(FieldResearch.BOSS_MAX_PERCENT, before + FieldResearch.BOSS_PERCENT_PER_SAMPLE);
        var boss = new TreeMap<>(bossPercent);
        boss.put(id, after);
        return new Analysis(new ResearchLedger(speciesPoints, boss), before, after);
    }
}
