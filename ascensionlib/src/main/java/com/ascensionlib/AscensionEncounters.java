package com.ascensionlib;

import com.ascensionlib.scout.ScoutEncounters;
import java.util.Collection;
import java.util.UUID;

/**
 * The contract for a mod that owns an encounter (CobbleTowers, CobbleRaids) to let its players scout the enemies with a
 * Scouter. Signatures use only {@code java.*}, so a caller can reach it by reflection when the library is optional
 * for it (see {@link AscensionRewards}). Call on the server thread.
 *
 * <p>Declare every enemy the players may scout when the encounter starts, and call {@link #end} when it is over: a
 * Scouter reveals one enemy of one encounter and nothing outlives it. The encounter ID must be unique and never
 * reused, and must not contain {@code |}. Nothing declared here is ever a profile; the library only generates the
 * enemy's rarity and modifiers (from the ID, so a retry or a second declaration gives the same enemy) and shows
 * it once a player has spent a Scouter on it.
 */
public final class AscensionEncounters {
    private AscensionEncounters() {}

    /**
     * Declares one enemy of an encounter. Repeating a call for the same (encounter, enemy index) changes nothing but
     * can add participants.
     *
     * @param encounterId  the encounter's own ID
     * @param participants who may scout it (and see a shared reveal)
     * @param enemyIndex   0-based and unique within the encounter
     * @param tierId       a tier of {@code enemy-tiers.json}: {@code threat_1} to {@code threat_7}, or a legacy
     *                     {@code trial_rank_1}, {@code trial_rank_2}, {@code trial_rank_3} or {@code boss}, which fights as
     *                     its Threat band when the {@code threat.enabled} setting is on
     * @param boss         whether its reveal is shared with every participant (otherwise personal to the one who scouts)
     * @param uniqueId     a boss's fixed Unique, or null/empty for none
     * @param speciesId    a Cobblemon species id such as {@code cobblemon:charizard}
     * @param level        the level it fights at
     * @return {@code DECLARED}, {@code DISABLED} (no running world; nothing is scoutable), {@code UNKNOWN_SPECIES} or
     *         {@code INVALID}
     */
    public static String declareEnemy(String encounterId, Collection<UUID> participants, int enemyIndex, String tierId,
                                      boolean boss, String uniqueId, String speciesId, int level) {
        return ScoutEncounters.declareEnemy(encounterId, participants, enemyIndex, tierId, boss, uniqueId, speciesId,
                level);
    }

    /**
     * Arms the next battle of these players to fight with an encounter's declared enemies: their rarity and modifiers
     * then act in that battle. Call it just before starting the battle and {@link #disarmBattle} right after (in a
     * {@code finally}), the way a floor arms its other battle effects. The encounter must already be declared, so the
     * enemy that was scouted is the one that fights. Without arming, a wild Pokemon fights with its derived rating and a
     * trainer or NPC fights natively.
     *
     * @param encounterId a declared encounter, or {@code null}/empty to make the next battle explicitly native (no
     *                    enemy effects, not even a wild rating): an exhibition, for instance
     */
    public static void armBattle(Collection<UUID> players, String encounterId) {
        ScoutEncounters.armBattle(players, encounterId);
    }

    /** Ends {@link #armBattle} for these players. Safe to call when nothing was armed. */
    public static void disarmBattle(Collection<UUID> players) {
        ScoutEncounters.disarmBattle(players);
    }

    /**
     * One player leaves an encounter that carries on for the others (withdrew, died, was dropped): they stop being a participant, are no longer armed for
     * it and their scouting panel clears. The encounter itself is ended, for everyone, by the call that ends it. Safe to call for a player who is not in it.
     */
    public static void leaveEncounter(String encounterId, java.util.UUID player) {
        ScoutEncounters.leave(encounterId, player);
    }

    /** The encounter is over (any outcome): forgets its enemies and every reveal. Safe to call twice. */
    /** Whether the Threat system is on ({@code threat.enabled} in config/ascensionlib.json), so a caller knows whether to offer Threat bands. */
    public static boolean threatEnabled() {
        return com.cobbleascend.domain.v1.Tunables.current().threat().enabled();
    }

    /**
     * The tower door's advice (balance phase 1): the Threat band (1-6) these players' teams are built for, from the mean rarity of the profiled Pokemon in
     * the online players' parties ({@code ThreatAdvice}). 1 when nobody is online or nothing is profiled. Advice only: no band is ever locked.
     */
    public static int recommendedThreat(java.util.Collection<java.util.UUID> players) {
        double mean = com.ascensionlib.telemetry.BalanceLog.meanTeamRarity(players);
        return com.cobbleascend.domain.v1.ThreatAdvice.recommended(mean);
    }

    /**
     * What each Threat band an entry door offers (1-6) is, as a JSON array for a screen that lets a player pick one: {@code band}, {@code mix} (the enemy
     * rarities as {@code {rarity, percent}}), {@code credits}, {@code bossCredits}, {@code unique}, {@code bossUnique}, and a Silver completion's {@code dust},
     * {@code facets}, {@code corePercent} and {@code apex}. It reads the enemy tiers in force and the payout's own methods, so it shows what the game does,
     * including an owner's override file. {@code "[]"} when it cannot say. Read-only; call on the server thread.
     */
    public static String threatBands() {
        return ScoutEncounters.threatBandsJson();
    }

    public static void end(String encounterId) {
        ScoutEncounters.end(encounterId);
    }
}
