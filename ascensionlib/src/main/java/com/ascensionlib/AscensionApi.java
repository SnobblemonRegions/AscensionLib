package com.ascensionlib;

import com.cobbleascend.domain.v1.RankedRules;
import com.cobbleascend.store.EncounterRewards;
import com.cobbleascend.store.ProgressionStore;
import com.cobbleascend.store.ScoutingService;
import java.util.Optional;
import java.util.UUID;

/**
 * The one entry point other mods use. Everything here is empty or "disabled" while no world is running or when the
 * world's store was refused (missing, mismatched or corrupt); callers must handle that, never fall back to their own
 * storage. Call on the server thread.
 */
public final class AscensionApi {
    /** Where the library stands for the current world. */
    public record Status(boolean serverRunning, boolean enabled, String disabledReason, UUID authority) {}

    private static volatile State state;

    private record State(AscensionRuntime runtime, ProfileService service, ScoutingService scouting,
                         EncounterRewards encounterRewards) {}

    private AscensionApi() {}

    private static volatile net.minecraft.server.MinecraftServer server;

    static void attach(AscensionRuntime runtime, ProfileService service, net.minecraft.server.MinecraftServer running) {
        server = running;
        state = service == null ? new State(runtime, null, null, null)
                : new State(runtime, service, new ScoutingService(service.store()), physical(new EncounterRewards(service.store())));
    }

    /** Encounter rewards arrive as voucher items while {@code threat.physicalRewards} is on (read per payout, so a reload applies). */
    private static EncounterRewards physical(EncounterRewards rewards) {
        rewards.physicalRewards(() -> com.cobbleascend.domain.v1.Tunables.current().threat().physicalRewards());
        return rewards;
    }

    static void detach() { state = null; server = null; }

    /** The running server, for library code that must reach online players from a reflected call. */
    public static net.minecraft.server.MinecraftServer server() { return server; }

    /** The progression service, or empty when there is no running world or progression is disabled for it. */
    public static Optional<ProfileService> service() {
        var current = state;
        return current == null ? Optional.empty() : Optional.ofNullable(current.service());
    }

    /** The canonical store (wallets, profiles, operations), or empty as for {@link #service()}. */
    /**
     * Whether this player asked for calm effects (no animation, flash or flare). Other mods that draw their own effects must honour it: it exists for players
     * who are sensitive to them, such as photosensitive epilepsy. False when the player is unknown or the world is not running.
     */
    public static boolean calmEffects(UUID player) {
        var running = server;
        return running != null && player != null && com.ascensionlib.web.UiPrefsWeb.calmOf(running).get(player);
    }

    public static Optional<ProgressionStore> store() { return service().map(ProfileService::store); }

    /** Scouter rules over the canonical wallet; one instance per world so reveals are shared by every caller. */
    public static Optional<ScoutingService> scouting() {
        var current = state;
        return current == null ? Optional.empty() : Optional.ofNullable(current.scouting());
    }

    /** Once-only wallet rewards for finished encounters; one instance per world. */
    public static Optional<EncounterRewards> encounterRewards() {
        var current = state;
        return current == null ? Optional.empty() : Optional.ofNullable(current.encounterRewards());
    }

    /** The catalog the running world uses. */
    public static Optional<RankedRules> rules() { return service().map(ProfileService::rules); }

    public static Status status() {
        var current = state;
        if (current == null) return new Status(false, false, "server not started", null);
        var runtime = current.runtime();
        return new Status(true, runtime.enabled(), runtime.disabledReason(), runtime.authority());
    }
}
