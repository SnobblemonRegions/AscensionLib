package com.cobbleascend.domain.v1;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Field Research serums (docs/FIELD-RESEARCH-DESIGN.md, decisions 2, 6-9 and follow-up 1): crafted at the Bench, held as tradable vouchers,
 * armed by a researcher for one family, and used up on that player's next wild catch of the family, where they set a floor on IVs once, at the
 * moment of capture.
 *
 * <p><b>Rules that close exploits.</b>
 * <ul>
 *   <li>Research gates both crafting (the recipe level on at least one family) and arming and using (the recipe level on the target family), so a
 *       serum bought from an alt account is useless to a player who has not researched the family.</li>
 *   <li>Craft limits count per player per UTC day (Floor, Focus) or UTC week starting Monday (Prime), on the server clock; the store counts them
 *       in {@code research_days} inside the craft transaction, so parallel crafts cannot both slip under the limit.</li>
 *   <li>A serum only ever raises a value and never above 31; the eligibility, the family and the level are checked again at the capture, not only
 *       when arming, and a serum that does not apply stays armed (it is consumed only by a capture it actually affected).</li>
 *   <li>One armed serum per family per player; arming takes the serum out of the wallet in the same transaction.</li>
 *   <li>The random stat of a Prime serum is drawn from a seed of the capture (Pokemon and serum), so a retried capture writes the same IVs.</li>
 *   <li>No serum applies to raid catches, gifts, eggs, legendaries, mythicals, Ultra Beasts or Ditto (decision 7). Ditto is excluded because it
 *       breeds with almost everything, so its IVs would spread through every egg group.</li>
 * </ul>
 */
public final class Serums {
    private Serums() {}

    /** The six IV stats, in Cobblemon's order. */
    public enum Stat {
        HP, ATTACK, DEFENCE, SPECIAL_ATTACK, SPECIAL_DEFENCE, SPEED;

        public static Stat fromId(String id) { return valueOf(id.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace("DEFENSE", "DEFENCE")); }
    }

    public static final int MAX_IV = 31;

    /** How often a craft limit resets. */
    public enum Period { DAY, WEEK }

    /**
     * A serum kind (decision 6). {@code chosen} stats the player picks; each is raised to at least {@code floor}. {@code randomPerfect} more stats,
     * drawn at the capture from the others below 31, are set to 31. The ids become material ids later: never rename them.
     */
    public enum Kind {
        FLOOR("floor_serum", 2, 1, 20, 0, Map.of(MaterialId.RESONANCE_DUST, 60L), 0, 5, Period.DAY),
        FOCUS("focus_serum", 3, 2, 25, 0, Map.of(MaterialId.RESONANCE_DUST, 150L, MaterialId.FACET, 3L), 0, 2, Period.DAY),
        PRIME("prime_serum", 4, 1, 31, 1, Map.of(MaterialId.RESONANCE_DUST, 300L, MaterialId.TOWER_MARK, 1L), 1, 3, Period.WEEK);

        public final String id;
        /** Family level needed to craft (on any family) and to arm or use (on the target family). */
        public final int level;
        public final int chosen, floor, randomPerfect;
        /** Wallet materials a craft costs, and Boss Samples (any species) it consumes. */
        public final Map<MaterialId, Long> cost;
        public final int bossSamples;
        /** Crafts allowed per {@link #period}. */
        public final int limit;
        public final Period period;

        Kind(String id, int level, int chosen, int floor, int randomPerfect, Map<MaterialId, Long> cost, int bossSamples, int limit, Period period) {
            this.id = id;
            this.level = level;
            this.chosen = chosen;
            this.floor = floor;
            this.randomPerfect = randomPerfect;
            this.cost = Collections.unmodifiableMap(new EnumMap<>(cost));
            this.bossSamples = bossSamples;
            this.limit = limit;
            this.period = period;
        }

        public static Kind fromId(String id) {
            for (var kind : values()) if (kind.id.equals(id)) return kind;
            throw new IllegalArgumentException("Unknown serum: " + id);
        }

        /** The wallet material a crafted serum of this kind is (same id). */
        public MaterialId material() { return MaterialId.fromId(id); }

        /** The kind a wallet material is, if it is a serum. */
        public static java.util.Optional<Kind> ofMaterial(MaterialId material) {
            for (var kind : values()) if (kind.id.equals(material.id())) return java.util.Optional.of(kind);
            return java.util.Optional.empty();
        }

        /** The day or week number this craft counts against. */
        public long periodOf(long epochMillis) {
            return period == Period.DAY ? FieldResearch.utcDay(epochMillis) : FieldResearch.utcWeek(epochMillis);
        }
    }

    /** Why a craft cannot happen, or {@link CraftCheck#OK}. Materials themselves are checked by the store's spend, in the same transaction. */
    public enum CraftCheck { OK, NEEDS_RESEARCH, LIMIT_REACHED, NEEDS_BOSS_SAMPLE, BAD_QUANTITY }

    /**
     * @param highestLevel  the player's highest level on any family
     * @param craftedThisPeriod serums of this kind the player already crafted this day or week
     * @param bossSamplesHeld Boss Samples the player holds (any species)
     * @param quantity      how many to craft at once (1 to the remaining limit)
     */
    public static CraftCheck craftCheck(Kind kind, int highestLevel, int craftedThisPeriod, int bossSamplesHeld, int quantity) {
        if (quantity < 1 || quantity > kind.limit) return CraftCheck.BAD_QUANTITY;
        if (highestLevel < kind.level) return CraftCheck.NEEDS_RESEARCH;
        if (craftedThisPeriod < 0 || (long) craftedThisPeriod + quantity > kind.limit) return CraftCheck.LIMIT_REACHED;
        if ((long) bossSamplesHeld < (long) kind.bossSamples * quantity) return CraftCheck.NEEDS_BOSS_SAMPLE;
        return CraftCheck.OK;
    }

    /** The wallet cost of crafting {@code quantity}. */
    public static Map<MaterialId, Long> costOf(Kind kind, int quantity) {
        if (quantity < 1 || quantity > kind.limit) throw new IllegalArgumentException("Bad serum quantity " + quantity);
        var total = new EnumMap<MaterialId, Long>(MaterialId.class);
        kind.cost.forEach((m, n) -> total.put(m, n * quantity));
        return Collections.unmodifiableMap(total);
    }

    /** A serum armed by a player for one family, with the stats they chose. */
    public record Armed(Kind kind, String familyId, List<Stat> chosen) {
        public Armed {
            if (kind == null || familyId == null) throw new IllegalArgumentException("An armed serum needs a kind and a family");
            chosen = List.copyOf(chosen);
        }
    }

    /** Why a serum cannot be armed, or {@link ArmCheck#OK}. */
    public enum ArmCheck { OK, UNKNOWN_FAMILY, NEEDS_RESEARCH, WRONG_STAT_COUNT, DUPLICATE_STAT, ALREADY_ARMED, EXCLUDED_FAMILY }

    /**
     * @param familyLevel   the player's level on the target family
     * @param alreadyArmed  whether the player already has a serum armed for this family
     * @param familyExcluded whether every species in the family is excluded (Ditto's family, a legendary line), so the serum could never apply
     */
    public static ArmCheck armCheck(Kind kind, ResearchFamilies.Catalog catalog, String familyId, List<Stat> chosen, int familyLevel,
                                    boolean alreadyArmed, boolean familyExcluded) {
        if (catalog.family(familyId).isEmpty()) return ArmCheck.UNKNOWN_FAMILY;
        if (familyLevel < kind.level) return ArmCheck.NEEDS_RESEARCH;
        if (chosen == null || chosen.size() != kind.chosen || hasNull(chosen)) return ArmCheck.WRONG_STAT_COUNT;
        if (EnumSet.copyOf(chosen).size() != chosen.size()) return ArmCheck.DUPLICATE_STAT;
        if (alreadyArmed) return ArmCheck.ALREADY_ARMED;
        if (familyExcluded) return ArmCheck.EXCLUDED_FAMILY;
        return ArmCheck.OK;
    }

    /** A plain loop: {@code List.of(...).contains(null)} throws instead of answering. */
    private static boolean hasNull(List<Stat> stats) {
        for (var stat : stats) if (stat == null) return true;
        return false;
    }

    /** How a Pokemon came to be caught, as far as a serum is concerned. Only {@link #WILD} can take a serum. */
    public enum CaptureKind { WILD, RAID, GIFT, EGG, OTHER }

    /** What a serum needs to know about a capture: its species, its labels and how it was caught. */
    public record CaptureFacts(String species, boolean legendary, boolean mythical, boolean ultraBeast, CaptureKind kind) {}

    /** Species no serum applies to, whatever their labels (decision 7). */
    public static final List<String> EXCLUDED_SPECIES = List.of("ditto");

    /** Whether a species can ever take a serum (its labels and the Ditto rule; the capture kind is checked separately). */
    public static boolean speciesEligible(String species, boolean legendary, boolean mythical, boolean ultraBeast) {
        String id = ResearchFamilies.normalise(species);
        return id != null && !legendary && !mythical && !ultraBeast && !EXCLUDED_SPECIES.contains(id);
    }

    /** What a capture with an armed serum did. {@code applied} false means nothing changed and the serum stays armed. */
    public record Outcome(boolean applied, String reason, int[] ivs, List<Stat> raised) {
        public Outcome {
            ivs = ivs.clone();
            raised = List.copyOf(raised);
        }

        @Override public int[] ivs() { return ivs.clone(); }
    }

    /**
     * Applies an armed serum to a capture. Never throws for game data: anything that does not fit returns {@code applied=false} with the reason
     * and the IVs unchanged. Values only go up, to at most {@link #MAX_IV}.
     *
     * @param familyLevel the player's level on the armed family <i>now</i> (checked again, not trusted from arming)
     * @param ivs         the caught Pokemon's six IVs, as Cobblemon rolled them (values outside 0..31 are clamped first)
     * @param seed        a seed of this capture (see {@link #seed}), so a retried capture writes the same IVs
     */
    public static Outcome apply(Armed armed, ResearchFamilies.Catalog catalog, CaptureFacts capture, int familyLevel, int[] ivs, long seed) {
        if (ivs == null || ivs.length != Stat.values().length) return new Outcome(false, "bad_ivs", new int[6], List.of());
        int[] base = new int[ivs.length];
        for (int i = 0; i < ivs.length; i++) base[i] = Math.max(0, Math.min(MAX_IV, ivs[i]));
        if (capture == null || capture.kind() != CaptureKind.WILD) return new Outcome(false, "not_wild", base, List.of());
        if (!speciesEligible(capture.species(), capture.legendary(), capture.mythical(), capture.ultraBeast()))
            return new Outcome(false, "excluded_species", base, List.of());
        var family = catalog.familyOf(capture.species());
        if (family.isEmpty() || !family.get().id().equals(ResearchFamilies.normalise(armed.familyId())))
            return new Outcome(false, "other_family", base, List.of());
        if (familyLevel < armed.kind().level) return new Outcome(false, "needs_research", base, List.of());
        if (armed.chosen().size() != armed.kind().chosen || EnumSet.copyOf(armed.chosen()).size() != armed.chosen().size())
            return new Outcome(false, "bad_choice", base, List.of());
        int[] out = base.clone();
        var raised = new ArrayList<Stat>();
        for (var stat : armed.chosen()) {
            int i = stat.ordinal();
            if (out[i] < armed.kind().floor) { out[i] = armed.kind().floor; raised.add(stat); }
        }
        if (armed.kind().randomPerfect > 0) {
            var random = new Random(seed);
            var candidates = new ArrayList<Stat>();
            for (var stat : Stat.values()) if (!armed.chosen().contains(stat) && out[stat.ordinal()] < MAX_IV) candidates.add(stat);
            for (int n = 0; n < armed.kind().randomPerfect && !candidates.isEmpty(); n++) {
                var stat = candidates.remove(random.nextInt(candidates.size()));
                out[stat.ordinal()] = MAX_IV;
                raised.add(stat);
            }
        }
        // A serum is used up by an eligible capture of its family even when the Pokemon already met every floor (the player chose to keep it
        // armed): the capture is what consumes it, so a player cannot keep one serum armed through many captures by catching high-IV Pokemon.
        return new Outcome(true, raised.isEmpty() ? "already_met" : "raised", out, raised);
    }

    /** The seed of one capture with one serum: the Pokemon's UUID and the armed serum's operation. */
    public static long seed(UUID pokemon, UUID armOperation) {
        return RewardSeed.of("field-research-serum", pokemon, armOperation);
    }

    /** The value written to the Pokemon's persistent {@code ascensionlib_serum} key (decision 9): the serum kind's id. Never renamed. */
    public static final String SERUM_MARK_KEY = "ascensionlib_serum";
}
