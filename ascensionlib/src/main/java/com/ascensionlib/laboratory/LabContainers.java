package com.ascensionlib.laboratory;

import com.ascensionlib.AscensionApi;
import com.cobbleascend.domain.v1.CurrencyItems;
import com.cobbleascend.domain.v1.LaboratoryLoot;
import com.cobbleascend.domain.v1.MaterialId;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Laboratory containers are searched, not opened: each player gets their own roll from each container once (owner decision 2026-11-20), paid
 * straight into the wallet in one store transaction keyed by (container, player), plus a little vanilla filler on the first search. The
 * container itself never opens, never empties and cannot be broken by a survival player, so a lab someone already looted is still worth
 * entering. Placing a hopper, a rail or a hopper minecart beside one is refused, so nobody can strip its marker for everyone else.
 */
public final class LabContainers {
    private static final Logger LOG = LoggerFactory.getLogger("AscensionLib/Laboratory");

    private LabContainers() {}

    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClientSide() || !(player instanceof ServerPlayer serverPlayer) || !(world instanceof ServerLevel level)) return InteractionResult.PASS;
            var pos = hit.getBlockPos();
            var held = player.getItemInHand(hand);
            if (isExtractor(held) && (nearMarked(level, pos) || nearMarked(level, pos.relative(hit.getDirection())))) {
                serverPlayer.displayClientMessage(Component.literal("The laboratory's wiring sparks: nothing can be attached to its containers.")
                        .withStyle(ChatFormatting.RED), true);
                return InteractionResult.FAIL;
            }
            var container = Laboratories.containerOf(level.getBlockEntity(pos));
            if (container.isEmpty()) return InteractionResult.PASS;
            if (player.isSpectator()) return InteractionResult.FAIL;
            search(serverPlayer, level, pos, container.get());
            return InteractionResult.SUCCESS;
        });
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (world.isClientSide() || player.isCreative()) return true;
            if (Laboratories.containerOf(blockEntity).isEmpty()) return true;
            player.displayClientMessage(Component.literal("This container is bolted to the laboratory floor.").withStyle(ChatFormatting.RED), true);
            return false;
        });
    }

    /** Pays this player's search of one container, once. Never throws. */
    static void search(ServerPlayer player, ServerLevel level, BlockPos pos, LaboratoryLoot.Container container) {
        var service = AscensionApi.service();
        if (service.isEmpty()) {
            player.displayClientMessage(Component.literal("The " + container.displayName() + " is sealed (Ascension progression is not running).")
                    .withStyle(ChatFormatting.GRAY), true);
            return;
        }
        String key = Laboratories.containerKey(level, pos);
        var operation = LaboratoryLoot.claimOperation(key, player.getUUID());
        Map<MaterialId, Long> materials = LaboratoryLoot.roll(container, key, player.getUUID());
        try {
            var outcome = service.get().store().grant(operation, player.getUUID(), materials, "laboratory_" + container.id());
            // Field Research from the same search (a Data Drive, specimen notes): once per container and player, so calling it on a replay too
            // finishes a payment a crash may have interrupted.
            com.ascensionlib.research.FieldResearchRuntime.labSearched(player, level, pos, container);
            if (outcome.replayed()) {
                player.displayClientMessage(Component.literal("You have already searched this " + container.displayName() + ".")
                        .withStyle(ChatFormatting.GRAY), true);
                return;
            }
        } catch (RuntimeException failure) {
            LOG.error("Could not pay {}'s search of the {} at {}", player.getGameProfile().getName(), container.id(), key, failure);
            player.displayClientMessage(Component.literal("The " + container.displayName() + " jammed. Try again in a moment.")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        giveFiller(player, level, pos, container, operation.getMostSignificantBits());
        level.playSound(null, pos, container == LaboratoryLoot.Container.SUPPLY ? SoundEvents.BARREL_OPEN : SoundEvents.CHEST_OPEN,
                SoundSource.BLOCKS, 0.6f, 1.0f);
        player.sendSystemMessage(Component.literal("[Laboratory] " + container.displayName() + ": ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(describe(materials)).withStyle(ChatFormatting.WHITE)));
    }

    private static void giveFiller(ServerPlayer player, ServerLevel level, BlockPos pos, LaboratoryLoot.Container container, long seed) {
        try {
            var key = ResourceKey.create(Registries.LOOT_TABLE,
                    ResourceLocation.fromNamespaceAndPath(Laboratories.NAMESPACE, Laboratories.FILLER_PREFIX + container.id()));
            var table = level.getServer().reloadableRegistries().getLootTable(key);
            var params = new LootParams.Builder(level)
                    .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                    .withParameter(LootContextParams.THIS_ENTITY, player)
                    .withLuck(player.getLuck())
                    .create(LootContextParamSets.CHEST);
            for (ItemStack stack : table.getRandomItems(params, seed)) {
                if (!player.getInventory().add(stack) && !stack.isEmpty()) player.drop(stack, false);
            }
        } catch (RuntimeException failure) {
            LOG.warn("Laboratory filler for {} could not be rolled", container.id(), failure);
        }
    }

    static String describe(Map<MaterialId, Long> materials) {
        var text = new StringBuilder();
        for (var entry : materials.entrySet()) {
            if (!text.isEmpty()) text.append(", ");
            text.append(entry.getValue()).append(' ').append(CurrencyItems.spec(entry.getKey()).name());
        }
        return text.append('.').toString();
    }

    private static boolean isExtractor(ItemStack held) {
        return held.is(Items.HOPPER) || held.is(Items.HOPPER_MINECART) || held.is(ItemTags.RAILS);
    }

    private static boolean nearMarked(ServerLevel level, BlockPos center) {
        for (var pos : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 1, 1))) {
            if (Laboratories.containerOf(level.getBlockEntity(pos)).isPresent()) return true;
        }
        return false;
    }

    /** For tests and admin tools: which container a block entity is. */
    public static Optional<LaboratoryLoot.Container> kindAt(ServerLevel level, BlockPos pos) {
        return Laboratories.containerOf(level.getBlockEntity(pos));
    }
}
