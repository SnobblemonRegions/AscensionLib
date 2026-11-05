package com.ascensionlib.wallet;

import com.ascensionlib.AscensionApi;
import com.cobbleascend.domain.v1.CurrencyItems;
import com.cobbleascend.domain.v1.MaterialId;
import com.cobbleascend.store.CraftRequest;
import com.cobbleascend.store.ProgressionStore;
import com.cobbleascend.store.StoreException;
import com.cobbleascend.domain.v1.CraftException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.ItemLore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The wallet as real items. Every material is a vanilla base item carrying a tag in its custom data and a custom model number a server
 * resource pack maps to art (see design/currency-items.json and docs/WALLET-ITEMS.md). Two forms share the art:
 *
 * <ul>
 *   <li><b>Loot form</b> (no batch): handed out by loot tables ({@code ascensionlib:currency/<material>}). A player who carries one has it absorbed into
 *       the wallet within a second, so it is never stored or traded; only the loot source can duplicate it.</li>
 *   <li><b>Voucher</b> (a batch id): minted by a withdrawal, which debits the wallet. It is tradable (an auction house sees an ordinary item) and is
 *       redeemed with a deposit, but only up to what its batch has outstanding, so duplicated stacks cannot inflate the currency.</li>
 * </ul>
 *
 * The wallet in the store stays the source of truth; everything here is a view of it or a transfer to and from it. All calls are on the server thread.
 */
public final class WalletItems {
    private static final Logger LOG = LoggerFactory.getLogger("ascensionlib");
    /** The inventory is scanned for loot-form items this often, in ticks. */
    private static final int SCAN_INTERVAL = 20;
    /** The main inventory slots a withdrawal can fill (hotbar and storage). */
    private static final int MAIN_SLOTS = 36;

    private WalletItems() {}

    /** What a stack is, when it is one of ours. {@code batch} is null for loot form. */
    public record Parsed(MaterialId material, UUID batch) {}

    // ---- building and reading items ----------------------------------------------------------------------

    /** A stack of {@code count} (at most 64) of the material; a voucher when {@code batch} is not null, else loot form. */
    public static ItemStack create(MaterialId material, int count, UUID batch) {
        var spec = CurrencyItems.spec(material);
        var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(spec.baseItem()));
        var stack = new ItemStack(item, Math.max(1, Math.min(CurrencyItems.STACK, count)));
        var inner = new CompoundTag();
        inner.putString(CurrencyItems.MATERIAL_KEY, material.id());
        if (batch != null) inner.putString(CurrencyItems.BATCH_KEY, batch.toString());
        var tag = new CompoundTag();
        tag.put(CurrencyItems.TAG, inner);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(batch == null ? spec.model() : spec.model() + CurrencyItems.VOUCHER_MODEL_OFFSET));
        var plain = Style.EMPTY.withItalic(false);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(spec.name()).withStyle(plain.withColor(TextColor.fromRgb(spec.color()))));
        var lore = new ArrayList<Component>();
        lore.add(rarityLine(spec.rarity(), spec.kind()));
        for (var line : spec.lore()) lore.add(Component.literal(line).withStyle(plain.withColor(net.minecraft.ChatFormatting.GRAY)));
        if (batch == null) {
            lore.add(Component.literal("Joins your wallet when you carry it.").withStyle(plain.withColor(net.minecraft.ChatFormatting.DARK_GRAY)));
        } else {
            lore.add(Component.literal("Right-click to deposit into your wallet,").withStyle(plain.withColor(net.minecraft.ChatFormatting.YELLOW)));
            lore.add(Component.literal("or use the Wallet tab of your AscensionDex. Tradable.").withStyle(plain.withColor(net.minecraft.ChatFormatting.DARK_GRAY)));
        }
        stack.set(DataComponents.LORE, new ItemLore(lore));
        return stack;
    }

    /** The first line of every tooltip: the rarity in its colour, bold, then what kind of item it is. Shared with the AscensionDex. */
    public static Component rarityLine(String rarityId, String kind) {
        var rarity = CurrencyItems.rarity(rarityId);
        var plain = Style.EMPTY.withItalic(false);
        return Component.literal(rarity.label()).withStyle(plain.withBold(true).withColor(TextColor.fromRgb(rarity.color())))
                .append(Component.literal(" \u00b7 " + kind).withStyle(plain.withBold(false).withColor(net.minecraft.ChatFormatting.DARK_GRAY)));
    }

    /** Reads a stack: present only for our tag on the base item that material uses (a tagged dirt block is not currency). */
    public static Optional<Parsed> parse(ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();
        var data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return Optional.empty();
        var root = data.copyTag();
        if (!root.contains(CurrencyItems.TAG, Tag.TAG_COMPOUND)) return Optional.empty();
        var inner = root.getCompound(CurrencyItems.TAG);
        MaterialId material;
        try {
            material = MaterialId.fromId(inner.getString(CurrencyItems.MATERIAL_KEY));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
        var base = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!base.equals(CurrencyItems.spec(material).baseItem())) return Optional.empty();
        UUID batch = inner.contains(CurrencyItems.BATCH_KEY, Tag.TAG_STRING) ? CurrencyItems.parseBatch(inner.getString(CurrencyItems.BATCH_KEY)) : null;
        if (inner.contains(CurrencyItems.BATCH_KEY) && batch == null) return Optional.empty();   // a malformed batch is not a voucher
        return Optional.of(new Parsed(material, batch));
    }

    // ---- loot form: carried items join the wallet -----------------------------------------------------------

    /** Physical rewards are handed over this often, in ticks: a reward reaches the player within a few seconds of its payout. */
    private static final int DELIVERY_INTERVAL = 60;

    /**
     * Called every server tick; once a second it absorbs loot-form currency from each online player's inventory, and every few seconds it hands
     * over voucher batches still waiting for them (physical rewards, and any interrupted withdrawal).
     */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % SCAN_INTERVAL != 0) return;
        var service = AscensionApi.service().orElse(null);
        if (service == null) return;
        boolean deliver = server.getTickCount() % DELIVERY_INTERVAL == 0;
        for (var player : server.getPlayerList().getPlayers()) {
            try {
                absorb(player, service.store());
                if (deliver) deliverPending(player);
            } catch (RuntimeException exception) {
                LOG.error("Absorbing or delivering currency failed for {}", player.getGameProfile().getName(), exception);
            }
        }
    }

    /**
     * Right-click with a voucher: deposits that one stack (what its batch has outstanding) and removes it. Returns the line for the player, or
     * empty when the stack is not a voucher (the click then does what it normally would).
     */
    public static Optional<Result> depositHeld(ServerPlayer player, net.minecraft.world.InteractionHand hand) {
        var stack = player.getItemInHand(hand);
        var parsed = parse(stack);
        if (parsed.isEmpty() || parsed.get().batch() == null) return Optional.empty();
        var service = AscensionApi.service().orElse(null);
        if (service == null) return Optional.of(new Result(false, "Progression is unavailable."));
        try {
            var outcome = service.store().redeemVoucher(UUID.randomUUID(), player.getUUID(), parsed.get().batch(), stack.getCount());
            long got = outcome.cost().getOrDefault(parsed.get().material(), 0L);
            long discarded = stack.getCount() - got;
            player.setItemInHand(hand, ItemStack.EMPTY);
            String name = CurrencyItems.spec(parsed.get().material()).name();
            return Optional.of(new Result(true, got > 0 ? "Deposited " + got + " " + name + "." + (discarded > 0 ? " " + discarded + " already redeemed." : "")
                    : "That voucher was already redeemed."));
        } catch (StoreException exception) {
            if (exception.code() == StoreException.Code.UNKNOWN_VOUCHER) return Optional.of(new Result(false, "This voucher is not recognised and was left alone."));
            LOG.warn("Redeeming a held voucher failed for {}: {}", player.getGameProfile().getName(), exception.getMessage());
            return Optional.of(new Result(false, "The wallet could not be changed right now. Your voucher is unchanged."));
        } catch (CraftException exception) {
            return Optional.of(new Result(false, "That voucher cannot be deposited: " + exception.getMessage()));
        }
    }

    /**
     * Moves every loot-form stack the player carries into their wallet: first the credit commits (one store transaction), then the stacks are
     * removed. A crash between the two could credit a stack twice after a restart; that is the loot source's own value, never a tradable item.
     */
    static void absorb(ServerPlayer player, ProgressionStore store) {
        var inventory = player.getInventory();
        var totals = new EnumMap<MaterialId, Long>(MaterialId.class);
        var slots = new ArrayList<Integer>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var parsed = parse(inventory.getItem(slot));
            if (parsed.isEmpty() || parsed.get().batch() != null) continue;
            totals.merge(parsed.get().material(), (long) inventory.getItem(slot).getCount(), Long::sum);
            slots.add(slot);
        }
        if (totals.isEmpty()) return;
        try {
            store.grant(UUID.randomUUID(), player.getUUID(), totals, "loot_pickup");
        } catch (StoreException | CraftException exception) {
            LOG.warn("Could not credit {}'s loot currency ({}); the items stay in the inventory", player.getGameProfile().getName(), exception.getMessage());
            return;
        }
        for (int slot : slots) inventory.setItem(slot, ItemStack.EMPTY);
        var text = new StringBuilder();
        totals.forEach((material, amount) -> text.append(text.length() == 0 ? "+" : "  +").append(amount).append(' ').append(CurrencyItems.spec(material).name()));
        player.displayClientMessage(Component.literal(text.toString()), true);
    }

    // ---- vouchers: tradable currency ---------------------------------------------------------------------------

    /** The answer to a wallet action: whether it worked and one line for the player. */
    public record Result(boolean ok, String message) {}

    /** How many free main-inventory slots the player has: a withdrawal needs one per 64 units. */
    private static int freeSlots(ServerPlayer player) {
        int free = 0;
        for (int slot = 0; slot < MAIN_SLOTS; slot++) if (player.getInventory().getItem(slot).isEmpty()) free++;
        return free;
    }

    /**
     * Turns wallet balance into voucher items: checks the room first, debits and opens a batch in one store transaction, hands the stacks over,
     * then marks the batch delivered. If the hand-over is interrupted the batch is delivered again at the next login.
     */
    public static Result withdraw(ServerPlayer player, MaterialId material, int amount) {
        var service = AscensionApi.service().orElse(null);
        if (service == null) return new Result(false, "Progression is unavailable.");
        if (amount < 1 || amount > ProgressionStore.MAX_MINT) return new Result(false, "Withdraw between 1 and " + ProgressionStore.MAX_MINT + ".");
        int stacks = (amount + CurrencyItems.STACK - 1) / CurrencyItems.STACK;
        if (freeSlots(player) < stacks) return new Result(false, "You need " + stacks + " free inventory slot" + (stacks == 1 ? "" : "s") + " for that.");
        var op = UUID.randomUUID();
        try {
            service.store().mintVoucher(op, player.getUUID(), material, amount, CraftRequest.ANY_REVISION);
        } catch (CraftException exception) {
            return new Result(false, exception.reason() == CraftException.Reason.INSUFFICIENT_FUNDS
                    ? "You need " + (amount + CurrencyItems.withdrawalFee(amount)) + " " + CurrencyItems.spec(material).name() + " (" + amount + " plus a "
                    + com.cobbleascend.domain.v1.Tunables.current().withdrawalFeePercent() + "% fee of " + CurrencyItems.withdrawalFee(amount) + ")." : "That withdrawal is not allowed: " + exception.getMessage());
        } catch (StoreException exception) {
            LOG.warn("Withdrawal failed for {}: {}", player.getGameProfile().getName(), exception.getMessage());
            return new Result(false, "The wallet could not be changed right now. Nothing was taken.");
        }
        var batch = ProgressionStore.voucherBatchId(op);
        give(player, material, amount, batch);
        service.store().markVoucherDelivered(batch);
        LOG.info("{} withdrew {} {} as voucher batch {}", player.getGameProfile().getName(), amount, material.id(), batch);
        return new Result(true, "Withdrew " + amount + " " + CurrencyItems.spec(material).name() + " as voucher items (fee " + CurrencyItems.withdrawalFee(amount) + ").");
    }

    private static void give(ServerPlayer player, MaterialId material, int units, UUID batch) {
        int left = units;
        while (left > 0) {
            var stack = create(material, Math.min(left, CurrencyItems.STACK), batch);
            left -= stack.getCount();
            if (!player.getInventory().add(stack)) player.drop(stack, false);
        }
    }

    /** Hands over any voucher batch whose items never reached this player (a crash between the debit and the hand-over). */
    public static void deliverPending(ServerPlayer player) {
        var service = AscensionApi.service().orElse(null);
        if (service == null) return;
        for (var voucher : service.store().undeliveredVouchers(player.getUUID())) {
            give(player, voucher.material(), (int) Math.min(ProgressionStore.MAX_MINT, voucher.outstanding()), voucher.batchId());
            service.store().markVoucherDelivered(voucher.batchId());
            LOG.info("Delivered voucher batch {} ({} {}) to {} after an interrupted hand-over", voucher.batchId(), voucher.outstanding(), voucher.material().id(),
                    player.getGameProfile().getName());
            player.sendSystemMessage(Component.literal("[Wallet] " + voucher.outstanding() + " " + CurrencyItems.spec(voucher.material()).name()
                    + " delivered as items. Right-click to deposit, or keep them to trade."));
        }
    }

    /**
     * Redeems every voucher the player carries (of one material, or all): each stack credits what its batch still has outstanding and is
     * removed; units beyond that are duplicates or already redeemed and are discarded. A stack the store has never minted is left alone and reported.
     */
    public static Result deposit(ServerPlayer player, Optional<MaterialId> only) {
        var service = AscensionApi.service().orElse(null);
        if (service == null) return new Result(false, "Progression is unavailable.");
        var inventory = player.getInventory();
        var credited = new EnumMap<MaterialId, Long>(MaterialId.class);
        long discarded = 0;
        int unknown = 0, found = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            var parsed = parse(stack);
            if (parsed.isEmpty() || parsed.get().batch() == null) continue;
            if (only.isPresent() && only.get() != parsed.get().material()) continue;
            found++;
            try {
                var outcome = service.store().redeemVoucher(UUID.randomUUID(), player.getUUID(), parsed.get().batch(), stack.getCount());
                long got = outcome.cost().getOrDefault(parsed.get().material(), 0L);
                credited.merge(parsed.get().material(), got, Long::sum);
                discarded += stack.getCount() - got;
                inventory.setItem(slot, ItemStack.EMPTY);
            } catch (StoreException exception) {
                if (exception.code() == StoreException.Code.UNKNOWN_VOUCHER) unknown++;
                else LOG.warn("Redeeming a voucher failed for {}: {}", player.getGameProfile().getName(), exception.getMessage());
            } catch (CraftException exception) {
                LOG.warn("Redeeming a voucher refused for {}: {}", player.getGameProfile().getName(), exception.getMessage());
            }
        }
        if (found == 0) return new Result(false, "You are carrying no voucher items" + (only.isPresent() ? " of that kind." : "."));
        var text = new StringBuilder();
        credited.forEach((material, amount) -> { if (amount > 0) text.append(text.length() == 0 ? "Deposited " : ", ").append(amount).append(' ').append(CurrencyItems.spec(material).name()); });
        if (text.length() == 0) text.append("Nothing to credit");
        if (discarded > 0) text.append(". ").append(discarded).append(" duplicated or already-redeemed unit").append(discarded == 1 ? "" : "s").append(" discarded");
        if (unknown > 0) text.append(". ").append(unknown).append(" unrecognised voucher stack").append(unknown == 1 ? "" : "s").append(" left alone");
        return new Result(true, text + ".");
    }

    /** Units of each voucher material the player is carrying right now (for the wallet page). */
    public static Map<MaterialId, Long> carriedVouchers(ServerPlayer player) {
        var out = new EnumMap<MaterialId, Long>(MaterialId.class);
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var parsed = parse(inventory.getItem(slot));
            if (parsed.isPresent() && parsed.get().batch() != null) out.merge(parsed.get().material(), (long) inventory.getItem(slot).getCount(), Long::sum);
        }
        return out;
    }

    public static List<MaterialId> materials() { return List.of(MaterialId.values()); }
}
