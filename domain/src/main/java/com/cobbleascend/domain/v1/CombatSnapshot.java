package com.cobbleascend.domain.v1;

import com.cobbleascend.domain.Rarity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * The immutable, frozen description of one combatant's rarity and modifiers that a battle adapter consumes
 * (specification section 3: "immutable battle snapshot"). Players' snapshots come from their stored profile;
 * enemies' come from {@link EnemyGenerator}. The battle code never needs to know which it is, and a snapshot is
 * never written back: an enemy snapshot is not a profile and cannot be captured, traded or rewarded.
 */
public record CombatSnapshot(Source source, String subjectId, Rarity rarity, int catalogVersion,
                             List<OrdinarySlot> slots, String uniqueId, Fused transcendent) {
    public enum Source { PLAYER, ENEMY }

    /**
     * A Transcendent (docs/FUSION-DESIGN.md): two Uniques fused into one holder. {@code benefitPercent} and {@code drawbackPercent}
     * are the share of each Unique's benefit and drawback that survives; {@code hostType} and {@code donorType} (lower case, either
     * may be null) shape the typed offence and defence. A snapshot has a plain Unique or a Transcendent, never both.
     */
    public record Fused(List<String> uniqueIds, int benefitPercent, int drawbackPercent, String hostType, String donorType,
                        String signature, List<PulseOp> twist, String riderAffix, int riderValue) {
        /** A fusion with no signature of its own: the module runs the two Uniques at the shares (the interim form). */
        public Fused(List<String> uniqueIds, int benefitPercent, int drawbackPercent, String hostType, String donorType) {
            this(uniqueIds, benefitPercent, drawbackPercent, hostType, donorType, null, List.of(), null, 0);
        }

        public Fused {
            uniqueIds = List.copyOf(uniqueIds);
            twist = List.copyOf(twist);
            if (signature != null && !signature.matches("[a-z][a-z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid signature");
            if (twist.size() > 2) throw new IllegalArgumentException("A twist has at most two effects");
            if ((riderAffix == null) != (riderValue == 0) || riderValue < 0 || riderValue > 100
                    || (riderAffix != null && !riderAffix.matches("[a-z][a-z0-9_]{0,63}")))
                throw new IllegalArgumentException("Invalid rider");
            if (uniqueIds.size() != 2 || uniqueIds.get(0).equals(uniqueIds.get(1))) throw new IllegalArgumentException("A fusion needs two different Uniques");
            for (var id : uniqueIds) {
                if (!id.matches("[a-z][a-z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid unique ID");
            }
            if (benefitPercent < 1 || benefitPercent > 100 || drawbackPercent < 1 || drawbackPercent > 100)
                throw new IllegalArgumentException("Fusion shares must be 1 to 100 percent");
            for (var type : java.util.Arrays.asList(hostType, donorType)) {
                if (type != null && !type.matches("[a-z]{3,16}")) throw new IllegalArgumentException("Invalid fusion type");
            }
        }

        /** The same Transcendent with offence and defence types traded: a Fuser bond after a Genome Shift, the head now the body. */
        public Fused swappedTypes() {
            return new Fused(uniqueIds, benefitPercent, drawbackPercent, donorType, hostType, signature, twist, riderAffix, riderValue);
        }
    }

    /** This snapshot with its Transcendent's types traded (see {@link Fused#swappedTypes}); unchanged when it has none. */
    public CombatSnapshot withSwappedTypes() {
        return transcendent == null ? this : new CombatSnapshot(source, subjectId, rarity, catalogVersion, slots, uniqueId, transcendent.swappedTypes());
    }

    public CombatSnapshot {
        Objects.requireNonNull(source);
        Objects.requireNonNull(subjectId);
        Objects.requireNonNull(rarity);
        slots = List.copyOf(slots);
        if (subjectId.isBlank() || subjectId.length() > 160) throw new IllegalArgumentException("Invalid subject ID");
        if (catalogVersion < 1) throw new IllegalArgumentException("Invalid catalog version");
        if (slots.size() > ProfileV1.MAX_SLOTS) throw new IllegalArgumentException("Too many slots");
        if (uniqueId != null && !uniqueId.matches("[a-z][a-z0-9_]{0,63}"))
            throw new IllegalArgumentException("Invalid unique ID");
        if (uniqueId != null && transcendent != null) throw new IllegalArgumentException("A Unique and a Transcendent cannot both be held");
    }

    /** A snapshot with a plain Unique (or none). */
    public CombatSnapshot(Source source, String subjectId, Rarity rarity, int catalogVersion, List<OrdinarySlot> slots, String uniqueId) {
        this(source, subjectId, rarity, catalogVersion, slots, uniqueId, null);
    }

    /** A player's combatant, frozen from the committed profile. Unique tuning is looked up by ID at battle time. */
    public static CombatSnapshot ofProfile(ProfileV1 profile) {
        return new CombatSnapshot(Source.PLAYER, profile.pokemonId().toString(), profile.rarity(),
                profile.catalogVersion(), profile.ordinarySlots(),
                profile.unique() == null ? null : profile.unique().uniqueId());
    }

    /** A player's Transcendent: the committed profile with its Unique replaced by the fusion (a snapshot holds one or the other). */
    public static CombatSnapshot ofFusedProfile(ProfileV1 profile, Fused fused) {
        return new CombatSnapshot(Source.PLAYER, profile.pokemonId().toString(), profile.rarity(),
                profile.catalogVersion(), profile.ordinarySlots(), null, fused);
    }

    /** Stable fingerprint, so a battle can prove its modifiers stayed frozen from start to finish. */
    public String contentHash() {
        var text = new StringJoiner("\n");
        text.add(source + "|" + subjectId + "|" + rarity.id() + "|" + catalogVersion + "|" + uniqueId + "|" + transcendent);
        for (var slot : slots) {
            text.add(slot.slotId() + "|" + slot.rank() + "|" + slot.affixId() + "|"
                    + new TreeMap<>(slot.parameters()) + "|" + slot.rolledValue());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
