package com.cobbleascend.sim;

import com.cobbleascend.domain.Rarity;
import com.cobbleascend.domain.v1.MaterialId;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.UUID;

/** One synthetic player: a six-Pokemon team (rarity and attunement each), a wallet, the day's caps and what happened to them. */
public final class SimPlayer {
    public final UUID id;
    public final Archetype archetype;
    public final Rarity[] team = new Rarity[6];
    public final int[] attunement = new int[6];
    public final EnumMap<MaterialId, Long> wallet = new EnumMap<>(MaterialId.class);

    /** Today's full-odds Core rolls and full Apex payouts used (AscensionLib's threat_daily counters). */
    int coreRollsToday;
    int apexPayoutsToday;
    /** Whether they played today; the Pokemon they caught today and do not want; and whether they use the Reclaimer / the Wager Altar (the sink prototypes). */
    boolean playedToday;
    final java.util.List<Rarity> unwanted = new java.util.ArrayList<>();
    boolean reclaimer, gambler;
    /** First day (0-based) the team held a Legendary / a Mythic; -1 if never. */
    public int firstLegendaryDay = -1;
    public int firstMythicDay = -1;
    public int towerRuns, towerWins, raidRuns, raidWins, promotions, captures;
    /** Whether they did each activity in the last 7 days of the run (the "both by choice" target). */
    public boolean towerLastWeek, raidLastWeek;
    /** Wins and runs by Threat gap (threat - advice + 2, so index 2 is "at the advice"). */
    public final int[] runsByGap = new int[5];
    public final int[] winsByGap = new int[5];

    SimPlayer(UUID id, Archetype archetype) {
        this.id = id;
        this.archetype = archetype;
        Arrays.fill(team, Rarity.COMMON);
    }

    /** Mean rarity ordinal, 0 Common to 5 Mythic, what ThreatAdvice reads. */
    public double meanRarity() {
        double sum = 0;
        for (Rarity r : team) sum += r.ordinal();
        return sum / team.length;
    }

    public Rarity best() {
        Rarity best = Rarity.COMMON;
        for (Rarity r : team) if (r.ordinal() > best.ordinal()) best = r;
        return best;
    }

    int weakest() {
        int at = 0;
        for (int i = 1; i < team.length; i++) if (team[i].ordinal() < team[at].ordinal()) at = i;
        return at;
    }

    void attune(int amount) {
        for (int i = 0; i < attunement.length; i++) attunement[i] += amount;
    }

    /**
     * A caught Pokemon replaces the weakest if it is better (it starts unattuned). Whichever one is left over, the replaced Pokemon or the catch itself, is
     * unwanted: it waits in {@link #unwanted} for the Reclaimer prototype and is otherwise ignored.
     */
    void offer(Rarity caught) {
        int at = weakest();
        if (caught.ordinal() > team[at].ordinal()) {
            unwanted.add(team[at]);
            team[at] = caught;
            attunement[at] = 0;
            captures++;
        } else {
            unwanted.add(caught);
        }
    }
}
