package com.cobbleascend.domain.v1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * What clearing a tower's F10 champion pays (docs/TOWER-COMPLETION-REWARDS.md, numbers approved 2026-10-15; tune from live counters). A completion
 * is about an hour of play, so it always ends in a large payout: a <b>pile</b> that is guaranteed, a <b>quality tier</b> earned by how hard and
 * how clean the run was (never how fast), and a <b>crown</b> of one to three rolls from a weighted table, so a completion never ends without a notable
 * item. Flat per player except for the seeded rolls, which are seeded by (encounter, player), so a retried settlement recomputes the same chest.
 */
public record TowerCompletionBands(int dust, int facets, int fragments, int scouters, int marks, int goldMarkBonus, int mythicMarkBonus,
                                   int fragmentBundle, List<TierSpec> tiers) {
    /** The crown items. {@link #ARMOR} and {@link #TICKET} are CobbleTowers' own tables, so they come back as counts for it to hand over. */
    public enum Crown { CORE, FRAGMENT_BUNDLE, SIGIL, CATALYST, ARMOR, TICKET, RESPEC_RANK, RESPEC_UNIQUE }

    public enum Tier { BRONZE, SILVER, GOLD, MYTHIC }

    /**
     * @param minScore the lowest score in this tier; @param multiplierPercent percent of the pile; @param attunement party attunement the completion pays
     * (the F5 boss adds its own flat share, so a whole tower pays this plus {@link Tunables#attunementPerVictory}); @param weights per {@link Crown}, in enum order
     */
    public record TierSpec(Tier tier, int minScore, int multiplierPercent, int crownSlots, int attunement, int[] weights) {
        public TierSpec {
            if (attunement < 0 || attunement > 100) throw new IllegalArgumentException("Tier attunement is 0-100");
            if (weights.length != Crown.values().length) throw new IllegalArgumentException("One weight per crown item");
            int total = 0;
            for (int weight : weights) { if (weight < 0) throw new IllegalArgumentException("Negative weight"); total += weight; }
            if (total < 1 || crownSlots < 1 || multiplierPercent < 1) throw new IllegalArgumentException("A tier needs weight, a slot and a multiplier");
            weights = weights.clone();
        }
    }

    /**
     * The result of one completion.
     * @param pile the guaranteed part; {@code materials} is the pile plus what the crown rolls paid (what the wallet receives);
     * @param crowns the crown rolls in reveal order; {@code armorPieces} and {@code tickets} count CobbleTowers' own crown items
     */
    public record Completion(Tier tier, int score, Map<MaterialId, Long> pile, Map<MaterialId, Long> materials, List<Crown> crowns, int armorPieces, int tickets) {}

    public static final TowerCompletionBands DEFAULTS = new TowerCompletionBands(800, 6, 3, 4, 3, 1, 1, 12, List.of(
            // Weights are the approved crown table times ten, with the two respec items added (docs/RESPEC-DESIGN.md): the Hourglass of Undoing about every
            // 10.6 completions and the Unbinding Prism about every 34.3.
            // Attunement is 12/15/18/22 here plus the F5 boss's 3, so a whole tower pays 15/18/21/25 by difficulty (owner, 2026-10-23).
            new TierSpec(Tier.BRONZE, 0, 70, 1, 12, new int[] {300, 400, 100, 0, 140, 60, 0, 0}),
            new TierSpec(Tier.SILVER, 2, 100, 1, 15, new int[] {360, 300, 120, 20, 140, 60, 60, 0}),
            new TierSpec(Tier.GOLD, 5, 150, 2, 18, new int[] {360, 220, 140, 40, 160, 80, 80, 30}),
            new TierSpec(Tier.MYTHIC, 8, 225, 3, 22, new int[] {360, 140, 140, 80, 180, 100, 100, 50})));

    /** The expected number of a crown entry per completion for a given mix of tiers (probabilities by tier ordinal): slots times the entry's share of the tier's weight. */
    public double expectedPerCompletion(Crown crown, double[] tierMix) {
        double expected = 0;
        for (var spec : tiers) {
            int total = 0;
            for (int w : spec.weights()) total += w;
            expected += tierMix[spec.tier().ordinal()] * spec.crownSlots() * spec.weights()[crown.ordinal()] / (double) total;
        }
        return expected;
    }

    /** The mix of tiers the economy was sized for (docs/TOWER-COMPLETION-REWARDS.md): 15 percent Bronze, 45 Silver, 30 Gold, 10 Mythic. */
    public static final double[] APPROVED_MIX = {0.15, 0.45, 0.30, 0.10};

    /** The configuration form: the pile and, per tier, its first score, pile percent, crown slots and crown weights by name. */
    public com.google.gson.JsonObject toJson() {
        var root = new com.google.gson.JsonObject();
        var pile = new com.google.gson.JsonObject();
        pile.addProperty("dust", dust); pile.addProperty("facets", facets); pile.addProperty("fragments", fragments); pile.addProperty("scouters", scouters);
        pile.addProperty("marks", marks); pile.addProperty("goldMarkBonus", goldMarkBonus); pile.addProperty("mythicMarkBonus", mythicMarkBonus);
        pile.addProperty("fragmentBundle", fragmentBundle);
        root.add("pile", pile);
        var tierJson = new com.google.gson.JsonObject();
        for (var spec : tiers) {
            var t = new com.google.gson.JsonObject();
            t.addProperty("minScore", spec.minScore());
            t.addProperty("pilePercent", spec.multiplierPercent());
            t.addProperty("crownSlots", spec.crownSlots());
            t.addProperty("attunement", spec.attunement());
            var weights = new com.google.gson.JsonObject();
            for (var crown : Crown.values()) weights.addProperty(crown.name().toLowerCase(java.util.Locale.ROOT), spec.weights()[crown.ordinal()]);
            t.add("crownWeights", weights);
            tierJson.add(spec.tier().name().toLowerCase(java.util.Locale.ROOT), t);
        }
        root.add("tiers", tierJson);
        return root;
    }

    /** Reads the configuration form over {@code base}: anything absent keeps the base value. Throws IllegalArgumentException for numbers that do not make a valid table. */
    public static TowerCompletionBands fromJson(com.google.gson.JsonObject root, TowerCompletionBands base) {
        var pile = Tunables.obj(root, "pile");
        int d = base.dust, f = base.facets, fr = base.fragments, sc = base.scouters, m = base.marks, gb = base.goldMarkBonus, mb = base.mythicMarkBonus, bundle = base.fragmentBundle;
        if (pile != null) {
            d = Tunables.intOf(pile, "dust", d); f = Tunables.intOf(pile, "facets", f); fr = Tunables.intOf(pile, "fragments", fr); sc = Tunables.intOf(pile, "scouters", sc);
            m = Tunables.intOf(pile, "marks", m); gb = Tunables.intOf(pile, "goldMarkBonus", gb); mb = Tunables.intOf(pile, "mythicMarkBonus", mb);
            bundle = Tunables.intOf(pile, "fragmentBundle", bundle);
        }
        var tierJson = Tunables.obj(root, "tiers");
        var specs = new java.util.ArrayList<TierSpec>();
        for (var old : base.tiers) {
            var t = tierJson == null ? null : Tunables.obj(tierJson, old.tier().name().toLowerCase(java.util.Locale.ROOT));
            if (t == null) { specs.add(old); continue; }
            int[] weights = old.weights().clone();
            var w = Tunables.obj(t, "crownWeights");
            if (w != null) for (var crown : Crown.values()) weights[crown.ordinal()] = Tunables.intOf(w, crown.name().toLowerCase(java.util.Locale.ROOT), weights[crown.ordinal()]);
            specs.add(new TierSpec(old.tier(), Tunables.intOf(t, "minScore", old.minScore()), Tunables.intOf(t, "pilePercent", old.multiplierPercent()),
                    Tunables.intOf(t, "crownSlots", old.crownSlots()), Tunables.intOf(t, "attunement", old.attunement()), weights));
        }
        return new TowerCompletionBands(d, f, fr, sc, m, gb, mb, bundle, specs);
    }

    public TowerCompletionBands {
        if (dust < 0 || facets < 0 || fragments < 0 || scouters < 0 || marks < 0 || goldMarkBonus < 0 || mythicMarkBonus < 0 || fragmentBundle < 1)
            throw new IllegalArgumentException("Amounts must be nonnegative and a bundle at least 1");
        if (tiers.size() != Tier.values().length || tiers.get(0).minScore() != 0)
            throw new IllegalArgumentException("One spec per tier, the first starting at score 0");
        for (int i = 0; i < tiers.size(); i++)
            if (tiers.get(i).tier() != Tier.values()[i] || (i > 0 && tiers.get(i).minScore() <= tiers.get(i - 1).minScore()))
                throw new IllegalArgumentException("Tiers must be in order with rising scores");
        tiers = List.copyOf(tiers);
    }

    /** Skill, not speed: challenge cards taken, plus two per Ascension cycle, plus two when nobody was knocked out. */
    public static int score(int challengeCards, int cycle, boolean flawless) {
        if (challengeCards < 0 || cycle < 0) throw new IllegalArgumentException("Cards and cycle must be nonnegative");
        return challengeCards + 2 * cycle + (flawless ? 2 : 0);
    }

    public TierSpec tierFor(int score) {
        var found = tiers.get(0);
        for (var spec : tiers) if (score >= spec.minScore()) found = spec;
        return found;
    }

    /** The guaranteed pile of one tier, before the crown rolls: what every player who reaches that tier is paid. */
    public Map<MaterialId, Long> pileFor(TierSpec spec) {
        var pile = new EnumMap<MaterialId, Long>(MaterialId.class);
        add(pile, MaterialId.RESONANCE_DUST, scaled(dust, spec.multiplierPercent()));
        add(pile, MaterialId.FACET, scaled(facets, spec.multiplierPercent()));
        add(pile, MaterialId.UNIQUE_FRAGMENT, scaled(fragments, spec.multiplierPercent()));
        add(pile, MaterialId.SCOUTER, scouters);
        add(pile, MaterialId.TOWER_MARK, marks + (spec.tier().ordinal() >= Tier.GOLD.ordinal() ? goldMarkBonus : 0)
                + (spec.tier() == Tier.MYTHIC ? mythicMarkBonus : 0));
        return pile;
    }

    public Completion payout(String encounterId, UUID player, int challengeCards, int cycle, boolean flawless) {
        int score = score(challengeCards, cycle, flawless);
        var spec = tierFor(score);
        var random = new Random(RewardSeed.of("tower-completion", encounterId, player));
        var materials = new EnumMap<>(pileFor(spec));
        var pile = Collections.unmodifiableMap(new EnumMap<>(materials));
        var crowns = new ArrayList<Crown>();
        int armor = 0, tickets = 0;
        for (int slot = 0; slot < spec.crownSlots(); slot++) {
            var crown = pick(spec.weights(), random);
            crowns.add(crown);
            switch (crown) {
                case CORE -> add(materials, MaterialId.ASCENSION_CORE, 1);
                case FRAGMENT_BUNDLE -> add(materials, MaterialId.UNIQUE_FRAGMENT, fragmentBundle);
                case SIGIL -> add(materials, MaterialId.ASCENSION_SIGIL, 1);
                case CATALYST -> add(materials, MaterialId.UNIQUE_CATALYST, 1);
                case ARMOR -> armor++;
                case TICKET -> tickets++;
                case RESPEC_RANK -> add(materials, MaterialId.RANK_RESPEC, 1);
                case RESPEC_UNIQUE -> add(materials, MaterialId.UNIQUE_RESPEC, 1);
            }
        }
        return new Completion(spec.tier(), score, pile, Collections.unmodifiableMap(materials), List.copyOf(crowns), armor, tickets);
    }

    // ---- Threat (balance phase 1, docs/BALANCE-THREAT.md) ---------------------------------------------------------------------------------

    /** Core chance of a Threat completion in percent: 20 + 12 per band, at most 95 (Threat I 32%, Threat VII 95%). */
    public static int threatCorePercent(int threat) { return Math.min(95, 20 + 12 * clampThreat(threat)); }

    /** Dust a Threat completion's pile pays at Silver quality (the F5 mini chest's 200 come on top): 100 at Threat I, +75 a band. */
    public static int threatDust(int threat) { return 100 + 75 * (clampThreat(threat) - 1); }

    /** Facets a Threat completion's pile pays at Silver quality (the mini chest's 1 comes on top): 4 at Threat I up to 13 at Threat VII. */
    public static int threatFacets(int threat) { return (int) Math.round(1.5 * (2 + clampThreat(threat))) - 1; }

    private static int clampThreat(int threat) { return Math.max(1, Math.min(7, threat)); }

    /**
     * A completion while the Threat system is on. The quality tier, the rest of the pile and the crown work as in {@link #payout}, with three changes:
     * the pile's dust and facets come from the Threat band ({@link #threatDust}, {@link #threatFacets}, times the tier's pile percent); the crown never
     * rolls a Core (its weight is dropped); and Cores come from one separate roll at {@link #threatCorePercent}, cut to {@code reducedPercent} of that
     * chance once the player has had their full-odds rolls for the day ({@code coreCapped}). Seeded like {@link #payout}, so a retry recomputes the same.
     */
    public Completion payoutThreat(String encounterId, UUID player, int threat, int challengeCards, int cycle, boolean flawless,
                                   boolean coreCapped, int reducedPercent) {
        int score = score(challengeCards, cycle, flawless);
        var spec = tierFor(score);
        var random = new Random(RewardSeed.of("tower-completion-threat", encounterId, player));
        var base = pileFor(spec);
        var materials = new EnumMap<MaterialId, Long>(MaterialId.class);
        materials.putAll(base);
        materials.remove(MaterialId.RESONANCE_DUST);
        materials.remove(MaterialId.FACET);
        add(materials, MaterialId.RESONANCE_DUST, scaled(threatDust(threat), spec.multiplierPercent()));
        add(materials, MaterialId.FACET, scaled(threatFacets(threat), spec.multiplierPercent()));
        var pile = Collections.unmodifiableMap(new EnumMap<>(materials));
        int[] weights = spec.weights().clone();
        weights[Crown.CORE.ordinal()] = 0;
        boolean anyLeft = false;
        for (int w : weights) anyLeft |= w > 0;
        var crowns = new ArrayList<Crown>();
        int armor = 0, tickets = 0;
        for (int slot = 0; anyLeft && slot < spec.crownSlots(); slot++) {
            var crown = pick(weights, random);
            crowns.add(crown);
            switch (crown) {
                case CORE -> { }
                case FRAGMENT_BUNDLE -> add(materials, MaterialId.UNIQUE_FRAGMENT, fragmentBundle);
                case SIGIL -> add(materials, MaterialId.ASCENSION_SIGIL, 1);
                case CATALYST -> add(materials, MaterialId.UNIQUE_CATALYST, 1);
                case ARMOR -> armor++;
                case TICKET -> tickets++;
                case RESPEC_RANK -> add(materials, MaterialId.RANK_RESPEC, 1);
                case RESPEC_UNIQUE -> add(materials, MaterialId.UNIQUE_RESPEC, 1);
            }
        }
        // The Core roll in permille, so a capped chance keeps its fraction (32% at 25% is 8.0%, not 8).
        int permille = threatCorePercent(threat) * 10;
        if (coreCapped) permille = permille * Math.max(0, Math.min(100, reducedPercent)) / 100;
        if (random.nextInt(1000) < permille) {
            add(materials, MaterialId.ASCENSION_CORE, 1);
            crowns.add(Crown.CORE);
        }
        return new Completion(spec.tier(), score, pile, Collections.unmodifiableMap(materials), List.copyOf(crowns), armor, tickets);
    }

    private static long scaled(int base, int percent) { return Math.round(base * percent / 100.0); }

    private static void add(Map<MaterialId, Long> into, MaterialId material, long amount) {
        if (amount > 0) into.merge(material, amount, Long::sum);
    }

    private static Crown pick(int[] weights, Random random) {
        int total = 0;
        for (int weight : weights) total += weight;
        int roll = random.nextInt(total);
        for (int i = 0; i < weights.length; i++) {
            roll -= weights[i];
            if (roll < 0) return Crown.values()[i];
        }
        throw new IllegalStateException("unreachable");
    }
}
