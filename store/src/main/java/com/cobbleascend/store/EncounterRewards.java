package com.cobbleascend.store;

import com.cobbleascend.domain.v1.CraftException;
import com.cobbleascend.domain.v1.DailyRewardBudget;
import com.cobbleascend.domain.v1.MaterialId;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Turns a finished encounter into wallet rewards exactly once. Raids, Towers and any later encounter owner decide
 * WHAT each participant earned (amounts are theirs and the economy's, never invented here) and call {@link #settle}
 * with the result; this class decides only WHETHER and guarantees the once.
 *
 * <p>Only a {@link EncounterOutcome#VICTORY} pays: defeat, abort and shutdown never do, and a missing or repeated
 * callback can neither lose nor duplicate a reward, because each payout is one store operation whose ID is derived
 * from {@code (encounterId, playerId, rewardKind)}. A retry after a crash completes whoever was not yet paid and
 * returns {@link Status#ALREADY_GRANTED} for the rest. Encounter IDs must be unique and never reused.
 */
public final class EncounterRewards {
    public enum EncounterOutcome { VICTORY, DEFEAT, ABORTED }

    public enum Status {
        /** Credited by this call. */
        GRANTED,
        /** Credited by an earlier call with the same amounts; nothing changed now. */
        ALREADY_GRANTED,
        /** The encounter was not a victory, so nothing was paid. */
        NOT_PAID,
        /** This player's reward for this encounter and kind was already paid with different amounts; the first stands. */
        CONFLICT,
        /** The wallet refused the credit (for example an overflow); nothing was paid to this player. */
        REFUSED
    }

    /** What one participant earned: positive amounts only. */
    public record Payout(UUID playerId, Map<MaterialId, Long> materials) {
        public Payout {
            Objects.requireNonNull(playerId, "playerId");
            materials = Map.copyOf(materials);
            if (materials.isEmpty()) throw new IllegalArgumentException("A payout needs at least one material");
            materials.forEach((id, amount) -> {
                if (amount == null || amount < 1) throw new IllegalArgumentException("Payout amounts must be positive: " + id.id());
            });
        }
    }

    /**
     * @param rewardKind a stable lower-case key for what was earned, such as {@code boss_defeated} or
     *                   {@code floor_cleared}; one encounter may settle several kinds, each paying once
     */
    public record Settlement(String encounterId, EncounterOutcome outcome, String rewardKind, List<Payout> payouts) {
        private static final Pattern KIND = Pattern.compile("[a-z][a-z0-9_]{0,63}");

        public Settlement {
            if (encounterId == null || encounterId.isBlank() || encounterId.contains("|"))
                throw new IllegalArgumentException("Invalid encounter ID");
            Objects.requireNonNull(outcome, "outcome");
            if (rewardKind == null || !KIND.matcher(rewardKind).matches())
                throw new IllegalArgumentException("rewardKind must match " + KIND.pattern());
            payouts = List.copyOf(payouts);
            Set<UUID> seen = new HashSet<>();
            for (var payout : payouts)
                if (!seen.add(payout.playerId())) throw new IllegalArgumentException("Player listed twice: " + payout.playerId());
        }
    }

    /** The answer for one budgeted payout: the status, and whether the daily budget cut its dust. */
    public record Budgeted(Status status, boolean reduced) {}

    private final ProgressionStore store;

    public EncounterRewards(ProgressionStore store) { this.store = Objects.requireNonNull(store); }

    /**
     * Physical rewards (ThreatSettings.physicalRewards): after a payout commits, it is moved into undelivered voucher batches, which the mod hands
     * over as items (now, or at the player's next login). Read per payout, so a config reload applies to the next one.
     */
    private volatile java.util.function.BooleanSupplier physical = () -> false;

    public void physicalRewards(java.util.function.BooleanSupplier physical) { this.physical = Objects.requireNonNull(physical); }

    /** After a fresh payout: its materials become vouchers. A replay issues nothing more (the issue is idempotent on its own id). */
    private void toVouchers(UUID operationId, UUID playerId, Map<MaterialId, Long> paid) {
        if (!physical.getAsBoolean()) return;
        var issue = UUID.nameUUIDFromBytes(("reward_voucher_issue|" + operationId).getBytes(StandardCharsets.UTF_8));
        try {
            store.issueRewardVouchers(issue, playerId, paid);
        } catch (RuntimeException exception) {
            // The reward is already in the wallet: it is not lost, only not physical.
            java.util.logging.Logger.getLogger("ascensionlib").warning("Reward stayed in the wallet of " + playerId + ": " + exception.getMessage());
        }
    }

    /** Deterministic, so a repeated settlement resolves the operation the first one committed. */
    static UUID operationId(String encounterId, UUID playerId, String rewardKind) {
        return UUID.nameUUIDFromBytes(("encounter_reward|" + encounterId + "|" + playerId + "|" + rewardKind)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Pays each participant independently; the result has one entry per payout, in order. */
    public synchronized Map<UUID, Status> settle(Settlement settlement) {
        var result = new LinkedHashMap<UUID, Status>();
        for (var payout : settlement.payouts()) {
            if (settlement.outcome() != EncounterOutcome.VICTORY) {
                result.put(payout.playerId(), Status.NOT_PAID);
                continue;
            }
            result.put(payout.playerId(), pay(settlement, payout));
        }
        return result;
    }

    /**
     * Like {@link #settle}, but each paid chest counts against the player's daily budget: after {@code budget.fullPerDay()} chests in a UTC day its dust
     * is cut (see {@link ProgressionStore#grantBudgeted}). A retry replays the first payment and uses no more budget.
     */
    public synchronized Map<UUID, Budgeted> settleBudgeted(Settlement settlement, DailyRewardBudget budget) {
        return settleBudgeted(settlement, budget, null);
    }

    /**
     * {@link #settleBudgeted(Settlement, DailyRewardBudget)} that also counts each paid player's use of a daily Threat counter ({@code core_roll} or
     * {@code apex}, balance phase 1); {@code null} counts nothing. The amounts were already decided from {@link ProgressionStore#threatUsedToday}.
     */
    public synchronized Map<UUID, Budgeted> settleBudgeted(Settlement settlement, DailyRewardBudget budget, String threatCounter) {
        var result = new LinkedHashMap<UUID, Budgeted>();
        for (var payout : settlement.payouts()) {
            if (settlement.outcome() != EncounterOutcome.VICTORY) {
                result.put(payout.playerId(), new Budgeted(Status.NOT_PAID, false));
                continue;
            }
            try {
                var op = operationId(settlement.encounterId(), payout.playerId(), settlement.rewardKind());
                var outcome = store.grantBudgeted(op,
                        payout.playerId(), payout.materials(), "encounter " + settlement.encounterId() + " " + settlement.rewardKind(), budget, threatCounter);
                if (!outcome.replayed()) toVouchers(op, payout.playerId(), outcome.cost());
                long asked = payout.materials().getOrDefault(MaterialId.RESONANCE_DUST, 0L);
                long paid = outcome.cost().getOrDefault(MaterialId.RESONANCE_DUST, 0L);
                result.put(payout.playerId(), new Budgeted(outcome.replayed() ? Status.ALREADY_GRANTED : Status.GRANTED, paid < asked));
            } catch (StoreException exception) {
                if (exception.code() != StoreException.Code.OPERATION_REUSED) throw exception;
                result.put(payout.playerId(), new Budgeted(Status.CONFLICT, false));
            } catch (CraftException exception) {
                result.put(payout.playerId(), new Budgeted(Status.REFUSED, false));
            }
        }
        return result;
    }

    private Status pay(Settlement settlement, Payout payout) {
        try {
            var op = operationId(settlement.encounterId(), payout.playerId(), settlement.rewardKind());
            var outcome = store.grant(op, payout.playerId(), payout.materials(),
                    "encounter " + settlement.encounterId() + " " + settlement.rewardKind());
            if (!outcome.replayed()) toVouchers(op, payout.playerId(), payout.materials());
            return outcome.replayed() ? Status.ALREADY_GRANTED : Status.GRANTED;
        } catch (StoreException exception) {
            // Only the same key arriving with different amounts is a verdict about this payout. Anything else (the
            // store is unavailable, corrupt) is not, and must reach the caller so the settlement is retried.
            if (exception.code() == StoreException.Code.OPERATION_REUSED) return Status.CONFLICT;
            throw exception;
        } catch (CraftException exception) {
            return Status.REFUSED;
        }
    }
}
