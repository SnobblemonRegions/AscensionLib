package com.ascensionlib.fusion;

import com.ascensionlib.ProfileService;
import com.ascensionlib.compat.FusionNbt;
import com.ascensionlib.web.WebUi;
import com.cobbleascend.domain.v1.Transcendence;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Pokemon;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * The moment a Transcendent bond is collected from a Fuser ({@code transcend.html}): the body and the head meet, the Transcendent's name
 * appears, then its power and drawback. The player may share it in chat once (the opt-in announcement); nothing is broadcast otherwise.
 * The page only displays what the server resolved.
 */
public final class TranscendReveal {
    private TranscendReveal() {}

    public record View(String name, String bodyName, String bodySpecies, String headName, String headSpecies, String blurb, String core,
                       String clutch, String drawback, String harmony, int benefit, int drawbackPercent, boolean namedRecipe) {}

    private static final Map<UUID, View> PENDING = new ConcurrentHashMap<>();
    /** Bonds already shared, so a held button cannot flood the chat. */
    private static final Set<String> SHARED = ConcurrentHashMap.newKeySet();

    static void register() {
        WebUi.listen("ascension_transcend_ready", (player, o) -> {
            var view = PENDING.get(player.getUUID());
            if (view != null) WebUi.emit(player, "transcendView", view);
        });
        WebUi.listen("ascension_transcend_share", (player, o) -> {
            var view = PENDING.get(player.getUUID());
            var server = player.getServer();
            if (view == null || server == null || !SHARED.add(player.getUUID() + "|" + view.name() + "|" + view.bodyName())) return;
            server.getPlayerList().broadcastSystemMessage(Component.literal(shareLine(player.getGameProfile().getName(), view)), false);
        });
    }

    public static void forget(UUID player) { PENDING.remove(player); }

    /** The chat line a shared Transcendent posts. Package-visible for the tests. */
    static String shareLine(String player, View view) {
        return player + " bonded " + view.headName() + " into " + view.bodyName() + " and made a Transcendent: " + view.name() + ".";
    }

    /** Opens the reveal for this bonded fusion, if it is one and its Transcendent resolves; otherwise nothing is shown. */
    public static void openFor(ServerPlayer player, ProfileService service, Pokemon fusion) {
        view(service, fusion).ifPresent(view -> {
            PENDING.put(player.getUUID(), view);
            com.ascensionlib.web.WebSprites.feed(player, java.util.List.of(view.bodySpecies(), view.headSpecies()));
            WebUi.open(player, "transcend.html");
        });
    }

    /** An operator's replay of the reveal (/ascend admin transcend): false when this Pokemon is not a bonded Transcendent. */
    public static boolean preview(ServerPlayer player, ProfileService service, Pokemon fusion) {
        if (view(service, fusion).isEmpty()) return false;
        openFor(player, service, fusion);
        return true;
    }

    static Optional<View> view(ProfileService service, Pokemon fusion) {
        var row = service.store().fusion(fusion.getUuid());
        var head = FusionNbt.headOf(fusion);
        if (row.isEmpty() || head.isEmpty()) return Optional.empty();
        try {
            var t = Transcendence.shared().resolve(row.get().hostSpecies(), row.get().hostUnique(), row.get().donorSpecies(), row.get().donorUnique());
            String headName = head.get().nickname().isBlank() ? speciesName(head.get().speciesId()) : head.get().nickname();
            return Optional.of(new View(t.name(), fusion.getSpecies().getName(), fusion.getSpecies().getResourceIdentifier().toString(), headName,
                    head.get().speciesId(), t.blurb(), t.signature().core(), t.signature().clutch(), t.signature().drawback(),
                    t.harmony().name().toLowerCase(java.util.Locale.ROOT), t.benefitPercent(), t.drawbackPercent(), t.recipeId() != null));
        } catch (RuntimeException unresolved) {
            return Optional.empty();
        }
    }

    private static String speciesName(String speciesId) {
        try {
            var species = PokemonSpecies.INSTANCE.getByIdentifier(ResourceLocation.parse(speciesId));
            return species == null ? speciesId : species.getName();
        } catch (RuntimeException unknown) {
            return speciesId;
        }
    }
}
