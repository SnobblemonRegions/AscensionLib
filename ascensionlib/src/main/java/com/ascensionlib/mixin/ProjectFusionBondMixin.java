package com.ascensionlib.mixin;

import com.ascensionlib.compat.bond.FuserBond;
import com.bigdadguys.projectfusion.FuserBlockEntity;
import com.bigdadguys.projectfusion.FusionCost;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Fuser bond's hooks into Project Fusion's {@code FusionService} (see {@link FuserBond}): the start of a Fuser fusion is offered to
 * AscensionLib first, and a bond is never split.
 *
 * <p>Methods are named without descriptors and {@code remap = false}: Project Fusion's own names never change between installs, and
 * Loom remaps this class's handler signatures with the rest of the jar. {@code require = 0}: a Project Fusion update that renames a
 * method leaves the bond off (reported at start-up by {@code ProjectFusionGuard.check}) instead of crashing. Checked against 2.1.0.
 */
@Pseudo
@Mixin(targets = "com.bigdadguys.projectfusion.FusionService", remap = false)
public abstract class ProjectFusionBondMixin {
    @Inject(method = "begin", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void ascensionlib$bondAtFuser(ServerPlayer player, PlayerPartyStore party, FuserBlockEntity fuser, Pokemon body, Pokemon head,
                                                 int ability, int nature, FusionCost.Payment payment, CallbackInfo ci) {
        if (FuserBond.intercept(player, fuser, body, head, ability, nature, payment)) ci.cancel();
    }

    /**
     * Opening a Fuser by hand (Project Fusion's three-argument {@code open}, which it calls with no message) shows the Fuser tip in the
     * screen's status line until the player has learned the bond. The descriptor is Project Fusion's installed (intermediary) one, which
     * tells it apart from the four-argument {@code open} Project Fusion uses after a split; that overload is left alone.
     */
    @Inject(method = "open(Lnet/minecraft/class_3222;Lnet/minecraft/class_2338;Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true,
            remap = false, require = 0)
    private static void ascensionlib$fuserTip(ServerPlayer player, net.minecraft.core.BlockPos pos, String message, CallbackInfo ci) {
        if (message != null && !message.isEmpty()) return;
        FuserBond.tip(player, pos).ifPresent(tip -> {
            ci.cancel();
            com.bigdadguys.projectfusion.FusionService.open(player, pos, tip, null);
        });
    }

    @Inject(method = "split", at = @At("HEAD"), remap = false, require = 0)
    private static void ascensionlib$lockBond(ServerPlayer player, PlayerPartyStore party, Pokemon fusion, CallbackInfo ci) {
        FuserBond.refuseSplit(fusion);
    }
}
