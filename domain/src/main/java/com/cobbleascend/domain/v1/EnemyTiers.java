package com.cobbleascend.domain.v1;

import com.cobbleascend.domain.Rarity;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Validated registry of enemy tiers loaded from {@code design/enemy-tiers.json} (provisional values). */
public final class EnemyTiers {
    private final Map<String, EnemyTier> tiers;
    private final Map<String, String> threatAliases;
    private final Set<String> uniqueExclusions;

    public EnemyTiers(List<EnemyTier> list) { this(list, Map.of(), Set.of()); }

    /**
     * @param threatAliases    legacy tier -> Threat tier, used only when Threat is switched on ({@link #resolve})
     * @param uniqueExclusions Uniques an enemy never rolls (777 only changes item rewards, which an enemy never earns)
     */
    public EnemyTiers(List<EnemyTier> list, Map<String, String> threatAliases, Set<String> uniqueExclusions) {
        var map = new LinkedHashMap<String, EnemyTier>();
        for (var tier : list) if (map.put(tier.id(), tier) != null) throw new IllegalArgumentException("Duplicate tier ID: " + tier.id());
        if (map.isEmpty()) throw new IllegalArgumentException("No enemy tiers defined");
        tiers = Collections.unmodifiableMap(map);
        for (var alias : threatAliases.entrySet()) {
            if (!map.containsKey(alias.getKey()) || !map.containsKey(alias.getValue()))
                throw new IllegalArgumentException("Threat alias names an unknown tier: " + alias.getKey() + " -> " + alias.getValue());
        }
        this.threatAliases = Map.copyOf(threatAliases);
        this.uniqueExclusions = Set.copyOf(uniqueExclusions);
    }

    public static EnemyTiers defaults() {
        var stream = EnemyTiers.class.getResourceAsStream("/cobbleascend/enemy-tiers.json");
        if (stream == null) throw new IllegalStateException("Missing rules resource: enemy-tiers.json");
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return parse(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read enemy tiers", exception);
        }
    }

    /**
     * The tiers from an owner's override file ({@code config/ascensionlib-enemy-tiers.json}, same format as the shipped one) when it
     * exists and is valid, otherwise the shipped tiers. A bad file is named in {@code problems} and ignored whole, so a typo can never
     * leave an encounter without tiers. Never throws.
     */
    public static EnemyTiers loadOrDefaults(java.nio.file.Path file, List<String> problems) {
        if (file == null || !java.nio.file.Files.exists(file)) return defaults();
        try (var reader = java.nio.file.Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (IOException | RuntimeException bad) {
            problems.add(file.getFileName() + ": " + bad.getMessage() + " (using the shipped enemy tiers)");
            return defaults();
        }
    }

    public static EnemyTiers parse(JsonObject root) {
        Json.keys(root, Set.of("schemaVersion", "tiers"), Set.of("status", "threatAliases", "enemyUniqueExclusions"));
        if (Json.integer(root.get("schemaVersion")) != 1) throw new IllegalArgumentException("Unknown enemy tier schema");
        var list = new ArrayList<EnemyTier>();
        for (var element : Json.array(root.get("tiers"))) {
            var tier = Json.object(element);
            Json.keys(tier, Set.of("id", "rankCredits", "rarities"), Set.of("bossBonusCredits", "bossUnique", "unique", "perfected"));
            var weights = new ArrayList<EnemyTier.RarityWeight>();
            for (var entry : Json.object(tier.get("rarities")).entrySet())
                weights.add(new EnemyTier.RarityWeight(Rarity.fromId(entry.getKey()), Json.integer(entry.getValue())));
            list.add(new EnemyTier(Json.string(tier.get("id")), weights, Json.integer(tier.get("rankCredits")),
                    tier.has("bossBonusCredits") ? Json.integer(tier.get("bossBonusCredits")) : 0,
                    tier.has("bossUnique") && flag(tier.get("bossUnique")),
                    tier.has("unique") && flag(tier.get("unique")),
                    tier.has("perfected") && flag(tier.get("perfected"))));
        }
        var aliases = new LinkedHashMap<String, String>();
        if (root.has("threatAliases"))
            for (var entry : Json.object(root.get("threatAliases")).entrySet()) aliases.put(entry.getKey(), Json.string(entry.getValue()));
        var exclusions = new LinkedHashSet<String>();
        if (root.has("enemyUniqueExclusions"))
            for (var element : Json.array(root.get("enemyUniqueExclusions"))) exclusions.add(Json.string(element));
        return new EnemyTiers(list, aliases, exclusions);
    }

    private static boolean flag(com.google.gson.JsonElement element) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Expected true or false");
        return element.getAsBoolean();
    }

    public EnemyTier tier(String id) {
        var tier = tiers.get(id);
        if (tier == null) throw new IllegalArgumentException("Unknown enemy tier: " + id);
        return tier;
    }

    public Collection<EnemyTier> all() { return tiers.values(); }

    /**
     * The tier an encounter actually fights at. With Threat off every ID is itself, so older callers see no change; with it
     * on, a legacy ID ({@code trial_rank_1} to {@code trial_rank_3}, {@code boss}) maps to its Threat tier.
     */
    public EnemyTier resolve(String id, boolean threatEnabled) {
        return tier(threatEnabled ? threatAliases.getOrDefault(id, id) : id);
    }

    /** The Threat tier for a band number 1 to 7, clamped. */
    public EnemyTier threat(int band) { return tier("threat_" + Math.max(1, Math.min(7, band))); }

    public Set<String> uniqueExclusions() { return uniqueExclusions; }
}
