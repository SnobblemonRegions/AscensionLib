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
    }

    private final long seed;
    private final int days;
    private final Model model;
    private final Rules rules;
    private final RankedRules ranked;
    private final Tunables tunables;
    private final List<SimPlayer> players = new ArrayList<>();
    private final Ledger ledger;
    private final Random random;
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
    }

    public List<SimPlayer> players() { return players; }

    public Ledger ledger() { return ledger; }

    public int days() { return days; }

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
            if (random.nextInt(7) >= player.archetype.daysPerWeek) continue;
            // Ecology: wild captures on a play day.
            for (int c = 0; c < model.wildCapturesPerDay; c++) player.offer(ranked.base().rollRarity(random));
            double minutes = player.archetype.hoursPerDay * 60 * (0.75 + random.nextDouble() * 0.5);
            double towerMinutes = minutes * player.archetype.towerShare;
            for (double t = model.towerMinutes; t <= towerMinutes; t += model.towerMinutes) tower(player, day);
            int raids = (int) ((minutes - towerMinutes) / model.raidMinutes);
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
        for (SimPlayer player : players) promote(player, day);
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
            var pay = RaidRewardBands.pay(encounter, player.id, tier, share, n, capped, settings.reducedPercent());
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
