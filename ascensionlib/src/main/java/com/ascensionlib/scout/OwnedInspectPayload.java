package com.ascensionlib.scout;

import java.util.List;
import java.util.Optional;

/** Read-only own-Pokemon inspection data. The server verifies party/PC membership before building it. */
public final class OwnedInspectPayload {
    private OwnedInspectPayload() {}

    /**
     * What else the owner may see about the profile: where the Pokemon came from, how far it has come, how many modifier slots
     * its rarity allows, and the identifiers the page needs to draw it. Catalog and profile facts only; never IVs, EVs or moves.
     */
    public record Extra(String speciesId, List<String> aspects, String origin, String initialRarity, int highestLevel,
                        int milestones, int prefixCap, int suffixCap, boolean craftLocked, int attunementNeeded) {
        public static final Extra NONE = new Extra("", List.of(), "", "", 0, 0, 0, 0, false, 0);

        /** Without the promotion goal (attunementNeeded 0: the page shows the attunement on its own). */
        public Extra(String speciesId, List<String> aspects, String origin, String initialRarity, int highestLevel, int milestones, int prefixCap, int suffixCap, boolean craftLocked) {
            this(speciesId, aspects, origin, initialRarity, highestLevel, milestones, prefixCap, suffixCap, craftLocked, 0);
        }
    }

    public record Result(String pokemonId, String name, String species, int pending, int attunement,
                         Optional<ScoutPayloads.Detail> detail, Extra extra) {}
}
