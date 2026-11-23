package com.cobbleascend.domain.v1;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * What one container in an Abandoned Laboratory pays one player (docs/LABORATORY-DESIGN.md; numbers approved 2026-11-20, still open to balance).
 * Every player searches every container once for themselves, so the roll is seeded by (container, player): a retried claim recomputes exactly
 * the same materials and the store's operation replay never sees a conflict. Laboratories pay no Ascension Cores and no Apex Shards (the Tower
 * stays the only Core source) and are not counted against the daily reward budget (owner, 2026-11-20).
 */
public final class LaboratoryLoot {
    private LaboratoryLoot() {}

    /** The three kinds of container a laboratory holds. The id is part of the structure templates' loot-table markers: never rename. */
    public enum Container {
        /** A barrel in the storage rooms, dorms, server rooms and the core lab. */
        SUPPLY("supply", "Supply Crate", 6, 16),
        /** A chest in a containment room. */
        SPECIMEN("specimen", "Specimen Locker", 10, 24),
        /** The chest at the core lab's research terminal: one per laboratory. */
        TERMINAL("terminal", "Research Terminal", 25, 50);

        private final String id;
        private final String displayName;
        private final int dustMin;
        private final int dustMax;

        Container(String id, String displayName, int dustMin, int dustMax) {
            this.id = id;
            this.displayName = displayName;
            this.dustMin = dustMin;
            this.dustMax = dustMax;
        }

        public String id() { return id; }
        public String displayName() { return displayName; }
        public int dustMin() { return dustMin; }
        public int dustMax() { return dustMax; }

        public static Container fromId(String id) {
            for (var container : values()) if (container.id.equals(id.toLowerCase(Locale.ROOT))) return container;
            throw new IllegalArgumentException("Unknown laboratory container: " + id);
        }
    }

    /** Percent chances, out of 100. */
    public static final int SUPPLY_SCOUTER_PERCENT = 20;
    public static final int SPECIMEN_FACET_PERCENT = 40;
    public static final int SPECIMEN_FRAGMENT_PERCENT = 6;
    public static final int TERMINAL_SECOND_FACET_PERCENT = 50;
    public static final int TERMINAL_FRAGMENT_PERCENT = 12;
    public static final int TERMINAL_SIGIL_PERCENT = 3;

    /**
     * @param container    which kind of container
     * @param containerKey where it is, stable for the life of the world (dimension and block position)
     * @param player       who is searching it
     * @return nonzero amounts; always some Resonance Dust
     */
    public static Map<MaterialId, Long> roll(Container container, String containerKey, UUID player) {
        var random = new Random(RewardSeed.of("laboratory", container.id(), containerKey, player));
        var result = new EnumMap<MaterialId, Long>(MaterialId.class);
        result.put(MaterialId.RESONANCE_DUST, (long) (container.dustMin() + random.nextInt(container.dustMax() - container.dustMin() + 1)));
        switch (container) {
            case SUPPLY -> {
                if (random.nextInt(100) < SUPPLY_SCOUTER_PERCENT) result.put(MaterialId.SCOUTER, 1L);
            }
            case SPECIMEN -> {
                if (random.nextInt(100) < SPECIMEN_FACET_PERCENT) result.put(MaterialId.FACET, 1L);
                if (random.nextInt(100) < SPECIMEN_FRAGMENT_PERCENT) result.put(MaterialId.UNIQUE_FRAGMENT, 1L);
            }
            case TERMINAL -> {
                result.put(MaterialId.FACET, random.nextInt(100) < TERMINAL_SECOND_FACET_PERCENT ? 2L : 1L);
                if (random.nextInt(100) < TERMINAL_FRAGMENT_PERCENT) result.put(MaterialId.UNIQUE_FRAGMENT, 1L);
                if (random.nextInt(100) < TERMINAL_SIGIL_PERCENT) result.put(MaterialId.ASCENSION_SIGIL, 1L);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** The store operation that pays this claim: one per (container, player), so a second search replays instead of paying again. */
    public static UUID claimOperation(String containerKey, UUID player) {
        return UUID.nameUUIDFromBytes(("ascensionlib:laboratory-claim:v1|" + containerKey + "|" + player).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
