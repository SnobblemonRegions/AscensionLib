package com.cobbleascend.domain.v1;

/**
 * A Field Research action refused for a reason the player can be told (not enough research, a limit reached, nothing to collect). Thrown before
 * anything is written, so the store's transaction rolls back and records no operation: the same request can simply be made again later.
 */
public final class ResearchException extends RuntimeException {
    /** Why: a {@link Serums.CraftCheck}, {@link Serums.ArmCheck}, {@link BenchStudies.StartCheck}, {@link FieldResearch.BeaconCheck} or one below. */
    public enum Reason {
        UNKNOWN_SPECIES, BAD_AMOUNT, SHARE_FULL, NO_ITEM, NOTHING_TO_COLLECT, NOT_FINISHED, NOT_ARMED, CRAFT, ARM, STUDY, BEACON
    }

    private final Reason reason;
    private final String detail;

    public ResearchException(Reason reason, String detail) {
        super(reason + (detail == null || detail.isEmpty() ? "" : ": " + detail));
        this.reason = reason;
        this.detail = detail == null ? "" : detail;
    }

    public Reason reason() { return reason; }

    /** The domain check's own result name (for example {@code LIMIT_REACHED}), or a short note. */
    public String detail() { return detail; }
}
