package com.cobbleascend.domain;

/** A price in wallet materials. {@code apex} is Apex Shards, used only by Threat-system promotions (balance phase 1). */
public record Cost(int dust, int facets, int cores, int apex) {
    public Cost {
        if (dust < 0 || facets < 0 || cores < 0 || apex < 0) throw new IllegalArgumentException("Negative cost");
    }

    /** A price with no Apex Shards (every price before the Threat system). */
    public Cost(int dust, int facets, int cores) { this(dust, facets, cores, 0); }
}
