package com.ascensionlib.mixin;

import com.ascensionlib.compat.ProjectFusionGuard;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Refuses a Project Fusion fuse that involves a Pokemon on loan, or a pair where either Pokemon holds no Unique (see {@link ProjectFusionGuard}).
 *
 * <p>{@code @Pseudo}: without Project Fusion the target is absent and this is skipped. The target's parameters are Cobblemon types
 * only, so nothing needs remapping. {@code require = 0} so a Project Fusion update that renames {@code validate} disables the guard
 * instead of crashing the server; {@link ProjectFusionGuard#check} reports that at start-up. Checked against Project Fusion 2.1.0.
 */
@Pseudo
@Mixin(targets = "com.bigdadguys.projectfusion.FusionService", remap = false)
public abstract class ProjectFusionServiceMixin {
    @Inject(method = "validate(Lcom/cobblemon/mod/common/api/storage/party/PlayerPartyStore;Lcom/cobblemon/mod/common/pokemon/Pokemon;Lcom/cobblemon/mod/common/pokemon/Pokemon;Z)V",
            at = @At("HEAD"), remap = false, require = 0)
    private static void ascensionlib$refuseLoaned(PlayerPartyStore party, Pokemon body, Pokemon head, boolean battleForms, CallbackInfo ci) {
        var refusal = ProjectFusionGuard.refusal(body == null ? null : body.getPersistentData(), head == null ? null : head.getPersistentData())
                .or(() -> ProjectFusionGuard.uniqueRefusal(body, head));
        if (refusal.isPresent()) throw new IllegalArgumentException(refusal.get());
    }
}
