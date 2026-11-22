package com.cobbleascend.domain.v1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Evolution families for Field Research (docs/FIELD-RESEARCH-DESIGN.md, owner decision 3): one research meter per family, worth
 * {@link #FAMILY_MAX} points, fed by every species in it, each species capped at its <b>share</b> so a player must study every stage to max the
 * family. Shares are weighted toward later stages and always sum to exactly {@link #FAMILY_MAX}.
 *
 * <p>The catalog is built from each species' pre-evolution (Cobblemon's species data at runtime; plain maps in tests), so it follows whatever
 * species the server has. It never throws on odd data: a pre-evolution that is not a known species makes the species a root, and species caught
 * in a cycle each become a family of their own; both are listed in {@link Catalog#problems()} for the log.
 */
public final class ResearchFamilies {
    /** Points a family meter holds when every species in it is fully studied. */
    public static final int FAMILY_MAX = 200;

    private ResearchFamilies() {}

    /**
     * Percent of {@link #FAMILY_MAX} each stage of an {@code n}-stage family is worth, earliest stage first (provisional, 2026-11-22): one stage
     * 100; two 40/60; three 25/30/45; longer chains (none in the main games) in proportion to the stage number.
     */
    public static int[] stageWeights(int stages) {
        if (stages < 1) throw new IllegalArgumentException("A family has at least one stage");
        return switch (stages) {
            case 1 -> new int[] {100};
            case 2 -> new int[] {40, 60};
            case 3 -> new int[] {25, 30, 45};
            default -> {
                int total = stages * (stages + 1) / 2;
                int[] weights = new int[stages];
                int used = 0;
                for (int i = 0; i < stages - 1; i++) used += weights[i] = 100 * (i + 1) / total;
                weights[stages - 1] = 100 - used;
                yield weights;
            }
        };
    }

    private static Map<String, java.util.Collection<String>> normaliseKeys(Map<String, ? extends java.util.Collection<String>> raw) {
        var out = new HashMap<String, java.util.Collection<String>>();
        raw.forEach((key, values) -> {
            String id = normalise(key);
            if (id != null && values != null) out.computeIfAbsent(id, k -> new ArrayList<>()).addAll(values);
        });
        return out;
    }

    /** Lower-case, trimmed species id; {@code null} for a blank one. */
    public static String normalise(String species) {
        if (species == null) return null;
        String id = species.trim().toLowerCase(Locale.ROOT);
        return id.isEmpty() ? null : id;
    }

    /**
     * One family: its root species and the species at each stage (stage 0 is the root). Species within a stage are kept sorted, so shares never
     * depend on the order the server listed them in.
     */
    public record Family(String id, List<List<String>> stages, Map<String, Integer> shares) {
        public Family {
            if (id == null || stages == null || stages.isEmpty()) throw new IllegalArgumentException("A family needs a root and stages");
            var copy = new ArrayList<List<String>>();
            for (var stage : stages) {
                if (stage.isEmpty()) throw new IllegalArgumentException("Empty stage in family " + id);
                var sorted = new ArrayList<>(stage);
                Collections.sort(sorted);
                copy.add(List.copyOf(sorted));
            }
            stages = List.copyOf(copy);
            shares = Collections.unmodifiableMap(new LinkedHashMap<>(computeShares(stages)));
        }

        /** Builds a family from its stages; the shares are derived. */
        public static Family of(String id, List<List<String>> stages) { return new Family(id, stages, Map.of()); }

        /** Points the species can add to this family's meter, 0 when it is not in the family. */
        public int shareOf(String species) { return shares.getOrDefault(species, 0); }

        public boolean contains(String species) { return shares.containsKey(species); }

        public Set<String> species() { return shares.keySet(); }

        private static Map<String, Integer> computeShares(List<List<String>> stages) {
            int[] weights = stageWeights(stages.size());
            var result = new LinkedHashMap<String, Integer>();
            int usedStages = 0;
            for (int s = 0; s < stages.size(); s++) {
                // Every stage but the last rounds down; the last takes what is left, so the family always sums to FAMILY_MAX.
                int stagePoints = s == stages.size() - 1 ? FAMILY_MAX - usedStages : FAMILY_MAX * weights[s] / 100;
                usedStages += stagePoints;
                var members = stages.get(s);
                int each = stagePoints / members.size();
                for (int m = 0; m < members.size(); m++) {
                    int points = m == members.size() - 1 ? stagePoints - each * (members.size() - 1) : each;
                    if (result.put(members.get(m), points) != null) throw new IllegalArgumentException("Species " + members.get(m) + " appears twice");
                }
            }
            return result;
        }
    }

    /** Every family the server has, and each species' family. */
    public record Catalog(Map<String, Family> families, Map<String, String> familyOfSpecies, List<String> problems) {
        public Catalog {
            families = Collections.unmodifiableMap(new TreeMap<>(families));
            familyOfSpecies = Collections.unmodifiableMap(new TreeMap<>(familyOfSpecies));
            problems = List.copyOf(problems);
        }

        public Optional<Family> familyOf(String species) {
            String id = familyOfSpecies.get(normalise(species));
            return id == null ? Optional.empty() : Optional.of(families.get(id));
        }

        public Optional<Family> family(String familyId) { return Optional.ofNullable(families.get(normalise(familyId))); }

        /** The species' share of its family, 0 for an unknown species. */
        public int shareOf(String species) {
            String id = normalise(species);
            return familyOf(id).map(f -> f.shareOf(id)).orElse(0);
        }

        public boolean knows(String species) { return familyOfSpecies.containsKey(normalise(species)); }
    }

    /**
     * Builds the catalog from each species' pre-evolution ({@code species -> preEvolution}, absent or blank for a root). Only keys are species: a
     * pre-evolution that is not also a key is unknown, and the species naming it becomes a root. Never throws for bad data (see the class comment);
     * throws only for a null map.
     */
    public static Catalog fromPreEvolutions(Map<String, String> preEvolutionOf) {
        return fromLinks(preEvolutionOf, Map.of());
    }

    /**
     * Builds the catalog from both ways a species' data can link an evolution, since datapacks and addon mods use either or both: a species'
     * declared pre-evolution ({@code species -> preEvolution}, whose keys are every species the server has) and what each species says it
     * evolves into ({@code species -> results}). A species' parent is its declared pre-evolution when that is a known species; otherwise the
     * known species that lists it as an evolution result (the smallest id when several do, reported). Links to unknown species (not
     * implemented, disabled, from a mod that is not installed) are ignored and reported, so a family only holds species that exist and its
     * meter can always be filled. Never throws for bad data.
     */
    public static Catalog fromLinks(Map<String, String> preEvolutionOf, Map<String, ? extends java.util.Collection<String>> evolvesInto) {
        var declared = new HashMap<String, String>();
        for (var entry : preEvolutionOf.entrySet()) {
            String species = normalise(entry.getKey());
            if (species == null) continue;
            String pre = normalise(entry.getValue());
            declared.put(species, pre != null && !pre.equals(species) ? pre : null);
        }
        var problems = new ArrayList<String>();
        // Parents named from the other side: who lists this species as an evolution result.
        var listedBy = new HashMap<String, java.util.TreeSet<String>>();
        if (evolvesInto != null) {
            for (var entry : new java.util.TreeMap<String, java.util.Collection<String>>(normaliseKeys(evolvesInto)).entrySet()) {
                String from = entry.getKey();
                if (!declared.containsKey(from)) continue;
                for (var raw : entry.getValue()) {
                    String to = normalise(raw);
                    if (to == null || to.equals(from)) continue;
                    if (!declared.containsKey(to)) {
                        problems.add(from + ": evolution " + to + " is not a known species; ignored");
                        continue;
                    }
                    listedBy.computeIfAbsent(to, k -> new java.util.TreeSet<>()).add(from);
                }
            }
        }
        var parent = new HashMap<String, String>();
        for (var species : new java.util.TreeSet<>(declared.keySet())) {
            String pre = declared.get(species);
            if (pre != null && !declared.containsKey(pre)) {
                problems.add(species + ": pre-evolution " + pre + " is not a known species; ignored");
                pre = null;
            }
            var listers = listedBy.getOrDefault(species, new java.util.TreeSet<>());
            if (pre == null && !listers.isEmpty()) {
                pre = listers.first();
                if (listers.size() > 1) problems.add(species + ": listed as an evolution of " + listers + "; joined to " + pre);
            } else if (pre != null && !listers.isEmpty() && !listers.contains(pre)) {
                problems.add(species + ": pre-evolution " + pre + " but listed as an evolution of " + listers + "; the pre-evolution wins");
            }
            parent.put(species, pre);
        }
        // Pass 1: find every species on a cycle (walking up from it returns to it) and cut its link upward, which breaks every cycle; each
        // such species becomes a root, and anything below it still hangs from it.
        var inCycle = new java.util.TreeSet<String>();
        for (var species : new java.util.TreeSet<>(parent.keySet())) {
            String at = parent.get(species);
            for (int steps = 0; at != null && steps <= parent.size(); steps++) {
                if (at.equals(species)) { inCycle.add(species); break; }
                at = parent.get(at);
            }
        }
        if (!inCycle.isEmpty()) problems.add("evolution cycle through " + String.join(", ", inCycle) + "; each treated as a root");
        for (var species : inCycle) parent.put(species, null);
        // Pass 2: the graph is a forest now; walk each species up to its root, counting the stages.
        var root = new HashMap<String, String>();
        var depth = new HashMap<String, Integer>();
        for (var species : parent.keySet()) {
            int d = 0;
            String at = species;
            while (parent.get(at) != null) { at = parent.get(at); d++; }
            root.put(species, at);
            depth.put(species, d);
        }
        var stagesByRoot = new TreeMap<String, TreeMap<Integer, List<String>>>();
        for (var species : root.keySet()) {
            stagesByRoot.computeIfAbsent(root.get(species), k -> new TreeMap<>())
                    .computeIfAbsent(depth.get(species), k -> new ArrayList<>()).add(species);
        }
        var families = new HashMap<String, Family>();
        var familyOf = new HashMap<String, String>();
        for (var entry : stagesByRoot.entrySet()) {
            var stages = new ArrayList<List<String>>(entry.getValue().values());
            families.put(entry.getKey(), Family.of(entry.getKey(), stages));
            for (var stage : stages) for (var s : stage) familyOf.put(s, entry.getKey());
        }
        return new Catalog(families, familyOf, problems);
    }
}
