package com.cobbleascend.store;

/** Provisional economy settings (specification section 9.2: starting tuning values, not approved rates). */
public record StoreConfig(int catalystFragments) {
    public StoreConfig {
        if (catalystFragments < 1) throw new IllegalArgumentException("Catalyst threshold must be at least 1");
    }

    /**
     * 40 Unique Fragments to a Catalyst (was 100). Live data, 12 Nov 2026: in three weeks no player reached 100 (the most was 69), so no Catalyst was
     * ever assembled, one Unique was ever installed and Fusion, which needs two Uniques, never happened. 40 is about four or five tower completions.
     */
    public static StoreConfig defaults() { return new StoreConfig(40); }
}
