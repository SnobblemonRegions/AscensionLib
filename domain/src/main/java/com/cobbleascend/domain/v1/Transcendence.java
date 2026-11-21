package com.cobbleascend.domain.v1;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The fusion recipe book (docs/FUSION-DESIGN.md). Given the host Pokemon (the left slot, which is kept) and the donor (the right
 * slot, which is consumed) with the Unique each holds, it resolves the Transcendent they make. Pure and deterministic: the same
 * inputs always give the same result, and the order matters (the host's type shapes the offence, the donor's the defence).
 *
 * <p>How a result is chosen, most specific first: (1) a curated pair recipe naming the two species, (2) a curated group both
 * species belong to, (3) a derived recipe from the two species' lore. Every result starts from the base Transcendent of the two
 * Uniques (21 of them, one per pair), takes a {@link Harmony} that sets how much of each Unique's benefit and drawback survives,
 * and is named from the lore. The battle module and the profile store consume the result; this class holds no state.
 */
public final class Transcendence {
    /** How well the two Pokemon fit, which sets the share of each Unique's benefit kept and of each drawback applied. */
    public enum Harmony { LINEAGE, PURE, KINDRED, NEUTRAL, OPPOSED }

    public record Scale(int benefitPercent, int drawbackPercent) {}

    private record Base(String id, String name, String blurb, Set<String> uniques) {}

    private record Recipe(String id, String name, Harmony harmony, String blurb, Twist twist) {}

    /** The pulse a signature is built around. */
    public enum Pulse { HIT, STRUCK, KO, LAST, TICK }

    /** A signature: the new power a pair of Uniques makes (its code lives in the battle module; this is its data). */
    public record Signature(String id, Pulse pulse, String core, String clutch, String drawback) {}

    /** What fires on every pulse: a name and one or two effects. {@code source} says whether it came from the host motif or a recipe. */
    public record Twist(String name, List<PulseOp> effects, Source source) {
        public enum Source { MOTIF, RECIPE }
    }

    /** The standing effect the donor adds: an existing affix at a small value. */
    public record Rider(String affix, int value) {}

    /** A resolved fusion. {@code recipeId} is null for a derived recipe. */
    public record Transcendent(String id, String name, List<String> uniqueIds, String hostType, String donorType, Harmony harmony,
                               int benefitPercent, int drawbackPercent, String recipeId, String blurb,
                               Signature signature, Twist twist, Rider rider) {
        public Transcendent {
            uniqueIds = List.copyOf(uniqueIds);
        }

        /** The frozen form a battle consumes: the signature, the twist, the rider, the shares kept, and the host and donor types. */
        public CombatSnapshot.Fused toFused() {
            return new CombatSnapshot.Fused(uniqueIds, benefitPercent, drawbackPercent, hostType, donorType,
                    signature.id(), twist.effects(), rider.affix(), rider.value());
        }
    }

    private final LoreCatalog lore;
    private final RankedRules rules;
    private final Map<Harmony, Scale> scales = new LinkedHashMap<>();
    private final Map<String, Base> bases = new LinkedHashMap<>();
    private final Map<String, Signature> signatures = new LinkedHashMap<>();
    private final Map<String, Twist> motifTwists = new LinkedHashMap<>();
    private final Map<String, Rider> motifRiders = new LinkedHashMap<>();
    private final Map<String, Recipe> pairs = new LinkedHashMap<>();
    private final List<Map.Entry<Set<String>, Recipe>> groups = new ArrayList<>();

    public Transcendence(LoreCatalog lore, RankedRules rules, JsonObject book) {
        this.lore = lore;
        this.rules = rules;
        if (Json.integer(book.get("schemaVersion")) != 2) throw new IllegalArgumentException("Unknown transcendent schema");
        for (var entry : Json.object(book.get("scales")).entrySet()) {
            var object = Json.object(entry.getValue());
            var scale = new Scale(Json.integer(object.get("benefit")), Json.integer(object.get("drawback")));
            if (scale.benefitPercent() < 1 || scale.benefitPercent() > 100 || scale.drawbackPercent() < 1 || scale.drawbackPercent() > 100)
                throw new IllegalArgumentException("Scale out of range: " + entry.getKey());
            scales.put(Harmony.valueOf(entry.getKey()), scale);
        }
        for (var harmony : Harmony.values()) {
            if (!scales.containsKey(harmony)) throw new IllegalArgumentException("Missing scale for " + harmony);
        }
        for (var element : Json.array(book.get("bases"))) {
            var object = Json.object(element);
            var uniqueIds = new java.util.TreeSet<String>();
            for (var id : Json.array(object.get("uniques"))) uniqueIds.add(Json.string(id));
            if (uniqueIds.size() != 2) throw new IllegalArgumentException("A base needs two different Uniques");
            for (var id : uniqueIds) {
                if (rules.unique(id).isEmpty()) throw new IllegalArgumentException("Unknown Unique in a base: " + id);
            }
            var base = new Base(Json.string(object.get("id")), Json.string(object.get("name")), Json.string(object.get("blurb")), uniqueIds);
            if (bases.put(String.join("+", uniqueIds), base) != null) throw new IllegalArgumentException("Duplicate base for " + uniqueIds);
        }
        int expected = rules.uniques().size() * (rules.uniques().size() - 1) / 2;
        if (bases.size() != expected) throw new IllegalArgumentException("Every pair of Uniques needs a base: " + bases.size() + " of " + expected);
        for (var element : Json.array(book.get("signatures"))) {
            var object = Json.object(element);
            var signature = new Signature(Json.string(object.get("id")), Pulse.valueOf(Json.string(object.get("pulse"))),
                    Json.string(object.get("core")), Json.string(object.get("clutch")), Json.string(object.get("drawback")));
            if (signatures.put(signature.id(), signature) != null) throw new IllegalArgumentException("Duplicate signature " + signature.id());
        }
        for (var base : bases.values()) {
            if (!signatures.containsKey(base.id())) throw new IllegalArgumentException("Base without a signature: " + base.id());
        }
        if (signatures.size() != bases.size()) throw new IllegalArgumentException("Signatures and bases must match one to one");
        for (var entry : Json.object(book.get("twists")).entrySet()) {
            motifTwists.put(entry.getKey(), twist(Json.object(entry.getValue()), Twist.Source.MOTIF));
        }
        for (var entry : Json.object(book.get("riders")).entrySet()) {
            var object = Json.object(entry.getValue());
            var rider = new Rider(Json.string(object.get("affix")), Json.integer(object.get("value")));
            rules.affix(rider.affix());   // throws for an affix that is not in the catalog
            if (rider.value() < 1 || rider.value() > 20) throw new IllegalArgumentException("Rider value out of range: " + entry.getKey());
            motifRiders.put(entry.getKey(), rider);
        }
        for (var motif : lore.motifs().keySet()) {
            if (!motifTwists.containsKey(motif) || !motifRiders.containsKey(motif))
                throw new IllegalArgumentException("Motif without a twist and a rider: " + motif);
        }
        for (var element : Json.array(book.get("pairs"))) {
            var object = Json.object(element);
            var members = species(object.get("species"));
            if (members.size() != 2) throw new IllegalArgumentException("A pair recipe needs two species");
            var recipe = recipe(object);
            if (pairs.put(pairKey(members.get(0), members.get(1)), recipe) != null)
                throw new IllegalArgumentException("Duplicate pair recipe " + recipe.id());
        }
        for (var element : Json.array(book.get("groups"))) {
            var object = Json.object(element);
            var members = species(object.get("members"));
            if (members.size() < 2) throw new IllegalArgumentException("A group needs at least two species");
            groups.add(Map.entry(Set.copyOf(members), recipe(object)));
        }
    }

    /** Parsed once and shared: the book is immutable and holds the lore of 1025 species, so building it per call would be wasteful. */
    public static Transcendence shared() {
        return Shared.INSTANCE;
    }

    private static final class Shared {
        static final Transcendence INSTANCE = defaults();
    }

    /** A fresh parse of the bundled book (tests, tooling). Game code should use {@link #shared()}. */
    public static Transcendence defaults() {
        var stream = Transcendence.class.getResourceAsStream("/cobbleascend/transcendents.json");
        if (stream == null) throw new IllegalStateException("Missing transcendents resource");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return new Transcendence(LoreCatalog.defaults(), RankedRules.defaults(), JsonParser.parseReader(reader).getAsJsonObject());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot read transcendents resource", exception);
        }
    }

    private Recipe recipe(JsonObject object) {
        return new Recipe(Json.string(object.get("id")), Json.string(object.get("name")),
                Harmony.valueOf(Json.string(object.get("harmony"))), Json.string(object.get("blurb")),
                twist(Json.object(object.get("twist")), Twist.Source.RECIPE));
    }

    private static Twist twist(JsonObject object, Twist.Source source) {
        var effects = new ArrayList<PulseOp>();
        for (var effect : Json.array(object.get("effects"))) effects.add(PulseOp.fromJson(Json.object(effect)));
        if (effects.isEmpty() || effects.size() > 2) throw new IllegalArgumentException("A twist has one or two effects");
        return new Twist(Json.string(object.get("name")), effects, source);
    }

    private List<String> species(com.google.gson.JsonElement element) {
        var list = new ArrayList<String>();
        for (var item : Json.array(element)) {
            var id = Json.string(item);
            if (lore.species(id).isEmpty()) throw new IllegalArgumentException("Recipe names an unknown species: " + id);
            list.add(id);
        }
        if (Set.copyOf(list).size() != list.size()) throw new IllegalArgumentException("Repeated species in a recipe");
        return list;
    }

    private static String pairKey(String a, String b) { return a.compareTo(b) <= 0 ? a + "+" + b : b + "+" + a; }

    /** A named recipe this species belongs to, and one partner species that completes it. */
    public record RecipeHint(String recipeName, String partnerSpecies) {}

    /**
     * The named recipes a species belongs to, for a hint on screen: each pair recipe with its partner, then each group with up to
     * {@code perGroup} of its other members (groups are large; the player only needs a lead). Species ids are Cobblemon ids without a
     * namespace. Empty for a species in no recipe.
     */
    public List<RecipeHint> recipesFor(String species, int perGroup) {
        var out = new ArrayList<RecipeHint>();
        for (var entry : pairs.entrySet()) {
            String[] members = entry.getKey().split("\\+", 2);
            if (members.length == 2 && (members[0].equals(species) || members[1].equals(species)))
                out.add(new RecipeHint(entry.getValue().name(), members[0].equals(species) ? members[1] : members[0]));
        }
        for (var group : groups) {
            if (!group.getKey().contains(species)) continue;
            group.getKey().stream().filter(other -> !other.equals(species)).sorted().limit(Math.max(0, perGroup))
                    .forEach(other -> out.add(new RecipeHint(group.getValue().name(), other)));
        }
        return out;
    }

    public Scale scale(Harmony harmony) { return scales.get(harmony); }
    public int baseCount() { return bases.size(); }
    public int pairRecipeCount() { return pairs.size(); }
    public int groupRecipeCount() { return groups.size(); }
    public Map<String, Signature> signatures() { return java.util.Collections.unmodifiableMap(signatures); }
    public Map<String, Twist> motifTwists() { return java.util.Collections.unmodifiableMap(motifTwists); }
    public Map<String, Rider> motifRiders() { return java.util.Collections.unmodifiableMap(motifRiders); }

    /**
     * The Transcendent made by fusing {@code donor} into {@code host}.
     *
     * @throws CraftException UNKNOWN_SPECIES for a species the lore does not know, UNKNOWN_UNIQUE for a Unique the catalog does not,
     *                        SAME_UNIQUE when both hold the same one
     */
    public Transcendent resolve(String hostSpecies, String hostUniqueId, String donorSpecies, String donorUniqueId) {
        var host = lore.species(hostSpecies).orElseThrow(() -> new CraftException(CraftException.Reason.UNKNOWN_SPECIES, "Unknown species " + hostSpecies));
        var donor = lore.species(donorSpecies).orElseThrow(() -> new CraftException(CraftException.Reason.UNKNOWN_SPECIES, "Unknown species " + donorSpecies));
        for (var unique : List.of(hostUniqueId, donorUniqueId)) {
            if (rules.unique(unique).isEmpty()) throw new CraftException(CraftException.Reason.UNKNOWN_UNIQUE, "Unknown Unique " + unique);
        }
        if (hostUniqueId.equals(donorUniqueId))
            throw new CraftException(CraftException.Reason.SAME_UNIQUE, "A fusion needs two different Uniques");
        var uniqueIds = new java.util.TreeSet<>(List.of(hostUniqueId, donorUniqueId));
        var base = bases.get(String.join("+", uniqueIds));

        var curated = Optional.ofNullable(pairs.get(pairKey(hostSpecies, donorSpecies)));
        if (curated.isEmpty()) {
            for (var group : groups) {
                if (!hostSpecies.equals(donorSpecies) && group.getKey().contains(hostSpecies) && group.getKey().contains(donorSpecies)) {
                    curated = Optional.of(group.getValue());
                    break;
                }
            }
        }
        var harmony = curated.map(Recipe::harmony).orElseGet(() -> harmonyOf(host, donor));
        var scale = scales.get(harmony);
        var hostMotif = lore.motif(host.primaryMotif());
        var donorMotif = lore.motif(donor.primaryMotif());

        String name;
        String blurb;
        String recipeId = null;
        if (curated.isPresent()) {
            var recipe = curated.get();
            recipeId = recipe.id();
            name = base.name() + " of " + recipe.name();
            blurb = recipe.blurb() + " " + base.blurb();
        } else {
            name = harmony == Harmony.LINEAGE
                    ? base.name() + " of the Bloodline"
                    : hostMotif.adjective() + " " + base.name() + " of the " + donorMotif.noun();
            blurb = String.format("%s (%s) takes in %s (%s). %s", host.name(), host.basis(), donor.name(), donor.basis(), base.blurb());
        }
        var twist = curated.map(Recipe::twist).orElseGet(() -> motifTwists.get(host.primaryMotif()));
        var rider = motifRiders.get(donor.primaryMotif());
        return new Transcendent(base.id(), name, List.copyOf(uniqueIds), host.primaryType(), donor.primaryType(), harmony,
                scale.benefitPercent(), scale.drawbackPercent(), recipeId, blurb, signatures.get(base.id()), twist, rider);
    }

    /** How well two species fit by their lore alone (no curated recipe). */
    public Harmony harmonyOf(LoreCatalog.SpeciesLore host, LoreCatalog.SpeciesLore donor) {
        if (host.family() == donor.family()) return Harmony.LINEAGE;
        var a = host.primaryMotif();
        var b = donor.primaryMotif();
        if (a.equals(b)) return Harmony.PURE;
        if (lore.isOpposed(a, b)) return Harmony.OPPOSED;
        if (lore.isKindred(a, b)) return Harmony.KINDRED;
        for (var x : host.motifs()) {
            if (donor.motifs().contains(x)) return Harmony.KINDRED;
        }
        return Harmony.NEUTRAL;
    }
}
