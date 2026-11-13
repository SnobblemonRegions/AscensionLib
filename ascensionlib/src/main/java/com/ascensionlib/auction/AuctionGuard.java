package com.ascensionlib.auction;

import com.ascensionlib.AscensionLib;
import com.ascensionlib.telemetry.Telemetry;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.PokemonStore;
import com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps SnobbleAuctions from duplicating or losing a Pokemon when the server dies hard (kill, power loss, JVM crash). The auction saves {@code listings.json}
 * at once at every step, but Cobblemon writes a player's party and PC only every {@code pokemonSaveIntervalSeconds} (30 by default), so after a crash the two
 * can disagree (see {@code docs/AUCTION-CRASH-WINDOW-NOTE.md}). Two measures, neither touches the auction mod:
 * <ol>
 *   <li><b>Flush.</b> The moment {@code listings.json} changes, every dirty party and PC is written to disk. Cobblemon serializes on the calling thread and queues
 *   only the file write, so the window shrinks from up to 30 s to one file write.</li>
 *   <li><b>Reconcile.</b> At start-up, after every listing change and when a player joins, a Pokemon that a held listing owns is removed from its seller's party
 *   and PC if it is still there (the listing is the authority). The removed Pokemon is first saved under {@code cobbleascend/auction-recovered/}.</li>
 * </ol>
 * A buyer who was charged but is missing the Pokemon is the mod's own start-up recovery to settle ({@code recoverPurchase}); the flush leaves that case almost no
 * window, and guessing there could create a duplicate, so it is not touched.
 */
public final class AuctionGuard {
    private static final Logger LOG = LoggerFactory.getLogger(AscensionLib.MOD_ID);
    private static final String AUCTION_MOD = "snobbleauctions";

    private static Path listingsFile;
    private static long lastModified = Long.MIN_VALUE;
    private static long lastSize = -1;
    private static boolean reconcileNext;
    private static boolean flushFailureLogged;

    private AuctionGuard() {}

    public static void install() {
        ServerLifecycleEvents.SERVER_STARTED.register(AuctionGuard::started);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { listingsFile = null; reportedUnremovable.clear(); });
        ServerTickEvents.END_SERVER_TICK.register(AuctionGuard::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (listingsFile != null) server.execute(() -> reconcile(server, handler.getPlayer().getUUID()));
        });
    }

    private static void started(MinecraftServer server) {
        if (!FabricLoader.getInstance().isModLoaded(AUCTION_MOD)) return;
        listingsFile = FabricLoader.getInstance().getConfigDir().resolve("snobbleauctions").resolve("listings.json");
        lastModified = Long.MIN_VALUE;
        lastSize = -1;
        reconcileNext = true; // the auction loads its file in its own SERVER_STARTED handler; reconcile on the first tick, when both have run
        LOG.info("Auction guard watching {}: storage is flushed on every listing change and a seller's duplicate is removed.", listingsFile);
    }

    private static void tick(MinecraftServer server) {
        Path file = listingsFile;
        if (file == null) return;
        boolean changed = false;
        try {
            if (Files.exists(file)) {
                long modified = Files.getLastModifiedTime(file).toMillis();
                long size = Files.size(file);
                changed = modified != lastModified || size != lastSize;
                lastModified = modified;
                lastSize = size;
            }
        } catch (IOException exception) {
            return; // being replaced at this instant; the next tick sees it
        }
        if (changed) flush(server);
        if (changed || reconcileNext) {
            reconcileNext = false;
            reconcile(server, null);
        }
    }

    // --- flush ---------------------------------------------------------------------------------------

    /** Writes every dirty party and PC now. Safe to call at any time; it only brings forward what Cobblemon would write at its next timer. */
    public static void flush(MinecraftServer server) {
        try {
            for (FileBackedPokemonStoreFactory<?> factory : factories()) factory.saveAll(server.registryAccess());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!flushFailureLogged) {
                flushFailureLogged = true;
                LOG.warn("Auction guard could not flush Cobblemon storage ({}); the duplicate check still runs, but a crash can lose a move", exception.toString());
            }
        }
    }

    private static void flush(MinecraftServer server, PokemonStore<?> store) {
        try {
            for (FileBackedPokemonStoreFactory<?> factory : factories()) {
                if (factory.isCached(store)) { factory.save(store, server.registryAccess()); return; }
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOG.warn("Auction guard could not save a store: {}", exception.toString());
        }
    }

    /** Cobblemon keeps its store factories in a private list; the file-backed one is the default and the only one that delays writes. */
    private static List<FileBackedPokemonStoreFactory<?>> factories() throws ReflectiveOperationException {
        Field field = Cobblemon.INSTANCE.getStorage().getClass().getDeclaredField("factories");
        field.setAccessible(true);
        var out = new java.util.ArrayList<FileBackedPokemonStoreFactory<?>>();
        for (Object factory : (Iterable<?>) field.get(Cobblemon.INSTANCE.getStorage())) {
            if (factory instanceof FileBackedPokemonStoreFactory<?> fileBacked) out.add(fileBacked);
        }
        return out;
    }

    // --- reconcile -----------------------------------------------------------------------------------

    /** Removes from sellers' storage any Pokemon a held listing owns. {@code only} limits it to one seller (a join); null checks every listing. */
    static void reconcile(MinecraftServer server, UUID only) {
        Path file = listingsFile;
        if (file == null || !Files.exists(file)) return;
        List<AuctionListings.Held> held;
        try {
            held = AuctionListings.held(Files.readString(file));
        } catch (IOException | IllegalArgumentException exception) {
            LOG.warn("Auction guard left everything alone: could not read {} ({})", file, exception.toString());
            return;
        }
        for (AuctionListings.Held listing : held) {
            if (only != null && !only.equals(listing.seller())) continue;
            try {
                removeDuplicate(server, listing);
            } catch (RuntimeException exception) {
                LOG.warn("Auction guard could not check listing {}: {}", listing.listingId(), exception.toString());
            }
        }
    }

    /**
     * The Pokemon with this UUID that sits in one of the store's slots, or null. Not {@code store.get(uuid)}: that reads Cobblemon's UUID index, which can keep
     * an entry after the Pokemon left its slot. Such an entry is no copy (no player can see or use it), and {@code remove} refuses it, so acting on it made
     * the guard back up and report the same listing on every check (live data, 12 Nov 2026: 75 listings, 1,413 reports, none removed).
     */
    static <P> P inSlot(Iterable<P> store, java.util.function.Function<P, UUID> uuidOf, UUID uuid) {
        if (store == null) return null;
        for (P mon : store) if (mon != null && uuid.equals(uuidOf.apply(mon))) return mon;
        return null;
    }

    /** Listings whose copy could not be removed, reported once per server run rather than on every check. */
    private static final java.util.Set<String> reportedUnremovable = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Whether a failed removal for this listing and Pokemon is new this run (and should be backed up and reported). */
    static boolean firstFailure(java.util.Set<String> reported, AuctionListings.Held listing) {
        return reported.add(listing.listingId() + "|" + listing.pokemon());
    }

    private static void removeDuplicate(MinecraftServer server, AuctionListings.Held listing) {
        RegistryAccess registries = server.registryAccess();
        var storage = Cobblemon.INSTANCE.getStorage();
        var party = storage.getParty(listing.seller(), registries);
        var pc = storage.getPC(listing.seller(), registries);
        Pokemon mon = inSlot(party, Pokemon::getUuid, listing.pokemon());
        PokemonStore<?> from = party;
        if (mon == null) { mon = inSlot(pc, Pokemon::getUuid, listing.pokemon()); from = pc; }
        if (mon == null) return;
        // A copy that cannot be removed is saved and reported the first time only; checking again cannot change the outcome until the next restart.
        String key = listing.listingId() + "|" + listing.pokemon();
        if (reportedUnremovable.contains(key)) return;
        Path saved = backup(server, listing, mon, registries);
        boolean removed = from == party ? party.remove(mon) : pc.remove(mon);
        if (removed) flush(server, from);
        else firstFailure(reportedUnremovable, listing);
        final Pokemon gone = mon;
        LOG.warn("Auction guard: listing {} ({}) owns Pokemon {} ({}) but its seller still held a copy; copy {} (saved to {})", listing.listingId(),
                listing.state(), gone.getUuid(), gone.getSpecies().getName(), removed ? "removed" : "COULD NOT BE REMOVED", saved);
        Telemetry.log("auction_duplicate_removed", e -> {
            e.addProperty("listing", listing.listingId());
            e.addProperty("state", listing.state());
            e.addProperty("seller", Telemetry.playerKey(listing.seller()));
            e.addProperty("species", gone.getSpecies().getName());
            e.addProperty("removed", removed);
        });
    }

    /** The removed Pokemon, exactly as Cobblemon saves it, so a wrong removal (which the data should make impossible) can be undone by hand. */
    private static Path backup(MinecraftServer server, AuctionListings.Held listing, Pokemon mon, RegistryAccess registries) {
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve("cobbleascend").resolve("auction-recovered");
        Path target = dir.resolve(Instant.now().toString().replace(':', '-') + "-" + listing.listingId() + "-" + mon.getUuid() + ".nbt");
        try {
            Files.createDirectories(dir);
            CompoundTag tag = mon.saveToNBT(registries, new CompoundTag());
            NbtIo.writeCompressed(tag, target);
        } catch (IOException exception) {
            throw new IllegalStateException("could not save a backup of " + mon.getUuid() + "; not removing it", exception);
        }
        return target;
    }
}
