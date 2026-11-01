package com.cobbleascend.domain.v1;

import com.cobbleascend.domain.Rarity;
import com.cobbleascend.domain.Rules;
import com.cobbleascend.domain.v1.CraftException.Reason;
import java.util.*;
import java.util.random.RandomGenerator;

/**
 * Pure schema-1 transitions. Each returns the next committed profile (revision + 1) and what the operation
 * costs; debiting a wallet, locking and persistence belong to the future transaction service.
 */
public final class RankedProgression {
    /** {@code cost} lists materials; {@code creditsSpent} lists pending milestone credits consumed. */
    public record CraftResult(ProfileV1 profile, Map<MaterialId, Long> cost, int creditsSpent) {}
    public record LevelResult(ProfileV1 profile, List<Integer> newlyAwarded) {}

    private final RankedRules rules;

    public RankedProgression(RankedRules rules) { this.rules = Objects.requireNonNull(rules); }

    /** One-time acquisition: rarity-sized slots at rank I and every milestone already reached by {@code level}. */
    public ProfileV1 create(UUID pokemonId, UUID authorityId, Rarity rarity, Origin origin, int level,
                            List<String> types, RandomGenerator random) {
        return build(pokemonId, authorityId, rarity, origin, level, rollInitialSlots(rarity, types, random));
    }

    /**
     * What a wild Pokemon will be when caught: its rarity, rank-I slots and the upgrades it would start with.
     * Fixed for that Pokemon (derived from its ID and a server-only secret), so fleeing and re-encountering
     * cannot reroll it and a Scouter preview equals the capture result exactly.
     */
    public record WildRating(Rarity rarity, List<OrdinarySlot> slots, int upgradesOnCapture) {
        public WildRating { slots = List.copyOf(slots); }
    }

    public WildRating rateWild(byte[] secret, UUID pokemonId, List<String> types, int level) {
        return rateWild(secret, pokemonId, types, level, 1);
    }

    /**
     * As above in a profile zone of this tier. Tier 1 is the ordinary rating, unchanged. A deeper tier is a different, equally fixed rating for the same
     * Pokemon: its rarity is one draw from that tier's own weights ({@link ZoneRarity}, set in the configuration), then its slots are rolled as usual.
     * Deterministic from the Pokemon's ID, the tier and the server secret, so a Scouter preview equals the capture as long as both use the tier of the
     * zone the Pokemon stands in.
     */
    public WildRating rateWild(byte[] secret, UUID pokemonId, List<String> types, int level, int tier) {
        return rateWild(secret, pokemonId, types, level, tier, Tunables.current().zones());
    }

    /** As above with the zone settings given, for a test or a preview of a table. */
    public WildRating rateWild(byte[] secret, UUID pokemonId, List<String> types, int level, int tier, ZoneSettings zones) {
        if (types.isEmpty() || !com.cobbleascend.domain.Rules.TYPES.containsAll(types))
            throw new IllegalArgumentException("Species types must be one or more standard types");
        if (tier < 1) throw new IllegalArgumentException("Zone tier must be at least 1");
        int upgrades = Milestones.reachedThrough(level).size();
        var random = WildSeed.random(secret, rules.catalogVersion(), pokemonId, tier);
        Rarity rarity = ZoneRarity.roll(random, ZoneRarity.weights(tier, ordinaryWeights(), zones));
        var slots = rollInitialSlots(rarity, types, random);
        return new WildRating(rarity, slots, upgrades);
    }

    /** The ordinary rarity table's weights, Common to Mythic. */
    int[] ordinaryWeights() {
        var out = new int[Rarity.values().length];
        for (var rarity : Rarity.values()) out[rarity.ordinal()] = rules.base().tier(rarity).weight();
        return out;
    }

    /** Captures a wild Pokemon using exactly the rating {@link #rateWild} previews. */
    public ProfileV1 createWild(byte[] secret, UUID pokemonId, UUID authorityId, Origin origin, int level,
                                List<String> types) {
        return createWild(secret, pokemonId, authorityId, origin, level, types, 1);
    }

    /** Captures a wild Pokemon in a zone of this tier using exactly the rating {@link #rateWild(byte[], UUID, List, int, int)} previews. */
    public ProfileV1 createWild(byte[] secret, UUID pokemonId, UUID authorityId, Origin origin, int level,
                                List<String> types, int tier) {
        var rating = rateWild(secret, pokemonId, types, level, tier);
        return build(pokemonId, authorityId, rating.rarity(), origin, level, rating.slots());
    }

    private ProfileV1 build(UUID pokemonId, UUID authorityId, Rarity rarity, Origin origin, int level,
                            List<OrdinarySlot> slots) {
        var profile = new ProfileV1(ProfileV1.SCHEMA, UUID.randomUUID(), pokemonId, authorityId, 1, rarity, rarity,
                origin, rules.catalogVersion(), 0, Math.min(level, Milestones.MAX_LEVEL),
                Milestones.reachedThrough(level), 0, slots, null);
        rules.validate(profile);
        return profile;
    }

    /**
     * A full set of rank-I slots for {@code rarity}, drawn with the same weights and family exclusions as a
     * capture. Shared by player acquisition and enemy generation so both follow identical rules.
     */
    public List<OrdinarySlot> rollInitialSlots(Rarity rarity, List<String> types, RandomGenerator random) {
        var slots = new ArrayList<OrdinarySlot>();
        for (var category : Category.values()) {
            for (int i = 0; i < rules.slotCount(rarity, category); i++)
                slots.add(draw(OrdinarySlot.slotId(category, i), category, slots, 1, types, random));
        }
        return List.copyOf(slots);
    }

    /** Monotonic catch-up: awards only milestones absent from the ledger. Lowering a level changes nothing. */
    public LevelResult observeLevel(ProfileV1 profile, int level) {
        rules.validate(profile);
        if (level < 1) throw new CraftException(Reason.INVALID_LEVEL, "Level must be at least 1");
        int observed = Math.min(level, Milestones.MAX_LEVEL);
        var awarded = new TreeSet<>(profile.awardedMilestones());
        var added = new ArrayList<Integer>();
        for (int milestone : Milestones.reachedThrough(observed)) if (awarded.add(milestone)) added.add(milestone);
        int highest = Math.max(profile.highestLevelObserved(), observed);
        if (added.isEmpty() && highest == profile.highestLevelObserved()) return new LevelResult(profile, List.of());
        var next = profile.advance(profile.rarity(), highest, awarded, profile.spentUpgradeCredits(),
                profile.ordinarySlots(), profile.unique());
        rules.validate(next);
        return new LevelResult(next, List.copyOf(added));
    }

    /** Spends one pending credit to advance the selected slot one rank and roll in the new band. */
    public CraftResult upgrade(ProfileV1 profile, String slotId, RandomGenerator random) {
        rules.validate(profile);
        var slot = requireSlot(profile, slotId);
        if (profile.pendingCredits() < 1)
            throw new CraftException(Reason.NO_PENDING_CREDIT, "No pending upgrade credit");
        if (slot.rank() >= RankedAffix.RANKS)
            throw new CraftException(Reason.MAX_RANK, "Slot is already at maximum rank");
        int rank = slot.rank() + 1;
        var value = rules.affix(slot.affixId()).band(rank).roll(random);
        var upgraded = new OrdinarySlot(slot.slotId(), slot.category(), rank, slot.affixId(), slot.parameters(),
                value, slot.definitionVersion());
        var next = profile.advance(profile.rarity(), profile.highestLevelObserved(), profile.awardedMilestones(),
                profile.spentUpgradeCredits() + 1, replace(profile, upgraded), profile.unique());
        rules.validate(next);
        return new CraftResult(next, Map.of(), 1);
    }

    /** Replaces the selected slot's affix and value. Slot ID, rank, other slots and credits are preserved. */
    public CraftResult reforge(ProfileV1 profile, String slotId, List<String> types, RandomGenerator random) {
        rules.validate(profile);
        var old = requireSlot(profile, slotId);
        var others = profile.ordinarySlots().stream().filter(s -> !s.slotId().equals(slotId)).toList();
        var replacement = draw(slotId, old.category(), others, old.rank(), types, random);
        var next = profile.advance(profile.rarity(), profile.highestLevelObserved(), profile.awardedMilestones(),
                profile.spentUpgradeCredits(), replace(profile, replacement), profile.unique());
        rules.validate(next);
        return new CraftResult(next, MaterialId.of(rules.base().craftCost("reforge")), 0);
    }

    /** Rerolls only the selected slot's value within its current rank band, above its raised floor if it has one. */
    public CraftResult refine(ProfileV1 profile, String slotId, RandomGenerator random) {
        rules.validate(profile);
        var old = requireSlot(profile, slotId);
        if (old.sealed()) throw new CraftException(Reason.SLOT_SEALED, "A sealed slot can only be reforged");
        var band = rules.affix(old.affixId()).band(old.rank());
        // A perfected slot rolls between its floor and the band maximum: the polish is never undone.
        int value = random.nextInt(Math.max(band.min(), old.floor()), band.max() + 1);
        var refined = new OrdinarySlot(old.slotId(), old.category(), old.rank(), old.affixId(), old.parameters(),
                value, old.definitionVersion(), old.floor());
        var next = profile.advance(profile.rarity(), profile.highestLevelObserved(), profile.awardedMilestones(),
                profile.spentUpgradeCredits(), replace(profile, refined), profile.unique());
        rules.validate(next);
        return new CraftResult(next, MaterialId.of(rules.base().craftCost("refine")), 0);
    }

    /** Dust for the first Perfecting step on a slot; each step already bought on it adds {@link #PERFECT_STEP_DUST}. */
    public static final int PERFECT_BASE_DUST = 30;
    public static final int PERFECT_STEP_DUST = 10;

    /** The lowest value a slot can roll now: its raised floor, else its band's minimum. */
    public int effectiveFloor(OrdinarySlot slot) {
        return Math.max(slot.floor(), rules.affix(slot.affixId()).band(slot.rank()).min());
    }

    /**
     * What raising this slot's floor one more point costs in Resonance Dust: the steps already bought are how far the floor stands above the
     * band's minimum, so the price rises by {@link #PERFECT_STEP_DUST} with each one. Only meaningful for a slot that can be perfected.
     */
    public int perfectCost(OrdinarySlot slot) {
        int steps = Math.max(0, effectiveFloor(slot) - rules.affix(slot.affixId()).band(slot.rank()).min());
        return PERFECT_BASE_DUST + PERFECT_STEP_DUST * steps;
    }

    /**
     * Endgame polish: raises a rank V slot's <b>floor</b> (the lowest value it can roll) by one point, up to the band maximum, for Resonance
     * Dust only. If the value was below the new floor it rises to it; if it was already higher it stays. The floor is stored on the slot and a
     * Refine rolls between it and the band maximum, so polish is never undone by rerolling (only a Reforge, which replaces the affix, clears it).
     * Fully raised, the slot always holds its band maximum. No roll, previewable, and never leaves the rank's band, so it adds depth without
     * raising the power ceiling.
     */
    public CraftResult perfect(ProfileV1 profile, String slotId) {
        rules.validate(profile);
        var old = requireSlot(profile, slotId);
        if (old.sealed()) throw new CraftException(Reason.SLOT_SEALED, "A sealed slot can only be reforged");
        if (old.rank() < RankedAffix.RANKS)
            throw new CraftException(Reason.NOT_MAX_RANK, "Only a rank V slot can be perfected");
        var band = rules.affix(old.affixId()).band(old.rank());
        int current = effectiveFloor(old);
        if (current >= band.max())
            throw new CraftException(Reason.AT_BAND_MAX, "This slot's floor is already at the top of its band");
        long cost = perfectCost(old);
        int floor = current + 1;
        var polished = new OrdinarySlot(old.slotId(), old.category(), old.rank(), old.affixId(), old.parameters(),
                Math.max(old.rolledValue(), floor), old.definitionVersion(), floor);
        var next = profile.advance(profile.rarity(), profile.highestLevelObserved(), profile.awardedMilestones(),
                profile.spentUpgradeCredits(), replace(profile, polished), profile.unique());
        rules.validate(next);
        return new CraftResult(next, Map.of(MaterialId.RESONANCE_DUST, cost), 0);
    }

    // ---- Overreach: the one gamble ----------------------------------------------------------------------

    /** Chance in percent that an Overreach succeeds. */
    public static final int OVERREACH_CHANCE_PERCENT = 45;
    /** A successful Overreach adds between these percentages of the band maximum (at least 1 point) above it. */
    public static final int OVERREACH_MIN_PERCENT = 10;
    public static final int OVERREACH_MAX_PERCENT = 25;
    /** What one attempt costs: dust and facets, never cores (the Tower stays the only core source). */
    public static final int OVERREACH_DUST = 60;
    public static final int OVERREACH_FACETS = 2;
    /** Tower Marks one attempt costs, and one Unique install costs (bound proof of a Tower clear). */
    public static final int OVERREACH_MARKS = 1;
    public static final int INSTALL_MARKS = 1;

    /** The materials one Overreach attempt costs. */
    /** Apex Shards an Overreach also costs while the Threat system is on (balance phase 1). */
    public static final int OVERREACH_APEX = 1;

    public static Map<MaterialId, Long> overreachCost() {
        if (Tunables.current().threat().enabled())
            return Map.of(MaterialId.RESONANCE_DUST, (long) OVERREACH_DUST, MaterialId.FACET, (long) OVERREACH_FACETS, MaterialId.TOWER_MARK, (long) OVERREACH_MARKS,
                    MaterialId.APEX_SHARD, (long) OVERREACH_APEX);
        return Map.of(MaterialId.RESONANCE_DUST, (long) OVERREACH_DUST, MaterialId.FACET, (long) OVERREACH_FACETS, MaterialId.TOWER_MARK, (long) OVERREACH_MARKS);
    }

    /**
     * The endgame gamble. A rank V slot sitting at its band maximum, with no attempt made on it yet, can Overreach: with
     * {@link #OVERREACH_CHANCE_PERCENT} percent chance its value rises above the band maximum by 10 to 25 percent of that maximum (at least one
     * point), a permanent gain; otherwise the value falls to a random point in the lower half of the band, ignoring any floor. Win or lose the slot
     * is <b>sealed</b>: it can no longer be Refined or Perfected, only Reforged, and it has no floor. One attempt per slot per affix (a Reforge makes
     * a fresh slot). The ceiling it raises is bounded by {@link RankedRules#overreachCeiling}, and the combat channel caps still apply.
     * The caller supplies the random source; the outcome is the difference between the old and the new value.
     */
    public CraftResult overreach(ProfileV1 profile, String slotId, RandomGenerator random) {
        rules.validate(profile);
        var old = requireSlot(profile, slotId);
        if (old.sealed()) throw new CraftException(Reason.SLOT_SEALED, "This slot has already had its attempt");
        if (old.rank() < RankedAffix.RANKS) throw new CraftException(Reason.NOT_MAX_RANK, "Only a rank V slot can overreach");
        var band = rules.affix(old.affixId()).band(old.rank());
        if (old.rolledValue() < band.max()) throw new CraftException(Reason.NOT_AT_BAND_MAX, "The slot must be at its band maximum");
        int value;
        if (random.nextInt(100) < OVERREACH_CHANCE_PERCENT) {
            int percent = random.nextInt(OVERREACH_MIN_PERCENT, OVERREACH_MAX_PERCENT + 1);
            int bonus = Math.max(1, Math.round(band.max() * percent / 100f));
            value = Math.min(RankedRules.overreachCeiling(band.max()), band.max() + bonus);
        } else {
            int top = band.min() + (band.max() - band.min()) / 2;
            value = random.nextInt(band.min(), top + 1);
        }
        var sealed = new OrdinarySlot(old.slotId(), old.category(), old.rank(), old.affixId(), old.parameters(), value, old.definitionVersion(), 0, true);
        var next = profile.advance(profile.rarity(), profile.highestLevelObserved(), profile.awardedMilestones(),
                profile.spentUpgradeCredits(), replace(profile, sealed), profile.unique());
        rules.validate(next);
        return new CraftResult(next, overreachCost(), 0);
    }

    /** Adds lifetime attunement (a Pokemon's battle-earned progress toward promotion). Free; the caller guarantees once-per-encounter. */
    public CraftResult awardAttunement(ProfileV1 profile, int points) {
        if (points < 1) throw new CraftException(Reason.INVALID_AMOUNT, "Attunement points must be positive");
        rules.validate(profile);
        return new CraftResult(profile.advanceAttunement(points), Map.of(), 0);
    }

    /** Adds exactly one rank-I slot. Credits, ranks, Unique and attunement are retained. */
    public CraftResult promote(ProfileV1 profile, List<String> types, RandomGenerator random) {
        rules.validate(profile);
        if (profile.rarity() == Rarity.MYTHICAL)
            throw new CraftException(Reason.MAX_RARITY, "Already at maximum rarity");
        Rules.Promotion promotion = rules.base().promotion(profile.rarity(), Tunables.current().threat().enabled());
        if (profile.attunement() < promotion.attunement())
            throw new CraftException(Reason.INSUFFICIENT_ATTUNEMENT, "Insufficient attunement");
        Category added = null;
        for (var category : Category.values()) {
            if (rules.slotCount(promotion.to(), category) > rules.slotCount(profile.rarity(), category)) added = category;
        }
        if (added == null) throw new IllegalStateException("Promotion adds no slot");
        var slots = new ArrayList<>(profile.ordinarySlots());
        slots.add(draw(OrdinarySlot.slotId(added, profile.slotCount(added)), added, slots, 1, types, random));
        var next = profile.advance(promotion.to(), profile.highestLevelObserved(), profile.awardedMilestones(),
                profile.spentUpgradeCredits(), slots, profile.unique());
        rules.validate(next);
        return new CraftResult(next, MaterialId.of(promotion.cost()), 0);
    }

    /** Powers a Catalyst could currently install or swap in. Empty means no Catalyst may be consumed. */
    public List<UniqueDefinition> eligibleUniques(ProfileV1 profile) {
        rules.validate(profile);
        return rules.uniques().stream()
                .filter(u -> profile.unique() == null || !u.id().equals(profile.unique().uniqueId())).toList();
    }

    public CraftResult installUnique(ProfileV1 profile, String uniqueId, UUID operationId) {
        rules.validate(profile);
        if (profile.unique() != null) throw new CraftException(Reason.UNIQUE_PRESENT, "A Unique is already installed");
        return setUnique(profile, uniqueId, operationId, Map.of(MaterialId.UNIQUE_CATALYST, 1L, MaterialId.TOWER_MARK, (long) INSTALL_MARKS));
    }

    public CraftResult replaceUnique(ProfileV1 profile, String uniqueId, UUID operationId) {
        rules.validate(profile);
        if (profile.unique() == null) throw new CraftException(Reason.NO_UNIQUE, "No Unique to replace");
        if (profile.unique().uniqueId().equals(uniqueId))
            throw new CraftException(Reason.SAME_UNIQUE, "Replacement must be a different Unique");
        return setUnique(profile, uniqueId, operationId, Map.of(MaterialId.UNIQUE_CATALYST, 1L, MaterialId.TOWER_MARK, (long) INSTALL_MARKS));
    }

    // ---- Respec items (docs/RESPEC-DESIGN.md) ------------------------------------------------------------------

    /** Whether a Rank Respec would be refused or pointless, as a reason (empty when it can be done). Never throws. */
    public String respecRanksBlock(ProfileV1 profile) {
        if (profile.ordinarySlots().isEmpty()) return "This Pokemon has no modifiers to reset";
        for (var slot : profile.ordinarySlots())
            if (slot.sealed()) return "An Overreached Pokemon is sealed for good and cannot be respecced";
        boolean anything = false;
        for (var slot : profile.ordinarySlots()) if (slot.rank() > 1 || slot.floor() > 0) anything = true;
        return anything ? "" : "Every modifier is already rank I: nothing to undo";
    }

    /**
     * The Hourglass of Undoing: every ordinary slot returns to rank I (its value rerolled inside the rank I band, its perfected floor cleared) and
     * every upgrade credit becomes pending again. The affixes, the Unique, the rarity, the attunement and the earned milestones are kept; no
     * material is refunded. A Pokemon with an Overreach-sealed slot can never be respecced (Overreach is a point of no return), and a Pokemon with
     * nothing above rank I has nothing to undo; both are refused, so the item is not spent. Costs one Hourglass.
     */
    public CraftResult respecRanks(ProfileV1 profile, RandomGenerator random) {
        rules.validate(profile);
        var slots = new ArrayList<OrdinarySlot>();
        String block = respecRanksBlock(profile);
        if (!block.isEmpty()) {
            boolean sealed = profile.ordinarySlots().stream().anyMatch(OrdinarySlot::sealed);
            throw new CraftException(sealed ? Reason.RESPEC_SEALED : Reason.NOTHING_TO_RESPEC, block);
        }
        for (var slot : profile.ordinarySlots()) {
            var value = rules.affix(slot.affixId()).band(1).roll(random);
            slots.add(new OrdinarySlot(slot.slotId(), slot.category(), 1, slot.affixId(), slot.parameters(), value, slot.definitionVersion()));
        }
        var next = profile.advance(profile.rarity(), profile.highestLevelObserved(), profile.awardedMilestones(), 0, slots, profile.unique());
        rules.validate(next);
        return new CraftResult(next, Map.of(MaterialId.RANK_RESPEC, 1L), 0);
    }

    /**
     * The Unbinding Prism: replaces the held Unique with a different one for one Prism, with no Catalyst and no Mark (a normal Replace costs both).
     * Same rules as {@link #replaceUnique}: there must be a Unique to replace and the new one must differ.
     */
    public CraftResult respecUnique(ProfileV1 profile, String uniqueId, UUID operationId) {
        rules.validate(profile);
        if (profile.unique() == null) throw new CraftException(Reason.NO_UNIQUE, "No Unique to swap: install one with a Catalyst first");
        if (profile.unique().uniqueId().equals(uniqueId))
            throw new CraftException(Reason.SAME_UNIQUE, "Replacement must be a different Unique");
        return setUnique(profile, uniqueId, operationId, Map.of(MaterialId.UNIQUE_RESPEC, 1L));
    }

    private CraftResult setUnique(ProfileV1 profile, String uniqueId, UUID operationId, Map<MaterialId, Long> cost) {
        var definition = rules.unique(uniqueId)
                .orElseThrow(() -> new CraftException(Reason.UNKNOWN_UNIQUE, "Unknown Unique: " + uniqueId));
        var instance = new UniqueInstance(definition.id(), definition.definitionVersion(), operationId);
        var next = profile.advance(profile.rarity(), profile.highestLevelObserved(), profile.awardedMilestones(),
                profile.spentUpgradeCredits(), profile.ordinarySlots(), instance);
        rules.validate(next);
        return new CraftResult(next, cost, 0);
    }

    private static OrdinarySlot requireSlot(ProfileV1 profile, String slotId) {
        return profile.slot(slotId)
                .orElseThrow(() -> new CraftException(Reason.UNKNOWN_SLOT, "Unknown ordinary slot: " + slotId));
    }

    private static List<OrdinarySlot> replace(ProfileV1 profile, OrdinarySlot replacement) {
        var slots = new ArrayList<>(profile.ordinarySlots());
        slots.replaceAll(s -> s.slotId().equals(replacement.slotId()) ? replacement : s);
        return slots;
    }

    private OrdinarySlot draw(String slotId, Category category, Collection<OrdinarySlot> others, int rank,
                              List<String> types, RandomGenerator random) {
        var families = new HashSet<String>();
        others.forEach(slot -> families.add(rules.affix(slot.affixId()).family()));
        var eligible = rules.affixes().stream()
                .filter(a -> a.category() == category && !families.contains(a.family())).toList();
        int total = eligible.stream().mapToInt(RankedAffix::weight).sum();
        if (total == 0) throw new CraftException(Reason.NO_ELIGIBLE_AFFIX, "No eligible affix family");
        int ticket = random.nextInt(total);
        RankedAffix selected = null;
        for (var affix : eligible) {
            ticket -= affix.weight();
            if (ticket < 0) { selected = affix; break; }
        }
        Objects.requireNonNull(selected);
        Map<String, String> parameters = Map.of();
        var rule = selected.base().parameter();
        if (rule != null) {
            var pool = rule.equals("one_current_species_type") ? types.stream().distinct().toList() : Rules.TYPES;
            if (pool.isEmpty() || !Rules.TYPES.containsAll(pool))
                throw new IllegalArgumentException("Invalid species type pool");
            parameters = Map.of("type", pool.get(random.nextInt(pool.size())));
        }
        return new OrdinarySlot(slotId, category, rank, selected.id(), parameters,
                selected.band(rank).roll(random), selected.definitionVersion());
    }
}
