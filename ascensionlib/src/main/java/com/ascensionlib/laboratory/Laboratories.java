package com.ascensionlib.laboratory;

import com.cobbleascend.domain.v1.LaboratoryLoot;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Abandoned Laboratories (docs/LABORATORY-DESIGN.md): an overworld structure from the mod's own datapack ({@code worldgen/structure/laboratory},
 * templates under {@code structure/laboratory/}). Its containers are recognised by a loot-table MARKER ({@code ascensionlib:laboratory/<kind>},
 * an empty table, so a hopper or an explosion that unpacks one yields nothing); a survival player cannot make a container that carries one.
 * Guards are Pokemon that spawn inside it ({@code spawn_pool_world/laboratory.json}); each is marked with {@link #GUARD_TIER} when it spawns.
 */
public final class Laboratories {
    public static final String NAMESPACE = "ascensionlib";
    public static final ResourceKey<Structure> STRUCTURE =
            ResourceKey.create(Registries.STRUCTURE, ResourceLocation.fromNamespaceAndPath(NAMESPACE, "laboratory"));
    /** The marker tables' folder: {@code ascensionlib:laboratory/supply} and so on. Never rename: it is written into generated chunks. */
    public static final String MARKER_PREFIX = "laboratory/";
    /** The vanilla filler each container also gives on its first search: {@code ascensionlib:chests/laboratory/<kind>}. */
    public static final String FILLER_PREFIX = "chests/laboratory/";
    /** The profile tier a guard is rated at (the tier-3 row of the zone rarity table, {@code ZoneRarity}). Provisional, 2026-11-20. */
    public static final int GUARD_TIER = 3;

    private Laboratories() {}

    /** The laboratory container this block entity is, if it still carries a marker. */
    public static Optional<LaboratoryLoot.Container> containerOf(BlockEntity blockEntity) {
        if (!(blockEntity instanceof RandomizableContainerBlockEntity container)) return Optional.empty();
        var table = container.getLootTable();
        if (table == null) return Optional.empty();
        var id = table.location();
        if (!NAMESPACE.equals(id.getNamespace()) || !id.getPath().startsWith(MARKER_PREFIX)) return Optional.empty();
        try {
            return Optional.of(LaboratoryLoot.Container.fromId(id.getPath().substring(MARKER_PREFIX.length())));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /** Where a container is, stable for the life of the world: the claim key every player's search is paid under. */
    public static String containerKey(ServerLevel level, BlockPos pos) {
        return level.dimension().location() + "/" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** Whether the position lies inside one of a laboratory's pieces (not merely its outer bounds). The chunk must be loaded. */
    public static boolean isInside(ServerLevel level, BlockPos pos) {
        Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(STRUCTURE);
        return structure != null && level.structureManager().getStructureWithPieceAt(pos, structure).isValid();
    }
}
