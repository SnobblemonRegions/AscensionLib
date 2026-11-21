package com.ascensionlib.compat;

import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.nbt.CompoundTag;

/**
 * What a Pokemon's own data says about Project Fusion, read without loading any Project Fusion class (so it is safe when the mod is
 * absent). Project Fusion keeps a fusion's two partners under {@value #KEY} in the fusion's persistent data, with a {@code shifted}
 * flag set while Genome Shift has the head and body swapped (checked against Project Fusion 2.1.0).
 */
public final class FusionNbt {
    static final String KEY = "cobblemon_project_fusion";

    private FusionNbt() {}

    /** Whether this Pokemon is a Project Fusion fusion (a body carrying a head). */
    public static boolean isFusion(Pokemon pokemon) { return isFusion(pokemon.getPersistentData()); }

    /** Whether Genome Shift currently has this fusion's head and body swapped. */
    public static boolean shifted(Pokemon pokemon) { return shifted(pokemon.getPersistentData()); }

    /** The Pokemon a fusion carries as its head, as Cobblemon saved it: its id, species id and nickname (empty when it has none). */
    public record Head(java.util.UUID id, String speciesId, String nickname) {}

    /** The head this fusion carries, read from Project Fusion's saved partners; empty when it is not a fusion or the data is unreadable. */
    public static java.util.Optional<Head> headOf(Pokemon pokemon) { return headOf(pokemon.getPersistentData()); }

    static java.util.Optional<Head> headOf(CompoundTag data) {
        if (!isFusion(data)) return java.util.Optional.empty();
        var head = data.getCompound(KEY).getCompound("head");
        if (!head.hasUUID("UUID") || head.getString("Species").isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.of(new Head(head.getUUID("UUID"), head.getString("Species"), head.getString("Nickname")));
    }

    static boolean isFusion(CompoundTag data) {
        return data != null && data.contains(KEY, net.minecraft.nbt.Tag.TAG_COMPOUND);
    }

    static boolean shifted(CompoundTag data) {
        return isFusion(data) && data.getCompound(KEY).getBoolean("shifted");
    }
}
