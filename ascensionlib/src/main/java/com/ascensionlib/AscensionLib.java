package com.ascensionlib;

import com.ascensionlib.telemetry.BalanceLog;
import com.ascensionlib.telemetry.Telemetry;
import com.cobbleascend.domain.v1.RankedRules;
import com.cobbleascend.store.ProgressionStore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the canonical store's lifecycle for the running world: opens it when the server starts and closes it when
 * the server stops, and registers the Cobblemon capture/hatch/level wiring and the {@code /ascend} commands.
 * Mods built on the library never open the database themselves; they call {@link AscensionApi}.
 */
public final class AscensionLib implements ModInitializer {
    public static final String MOD_ID = "ascensionlib";
    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    private RankedRules rules;
    private AscensionRuntime runtime;

    @Override public void onInitialize() {
        rules = RankedRules.defaults();
        try {
            LOG.info("SQLite driver OK (engine {}); native library loaded in this runtime", ProgressionStore.driverVersion());
        } catch (RuntimeException exception) {
            LOG.error("SQLite driver is NOT usable; progression will stay disabled", exception);
        }
        com.ascensionlib.scout.ScoutNet.register();
        // Optional: with Polymer installed, the item art joins its server resource pack (the class is not loaded otherwise).
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("polymer-resource-pack")) com.ascensionlib.wallet.PolymerPack.register();
        ServerLifecycleEvents.SERVER_STARTING.register(this::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::stop);
        // Closed only once the server has stopped: players are disconnected and other mods end their raids and runs after STOPPING,
        // and those endings (leave, raid_end server_stop, interrupted tower floors) must still reach the log.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> Telemetry.close());
        // A player who leaves keeps neither their request throttles nor an "armed" battle: the encounter owner re-arms at the next battle.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var id = handler.getPlayer().getUUID();
            BalanceLog.left(handler.getPlayer());
            com.ascensionlib.net.RateLimit.forget(id);
            com.ascensionlib.web.HudWeb.forget(id);
            com.ascensionlib.web.WebSprites.forget(id);
            com.ascensionlib.scout.InspectWeb.forget(id);
            com.ascensionlib.scout.ScoutEncounters.endWild(id);
            com.ascensionlib.craft.CraftNet.forget(id);
            com.ascensionlib.fusion.FusionWeb.forget(id);
            com.ascensionlib.scout.ScoutEncounters.disarmBattle(java.util.List.of(id));
        });
        new AscendWiring().register();
        LOG.info("AscensionLib: shared progression store ready to open with the world.");
    }

    private void start(MinecraftServer server) {
        CompatReport.log(LOG);
        var config = TunablesConfig.loadAndUse(TunablesConfig.file());
        var changed = TunablesConfig.differences(com.cobbleascend.domain.v1.Tunables.defaults(), config.tunables());
        if (!changed.isEmpty()) LOG.info("Configuration differs from the defaults: {}", changed);
        runtime = AscensionRuntime.open(server, rules);
        AscensionApi.attach(runtime, runtime.enabled() ? new ProfileService(runtime, rules) : null, server);
        com.ascensionlib.scout.ScoutEncounters.attach(server);
        Telemetry.open(server.getWorldPath(LevelResource.ROOT).resolve(AscensionRuntime.DATA_DIRECTORY).resolve("telemetry"));
        if (runtime.enabled()) BalanceLog.attach(runtime.store());
        BalanceLog.serverStarted(server, changed);
    }

    private void stop(MinecraftServer server) {
        BalanceLog.serverStopping(server);
        com.ascensionlib.net.RateLimit.clear();
        com.ascensionlib.scout.ScoutEncounters.detach();
        AscensionApi.detach();
        if (runtime != null) runtime.close();
        runtime = null;
    }
}
