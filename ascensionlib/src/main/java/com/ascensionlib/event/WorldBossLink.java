package com.ascensionlib.event;

import com.ascensionlib.AscensionApi;
import com.ascensionlib.AscensionLib;
import com.cobbleascend.domain.v1.MaterialId;
import com.cobbleascend.domain.v1.WorldBossEssence;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pays Spooky Essence when CobbleRaids' World Boss is defeated during the Halloween window ({@link HalloweenEvent}; docs/HALLOWEEN-EVENT-DESIGN.md).
 * After the window a defeat pays none: the Essence is the event's currency. CobbleRaids is optional for this library, so its
 * World Boss API is reached by reflection, the same way {@code RaidsShowdownHost} reaches its Showdown extension API: when it is absent, or is not
 * version 1, nothing happens and the library runs as before.
 *
 * <p>The listener may be told twice for one boss (a restart between the defeat and its bookkeeping), so each payment is one store operation whose
 * ID is made from the boss and the player: the second call replays and credits nothing.
 */
public final class WorldBossLink {
    private static final Logger LOG = LoggerFactory.getLogger(AscensionLib.MOD_ID);
    private static final String API = "com.cobbleraids.api.worldboss.CobbleRaidsWorldBoss";
    private static final String LISTENER = "com.cobbleraids.api.worldboss.WorldBossListener";
    private static final int SUPPORTED_API_VERSION = 1;

    private static volatile Object registered;

    private WorldBossLink() {}

    /** Registers once per JVM; a second call (an integrated server restarting) replaces the first so a boss never pays twice. */
    public static synchronized void register(MinecraftServer server) {
        try {
            Class<?> api = Class.forName(API);
            Class<?> listenerType = Class.forName(LISTENER);
            int version = api.getField("API_VERSION").getInt(null);
            if (version != SUPPORTED_API_VERSION) {
                LOG.warn("CobbleRaids' World Boss API is version {}, not {}: World Boss rewards are off.", version, SUPPORTED_API_VERSION);
                return;
            }
            Method add = api.getMethod("addListener", listenerType);
            Method remove = api.getMethod("removeListener", listenerType);
            if (registered != null) remove.invoke(null, registered);
            Object proxy = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[] {listenerType}, (self, method, args) -> {
                switch (method.getName()) {
                    case "onDefeated" -> {
                        @SuppressWarnings("unchecked") Map<UUID, Float> damage = (Map<UUID, Float>) args[2];
                        paid(server, (UUID) args[0], damage, (Float) args[3]);
                        return null;
                    }
                    case "toString" -> { return "AscensionLib World Boss listener"; }
                    case "hashCode" -> { return System.identityHashCode(self); }
                    case "equals" -> { return self == args[0]; }
                    default -> { return null; }
                }
            });
            add.invoke(null, proxy);
            registered = proxy;
            LOG.info("Listening for the CobbleRaids World Boss: while Halloween is on, a defeat pays {} Spooky Essence to each participant.",
                    WorldBossEssence.PER_PARTICIPANT);
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException absent) {
            LOG.debug("CobbleRaids has no World Boss API here ({}); World Boss rewards are off.", absent.toString());
        } catch (ReflectiveOperationException | LinkageError unexpected) {
            LOG.warn("CobbleRaids' World Boss API could not be reached ({}); World Boss rewards are off.", unexpected.toString());
        }
    }

    /** Pays the essence for one defeated boss. Never throws: one bad payment must not cost the others. */
    static void paid(MinecraftServer server, UUID bossId, Map<UUID, Float> damage, float total) {
        var service = AscensionApi.service();
        if (service.isEmpty()) {
            LOG.warn("World Boss {} was defeated but progression is disabled, so no Spooky Essence was paid.", bossId);
            return;
        }
        if (!HalloweenEvent.isOpen()) {
            LOG.info("World Boss {} defeated after Halloween: no Spooky Essence paid.", bossId);
            return;
        }
        int paid = 0;
        for (var entry : WorldBossEssence.payouts(damage, true).entrySet()) {
            try {
                var outcome = service.get().store().grant(WorldBossEssence.operationId(bossId, entry.getKey()), entry.getKey(),
                        Map.of(MaterialId.SPOOKY_ESSENCE, entry.getValue()), "world boss " + bossId);
                if (outcome.replayed()) continue;
                paid++;
                var online = server.getPlayerList().getPlayer(entry.getKey());
                if (online != null) {
                    online.sendSystemMessage(Component.literal("[Haunting] You earned " + entry.getValue()
                            + " Spooky Essence. Spend it with /ascend haunt <slot>."));
                }
            } catch (RuntimeException exception) {
                LOG.error("Could not pay Spooky Essence to {} for World Boss {}", entry.getKey(), bossId, exception);
            }
        }
        LOG.info("World Boss {} defeated: {} player(s) paid Spooky Essence", bossId, paid);
    }
}
