package com.ascensionlib.research;

import com.cobbleascend.domain.v1.ResearchFamilies;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Field Research's family catalog, built from the species the server actually has: Cobblemon's own, any a datapack adds or changes and any
 * an addon mod registers, rebuilt whenever data packs reload (docs/FIELD-RESEARCH-DESIGN.md, "Species from datapacks and addons").
 *
 * <ul>
 *   <li><b>Ids.</b> A Cobblemon species is its bare path ({@code garchomp}); a species from any other namespace keeps it ({@code mymod:fakemon}),
 *       so two mods' species of the same name never share research. {@link #speciesId} turns any id into this form; {@link #resolve} also
 *       matches a bare name to the one species of that name in another namespace, when exactly one exists.</li>
 *   <li><b>Only implemented species</b> count, so every member of a family can be caught and a family meter can always be filled; a family
 *       simply has fewer members while a species is not implemented or its mod is missing.</li>
 *   <li><b>Both link directions.</b> Datapacks and addons declare evolutions as a child's {@code preEvolution}, as a parent's
 *       {@code evolutions} list, or both; both are read ({@link ResearchFamilies#fromLinks}), and conflicts are logged.</li>
 *   <li>Research stored for a species that later disappears stays in the store but counts toward nothing until it comes back.</li>
 * </ul>
 */
public final class ResearchCatalog {
    private static final Logger LOG = LoggerFactory.getLogger("AscensionLib/Research");
    public static final String COBBLEMON = "cobblemon";
    private static volatile ResearchFamilies.Catalog catalog = ResearchFamilies.fromPreEvolutions(Map.of());
    private static volatile Map<String, Set<String>> labels = Map.of();
    /** Bare name -> every catalog id with that name, for {@link #resolve}. */
    private static volatile Map<String, List<String>> byName = Map.of();

    private ResearchCatalog() {}

    /** The current catalog (empty until the server has started). */
    public static ResearchFamilies.Catalog get() { return catalog; }

    /**
     * A species id in catalog form: lower case; a Cobblemon species without its namespace, any other species with it. A bare id is taken as
     * Cobblemon's. Null for a blank id or an empty path.
     */
    public static String speciesId(String raw) {
        if (raw == null) return null;
        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) return null;
        int colon = id.indexOf(':');
        if (colon < 0) return id;
        String namespace = id.substring(0, colon), path = id.substring(colon + 1);
        if (path.isEmpty()) return null;
        return namespace.isEmpty() || namespace.equals(COBBLEMON) ? path : namespace + ":" + path;
    }

    /** {@link #speciesId} as the catalog form of a namespace and a path. */
    public static String speciesId(String namespace, String path) {
        return speciesId(namespace + ":" + path);
    }

    /**
     * An id from outside (a raid boss, a command, an evolution result) as a species the catalog knows: its catalog form when known; else, for a
     * bare name, the one species of that name in another namespace (none or several: empty).
     */
    public static java.util.Optional<String> resolve(String raw) {
        return resolve(raw, catalog::knows, byName);
    }

    static java.util.Optional<String> resolve(String raw, java.util.function.Predicate<String> known, Map<String, List<String>> index) {
        String id = speciesId(raw);
        if (id == null) return java.util.Optional.empty();
        if (known.test(id)) return java.util.Optional.of(id);
        if (id.indexOf(':') >= 0) return java.util.Optional.empty();
        var candidates = index.getOrDefault(id, List.of());
        return candidates.size() == 1 ? java.util.Optional.of(candidates.get(0)) : java.util.Optional.empty();
    }

    /** Cobblemon's labels for a species (legendary, mythical, ultra_beast, ...); empty for an unknown one. */
    public static Set<String> labelsOf(String species) {
        return labels.getOrDefault(resolve(species).orElse(""), Set.of());
    }

    public static boolean legendary(String species) { return labelsOf(species).contains("legendary"); }

    public static boolean mythical(String species) { return labelsOf(species).contains("mythical"); }

    public static boolean ultraBeast(String species) { return labelsOf(species).contains("ultra_beast"); }

    /** One species as the catalog needs it: its id, its declared pre-evolution, what it evolves into, and its labels. */
    record Entry(String id, String preEvolution, List<String> evolvesInto, Set<String> labels) {}

    /** Rebuilds from Cobblemon's species. Never throws: a failure keeps the previous catalog and is logged. */
    public static void rebuild() {
        try {
            var entries = new ArrayList<Entry>();
            int skipped = 0;
            for (var species : PokemonSpecies.getSpecies()) {
                if (!species.getImplemented()) { skipped++; continue; }
                var location = species.getResourceIdentifier();
                String id = speciesId(location.getNamespace(), location.getPath());
                if (id == null) continue;
                var preEvolution = species.getPreEvolution();
                String pre = null;
                if (preEvolution != null && preEvolution.getSpecies() != null) {
                    var preLocation = preEvolution.getSpecies().getResourceIdentifier();
                    pre = speciesId(preLocation.getNamespace(), preLocation.getPath());
                }
                var results = new ArrayList<String>();
                for (var evolution : species.getEvolutions()) {
                    var result = evolution.getResult();
                    if (result != null && result.getSpecies() != null) results.add(result.getSpecies());
                }
                entries.add(new Entry(id, pre, results, Set.copyOf(species.getLabels())));
            }
            var built = build(entries);
            catalog = built.catalog();
            labels = built.labels();
            byName = built.byName();
            LOG.info("Field Research: {} species in {} families ({} not implemented, left out)", catalog.familyOfSpecies().size(),
                    catalog.families().size(), skipped);
            for (var problem : catalog.problems()) LOG.warn("Field Research species data: {}", problem);
        } catch (RuntimeException | LinkageError failure) {
            LOG.error("Field Research could not read Cobblemon's species; keeping the previous catalog", failure);
        }
    }

    record Built(ResearchFamilies.Catalog catalog, Map<String, Set<String>> labels, Map<String, List<String>> byName) {}

    /** The catalog from plain entries (the Minecraft-free part of {@link #rebuild}, so it can be tested). */
    static Built build(List<Entry> entries) {
        var pre = new HashMap<String, String>();
        var tags = new HashMap<String, Set<String>>();
        var names = new HashMap<String, List<String>>();
        for (var entry : entries) {
            pre.put(entry.id(), entry.preEvolution());
            tags.put(entry.id(), entry.labels());
            String name = entry.id().substring(entry.id().indexOf(':') + 1);
            names.computeIfAbsent(name, k -> new ArrayList<>()).add(entry.id());
        }
        names.values().forEach(list -> list.sort(String::compareTo));
        // Evolution results name species the way the data wrote them (often bare): resolve each against the species being built.
        var evolves = new HashMap<String, List<String>>();
        for (var entry : entries) {
            var targets = new ArrayList<String>();
            for (var raw : entry.evolvesInto()) targets.add(resolve(raw, pre::containsKey, names).orElse(speciesId(raw)));
            evolves.put(entry.id(), targets);
        }
        // A declared pre-evolution written bare for another namespace's species resolves the same way.
        pre.replaceAll((id, p) -> p == null ? null : resolve(p, pre::containsKey, names).orElse(p));
        var immutableNames = new HashMap<String, List<String>>();
        names.forEach((k, v) -> immutableNames.put(k, List.copyOf(v)));
        return new Built(ResearchFamilies.fromLinks(pre, evolves), Map.copyOf(tags), Map.copyOf(immutableNames));
    }
}
