package com.ascensionlib.compat;

import com.ascensionlib.CraftLocks;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.Logger;

/**
 * Keeps Cobblemon Project Fusion (mod id {@value #MOD_ID}) from fusing a Pokemon that is on loan.
 *
 * <p>Project Fusion stores the head as a full copy inside the fusion and gives it back on split, and the fusion is a copy of the body.
 * A lent Pokemon (a CobbleTowers rental, marked with {@link CraftLocks#TAG}) fused as the head would hide from the run-end delete,
 * which scans only the party and PC, and come back for good on split. Fused as the body, the fusion inherits the mark and is deleted
 * at run end with the player's own head inside it. So a fusion that involves a lent Pokemon is refused before anything moves.
 *
 * <p>The refusal is injected at the head of Project Fusion's {@code FusionService.validate} (see {@code ProjectFusionServiceMixin}),
 * which both its Fuser screen and its direct fuse call run first. Its {@code IllegalArgumentException} is the mod's own way to refuse:
 * the Fuser screen shows the message and nothing is taken.
 */
public final class ProjectFusionGuard {
    public static final String MOD_ID = "cobblemon_project_fusion";
    static final String SERVICE_CLASS = "com.bigdadguys.projectfusion.FusionService";
    /** Part of the injected handler's name; Mixin keeps it in the merged method name, which is how {@link #check} finds it. */
    static final String HANDLER = "ascensionlib$refuseLoaned";
    public static final String REASON = "A Pokemon on loan cannot be fused. Return it first.";

    private ProjectFusionGuard() {}

    /** Why this pair may not be fused, from each partner's persistent data, or empty when it may. */
    public static Optional<String> refusal(CompoundTag bodyData, CompoundTag headData) {
        return lent(bodyData) || lent(headData) ? Optional.of(REASON) : Optional.empty();
    }

    public static final String NO_UNIQUE = "Both Pokémon must hold a Unique to be fused. Install one with a Unique Catalyst on the Craft page.";
    public static final String UNAVAILABLE = "The Fuser is unavailable while progression is offline.";

    /**
     * Owner rule 2026-11-20: the Fuser fuses only two Pokemon that each hold a Unique; any other pair is refused before Project Fusion
     * takes its fee. Checked at the same hook as the loan guard. Without the progression store nothing can be checked, so it refuses too.
     */
    public static Optional<String> uniqueRefusal(com.cobblemon.mod.common.pokemon.Pokemon body, com.cobblemon.mod.common.pokemon.Pokemon head) {
        var service = com.ascensionlib.AscensionApi.service();
        if (service.isEmpty()) return Optional.of(UNAVAILABLE);
        return uniqueRefusal(holdsUnique(service.get(), body), holdsUnique(service.get(), head));
    }

    private static boolean holdsUnique(com.ascensionlib.ProfileService service, com.cobblemon.mod.common.pokemon.Pokemon pokemon) {
        return pokemon != null && service.canonical(pokemon).map(p -> p.unique() != null).orElse(false);
    }

    /** The rule itself. Package-visible for the tests. */
    static Optional<String> uniqueRefusal(boolean bodyHoldsUnique, boolean headHoldsUnique) {
        return bodyHoldsUnique && headHoldsUnique ? Optional.empty() : Optional.of(NO_UNIQUE);
    }

    private static boolean lent(CompoundTag data) {
        return data != null && data.getBoolean(CraftLocks.TAG);
    }

    /**
     * Logs whether the guard is in place. With Project Fusion installed and the injection missing (a Project Fusion update renamed
     * {@code validate}), the mixin is skipped rather than crashing the server, so this is where an operator learns of it.
     */
    public static void check(Logger log) {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) return;
        String version = FabricLoader.getInstance().getModContainer(MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        try {
            Method[] service = Class.forName(SERVICE_CLASS).getDeclaredMethods();
            if (guarded(service))
                log.info("Project Fusion {} found: only two Pokemon that each hold a Unique can be fused, never one on loan.", version);
            else
                log.error("Project Fusion {} found but the fuse guard did NOT apply: a lent Pokemon can be fused and kept, and Pokemon without a Unique can be fused. "
                        + "Remove Project Fusion or update AscensionLib before players use a Fuser.", version);
            // The Fuser bond needs its start hook and its split lock; without both it stays off and the Fuser works as Project Fusion made it.
            boolean bondHooks = has(service, BOND_HANDLER) && has(service, SPLIT_HANDLER);
            boolean refundHook = has(Class.forName(REFUNDS_CLASS).getDeclaredMethods(), REFUND_HANDLER);
            if (com.ascensionlib.compat.bond.FuserBond.enable(bondHooks))
                log.info("Project Fusion {}: Transcendence is made at the Fuser; a bond cannot be split{}.", version,
                        refundHook ? "" : " (the refund hook did NOT apply: an interrupted Fuser is refunded at the owner's next login)");
            else
                log.error("Project Fusion {} found but the Fuser bond hooks did NOT apply; Transcendence at the Fuser is off.", version);
            if (com.ascensionlib.compat.bond.FuserBond.enabled() && !has(service, TIP_HANDLER))
                log.warn("Project Fusion {}: the Fuser screen tip did not apply; players learn the bond from the page and the Guide only.", version);
            if (com.ascensionlib.compat.bond.FuserBond.enabled()
                    && !has(Class.forName("com.bigdadguys.projectfusion.FuserBlockEntity").getDeclaredMethods(), COLLECT_HANDLER))
                log.warn("Project Fusion {}: the collect hook did not apply; a new Transcendent is collected without its reveal.", version);
        } catch (ClassNotFoundException | LinkageError exception) {
            log.error("Project Fusion {} found but {} could not be loaded; the loan guard is not in place", version, SERVICE_CLASS, exception);
        }
    }

    static final String REFUNDS_CLASS = "com.bigdadguys.projectfusion.FusionRefunds";
    static final String BOND_HANDLER = "ascensionlib$bondAtFuser";
    static final String SPLIT_HANDLER = "ascensionlib$lockBond";
    static final String REFUND_HANDLER = "ascensionlib$refundBond";
    static final String TIP_HANDLER = "ascensionlib$fuserTip";
    static final String COLLECT_HANDLER = "ascensionlib$revealBond";

    /** Whether the loan guard's injected handler is among these methods. Package-visible for the tests. */
    static boolean guarded(Method[] methods) {
        return has(methods, HANDLER);
    }

    /** Whether a handler merged under this name is among these methods (Mixin keeps the name inside the merged one). */
    static boolean has(Method[] methods, String handler) {
        return Arrays.stream(methods).anyMatch(m -> m.getName().contains(handler));
    }
}
