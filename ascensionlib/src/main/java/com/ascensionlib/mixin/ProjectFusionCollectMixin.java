package com.ascensionlib.mixin;

import com.ascensionlib.compat.bond.FuserBond;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Opens the Transcendence reveal when a finished Fuser bond is collected ({@link FuserBond#collected}). Project Fusion's {@code collect}
 * clears the Fuser's saved fusion as it hands the Pokemon over, so its id is read at the start and used once the collect succeeded
 * ({@code "party"} or {@code "pc"}). Same targeting rules as {@link ProjectFusionBondMixin}; checked against Project Fusion 2.1.0.
 */
@Pseudo
@Mixin(targets = "com.bigdadguys.projectfusion.FuserBlockEntity", remap = false)
public abstract class ProjectFusionCollectMixin {
    @Shadow(remap = false) private CompoundTag fusion;

    private static final ThreadLocal<java.util.UUID> ascensionlib$collecting = new ThreadLocal<>();

    @Inject(method = "collect", at = @At("HEAD"), remap = false, require = 0)
    private void ascensionlib$noteCollect(ServerPlayer player, ItemStack ball, CallbackInfoReturnable<String> cir) {
        ascensionlib$collecting.set(fusion != null && fusion.hasUUID("UUID") ? fusion.getUUID("UUID") : null);
    }

    @Inject(method = "collect", at = @At("RETURN"), remap = false, require = 0)
    private void ascensionlib$revealBond(ServerPlayer player, ItemStack ball, CallbackInfoReturnable<String> cir) {
        var id = ascensionlib$collecting.get();
        ascensionlib$collecting.remove();
        String where = cir.getReturnValue();
        if (id != null && ("party".equals(where) || "pc".equals(where))) FuserBond.collected(player, id);
    }
}
