package com.ascensionlib.compat.bond;

import com.ascensionlib.AscensionApi;
import com.bigdadguys.projectfusion.FuserBlockEntity;
import com.bigdadguys.projectfusion.FusionCost;
import com.bigdadguys.projectfusion.FusionService;
import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Transcendence made at a Project Fusion Fuser (owner decision 2026-11-20, "permanent bond"). One Fuser action makes both the physical
 * fusion (Project Fusion: the body keeps its id, the head is stored inside it) and the Transcendent (AscensionLib), for good.
 *
 * <ol>
 *   <li>The player picks a body and a head on the Fuser's own screen and presses Fuse. If both hold a Unique, {@link #intercept} stops
 *   Project Fusion's start (refunding the fee it already took), remembers the pairing as an {@link Offer} and opens AscensionLib's fusion
 *   page on it. Any other pairing runs as an ordinary Project Fusion fusion.</li>
 *   <li>On that page the player confirms the Transcendence ({@link #confirm}): AscensionLib checks its rules and takes its price in one
 *   transaction ({@code ProfileService.bond}), then starts the Fuser exactly as Project Fusion would. Or the player chooses a plain
 *   fusion, which pays Project Fusion's fee and starts the Fuser with no Transcendence.</li>
 *   <li>The Fuser plays its animation; the result is collected with a Poke Ball as usual.</li>
 * </ol>
 * If the Fuser cannot start, the bond is undone at once. If it never finishes, Project Fusion hands both Pokemon back and
 * {@link #refunded} undoes the bond and refunds its price; the login sweep catches any case that hook misses. A bond can never be
 * split ({@link #refuseSplit}).
 *
 * <p>Loaded only when Project Fusion is installed (the mixins that call it target Project Fusion's classes). Project Fusion's private
 * methods are reached by reflection, resolved once; if they are missing the bond stays off and Project Fusion runs unchanged.
 */
public final class FuserBond {
    private static final Logger LOG = LoggerFactory.getLogger("ascensionlib");
    /** How long a pairing waits on the page for a decision, like Project Fusion's own Fuser session. */
    static final long OFFER_MILLIS = 10 * 60 * 1000L;
    /** Project Fusion's own reach to a Fuser, squared (8 blocks). */
    static final double REACH_SQUARED = 64.0;
    public static final String SPLIT_REFUSAL = "A Transcendent bond cannot be separated.";

    /** A pairing the Fuser screen sent, waiting for the player's decision on AscensionLib's page. Ability and nature: 0 body, 1 head. */
    public record Offer(UUID bodyId, UUID headId, int ability, int nature, BlockPos fuser, ResourceKey<Level> world, long expires) {
        /** The same pairing with head and body traded, and the ability and nature choices following their Pokemon. */
        public Offer swapped() { return new Offer(headId, bodyId, 1 - ability, 1 - nature, fuser, world, expires); }
        boolean pairs(UUID body, UUID head) { return bodyId.equals(body) && headId.equals(head); }
    }

    private static final Map<UUID, Offer> OFFERS = new ConcurrentHashMap<>();
    /** Set while AscensionLib itself starts the Fuser, so its own call is not intercepted again. */
    private static final ThreadLocal<Boolean> STARTING = ThreadLocal.withInitial(() -> false);

    private static volatile boolean enabled;
    private static Method begin, fuserAt, take;

    private FuserBond() {}

    /** Resolves Project Fusion's methods; the bond turns on only if every hook applied and every method is found. Returns whether it is on. */
    public static boolean enable(boolean hooksApplied) {
        try {
            begin = FusionService.class.getDeclaredMethod("begin", ServerPlayer.class, PlayerPartyStore.class, FuserBlockEntity.class,
                    Pokemon.class, Pokemon.class, int.class, int.class, FusionCost.Payment.class);
            fuserAt = FusionService.class.getDeclaredMethod("fuserAt", ServerLevel.class, BlockPos.class);
            take = FusionCost.class.getDeclaredMethod("take", ServerPlayer.class, Pokemon.class, Pokemon.class);
            for (var method : new Method[] {begin, fuserAt, take}) method.setAccessible(true);
            enabled = hooksApplied;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOG.error("Project Fusion's Fuser could not be reached ({}); Fuser Transcendence is off", exception.toString());
            enabled = false;
        }
        return enabled;
    }

    public static boolean enabled() { return enabled; }

    /** The pairing waiting on this player's page, if it is still fresh. */
    public static Optional<Offer> offer(ServerPlayer player) {
        var offer = OFFERS.get(player.getUUID());
        if (offer != null && offer.expires() < System.currentTimeMillis()) { OFFERS.remove(player.getUUID(), offer); return Optional.empty(); }
        return Optional.ofNullable(offer);
    }

    public static void forget(UUID player) { OFFERS.remove(player); }

    /** Trades head and body of the waiting pairing (the page's Swap). Returns the new pairing. */
    public static Optional<Offer> swap(ServerPlayer player) {
        return offer(player).map(old -> { var swapped = old.swapped(); OFFERS.put(player.getUUID(), swapped); return swapped; });
    }

    // --- called from the mixins ------------------------------------------------------------------------

    /**
     * Project Fusion is about to start the Fuser on {@code body} and {@code head}. Returns true to stop it: both hold a Unique, so the
     * player decides on AscensionLib's page first. Project Fusion has already taken its fee, so it is refunded here.
     */
    public static boolean intercept(ServerPlayer player, FuserBlockEntity fuser, Pokemon body, Pokemon head, int ability, int nature,
                                    FusionCost.Payment payment) {
        if (!enabled || STARTING.get()) return false;
        var service = AscensionApi.service().orElse(null);
        if (service == null) return false;
        boolean bothUnique = service.canonical(body).map(p -> p.unique() != null).orElse(false)
                && service.canonical(head).map(p -> p.unique() != null).orElse(false);
        if (!bothUnique) return false;
        payment.refund();
        OFFERS.put(player.getUUID(), new Offer(body.getUuid(), head.getUuid(), ability, nature, fuser.getBlockPos().immutable(),
                player.serverLevel().dimension(), System.currentTimeMillis() + OFFER_MILLIS));
        com.ascensionlib.fusion.FusionWeb.openAtFuser(player, body.getUuid().toString(), head.getUuid().toString());
        return true;
    }

    /**
     * The tip shown on the Fuser's own screen, in its status line, when a player opens an idle Fuser and has not yet dismissed the
     * Transcendence page's intro. It names the one rule they need before they choose: only two Unique holders can be fused. Project Fusion
     * shows a server message there until the player presses Fuse, so it stays in view while they pick.
     */
    public static final String FUSER_TIP = "Fuse two Pokémon that each hold a Unique to make a Transcendent.";

    /** The tip for this player at this Fuser, or empty when it should not show. */
    public static Optional<String> tip(ServerPlayer player, BlockPos core) {
        if (!enabled) return Optional.empty();
        if (!(player.serverLevel().getBlockEntity(core) instanceof FuserBlockEntity fuser) || !fuser.idle()) return Optional.empty();
        if (!com.ascensionlib.fusion.FusionWeb.introSeen(player)) return Optional.of(FUSER_TIP);
        // After the intro: warn before the Separate button is tried on a bond (Project Fusion only refuses after the click).
        return carriesBond(player) ? Optional.of(SPLIT_NOTE) : Optional.empty();
    }

    /**
     * A finished fusion with this id was just collected from a Fuser into the player's party or PC. If it holds a Fuser bond, the
     * Transcendence reveal opens: the payoff for the most expensive thing a player can make.
     */
    public static void collected(ServerPlayer player, UUID fusionId) {
        if (!enabled || fusionId == null) return;
        var service = AscensionApi.service().orElse(null);
        if (service == null || service.bondOf(fusionId).filter(b -> b.hostId().equals(fusionId)).isEmpty()) return;
        var storage = Cobblemon.INSTANCE.getStorage();
        Pokemon found = storage.getParty(player).get(fusionId);
        if (found == null) found = storage.getPC(player).get(fusionId);
        if (found != null) com.ascensionlib.fusion.TranscendReveal.openFor(player, service, found);
    }

    /** Project Fusion's split hint does not know a bond cannot be split: say so when the party carries one. */
    public static final String SPLIT_NOTE = "A Transcendent bond in your party can never be separated.";

    static boolean carriesBond(ServerPlayer player) {
        var service = AscensionApi.service().orElse(null);
        if (service == null) return false;
        for (var member : Cobblemon.INSTANCE.getStorage().getParty(player))
            if (com.ascensionlib.compat.FusionNbt.isFusion(member) && service.bondOf(member.getUuid()).filter(b -> b.hostId().equals(member.getUuid())).isPresent())
                return true;
        return false;
    }

    /** Refuses to split a fusion that holds a Fuser bond. */
    public static void refuseSplit(Pokemon fusion) {
        if (!enabled) return;
        var service = AscensionApi.service().orElse(null);
        if (service != null && service.bondOf(fusion.getUuid()).filter(b -> b.hostId().equals(fusion.getUuid())).isPresent())
            throw new IllegalArgumentException(SPLIT_REFUSAL);
    }

    /**
     * Project Fusion is handing a Fuser's Pokemon back to their owner. With no finished fusion among them the Fuser never finished, so a
     * bond on the body is undone and refunded. A finished fusion handed back (the Fuser was packed up before collection) keeps its bond.
     */
    public static void refunded(CompoundTag body, CompoundTag fusion) {
        if (fusion != null || body == null) return;
        var service = AscensionApi.service().orElse(null);
        UUID bodyId = uuidOf(body);
        if (service == null || bodyId == null) return;
        try {
            service.unbond(bodyId, "the Fuser handed both Pokemon back");
        } catch (RuntimeException exception) {
            LOG.error("Could not undo the Fuser bond of {} now; the owner's next login does it", bodyId, exception);
        }
    }

    /** The Pokemon id in Cobblemon's saved form (the {@code UUID} key), or null. Package-visible for the tests. */
    static UUID uuidOf(CompoundTag pokemon) {
        if (pokemon.hasUUID("UUID")) return pokemon.getUUID("UUID");
        try { return pokemon.contains("UUID") ? UUID.fromString(pokemon.getString("UUID")) : null; } catch (IllegalArgumentException e) { return null; }
    }

    // --- called from the page --------------------------------------------------------------------------

    /**
     * Carries out the player's decision on the waiting pairing: with {@code transcend} the bond (AscensionLib's rules and price, then the
     * Fuser), without it a plain Project Fusion fusion (its own fee, then the Fuser). Returns the Transcendent's name, or empty for a plain
     * fusion. Throws with a player-facing message when it cannot be done; nothing is spent then.
     */
    public static String confirm(ServerPlayer player, UUID bodyId, UUID headId, boolean transcend, long hostRevision, long donorRevision, long walletRevision) {
        if (!enabled) throw new IllegalStateException("Fuser Transcendence is off on this server.");
        var offer = offer(player).filter(o -> o.pairs(bodyId, headId))
                .orElseThrow(() -> new IllegalStateException("Open the Fuser and choose the pair again."));
        var party = Cobblemon.INSTANCE.getStorage().getParty(player);
        Pokemon body = party.get(bodyId), head = party.get(headId);
        if (body == null || head == null) throw new IllegalStateException("Both Pokémon must be in your party. Open the Fuser again.");
        FuserBlockEntity fuser = fuser(player, offer);
        if (transcend) {
            var service = AscensionApi.service().orElseThrow(() -> new IllegalStateException("Progression is unavailable."));
            var made = service.bond(player, body, head, hostRevision, donorRevision, walletRevision);
            try {
                start(player, party, fuser, body, head, offer, () -> {});
            } catch (RuntimeException exception) {
                service.unbond(bodyId, "the Fuser could not start");
                throw exception;
            }
            OFFERS.remove(player.getUUID(), offer);
            LOG.info("Fuser bond: {} bonded {} into {} -> {}", player.getGameProfile().getName(), headId, bodyId, made.id());
            return made.name();
        }
        FusionCost.Payment payment = (FusionCost.Payment) call(take, player, body, head);
        try {
            start(player, party, fuser, body, head, offer, payment);
        } catch (RuntimeException exception) {
            payment.refund();
            throw exception;
        }
        OFFERS.remove(player.getUUID(), offer);
        return "";
    }

    private static FuserBlockEntity fuser(ServerPlayer player, Offer offer) {
        if (!player.serverLevel().dimension().equals(offer.world()) || player.distanceToSqr(offer.fuser().getCenter()) > REACH_SQUARED)
            throw new IllegalStateException("Stand next to the Fuser and open it again.");
        return (FuserBlockEntity) call(fuserAt, player.serverLevel(), offer.fuser());
    }

    private static void start(ServerPlayer player, PlayerPartyStore party, FuserBlockEntity fuser, Pokemon body, Pokemon head, Offer offer,
                              FusionCost.Payment payment) {
        STARTING.set(true);
        try {
            call(begin, player, party, fuser, body, head, offer.ability(), offer.nature(), payment);
        } finally {
            STARTING.set(false);
        }
    }

    /** Calls a Project Fusion method; its own refusals (IllegalArgumentException with a message for the player) come through as they are. */
    private static Object call(Method method, Object... args) {
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException("The Fuser failed.", wrapped.getCause());
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("The Fuser failed.", exception);
        }
    }

    /** For a server stopping: forget every waiting pairing (nothing was spent for any of them). */
    public static void clear(MinecraftServer server) { OFFERS.clear(); }
}
