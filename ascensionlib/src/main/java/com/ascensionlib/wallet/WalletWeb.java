package com.ascensionlib.wallet;

import com.ascensionlib.AscensionApi;
import com.ascensionlib.net.RateLimit;
import com.ascensionlib.web.WebUi;
import com.cobbleascend.domain.v1.CurrencyItems;
import com.cobbleascend.domain.v1.MaterialId;
import java.util.ArrayList;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

/**
 * The wallet page ({@code wallet.html}): every material with its balance and the voucher units the player is carrying, a withdraw box per material
 * and Deposit buttons. The page only asks; {@link WalletItems} and the store decide, and every action is rate limited.
 */
public final class WalletWeb {
    private WalletWeb() {}

    /** One row of the wallet page. {@code color} is a CSS colour. */
    public record Line(String id, String name, String color, long amount, long carried, boolean tradable) {}

    public record View(java.util.List<Line> lines, int freeSlots, int feePercent, int catalystNeeded) {}

    public record ResultView(boolean ok, String message) {}

    public static void register() {
        WebUi.listen("ascension_wallet_ready", (player, o) -> send(player));
        WebUi.listen("ascension_wallet_withdraw", (player, o) -> {
            if (!RateLimit.allow(player.getUUID(), "wallet.action", 4, 2)) return;
            var material = material(WebUi.str(o, "material", 32));
            long amount = WebUi.num(o, "amount");
            if (material.isPresent() && !CurrencyItems.spec(material.get()).tradable()) { answer(player, new WalletItems.Result(false, CurrencyItems.spec(material.get()).name() + " is bound to you and cannot be taken out.")); return; }
            if (material.isEmpty() || amount < 1 || amount > Integer.MAX_VALUE) { answer(player, new WalletItems.Result(false, "Choose a material and an amount.")); return; }
            answer(player, WalletItems.withdraw(player, material.get(), (int) amount));
        });
        WebUi.listen("ascension_wallet_deposit", (player, o) -> {
            if (!RateLimit.allow(player.getUUID(), "wallet.action", 4, 2)) return;
            String text = WebUi.str(o, "material", 32);
            answer(player, WalletItems.deposit(player, text.isEmpty() ? Optional.empty() : material(text)));
        });
    }

    private static Optional<MaterialId> material(String id) {
        try {
            return Optional.of(MaterialId.fromId(id));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    public static boolean open(ServerPlayer player) { return WebUi.open(player, "wallet.html", "wallet", WalletWeb::open); }

    private static void answer(ServerPlayer player, WalletItems.Result result) {
        WebUi.emit(player, "walletResult", new ResultView(result.ok(), result.message()));
        send(player);
    }

    static void send(ServerPlayer player) {
        var service = AscensionApi.service().orElse(null);
        if (service == null) return;
        var wallet = service.store().wallet(player.getUUID());
        var carried = WalletItems.carriedVouchers(player);
        var lines = new ArrayList<Line>();
        for (var material : MaterialId.values()) {
            var spec = CurrencyItems.spec(material);
            lines.add(new Line(material.id(), spec.name(), String.format("#%06x", spec.color()), wallet.balance(material), carried.getOrDefault(material, 0L), spec.tradable()));
        }
        int free = 0;
        for (int slot = 0; slot < 36; slot++) if (player.getInventory().getItem(slot).isEmpty()) free++;
        WebUi.emit(player, "walletView", new View(lines, free, com.cobbleascend.domain.v1.Tunables.current().withdrawalFeePercent(), com.cobbleascend.store.StoreConfig.defaults().catalystFragments()));
    }
}
