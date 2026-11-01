package com.cobbleascend.domain;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.random.RandomGenerator;

/** Validated, immutable rules loaded from the same proposal data used by the balance tool. */
public final class Rules {
    public static final List<String> TYPES = List.of("normal", "fire", "water", "electric", "grass",
            "ice", "fighting", "poison", "ground", "flying", "psychic", "bug", "rock", "ghost",
            "dragon", "dark", "steel", "fairy");
    public record Tier(Rarity rarity, int weight, int prefixSlots, int suffixSlots) {}
    public record Promotion(Rarity from, Rarity to, Cost cost, int attunement) {}

    private final Map<Rarity, Tier> tiers;
    private final Map<String, AffixDefinition> affixes;
    private final Map<Rarity, Promotion> promotions;
    /** Promotion prices while the Threat system is on (balance.json "threatPromotions"); the same as {@link #promotions} when absent. */
    private final Map<Rarity, Cost> threatPromotionCosts;
    private final Map<String, Integer> caps;
    private final Map<String, Cost> craftCosts;
    private final int totalWeight;

    public Rules(List<Tier> tiers, List<AffixDefinition> affixes, List<Promotion> promotions,
                 Map<String, Integer> caps, Map<String, Cost> craftCosts) {
        this(tiers, affixes, promotions, caps, craftCosts, Map.of());
    }

    /** @param threatPromotionCosts the price of each promotion (keyed by the rarity it starts from) with Threat on; empty keeps the normal prices */
    public Rules(List<Tier> tiers, List<AffixDefinition> affixes, List<Promotion> promotions,
                 Map<String, Integer> caps, Map<String, Cost> craftCosts, Map<Rarity, Cost> threatPromotionCosts) {
        var tierMap = new EnumMap<Rarity, Tier>(Rarity.class);
        for (var tier : tiers) {
            if (tier.weight() < 1 || tier.weight() > 10000 || tier.prefixSlots() < 0
                    || tier.prefixSlots() > 3 || tier.suffixSlots() < 0 || tier.suffixSlots() > 3
                    || tierMap.put(tier.rarity(), tier) != null)
                throw new IllegalArgumentException("Invalid or duplicate tier");
        }
        if (tierMap.size() != Rarity.values().length) throw new IllegalArgumentException("Missing tiers");
        this.tiers = Collections.unmodifiableMap(tierMap);
        totalWeight = tiers.stream().mapToInt(Tier::weight).sum();
        var affixMap = new LinkedHashMap<String, AffixDefinition>();
        for (var affix : affixes) {
            if (affixMap.put(affix.id(), affix) != null) throw new IllegalArgumentException("Duplicate affix");
        }
        this.affixes = Collections.unmodifiableMap(affixMap);
        this.caps = Map.copyOf(caps);
        if (!this.caps.keySet().equals(Set.of("outgoingDamage", "incomingReduction", "healing", "residual", "mechanic"))
                || this.caps.values().stream().anyMatch(cap -> cap < 0 || cap > 100)
                || this.caps.get("incomingReduction") > 90)
            throw new IllegalArgumentException("Invalid combat channel caps");
        for (var affix : affixes) {
            if (!this.caps.containsKey(affix.channel())) throw new IllegalArgumentException("Unknown channel");
        }
        for (var rarity : Rarity.values()) {
            var tier = tier(rarity);
            if (tier.prefixSlots() + tier.suffixSlots() != rarity.ordinal() + 1)
                throw new IllegalArgumentException("Tier slot total must match progression");
            for (var slot : List.of("prefix", "suffix")) {
                long families = affixes.stream().filter(a -> a.slot().equals(slot)).map(AffixDefinition::family).distinct().count();
                if (families < slots(tier, slot)) throw new IllegalArgumentException("Insufficient " + slot + " families");
            }
        }
        var promotionMap = new EnumMap<Rarity, Promotion>(Rarity.class);
        int previousAttunement = -1;
        for (int i = 0; i < Rarity.values().length - 1; i++) {
            Rarity from = Rarity.values()[i], to = Rarity.values()[i + 1];
            var matches = promotions.stream().filter(p -> p.from() == from).toList();
            if (matches.size() != 1) throw new IllegalArgumentException("Missing or duplicate promotion");
            var p = matches.getFirst();
            if (p.to() != to || p.cost() == null || p.attunement() <= previousAttunement
                    || tier(to).prefixSlots() < tier(from).prefixSlots()
                    || tier(to).suffixSlots() < tier(from).suffixSlots())
                throw new IllegalArgumentException("Invalid promotion chain");
            previousAttunement = p.attunement();
            promotionMap.put(from, p);
        }
        if (promotions.size() != 5) throw new IllegalArgumentException("Unexpected promotion");
        this.promotions = Collections.unmodifiableMap(promotionMap);
        var threatCosts = new EnumMap<Rarity, Cost>(Rarity.class);
        for (var from : promotionMap.keySet()) threatCosts.put(from, promotionMap.get(from).cost());
        if (!threatPromotionCosts.isEmpty()) {
            if (!threatPromotionCosts.keySet().equals(promotionMap.keySet()))
                throw new IllegalArgumentException("threatPromotions must price every promotion once");
            threatCosts.putAll(threatPromotionCosts);
        }
        this.threatPromotionCosts = Collections.unmodifiableMap(threatCosts);
        this.craftCosts = Map.copyOf(craftCosts);
        if (!this.craftCosts.keySet().equals(Set.of("refine", "reforge")))
            throw new IllegalArgumentException("Missing craft costs");
    }

    public static Rules defaults() {
        return parse(resource("balance.json"), resource("affixes.json"));
    }

    static JsonObject resource(String name) {
        var stream = Rules.class.getResourceAsStream("/cobbleascend/" + name);
        if (stream == null) throw new IllegalStateException("Missing rules resource: " + name);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot read rules resource", exception);
        }
    }

    public static Rules parse(JsonObject balance, JsonObject catalog) {
        if (balance.get("schemaVersion").getAsInt() != 1 || catalog.get("schemaVersion").getAsInt() != 1)
            throw new IllegalArgumentException("Unknown rules schema");
        var tiers = new ArrayList<Tier>();
        balance.getAsJsonArray("rarities").forEach(element -> {
            var t = element.getAsJsonObject();
            tiers.add(new Tier(Rarity.fromId(t.get("id").getAsString()), t.get("weight").getAsInt(),
                    t.get("prefixSlots").getAsInt(), t.get("suffixSlots").getAsInt()));
        });
        var affixes = new ArrayList<AffixDefinition>();
        var gson = new Gson();
        catalog.getAsJsonArray("affixes").forEach(a -> affixes.add(gson.fromJson(a, AffixDefinition.class)));
        var promotions = new ArrayList<Promotion>();
        balance.getAsJsonArray("promotions").forEach(element -> {
            var p = element.getAsJsonObject();
            promotions.add(new Promotion(Rarity.fromId(p.get("from").getAsString()),
                    Rarity.fromId(p.get("to").getAsString()), cost(p), p.get("attunement").getAsInt()));
        });
        var caps = new HashMap<String, Integer>();
        balance.getAsJsonObject("capsPercent").entrySet().forEach(e -> caps.put(e.getKey(), e.getValue().getAsInt()));
        var costs = new HashMap<String, Cost>();
        balance.getAsJsonObject("crafts").entrySet().forEach(e -> costs.put(e.getKey(), cost(e.getValue().getAsJsonObject())));
        var threatCosts = new EnumMap<Rarity, Cost>(Rarity.class);
        if (balance.has("threatPromotions")) {
            balance.getAsJsonArray("threatPromotions").forEach(element -> {
                var p = element.getAsJsonObject();
                var from = Rarity.fromId(p.get("from").getAsString());
                var to = Rarity.fromId(p.get("to").getAsString());
                if (to.ordinal() != from.ordinal() + 1) throw new IllegalArgumentException("threatPromotions must step one rarity");
                if (threatCosts.put(from, cost(p)) != null) throw new IllegalArgumentException("Duplicate threat promotion");
            });
        }
        return new Rules(tiers, affixes, promotions, caps, costs, threatCosts);
    }

    private static Cost cost(JsonObject object) {
        return new Cost(object.get("dust").getAsInt(), object.get("facets").getAsInt(), object.get("cores").getAsInt(),
                object.has("apex") ? object.get("apex").getAsInt() : 0);
    }

    public Tier tier(Rarity rarity) { return Objects.requireNonNull(tiers.get(rarity)); }
    public Collection<AffixDefinition> affixes() { return affixes.values(); }
    public AffixDefinition affix(String id) {
        var result = affixes.get(id);
        if (result == null) throw new IllegalArgumentException("Unknown affix: " + id);
        return result;
    }
    public Promotion promotion(Rarity from) {
        var result = promotions.get(from);
        if (result == null) throw new IllegalArgumentException("Already at maximum rarity");
        return result;
    }

    /** The promotion from {@code from}, priced for the Threat system when {@code threat} is on (same attunement, different materials). */
    public Promotion promotion(Rarity from, boolean threat) {
        var normal = promotion(from);
        return threat ? new Promotion(normal.from(), normal.to(), threatPromotionCosts.get(from), normal.attunement()) : normal;
    }
    public Cost craftCost(String action) { return Objects.requireNonNull(craftCosts.get(action)); }
    public int cap(String channel) { return Objects.requireNonNull(caps.get(channel)); }
    public int totalWeight() { return totalWeight; }
    public Rarity rollRarity(RandomGenerator random) { return rarityAt(random.nextInt(totalWeight)); }
    public Rarity rarityAt(int ticket) {
        if (ticket < 0 || ticket >= totalWeight) throw new IllegalArgumentException("Ticket outside weights");
        for (var rarity : Rarity.values()) {
            ticket -= tier(rarity).weight();
            if (ticket < 0) return rarity;
        }
        throw new IllegalStateException("Unreachable weighted draw");
    }
    static int slots(Tier tier, String category) {
        return category.equals("prefix") ? tier.prefixSlots() : tier.suffixSlots();
    }

    public void validate(Profile profile) {
        var seen = new HashSet<String>();
        int prefixes = 0, suffixes = 0;
        for (var roll : profile.affixes()) {
            var definition = affix(roll.id());
            if (!seen.add(definition.family())) throw new IllegalArgumentException("Duplicate family");
            if (roll.value() < definition.min() || roll.value() > definition.max())
                throw new IllegalArgumentException("Affix roll outside definition range");
            if (definition.parameter() == null ? roll.type() != null : !TYPES.contains(roll.type()))
                throw new IllegalArgumentException("Invalid typed affix parameter");
            if (definition.slot().equals("prefix")) prefixes++; else suffixes++;
        }
        var tier = tier(profile.rarity());
        if (prefixes != tier.prefixSlots() || suffixes != tier.suffixSlots())
            throw new IllegalArgumentException("Profile slots do not match rarity");
    }
}
