package com.cobbleascend.sim;

/**
 * A kind of synthetic player: how long they play, how they split it between Towers and raids, how often they show up and how
 * ambitious they are at the door. The mix is the spec's pacing model (Casual, Regular, Tower-heavy, Raid-heavy, Grinder).
 */
public enum Archetype {
    CASUAL(0.75, 0.5, 4, 0.10, 0.20),
    REGULAR(1.5, 0.5, 6, 0.20, 0.40),
    TOWER_HEAVY(1.5, 0.8, 6, 0.20, 0.15),
    RAID_HEAVY(1.5, 0.2, 6, 0.20, 0.15),
    GRINDER(4.0, 0.5, 7, 0.30, 0.10);

    /** Hours on a day they play. */
    public final double hoursPerDay;
    /** Share of that time spent in Towers; the rest is raids. */
    public final double towerShare;
    /** Days a week they log in. */
    public final int daysPerWeek;
    /** Chance of picking one Threat above the advice at the Tower door. */
    public final double ambition;
    /** Share of the population. */
    public final double weight;

    Archetype(double hoursPerDay, double towerShare, int daysPerWeek, double ambition, double weight) {
        this.hoursPerDay = hoursPerDay;
        this.towerShare = towerShare;
        this.daysPerWeek = daysPerWeek;
        this.ambition = ambition;
        this.weight = weight;
    }
}
