package com.ascensionlib.research;

import com.ascensionlib.AscensionEncounters;
import com.ascensionlib.AscensionRewards;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Test seam: {@code /ascendtestraidresearch <player> <species> <level>} plays the part of CobbleRaids for one won raid, without a battle, so a
 * headless test can check that a raid win pays Field Research once. It makes the same calls CobbleRaids' {@code AscensionLibArming} makes, in its
 * order: {@code declareEnemy("raid:" + boss UUID, players, 0, "boss", true, null, species, level)}, then {@code settleRaidAttunement} with
 * {@code VICTORY} (twice here, as a retried callback would), then {@code end}. Exists only when the JVM is started with
 * {@code -Dascensionlib.testResearch=true}; otherwise {@link #node()} is null and nothing is registered.
 */
public final class RaidResearchTestCommand {
    static final boolean ENABLED = Boolean.getBoolean("ascensionlib.testResearch");

    private RaidResearchTestCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        if (!ENABLED) return null;
        return Commands.literal("ascendtestraidresearch").requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("species", ResourceLocationArgument.id())
                                .then(Commands.argument("level", IntegerArgumentType.integer(1, 200))
                                        .executes(ctx -> run(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                                                bossId(ResourceLocationArgument.getId(ctx, "species")),
                                                IntegerArgumentType.getInteger(ctx, "level"))))));
    }

    /** The species as CobbleRaids passes it: its full resource identifier ({@code cobblemon:garchomp}); a bare name means Cobblemon's. */
    private static String bossId(net.minecraft.resources.ResourceLocation id) {
        return id.getNamespace().equals("minecraft") ? "cobblemon:" + id.getPath() : id.toString();
    }

    private static int run(CommandSourceStack source, ServerPlayer player, String species, int level) {
        String encounter = "raid:" + UUID.randomUUID();
        var players = List.of(player.getUUID());
        String declared = AscensionEncounters.declareEnemy(encounter, players, 0, "boss", true, null, species, level);
        int first = AscensionRewards.settleRaidAttunement(encounter, "VICTORY", players);
        int second = AscensionRewards.settleRaidAttunement(encounter, "VICTORY", players);
        AscensionEncounters.end(encounter);
        source.sendSuccess(() -> Component.literal("Test raid " + encounter + ": declare " + declared + ", settled twice (attunement " + first + ", "
                + second + "), ended."), false);
        return 1;
    }
}
