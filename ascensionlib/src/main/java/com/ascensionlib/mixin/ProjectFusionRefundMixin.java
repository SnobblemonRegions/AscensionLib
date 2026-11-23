package com.ascensionlib.mixin;

import com.ascensionlib.compat.bond.FuserBond;
import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When a Fuser hands its Pokemon back unfinished (the server stopped mid-fusion, or the fusion failed), the Fuser bond on them is undone
 * and refunded ({@link FuserBond#refunded}). Same targeting rules as {@link ProjectFusionBondMixin}; checked against Project Fusion 2.1.0.
 */
@Pseudo
@Mixin(targets = "com.bigdadguys.projectfusion.FusionRefunds", remap = false)
public abstract class ProjectFusionRefundMixin {
    @Inject(method = "returnTo", at = @At("HEAD"), remap = false, require = 0)
    private static void ascensionlib$refundBond(MinecraftServer server, UUID owner, String ownerName, CompoundTag body, CompoundTag head,
                                                CompoundTag fusion, JsonObject cost, CallbackInfo ci) {
        FuserBond.refunded(body, fusion);
    }
}
