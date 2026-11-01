package com.ascensionlib;

import com.ascensionlib.telemetry.BalanceLog;
import com.cobbleascend.domain.v1.MaterialId;
import com.cobbleascend.domain.v1.ScouterDrops;
import com.cobbleascend.domain.v1.TowerCompletionBands;
import com.cobbleascend.domain.v1.TowerRewardBands;
import com.cobbleascend.store.EncounterRewards;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The contract for a mod that owns an encounter (CobbleRaids, CobbleTowers) to pay its participants in the shared
 * wallet exactly once. Signatures use only {@code java.*}, so a caller can reach it by reflection when the library is
 * optional for it. The caller decides what each participant earned; the library decides only whether it is paid
 * (a victory, once per encounter, player and reward kind) and records it durably.
 *
 * <p>Call it from the encounter's end callback on the server thread, passing the encounter's own ID, which must be
 * unique and never reused. Calling again after a crash or a duplicate callback is the intended recovery: it pays
 * whoever was not yet paid and changes nothing for the rest.
 */
public final class AscensionRewards {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AscensionLib.MOD_ID);

    private AscensionRewards() {}

    /**
     * Pays a CobbleTowers boss victory from the library's own bands ({@code TowerRewardBands}), flat per player, so
     * the caller supplies no amounts. Same exactly-once rules and result statuses as {@link #settle}; reward kind
     * {@code tower_boss}. Amounts are seeded by (encounter, player, boss floor), so a retry recomputes the same.
     *
     * @param encounterId the boss encounter's own ID (never reused)
     * @param outcome     as for {@link #settle}
     * @param fromFloor   first floor since the previous boss (1 after the entrance)
     * @param bossFloor   the boss floor just cleared
     * @param players     every participant to pay
     */
    public static Map<UUID, String> settleTowerBoss(UUID encounterId, String outcome, int fromFloor, int bossFloor,
                                                    java.util.Collection<UUID> players) {
        var payouts = new LinkedHashMap<UUID, Map<String, Long>>();
        for (var player : players) {
            var amounts = new LinkedHashMap<String, Long>();
            TowerRewardBands.DEFAULTS.payout(encounterId.toString(), player, fromFloor, bossFloor)
                    .forEach((material, amount) -> amounts.put(material.id(), amount));
            payouts.put(player, amounts);
        }
        BalanceLog.encounter("tower_boss", encounterId, Map.of("fromFloor", fromFloor, "bossFloor", bossFloor, "players", players.size()), players);
        var result = settleBudgeted(encounterId, outcome, "tower_boss", payouts);
        var paid = paidPlayers(result);
        awardAttunement("tower_boss:" + encounterId, paid);
        settleSigilDrops(encounterId, outcome, bossFloor, players);
        return result;
    }

    /**
     * Pays a cleared tower cycle's F10 champion: the hour-long completion chest from {@code TowerCompletionBands} (a guaranteed pile, a quality tier
     * earned by difficulty and cleanness, and a crown of one to three rolls), flat per player apart from seeded rolls. Gives each paid player's party
     * the tier's attunement (12 Bronze to 22 Mythic; with the F5 boss's 3 a tower pays 15 to 25), shows each newly paid player the reveal page (chat lines without WebGUI) and announces a Core, a Catalyst or a Mythic tier to the
     * party. Same exactly-once rules and statuses as {@link #settle}; reward kind {@code tower_completion}. Amounts are seeded by (encounter, player),
     * so a retry pays the same chest. The F5 boss stays on {@link #settleTowerBoss} (a mini chest). Armor pieces and CobbleCards tickets that the crown
     * rolls are CobbleTowers' own items: read them with {@link #towerCompletionExtras}.
     *
     * @param encounterId    the champion encounter's own ID (never reused)
     * @param outcome        as for {@link #settle}
     * @param bossFloor      the floor just cleared (10 for the first cycle); must be at least 1
     * @param cycle          the Ascension cycle, 0 for the first run
     * @param challengeCards the challenge (modifier) cards the run took
     * @param flawless       whether nobody was knocked out
     * @param players        every participant to pay
     * @throws IllegalArgumentException for a malformed call
     */
    public static Map<UUID, String> settleTowerCompletion(UUID encounterId, String outcome, int bossFloor, int cycle, int challengeCards,
                                                          boolean flawless, java.util.Collection<UUID> players) {
        if (bossFloor < 1) throw new IllegalArgumentException("Boss floor must be at least 1");
        var bands = com.cobbleascend.domain.v1.Tunables.current().towerCompletion();
        var payouts = new LinkedHashMap<UUID, Map<String, Long>>();
        var completions = new LinkedHashMap<UUID, TowerCompletionBands.Completion>();
        for (var player : players) {
            var completion = bands.payout(encounterId.toString(), player, challengeCards, cycle, flawless);
            completions.put(player, completion);
            var amounts = new LinkedHashMap<String, Long>();
            completion.materials().forEach((material, amount) -> amounts.put(material.id(), amount));
            payouts.put(player, amounts);
        }
        BalanceLog.encounter("tower_completion", encounterId, Map.of("bossFloor", bossFloor, "cycle", cycle, "cards", challengeCards,
                "flawless", flawless, "players", players.size()), players);
        var result = settleBudgeted(encounterId, outcome, "tower_completion", payouts);
        var paid = paidPlayers(result);
        int attunement = bands.tierFor(TowerCompletionBands.score(challengeCards, cycle, flawless)).attunement();
        awardAttunement("tower_completion:" + encounterId, paid, attunement);
        var fresh = new java.util.ArrayList<UUID>();
        result.forEach((player, status) -> { if (status.equals("GRANTED")) fresh.add(player); });
        reveal(fresh, completions, cycle, challengeCards, flawless, attunement, players);
        return result;
    }

    // ---- Threat (balance phase 1, docs/BALANCE-THREAT.md) ---------------------------------------------------------------------------------

    /** The daily Threat counters in the store: tower completions that rolled a Core at full odds, and raid wins paid Apex in full. */
    static final String CORE_COUNTER = "core_roll";
    static final String APEX_COUNTER = "apex";

    private static int usedToday(UUID player, String counter) {
        return AscensionApi.store().map(store -> store.threatUsedToday(player, counter)).orElse(0);
    }

    /**
     * {@link #settleTowerCompletion(UUID, String, int, int, int, boolean, java.util.Collection)} for a run fought at a Threat band. With the
     * {@code threat.enabled} setting on, the pile's dust and facets come from the band and Cores from one roll at the band's chance (see
     * {@code TowerCompletionBands.payoutThreat}), at a reduced chance after the player's full-odds rolls for the UTC day; with it off this is exactly the
     * call without {@code threat}. Read CobbleTowers' own crown items with {@link #towerCompletionExtras(UUID, UUID, int, int, boolean, int)}.
     *
     * @param threat the band the champion was fought at, 1 to 7
     */
    public static Map<UUID, String> settleTowerCompletion(UUID encounterId, String outcome, int bossFloor, int cycle, int challengeCards,
                                                          boolean flawless, java.util.Collection<UUID> players, int threat) {
        var settings = com.cobbleascend.domain.v1.Tunables.current().threat();
        if (!settings.enabled()) return settleTowerCompletion(encounterId, outcome, bossFloor, cycle, challengeCards, flawless, players);
        if (bossFloor < 1) throw new IllegalArgumentException("Boss floor must be at least 1");
        if (threat < 1 || threat > 7) throw new IllegalArgumentException("Threat must be 1-7");
        var bands = com.cobbleascend.domain.v1.Tunables.current().towerCompletion();
        var payouts = new LinkedHashMap<UUID, Map<String, Long>>();
        var completions = new LinkedHashMap<UUID, TowerCompletionBands.Completion>();
        for (var player : players) {
            boolean capped = usedToday(player, CORE_COUNTER) >= settings.fullCoreRollsPerDay();
            var completion = bands.payoutThreat(encounterId.toString(), player, threat, challengeCards, cycle, flawless, capped, settings.reducedPercent());
            completions.put(player, completion);
            var amounts = new LinkedHashMap<String, Long>();
            completion.materials().forEach((material, amount) -> amounts.put(material.id(), amount));
            payouts.put(player, amounts);
        }
        BalanceLog.encounter("tower_completion", encounterId, Map.of("bossFloor", bossFloor, "cycle", cycle, "cards", challengeCards,
                "flawless", flawless, "players", players.size(), "threat", threat), players);
        var result = settleBudgeted(encounterId, outcome, "tower_completion", payouts, CORE_COUNTER);
        var paid = paidPlayers(result);
        int attunement = bands.tierFor(TowerCompletionBands.score(challengeCards, cycle, flawless)).attunement();
        awardAttunement("tower_completion:" + encounterId, paid, attunement);
        var fresh = new java.util.ArrayList<UUID>();
        result.forEach((player, status) -> { if (status.equals("GRANTED")) fresh.add(player); });
        reveal(fresh, completions, cycle, challengeCards, flawless, attunement, players);
        return result;
    }

    /** {@link #towerCompletionExtras(UUID, UUID, int, int, boolean)} for a Threat completion; the crown is the same whether the Core roll was capped. */
    public static Map<String, Integer> towerCompletionExtras(UUID encounterId, UUID player, int cycle, int challengeCards, boolean flawless, int threat) {
        if (!com.cobbleascend.domain.v1.Tunables.current().threat().enabled()) return towerCompletionExtras(encounterId, player, cycle, challengeCards, flawless);
        var completion = com.cobbleascend.domain.v1.Tunables.current().towerCompletion()
                .payoutThreat(encounterId.toString(), player, threat, challengeCards, cycle, flawless, false, 100);
        var extras = new LinkedHashMap<String, Integer>();
        extras.put("armor", completion.armorPieces());
        extras.put("tickets", completion.tickets());
        extras.put("tier", completion.tier().ordinal());
        return extras;
    }

    /**
     * {@link #settleTowerBoss(UUID, String, int, int, java.util.Collection)} for a run fought at a Threat band. The F5 mini chest is the same at every
     * band; the band is only recorded in the telemetry, so CobbleTowers can call one signature whether or not Threat is on.
     */
    public static Map<UUID, String> settleTowerBoss(UUID encounterId, String outcome, int fromFloor, int bossFloor,
                                                    java.util.Collection<UUID> players, int threat) {
        if (com.cobbleascend.domain.v1.Tunables.current().threat().enabled())
            BalanceLog.encounter("tower_boss_threat", encounterId, Map.of("bossFloor", bossFloor, "threat", threat, "players", players.size()), players);
        return settleTowerBoss(encounterId, outcome, fromFloor, bossFloor, players);
    }

    /**
     * Pays a finished raid while the Threat system is on: Apex Shards (raids are their only source), attunement and dust by the raid's tier, each scaled by
     * the player's share of the damage ({@code share x players}, clamped to 0.5-1.5; see {@code RaidRewardBands}). A player under a quarter of an even
     * split is paid nothing ({@code TOO_SMALL}). After a player's full-pay raids for the UTC day, Apex is cut to the reduced share. Each player is paid
     * once per encounter; a retry replays. With Threat off it awards the old flat attunement ({@link #settleRaidAttunement}) and pays no materials,
     * answering {@code THREAT_OFF} for each player. CobbleRaids keeps its own loot and Raid Points either way.
     *
     * @param encounterId the raid's encounter ID, as for {@link #settleRaidAttunement}
     * @param outcome     {@code VICTORY}, {@code DEFEAT} or {@code ABORTED}
     * @param raidTier    {@code starter}, {@code powerhouse}, {@code legendary}, {@code mythical} or {@code world_boss}
     * @param shares      every participant with their share of the boss's damage, 0 to 1 (an absent or invalid share counts as an even split)
     * @return per player: {@code GRANTED}, {@code ALREADY_GRANTED}, {@code NOT_PAID}, {@code TOO_SMALL}, {@code CONFLICT}, {@code REFUSED},
     *         {@code DISABLED} or {@code THREAT_OFF}
     */
    public static Map<UUID, String> settleRaid(String encounterId, String outcome, String raidTier, Map<UUID, Double> shares) {
        if (encounterId == null || encounterId.isBlank() || encounterId.contains("|")) throw new IllegalArgumentException("Invalid encounter ID");
        var tier = com.cobbleascend.domain.v1.RaidRewardBands.Tier.fromId(raidTier);
        var settings = com.cobbleascend.domain.v1.Tunables.current().threat();
        var players = new java.util.ArrayList<>(shares.keySet());
        if (!settings.enabled()) {
            settleRaidAttunement(encounterId, outcome, players);
            var off = new LinkedHashMap<UUID, String>();
            players.forEach(player -> off.put(player, "THREAT_OFF"));
            return off;
        }
        BalanceLog.encounter("raid_threat", encounterId, Map.of("outcome", String.valueOf(outcome), "tier", tier.name().toLowerCase(java.util.Locale.ROOT),
                "players", players.size()), players);
        int n = players.size();
        var payouts = new LinkedHashMap<UUID, Map<String, Long>>();
        var attunement = new LinkedHashMap<UUID, Integer>();
        var skipped = new LinkedHashMap<UUID, String>();
        for (var player : players) {
            double share = shares.get(player) == null ? -1 : shares.get(player);
            if (com.cobbleascend.domain.v1.RaidRewardBands.tooSmall(share, n)) { skipped.put(player, "TOO_SMALL"); continue; }
            int usedToday = usedToday(player, APEX_COUNTER);
            boolean capped = usedToday >= settings.fullApexPayoutsPerDay();
            // The first raid payout of the UTC day pays double: the day's counter is still zero.
            var pay = com.cobbleascend.domain.v1.RaidRewardBands.pay(encounterId, player, tier, share, n, capped, settings.reducedPercent(), usedToday == 0);
            var amounts = new LinkedHashMap<String, Long>();
            pay.materials().forEach((material, amount) -> amounts.put(material.id(), amount));
            payouts.put(player, amounts);
            attunement.put(player, pay.attunement());
        }
        var result = new LinkedHashMap<UUID, String>();
        if (!payouts.isEmpty()) {
            var settled = settleBudgeted(raidEncounterUuid(encounterId), outcome, "raid_" + tier.name().toLowerCase(java.util.Locale.ROOT), payouts, APEX_COUNTER);
            result.putAll(settled);
            settled.forEach((player, status) -> {
                if (status.equals("GRANTED")) awardAttunement("raid:" + encounterId, java.util.List.of(player), attunement.get(player));
            });
        }
        result.putAll(skipped);
        return result;
    }

    /** A raid's string encounter ID as the UUID the store keys settlements by: name-based, so a retry finds the same settlement. */
    static UUID raidEncounterUuid(String encounterId) {
        return UUID.nameUUIDFromBytes(("ascensionlib:raid|" + encounterId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * The parts of a completion chest that CobbleTowers hands over itself, computed from the same seed as {@link #settleTowerCompletion} so they agree:
     * {@code armor} (Tower armor pieces), {@code tickets} (CobbleCards tickets) and {@code tier} (0 Bronze, 1 Silver, 2 Gold, 3 Mythic). Pure: it pays
     * nothing, so call it only for a player whose settlement came back {@code GRANTED} (or {@code ALREADY_GRANTED} when recovering) and hand the items over once.
     */
    public static Map<String, Integer> towerCompletionExtras(UUID encounterId, UUID player, int cycle, int challengeCards, boolean flawless) {
        var completion = com.cobbleascend.domain.v1.Tunables.current().towerCompletion().payout(encounterId.toString(), player, challengeCards, cycle, flawless);
        var extras = new LinkedHashMap<String, Integer>();
        extras.put("armor", completion.armorPieces());
        extras.put("tickets", completion.tickets());
        extras.put("tier", completion.tier().ordinal());
        return extras;
    }

    /**
     * What a completion chest can pay, for a mod that wants to show it (CobbleTowers' chest preview) instead of copying numbers that drift when the
     * balance is tuned. Pure and read-only: it pays nothing and reads the live tunables. One entry per tier, in order (BRONZE, SILVER, GOLD, MYTHIC),
     * each a map of {@code minScore} (int), {@code pilePercent} (int), {@code crownSlots} (int), {@code attunement} (int, party attunement the completion pays on top of the F5 boss's flat share), {@code pile} (material id to amount, guaranteed) and
     * {@code crown} (crown item, lower case, to its percent chance on each slot, one decimal). {@code armor} and {@code ticket} are CobbleTowers' own items.
     */
    public static Map<String, Map<String, Object>> towerCompletionPreview() {
        var bands = com.cobbleascend.domain.v1.Tunables.current().towerCompletion();
        var preview = new LinkedHashMap<String, Map<String, Object>>();
        for (var spec : bands.tiers()) {
            var pile = new LinkedHashMap<String, Long>();
            bands.pileFor(spec).forEach((material, amount) -> pile.put(material.id(), amount));
            int total = 0;
            for (int weight : spec.weights()) total += weight;
            var crown = new LinkedHashMap<String, Double>();
            for (var item : TowerCompletionBands.Crown.values()) {
                int weight = spec.weights()[item.ordinal()];
                if (weight > 0) crown.put(item.name().toLowerCase(java.util.Locale.ROOT), Math.round(weight * 1000.0 / total) / 10.0);
            }
            var tier = new LinkedHashMap<String, Object>();
            tier.put("minScore", spec.minScore());
            tier.put("pilePercent", spec.multiplierPercent());
            tier.put("crownSlots", spec.crownSlots());
            tier.put("attunement", spec.attunement());
            tier.put("pile", pile);
            tier.put("crown", crown);
            preview.put(spec.tier().name(), tier);
        }
        return preview;
    }

    /** The players whose settlement came back paid now or earlier: the ones a reward's side effects (attunement, the reveal) still apply to. */
    private static java.util.List<UUID> paidPlayers(Map<UUID, String> result) {
        var paid = new java.util.ArrayList<UUID>();
        result.forEach((player, status) -> { if (status.equals("GRANTED") || status.equals("ALREADY_GRANTED")) paid.add(player); });
        return paid;
    }

    private static void reveal(java.util.Collection<UUID> fresh, Map<UUID, TowerCompletionBands.Completion> completions, int cycle, int cards,
                               boolean flawless, int attunement, java.util.Collection<UUID> party) {
        var server = AscensionApi.server();
        if (server == null) return;
        try {
            for (var player : fresh) {
                var online = server.getPlayerList().getPlayer(player);
                if (online == null) continue;
                var view = com.ascensionlib.reward.CompletionReveal.view(completions.get(player), cycle, cards, flawless, attunement, false);
                com.ascensionlib.reward.CompletionReveal.show(online, view);
                String line = com.ascensionlib.reward.CompletionReveal.announcement(online.getGameProfile().getName(), view);
                if (line.isEmpty()) continue;
                for (var member : party) {
                    var friend = server.getPlayerList().getPlayer(member);
                    if (friend != null && !friend.getUUID().equals(player)) friend.sendSystemMessage(net.minecraft.network.chat.Component.literal(line));
                }
            }
        } catch (RuntimeException exception) {
            LOG.warn("Tower completion reveal failed; the payout already made stands: {}", exception.toString());
        }
    }

    /**
     * Rolls the rare Ascension Sigil drop for a won tower boss, once per participant ({@code SigilDrops}), reward kind
     * {@code tower_sigil}. Seeded by (encounter, player), so a retry recomputes the same winners. Called by {@link #settleTowerBoss},
     * so CobbleTowers needs no change. A failure here never changes the boss payout already made.
     */
    static void settleSigilDrops(UUID encounterId, String outcome, int bossFloor, java.util.Collection<UUID> players) {
        var payouts = new LinkedHashMap<UUID, Map<String, Long>>();
        for (var player : players)
            if (com.cobbleascend.domain.v1.SigilDrops.DEFAULTS.rollTower(encounterId.toString(), player, bossFloor))
                payouts.put(player, Map.of(MaterialId.ASCENSION_SIGIL.id(), 1L));
        if (payouts.isEmpty()) return;
        try {
            settle(encounterId, outcome, "tower_sigil", payouts);
        } catch (RuntimeException exception) {
            LOG.warn("Sigil drop for tower boss {} not recorded: {}", encounterId, exception.toString());
        }
    }

    /**
     * Pays a finished ascensionDungeons run from the library's own bands ({@code DungeonRewardBands}), flat per player, so the caller
     * supplies no amounts: dust for the run's tier and each arena raid won, Facets from arena wins and a boss, Unique Fragments from a
     * boss, never Ascension Cores. Also gives each paid player's party +3 attunement (once per run) and rolls the rare Ascension Sigil
     * ({@code SigilDrops.rollDungeon}, reward kind {@code dungeon_sigil}). Same exactly-once rules and statuses as {@link #settle}; reward
     * kind {@code dungeon_run}. Amounts are seeded by (run, player, tier), so a retry recomputes the same.
     *
     * @param runId        the run's own ID (never reused)
     * @param outcome      {@code VICTORY} when the run counts as a success (the dungeon decides: for example at least one arena won or the
     *                     boss beaten); {@code DEFEAT} and {@code ABORTED} pay nothing
     * @param tier         the run's zone tier, 1..10 (the one passed to {@code AscensionZones.openZone})
     * @param arenaWins    arena raids the party won, 0..16
     * @param bossDefeated whether the run's final boss was beaten
     * @param players      every participant to pay (those who left early are the caller's decision)
     * @throws IllegalArgumentException for a malformed call (unknown outcome, tier or arena count out of range)
     */
    public static Map<UUID, String> settleDungeonRun(UUID runId, String outcome, int tier, int arenaWins, boolean bossDefeated,
                                                     java.util.Collection<UUID> players) {
        var payouts = new LinkedHashMap<UUID, Map<String, Long>>();
        for (var player : players) {
            var amounts = new LinkedHashMap<String, Long>();
            com.cobbleascend.domain.v1.DungeonRewardBands.DEFAULTS.payout(runId.toString(), player, tier, arenaWins, bossDefeated)
                    .forEach((material, amount) -> amounts.put(material.id(), amount));
            payouts.put(player, amounts);
        }
        BalanceLog.encounter("dungeon_run", runId, Map.of("tier", tier, "arenaWins", arenaWins, "boss", bossDefeated, "players", players.size()), players);
        var result = settleBudgeted(runId, outcome, "dungeon_run", payouts);
        var paid = paidPlayers(result);
        awardAttunement("dungeon:" + runId, paid);
        var sigils = new LinkedHashMap<UUID, Map<String, Long>>();
        for (var player : players)
            if (com.cobbleascend.domain.v1.SigilDrops.DEFAULTS.rollDungeon(runId.toString(), player, tier))
                sigils.put(player, Map.of(MaterialId.ASCENSION_SIGIL.id(), 1L));
        if (!sigils.isEmpty()) {
            try {
                settle(runId, outcome, "dungeon_sigil", sigils);
            } catch (RuntimeException exception) {
                LOG.warn("Sigil drop for dungeon run {} not recorded: {}", runId, exception.toString());
            }
        }
        return result;
    }

    /**
     * Pays a won CobbleTowers Trial (a floor-limited run) from {@code TrialRewardBands}: flat per player, victory only, reward kind
     * {@code trial_rank_N}, once per (encounter, player), and +3 attunement to each paid player's party. No entry gate or daily
     * budget yet. Same statuses as {@link #settle}.
     *
     * @param encounterId the encounter that ends the trial (its final floor's boss; never reused)
     * @param rank        1-3; {@code TrialRewardBands.rankForFloorLimit} maps a trial's floor limit to it
     */
    public static Map<UUID, String> settleTrial(UUID encounterId, String outcome, int rank, java.util.Collection<UUID> players) {
        var amounts = new LinkedHashMap<String, Long>();
        com.cobbleascend.domain.v1.TrialRewardBands.payout(rank).forEach((material, amount) -> amounts.put(material.id(), amount));
        var payouts = new LinkedHashMap<UUID, Map<String, Long>>();
        for (var player : players) payouts.put(player, amounts);
        BalanceLog.encounter("trial_rank_" + rank, encounterId, Map.of("rank", rank, "players", players.size()), players);
        var result = settleBudgeted(encounterId, outcome, "trial_rank_" + rank, payouts);
        var paid = paidPlayers(result);
        awardAttunement("trial:" + encounterId, paid);
        return result;
    }

    /**
     * Gives each participant's party attunement for a won raid (a Pokemon's progress toward promotion), without paying any material:
     * Raids keeps its own rewards. Only {@code VICTORY} counts; call it for the players who earned the victory. Once per
     * (encounter, Pokemon), so a repeated call changes nothing more. Players who are offline miss it.
     *
     * @param encounterId the raid's own encounter ID (never reused); it may be any string without {@code |}
     * @param outcome     {@code VICTORY}, {@code DEFEAT} or {@code ABORTED}
     * @return how many players' parties were handled; 0 when the library is disabled or the outcome is not a victory
     */
    public static int settleRaidAttunement(String encounterId, String outcome, java.util.Collection<UUID> players) {
        boolean validId = encounterId != null && !encounterId.isBlank() && !encounterId.contains("|");
        // Balance phase 0: each raid settled here is an encounter event, so raid results by team rarity can be measured.
        if (validId) BalanceLog.encounter("raid", encounterId, Map.of("outcome", String.valueOf(outcome), "players", players.size()), players);
        if (!"VICTORY".equals(outcome)) return 0;
        if (!validId) throw new IllegalArgumentException("Invalid encounter ID");
        return awardAttunement("raid:" + encounterId, players);
    }

    /**
     * The quantity of an item reward after the 777 Unique (+20%) or a Transcendent built on it (its signature's percent times its benefit share): the best one in the player's party, otherwise
     * {@code count} unchanged. A fraction of an item is settled by a roll seeded by {@code key}, so a retried or repeated grant of the
     * same reward gives the same amount. For item rewards only (never materials, currency or cards). Call it when the reward is
     * handed over, with the player online; offline, disabled or without 777 it returns {@code count}.
     *
     * @param key identifies the reward (source, item, claim), so the rounding is stable
     */
    public static int scaleItemQuantity(UUID player, int count, String key) {
        if (count < 1) return count;
        var service = AscensionApi.service().orElse(null);
        var server = AscensionApi.server();
        if (service == null || server == null) return count;
        var online = server.getPlayerList().getPlayer(player);
        if (online == null) return count;
        int percent = service.itemRewardPercent(online);
        return percent <= 0 ? count : com.cobbleascend.domain.v1.ItemQuantityBonus.scale(count, player + "|" + key, percent);
    }


    /** Credits each player's party, once per key; players who are offline miss it. Returns the players handled. */
    private static int awardAttunement(String key, java.util.Collection<UUID> players) {
        return awardAttunement(key, players, com.cobbleascend.domain.v1.Tunables.current().attunementPerVictory());
    }

    private static int awardAttunement(String key, java.util.Collection<UUID> players, int points) {
        if (points < 1) return 0;
        var service = AscensionApi.service().orElse(null);
        var server = AscensionApi.server();
        if (service == null || server == null) return 0;
        int handled = 0;
        for (var player : players) {
            var online = server.getPlayerList().getPlayer(player);
            if (online == null) continue;
            service.awardPartyAttunement(online, key, points);
            handled++;
        }
        return handled;
    }

    /**
     * Rolls the Scouter drop for a cleared tower floor, once per participant ({@code ScouterDrops}: 5%, 15% on a
     * Keen Eye floor), and pays each winner one Scouter. Reward kind {@code scouter_drop}; the roll is seeded by
     * (encounter, player), so a retry recomputes the same winners. Returns only the players who rolled a drop (empty
     * when nobody did, in which case nothing is written); statuses are as for {@link #settle}.
     *
     * @param encounterId  the floor's boss encounter ID (never reused)
     * @param keenEyeFloor whether the floor uses the raised chance
     */
    public static Map<UUID, String> settleScouterDrops(UUID encounterId, String outcome, boolean keenEyeFloor,
                                                       java.util.Collection<UUID> players) {
        var payouts = new LinkedHashMap<UUID, Map<String, Long>>();
        for (var player : players) {
            if (ScouterDrops.DEFAULTS.rollFor(keenEyeFloor, encounterId.toString(), player))
                payouts.put(player, Map.of(MaterialId.SCOUTER.id(), 1L));
        }
        if (payouts.isEmpty()) return new LinkedHashMap<>();
        return settle(encounterId, outcome, "scouter_drop", payouts);
    }

    /**
     * Like {@link #settle} for a main chest (a tower boss or completion, a trial or a dungeon run): it counts against the player's daily full-reward
     * budget ({@code DailyRewardBudget}: 20 full chests a UTC day, then dust at 25 percent). A player whose chest was cut is told in chat.
     */
    static Map<UUID, String> settleBudgeted(UUID encounterId, String outcome, String rewardKind, Map<UUID, Map<String, Long>> payouts) {
        return settleBudgeted(encounterId, outcome, rewardKind, payouts, null);
    }

    /** As above, also counting each paid player's use of a daily Threat counter ({@link #CORE_COUNTER}, {@link #APEX_COUNTER}); null counts nothing. */
    static Map<UUID, String> settleBudgeted(UUID encounterId, String outcome, String rewardKind, Map<UUID, Map<String, Long>> payouts, String threatCounter) {
        var settlement = settlement(encounterId, outcome, rewardKind, payouts);
        var rewards = AscensionApi.encounterRewards();
        if (rewards.isEmpty()) return disabled(settlement);
        var result = new LinkedHashMap<UUID, String>();
        var budget = com.cobbleascend.domain.v1.Tunables.current().budget();
        rewards.get().settleBudgeted(settlement, budget, threatCounter).forEach((player, answer) -> {
            result.put(player, answer.status().name());
            if (answer.reduced() && answer.status() == EncounterRewards.Status.GRANTED) tellReduced(player, budget);
        });
        BalanceLog.settlement(rewardKind, encounterId, outcome, result);
        logPaid(rewardKind, encounterId, result, payouts);
        return result;
    }

    /** One line in the server log per chest actually paid now, so an admin can see a completion pay without the telemetry files. */
    private static void logPaid(String rewardKind, UUID encounterId, Map<UUID, String> result, Map<UUID, Map<String, Long>> payouts) {
        var server = AscensionApi.server();
        result.forEach((player, status) -> {
            if (!status.equals("GRANTED")) return;
            var online = server == null ? null : server.getPlayerList().getPlayer(player);
            LOG.info("{} {} paid {}: {}", rewardKind, encounterId, online == null ? player : online.getGameProfile().getName(), payouts.get(player));
        });
    }

    /** The store-side settlement for a caller's per-player material ids; an unknown id or outcome is the caller's mistake and throws. */
    private static EncounterRewards.Settlement settlement(UUID encounterId, String outcome, String rewardKind, Map<UUID, Map<String, Long>> payouts) {
        var parsed = new java.util.ArrayList<EncounterRewards.Payout>();
        payouts.forEach((player, materials) -> {
            var amounts = new java.util.EnumMap<MaterialId, Long>(MaterialId.class);
            materials.forEach((id, amount) -> amounts.put(MaterialId.fromId(id), amount));
            parsed.add(new EncounterRewards.Payout(player, amounts));
        });
        return new EncounterRewards.Settlement(encounterId.toString(), EncounterRewards.EncounterOutcome.valueOf(outcome), rewardKind, parsed);
    }

    /** What every player of a settlement is told when there is no running world or progression is off: nothing was written, settle again later. */
    private static Map<UUID, String> disabled(EncounterRewards.Settlement settlement) {
        var result = new LinkedHashMap<UUID, String>();
        settlement.payouts().forEach(payout -> result.put(payout.playerId(), "DISABLED"));
        return result;
    }

    private static void tellReduced(UUID player, com.cobbleascend.domain.v1.DailyRewardBudget budget) {
        var server = AscensionApi.server();
        var online = server == null ? null : server.getPlayerList().getPlayer(player);
        if (online != null) online.sendSystemMessage(net.minecraft.network.chat.Component.literal("[Ascend] Daily reward limit reached ("
                + budget.fullPerDay() + " full chests today): this chest paid " + budget.reducedDustPercent() + "% of its Resonance Dust. Everything else was paid in full. It resets at midnight UTC."));
    }

    /**
     * @param encounterId the encounter's own ID
     * @param outcome   {@code VICTORY}, {@code DEFEAT} or {@code ABORTED}; only a victory pays (these are the names
     *                    of CobbleRaids' {@code EncounterOutcome}, so {@code result.outcome().name()} is passed as is)
     * @param rewardKind  a stable lower-case key such as {@code boss_defeated}; one encounter may settle several
     * @param payouts     per player, material id (for example {@code resonance_dust}) to a positive amount
     * @return per player one of {@code GRANTED}, {@code ALREADY_GRANTED}, {@code NOT_PAID}, {@code CONFLICT} (already
     *         paid with different amounts; the first stands), {@code REFUSED} (the wallet refused this player's
     *         credit) or {@code DISABLED} (no running world, or progression is disabled for it; nothing was written,
     *         so settle again once it is back)
     * @throws IllegalArgumentException for a malformed call (unknown outcome or material, bad ID, empty payout)
     */
    public static Map<UUID, String> settle(UUID encounterId, String outcome, String rewardKind,
                                           Map<UUID, Map<String, Long>> payouts) {
        var settlement = settlement(encounterId, outcome, rewardKind, payouts);
        var rewards = AscensionApi.encounterRewards();
        if (rewards.isEmpty()) return disabled(settlement);
        var result = new LinkedHashMap<UUID, String>();
        rewards.get().settle(settlement).forEach((player, status) -> result.put(player, status.name()));
        BalanceLog.settlement(rewardKind, encounterId, outcome, result);
        return result;
    }
}
