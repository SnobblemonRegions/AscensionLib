package com.cobbleascend.domain.v1;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * Field Research (docs/FIELD-RESEARCH-DESIGN.md): research levels, where research comes from, the raid catch bonus and the Research Beacon. A
 * progression of its own, separate from Ascension profiles. Every number is provisional until the owner approves it (decision 6 and the
 * balance sim).
 *
 * <p><b>Exploit rules this class pins.</b> Every grant has a deterministic store operation ID ({@link #operation}), so a retried, replayed or
 * doubled callback (two raid callbacks, a double-click, a crash between the write and the reply) replays the first result instead of paying
 * twice. Research is never spent and never goes down, and a species can never add more than its share, so farming one species past its share
 * earns nothing. The raid catch bonus can never push a chance past {@link #RAID_CATCH_CAP} and never lowers a chance.
 */
public final class FieldResearch {
    private FieldResearch() {}

    /** Family points at which levels 1 to 4 are reached. */
    public static final int[] LEVEL_THRESHOLDS = {10, 40, 100, 200};
    public static final int MAX_LEVEL = LEVEL_THRESHOLDS.length;
    /** The level that unlocks the Boss Dossier (decision 11) and the one that unlocks the Research Beacon (decision 12). */
    public static final int DOSSIER_LEVEL = 2, BEACON_LEVEL = 4;
    /** The largest single research grant any source can make; anything larger is a bug, refused rather than paid. */
    public static final int MAX_GRANT = 1_000;

    /** Boss research, per species: each analyzed Boss Sample adds this many percent, up to {@link #BOSS_MAX_PERCENT}. */
    public static final int BOSS_PERCENT_PER_SAMPLE = 10, BOSS_MAX_PERCENT = 100;
    /** No raid catch chance boosted by research may exceed this (owner, decision 19). */
    public static final double RAID_CATCH_CAP = 0.25;
    /** Research Beacons a player may use per UTC day (decision 12). */
    public static final int BEACONS_PER_DAY = 1;

    /** Level 0 to 4 for a family meter holding {@code points}. */
    public static int levelOf(int points) {
        int level = 0;
        for (int threshold : LEVEL_THRESHOLDS) if (points >= threshold) level++;
        return level;
    }

    /** Raid tiers as CobbleRaids names them; research paid for a won raid against that tier's boss. */
    public enum RaidTier {
        STARTER(5), POWERHOUSE(8), LEGENDARY(10), MYTHICAL(13);

        public final int research;

        RaidTier(int research) { this.research = research; }

        /** CobbleRaids' tier id ({@code starter}, {@code powerhouse}, ...); throws for anything else. */
        public static RaidTier fromId(String id) {
            return valueOf(id.trim().toUpperCase(Locale.ROOT));
        }
    }

    /**
     * Where research comes from (owner decision 5: the Bench, raids and Laboratories only) and how much each pays: a quarter of the first
     * numbers (owner, 2026-11-22, after the balance sim put a family at level 4 within a week; now about day 21).
     */
    public enum Source {
        BENCH_QUICK(3), BENCH_STANDARD(10), RAID(0), LAB_DATA_DRIVE(10), LAB_LOCKER(4);

        /** Points paid; {@link #RAID} pays by tier instead ({@link RaidTier#research}). */
        public final int points;

        Source(int points) { this.points = points; }
    }

    /**
     * The store operation that pays one grant. One per (kind, key, player): a raid win is keyed by the boss entity's UUID, a Laboratory search by
     * its container, a Bench study by its job, so the same event can never pay a player twice, while every player in a raid is paid once each.
     */
    public static UUID operation(String kind, String key, UUID player) {
        if (kind == null || kind.isBlank() || key == null || key.isBlank() || player == null)
            throw new IllegalArgumentException("A research operation needs a kind, a key and a player");
        // Each part is length-prefixed, so no key (container keys come from world data) can contain a separator that forges another event's ID.
        String text = "ascensionlib:field-research:v1|" + kind.length() + ":" + kind + "|" + key.length() + ":" + key + "|" + player;
        return UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The raid catch chance with boss research (decision 19): {@code base x (1 + percent/100)}, capped at {@link #RAID_CATCH_CAP}. Research
     * never lowers a chance, so a server whose base chance is already above the cap keeps its base. Bad input (NaN, negative) is treated as 0.
     */
    public static double raidCatchChance(double base, int bossPercent) {
        if (!(base > 0)) return 0;                                                                  // NaN, zero or negative
        double safeBase = Math.min(base, 1.0);
        if (safeBase >= RAID_CATCH_CAP) return safeBase;
        int percent = Math.max(0, Math.min(BOSS_MAX_PERCENT, bossPercent));
        return Math.min(RAID_CATCH_CAP, safeBase * (1.0 + percent / 100.0));
    }

    /** The multiplier CobbleRaids applies before its own cap: 1.0 to 2.0. */
    public static double raidCatchMultiplier(int bossPercent) {
        return 1.0 + Math.max(0, Math.min(BOSS_MAX_PERCENT, bossPercent)) / 100.0;
    }

    /** Whether the player may read the Boss Dossier for a family at this level. */
    public static boolean dossierUnlocked(int familyLevel) { return familyLevel >= DOSSIER_LEVEL; }

    /** Why a Research Beacon cannot start, or {@link BeaconCheck#OK}. */
    public enum BeaconCheck { OK, NEEDS_FAMILY_LEVEL_4, NEEDS_FULL_BOSS_RESEARCH, DAILY_LIMIT, MEMBER_NOT_RESEARCHED, NO_PARTICIPANTS }

    /** One participant's standing for the Beacon's species: their level on its family, their boss research on it, Beacons used today. */
    public record BeaconStanding(UUID player, int familyLevel, int bossPercent, int beaconsUsedToday) {}

    /** Whether one player has that species "fully researched" (owner follow-up 2: family level 4 and 100% boss research). */
    public static boolean fullyResearched(int familyLevel, int bossPercent) {
        return familyLevel >= BEACON_LEVEL && bossPercent >= BOSS_MAX_PERCENT;
    }

    /**
     * Whether a Beacon raid may start (decision 12): the owner (first in the list) must have the species fully researched and a Beacon left today;
     * every other participant must also have it fully researched. One attempt: the store consumes the Beacon when the raid starts, win or lose.
     */
    public static BeaconCheck beaconCheck(List<BeaconStanding> participants) {
        if (participants == null || participants.isEmpty()) return BeaconCheck.NO_PARTICIPANTS;
        var owner = participants.get(0);
        if (owner.familyLevel() < BEACON_LEVEL) return BeaconCheck.NEEDS_FAMILY_LEVEL_4;
        if (owner.bossPercent() < BOSS_MAX_PERCENT) return BeaconCheck.NEEDS_FULL_BOSS_RESEARCH;
        if (owner.beaconsUsedToday() >= BEACONS_PER_DAY) return BeaconCheck.DAILY_LIMIT;
        var seen = new java.util.HashSet<UUID>();
        for (var p : participants) {
            if (!seen.add(p.player())) return BeaconCheck.MEMBER_NOT_RESEARCHED;                       // the same player listed twice is refused
            if (!fullyResearched(p.familyLevel(), p.bossPercent())) return BeaconCheck.MEMBER_NOT_RESEARCHED;
        }
        return BeaconCheck.OK;
    }

    /** The lab-guard species (Laboratories' spawn file) whose research a Specimen Locker pays. */
    public static final List<String> LAB_GUARD_SPECIES = List.of("ditto", "klink", "magnemite", "magneton", "porygon", "voltorb");

    /**
     * Which guard species a Specimen Locker's research goes to for this player: drawn once, seeded by (container, player), among the guard species
     * the server knows, so a retried claim pays the same species. Empty when the server has none of them.
     */
    public static java.util.Optional<String> lockerSpecies(String containerKey, UUID player, ResearchFamilies.Catalog catalog) {
        var known = LAB_GUARD_SPECIES.stream().filter(catalog::knows).toList();
        if (known.isEmpty()) return java.util.Optional.empty();
        var random = new Random(RewardSeed.of("field-research-locker", containerKey, player));
        return java.util.Optional.of(known.get(random.nextInt(known.size())));
    }

    /**
     * Bound research items, held in the store's {@code research_items} table, never in the wallet and never tradable (owner decision 12): a Boss
     * Sample is of one species (from a raid win against that species' boss); a Data Drive (from a Laboratory terminal) is for any species the
     * player chooses. Ids are stored: never rename.
     */
    public enum Item {
        BOSS_SAMPLE("boss_sample", true), DATA_DRIVE("data_drive", false);

        public final String id;
        /** Whether each item belongs to one species (a sample) or to none (a drive, stored with species ""). */
        public final boolean perSpecies;

        Item(String id, boolean perSpecies) {
            this.id = id;
            this.perSpecies = perSpecies;
        }
    }

    /** The counter names {@code research_days} keeps per player and day or week: one per serum kind, and Beacons. */
    public static final String BEACON_COUNTER = "beacon";

    /** The UTC day number (days since 1970-01-01) for a server clock reading. */
    public static long utcDay(long epochMillis) { return Math.floorDiv(epochMillis, 86_400_000L); }

    /** The UTC week number, weeks starting on Monday (1970-01-01 was a Thursday). */
    public static long utcWeek(long epochMillis) { return Math.floorDiv(utcDay(epochMillis) + 3, 7); }
}
