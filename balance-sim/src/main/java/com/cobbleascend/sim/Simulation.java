package com.cobbleascend.sim;

import com.cobbleascend.domain.Rarity;
import com.cobbleascend.domain.Rules;
import com.cobbleascend.domain.v1.MaterialId;
import com.cobbleascend.domain.v1.RaidRewardBands;
import com.cobbleascend.domain.v1.RankedRules;
import com.cobbleascend.domain.v1.ThreatAdvice;
import com.cobbleascend.domain.v1.Tunables;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Ninety days of the Threat economy with synthetic players and no Minecraft (balance phase 3). Everything that is a rule comes
 * from the shipped domain code: promotion prices ({@link Rules#promotion(Rarity, boolean)}), Tower completion pay
 * ({@code TowerCompletionBands.payoutThreat}), raid pay ({@link RaidRewardBands#pay}), the door's advice ({@link ThreatAdvice})
 * and the daily caps ({@code ThreatSettings}). What cannot be computed without a server is a stated model in {@link Model}: how
 * long a clear takes, how often a team wins at each Threat gap, how often a raid boss is caught.
 *
 * <p>Four parts, each checked: the <b>ledger</b> (every material minted and spent balances against what players hold), the
 * <b>ecology</b> (wild and raid captures improving teams), the <b>economy</b> (pay in, promotions out) and <b>stability</b> (in
 * the last 30 days, sinks keep up with faucets and wallets stop growing without bound). Deterministic for a seed.
 */
public final class Simulation {

    /** The modelled parts of play: change these when the phase-0 logs measure them. */
    public static final class Model {
        /** Minutes per Tower clear (boss rush, spec: at most an hour) and per raid including forming the group. */
        public double towerMinutes = 45, raidMinutes = 15;
        /** Win chance by Threat gap (threat - advice): -1 or less, 0, +1, +2, +3 or more. The spec's targets. */
        public double[] winByGap = {0.95, 0.85, 0.50, 0.15, 0.03};
        /** A group this big fights one Threat lower (spec: four Rare teams beat a powerhouse). */
        public int groupBonusAt = 4;
        /** Attunement a Tower clear gives every Pokemon of the team (spec: 15-25 a Tower). */
        public int towerAttunement = 20;
        /** Chance a raid winner catches the boss, and wild captures a play day. */
        public double raidCatchChance = 0.25;
        public int wildCapturesPerDay = 1;
        /** Challenge cards a run carries (scores the completion chest). */
        public int challengeCards = 4;
        /** Raid charges: a player holds at most this many (0 = no limit), regaining {@code raidChargeRegenPerHour} an hour; they start each play day full. */
        public int raidChargeCap = 3;
        public double raidChargeRegenPerHour = 1.0;
        /**
         * Sink prototypes ({@link Sinks}), off by default. {@code recycle}: players in the {@code recycleParticipation} share send every unwanted Pokemon to the
         * Reclaimer. {@code gamble}: players in the {@code gambleParticipation} share, once they hold a Mythic, stake dust above {@code wagerReserve} at the
         * Wager Altar, the largest tier they can cover, up to {@code wagerPlaysPerDay} plays a play day.
         */
        public boolean recycle = false, gamble = false;
        public double recycleParticipation = 0.8, gambleParticipation = 0.4;
        public int wagerReserve = 5000, wagerPlaysPerDay = 3;
        /** Facets, Cores and Apex Shards a gambler keeps back for the promotions still ahead of them. */
        public int wagerReserveFacets = 100, wagerReserveCores = 20, wagerReserveApex = 40;    }

    private final long seed;
    private final int days;
    private final Model model;
    private final Rules rules;
    private final RankedRules ranked;
    private final Tunables tunables;
    private final List<SimPlayer> players = new ArrayList<>();
    private final Ledger ledger;
    private final Random random;
    /** A stream of its own for the sink prototypes, so switching them on never changes the base economy's rolls. */
    private final Random sinkRandom;
    public final Sinks.Stats sinks = new Sinks.Stats();
    private int encounters;
    /** Day of each player's last tower / raid, for the "both in the last week" figure. */
    private final Map<UUID, int[]> lastActivity = new java.util.HashMap<>();

    public Simulation(long seed, int playerCount, int days, Model model) {
        this.seed = seed;
        this.days = days;
        this.model = model;
        this.rules = Rules.defaults();
        this.ranked = RankedRules.defaults();
        this.tunables = Tunables.defaults();
        this.ledger = new Ledger(days);
        this.random = new Random(seed);
        this.sinkRandom = new Random(seed * 31 + 7);
        double total = 0;
        for (Archetype a : Archetype.values()) total += a.weight;
        for (int i = 0; i < playerCount; i++) {
            double pick = random.nextDouble() * total;
            Archetype chosen = Archetype.REGULAR;
            for (Archetype a : Archetype.values()) {
                pick -= a.weight;
                if (pick < 0) { chosen = a; break; }
            }
            SimPlayer player = new SimPlayer(new UUID(seed, i), chosen);
            // A fresh team is six wild captures from the ordinary table.
            for (int slot = 0; slot < 6; slot++) player.team[slot] = ranked.base().rollRarity(random);
            players.add(player);
        }
        // Who uses the sinks: drawn from the sink stream after the players exist, so the base rolls above are untouched.
        for (SimPlayer player : players) {
            player.reclaimer = sinkRandom.nextDouble() < model.recycleParticipation;
            player.gambler = sinkRandom.nextDouble() < model.gambleParticipation;
        }
    }

    public List<SimPlayer> players() { return players; }

    public Ledger ledger() { return ledger; }

    public int days() { return days; }

    /** Whether either sink prototype was switched on for this run. */
    public boolean sinksOn() { return model.recycle || model.gamble; }

    public long seed() { return seed; }

    /** Runs every day. Returns this for chaining. */
    public Simulation run() {
        for (int day = 0; day < days; day++) runDay(day);
        return this;
    }

    private void runDay(int day) {
        List<SimPlayer> raiders = new ArrayList<>();
        for (SimPlayer player : players) {
            player.coreRollsToday = 0;
            player.apexPayoutsToday = 0;
            player.playedToday = false;
            if (random.nextInt(7) >= player.archetype.daysPerWeek) continue;
            player.playedToday = true;
            // Ecology: wild captures on a play day.
            for (int c = 0; c < model.wildCapturesPerDay; c++) player.offer(ranked.base().rollRarity(random));
            double minutes = player.archetype.hoursPerDay * 60 * (0.75 + random.nextDouble() * 0.5);
            double towerMinutes = minutes * player.archetype.towerShare;
            for (double t = model.towerMinutes; t <= towerMinutes; t += model.towerMinutes) tower(player, day);
            int raids = (int) ((minutes - towerMinutes) / model.raidMinutes);
            // Charges: the full tank plus what regenerates over the session, and no more raids than that.
            if (model.raidChargeCap > 0) raids = Math.min(raids, model.raidChargeCap + (int) Math.floor(minutes / 60.0 * model.raidChargeRegenPerHour));
            for (int r = 0; r < raids; r++) raiders.add(player);
        }
        // Raids are group content: the day's raid slots are shuffled into groups of 2-8.
        java.util.Collections.shuffle(raiders, random);
        int at = 0;
        while (at < raiders.size()) {
            int size = Math.min(raiders.size() - at, 2 + random.nextInt(7));
            List<SimPlayer> group = new ArrayList<>(new java.util.LinkedHashSet<>(raiders.subList(at, at + size)));
            at += size;
            raid(group, day);
        }
        for (SimPlayer player : players) {
            reclaim(player, day);
            promote(player, day);
            wager(player, day);
        }
    }

    /** R1: today's unwanted Pokemon become dust by rarity (the first 20 in full, then a quarter); everyone else's are simply let go. */
    private void reclaim(SimPlayer player, int day) {
        if (model.recycle && player.reclaimer) {
            int count = 0;
            for (Rarity rarity : player.unwanted) {
                long dust = Sinks.RECLAIM_DUST[rarity.ordinal()];
                if (count++ >= Sinks.RECLAIM_FULL_PER_DAY) dust = Math.max(1, dust * Sinks.RECLAIM_REDUCED_PERCENT / 100);
                ledger.mint(player, day, Map.of(MaterialId.RESONANCE_DUST, dust));
                sinks.reclaimed++;
                sinks.reclaimedDust += dust;
                sinks.reclaimers.add(player.id);
            }
        }
        player.unwanted.clear();
    }

    /**
     * G1: a player with a Mythic stakes the dearest tier their spare materials cover (after a reserve of each kept for the rest of their promotions), up
     * to a few plays a play day.
     */
    private void wager(SimPlayer player, int day) {
        if (!model.gamble || !player.gambler || !player.playedToday || player.firstMythicDay < 0) return;
        // Each tier at most once a day, dearest first, while the plays last.
        int plays = 0;
        for (int tier = Sinks.TIERS.length - 1; tier >= 0 && plays < model.wagerPlaysPerDay; tier--) {
            var stake = Sinks.TIERS[tier];
            if (!covers(player, stake)) continue;
            ledger.burn(player, day, MaterialId.RESONANCE_DUST, stake.dust());
            ledger.burn(player, day, MaterialId.FACET, stake.facets());
            ledger.burn(player, day, MaterialId.ASCENSION_CORE, stake.cores());
            ledger.burn(player, day, MaterialId.APEX_SHARD, stake.apex());
            sinks.wager(player.id, tier, sinkRandom.nextDouble());
            plays++;
        }
    }

    private boolean covers(SimPlayer player, Sinks.Tier stake) {
        return player.wallet.getOrDefault(MaterialId.RESONANCE_DUST, 0L) - model.wagerReserve >= stake.dust()
                && player.wallet.getOrDefault(MaterialId.FACET, 0L) - model.wagerReserveFacets >= stake.facets()
                && player.wallet.getOrDefault(MaterialId.ASCENSION_CORE, 0L) - model.wagerReserveCores >= stake.cores()
                && player.wallet.getOrDefault(MaterialId.APEX_SHARD, 0L) - model.wagerReserveApex >= stake.apex();
    }

    private double winChance(int gap) {
        int index = Math.max(0, Math.min(4, gap + 1));
        return model.winByGap[index];
    }

    private static int gapIndex(int gap) { return Math.max(0, Math.min(4, gap + 2)); }

    private void tower(SimPlayer player, int day) {
        int advice = ThreatAdvice.recommended(player.meanRarity());
        int threat = advice;
        double roll = random.nextDouble();
        if (roll < player.archetype.ambition) threat = Math.min(6, advice + 1);
        else if (roll < player.archetype.ambition + 0.05) threat = Math.max(1, advice - 1);
        int gap = threat - advice;
        player.towerRuns++;
        player.runsByGap[gapIndex(gap)]++;
        lastActivity.computeIfAbsent(player.id, k -> new int[] {-99, -99})[0] = day;
        boolean won = random.nextDouble() < winChance(gap);
        var mini = tunables.miniChest();
        if (!won) {
            // A lost run usually got past floor 5: the mini chest without its Core.
            if (random.nextBoolean()) ledger.mint(player, day, Map.of(MaterialId.RESONANCE_DUST, (long) mini.dust(), MaterialId.FACET, (long) mini.facets()));
            return;
        }
        player.towerWins++;
        player.winsByGap[gapIndex(gap)]++;
        var settings = tunables.threat();
        boolean capped = player.coreRollsToday >= settings.fullCoreRollsPerDay();
        player.coreRollsToday++;
        var completion = tunables.towerCompletion().payoutThreat("sim-tower-" + (encounters++), player.id, threat,
                model.challengeCards, 0, false, capped, settings.reducedPercent());
        EnumMap<MaterialId, Long> pay = new EnumMap<>(MaterialId.class);
        pay.putAll(completion.materials());
        pay.merge(MaterialId.RESONANCE_DUST, (long) mini.dust(), Long::sum);
        pay.merge(MaterialId.FACET, (long) mini.facets(), Long::sum);
        ledger.mint(player, day, pay);
        player.attune(model.towerAttunement);
    }

    private void raid(List<SimPlayer> group, int day) {
        int n = group.size();
        double mean = group.stream().mapToDouble(SimPlayer::meanRarity).average().orElse(0);
        int advice = ThreatAdvice.recommended(mean);
        // The group takes the highest tier its advice covers (starter II, powerhouse IV, legendary V, mythical VI).
        RaidRewardBands.Tier tier = advice >= 6 ? RaidRewardBands.Tier.MYTHICAL : advice >= 5 ? RaidRewardBands.Tier.LEGENDARY
                : advice >= 4 ? RaidRewardBands.Tier.POWERHOUSE : RaidRewardBands.Tier.STARTER;
        int threat = switch (tier) { case STARTER -> 2; case POWERHOUSE -> 4; case LEGENDARY -> 5; default -> 6; };
        int gap = threat - advice - (n >= model.groupBonusAt ? 1 : 0);
        boolean won = random.nextDouble() < winChance(gap);
        String encounter = "sim-raid-" + (encounters++);
        double[] weights = new double[n];
        double total = 0;
        for (int i = 0; i < n; i++) total += weights[i] = Math.exp(random.nextGaussian() * 0.4);
        for (int i = 0; i < n; i++) {
            SimPlayer player = group.get(i);
            player.raidRuns++;
            lastActivity.computeIfAbsent(player.id, k -> new int[] {-99, -99})[1] = day;
            if (!won) continue;
            player.raidWins++;
            double share = weights[i] / total;
            if (RaidRewardBands.tooSmall(share, n)) continue;
            var settings = tunables.threat();
            boolean capped = player.apexPayoutsToday >= settings.fullApexPayoutsPerDay();
            player.apexPayoutsToday++;
            // The first raid payout of the day pays double, as AscensionRewards.settleRaid does (the day's Apex counter is still zero).
            var pay = RaidRewardBands.pay(encounter, player.id, tier, share, n, capped, settings.reducedPercent(), player.apexPayoutsToday == 1);
            ledger.mint(player, day, pay.materials());
            player.attune(pay.attunement());
            // Ecology: a caught raid boss, never below the tier's rarity floor (CobbleRaids CaptureRarityFloor).
            if (random.nextDouble() < model.raidCatchChance) {
                Rarity floor = switch (tier) { case STARTER -> Rarity.UNCOMMON; case POWERHOUSE, LEGENDARY -> Rarity.RARE; default -> Rarity.EPIC; };
                Rarity rolled = ranked.base().rollRarity(random);
                player.offer(rolled.ordinal() >= floor.ordinal() ? rolled : floor);
            }
        }
    }

    /** Spends on the highest-rarity Pokemon first: players chase their first Legendary and Mythic. */
    private void promote(SimPlayer player, int day) {
        boolean promoted = true;
        while (promoted) {
            promoted = false;
            Integer[] order = new Integer[6];
            for (int i = 0; i < 6; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> player.team[b].ordinal() - player.team[a].ordinal());
            int focus = order[0];
            boolean focusDone = player.team[focus] == Rarity.MYTHICAL;
            for (int i : order) {
                Rarity from = player.team[i];
                if (from == Rarity.MYTHICAL) continue;
                var promotion = rules.promotion(from, true);
                // Saving for the first Mythic: Cores and Apex go to the best Pokemon until it is Mythic; the rest of the team only
                // takes the dust-and-facet steps meanwhile.
                boolean scarce = promotion.cost().cores() > 0 || promotion.cost().apex() > 0;
                if (scarce && i != focus && !focusDone) continue;
                if (player.attunement[i] < promotion.attunement() || !ledger.canAfford(player, promotion.cost())) continue;
                ledger.spend(player, day, promotion.cost());
                player.attunement[i] -= promotion.attunement();
                player.team[i] = promotion.to();
                player.promotions++;
                promoted = true;
                break;
            }
        }
        if (player.firstLegendaryDay < 0 && player.best().ordinal() >= Rarity.LEGENDARY.ordinal()) player.firstLegendaryDay = day;
        if (player.firstMythicDay < 0 && player.best() == Rarity.MYTHICAL) player.firstMythicDay = day;
        int[] last = lastActivity.get(player.id);
        if (day == days - 1 && last != null) {
            player.towerLastWeek = last[0] >= days - 7;
            player.raidLastWeek = last[1] >= days - 7;
        }
    }
}
