package com.ascensionlib.research;

import com.ascensionlib.AscensionApi;
import com.cobbleascend.domain.v1.BenchStudies;
import com.cobbleascend.domain.v1.FieldResearch;
import com.cobbleascend.domain.v1.ResearchException;
import com.cobbleascend.domain.v1.ResearchFamilies;
import com.cobbleascend.domain.v1.ResearchLedger;
import com.cobblemon.mod.common.Cobblemon;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /ascend research} (a player's research in chat until the research page exists) and {@code /ascend admin research} (grants and Bench
 * studies for testing until the Research Bench block exists). Every refusal is a message, never a stack trace.
 */
public final class ResearchCommands {
    private ResearchCommands() {}

    private static final SuggestionProvider<CommandSourceStack> SPECIES = (ctx, builder) ->
            SharedSuggestionProvider.suggest(ResearchCatalog.get().familyOfSpecies().keySet(), builder);

    /**
     * The species argument as text: a resource location, so a namespaced id ({@code mymod:fakemon}) parses; a bare name arrives as
     * {@code minecraft:<name>}, which means "no namespace given".
     */
    private static String species(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        var location = ResourceLocationArgument.getId(ctx, "species");
        return location.getNamespace().equals("minecraft") ? location.getPath() : location.toString();
    }

    /** A species argument as the catalog knows it, or its plain catalog form (the store then refuses it as unknown). */
    private static String known(String raw) {
        return ResearchCatalog.resolve(raw).orElse(ResearchCatalog.speciesId(raw));
    }

    /** {@code research}, {@code research family <species>}, {@code research drive <species>}. */
    public static LiteralArgumentBuilder<CommandSourceStack> player() {
        return Commands.literal("research")
                .executes(ctx -> summary(ctx.getSource()))
                .then(Commands.literal("family").then(Commands.argument("species", ResourceLocationArgument.id()).suggests(SPECIES)
                        .executes(ctx -> family(ctx.getSource(), species(ctx)))))
                .then(Commands.literal("drive").then(Commands.argument("species", ResourceLocationArgument.id()).suggests(SPECIES)
                        .executes(ctx -> drive(ctx.getSource(), species(ctx)))));
    }

    /** {@code admin research grant|item|study|collect|recall}. */
    public static LiteralArgumentBuilder<CommandSourceStack> admin() {
        return Commands.literal("research")
                .then(Commands.literal("grant").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("species", ResourceLocationArgument.id()).suggests(SPECIES)
                                .then(Commands.argument("amount", IntegerArgumentType.integer(1, FieldResearch.MAX_GRANT))
                                        .executes(ctx -> grant(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"),
                                                species(ctx), IntegerArgumentType.getInteger(ctx, "amount")))))))
                .then(Commands.literal("item").then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.literal("sample").then(Commands.argument("species", ResourceLocationArgument.id()).suggests(SPECIES)
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> item(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), FieldResearch.Item.BOSS_SAMPLE,
                                                species(ctx), IntegerArgumentType.getInteger(ctx, "count"))))))
                        .then(Commands.literal("drive").then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> item(ctx.getSource(), EntityArgument.getPlayer(ctx, "player"), FieldResearch.Item.DATA_DRIVE, "",
                                        IntegerArgumentType.getInteger(ctx, "count")))))))
                .then(Commands.literal("study").then(Commands.argument("kind", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(new String[] {"quick", "standard", "analysis"}, builder))
                        .then(Commands.argument("slot", IntegerArgumentType.integer(1, 6))
                                .executes(ctx -> study(ctx.getSource(), StringArgumentType.getString(ctx, "kind"), IntegerArgumentType.getInteger(ctx, "slot"))))))
                .then(Commands.literal("collect").executes(ctx -> collect(ctx.getSource())))
                .then(Commands.literal("recall").executes(ctx -> recall(ctx.getSource())));
    }

    // ---- player ----------------------------------------------------------------------------------------------------------------------------

    private static int summary(CommandSourceStack source) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        var store = AscensionApi.store();
        if (store.isEmpty()) return fail(source, "Ascension progression is not running.");
        var catalog = ResearchCatalog.get();
        var ledger = store.get().researchLedger(player.getUUID());
        var families = new ArrayList<String>();
        for (var species : ledger.speciesPoints().keySet()) catalog.familyOf(species).ifPresent(f -> { if (!families.contains(f.id())) families.add(f.id()); });
        families.sort(Comparator.<String>comparingInt(f -> -ledger.familyPoints(catalog, f)).thenComparing(f -> f));
        var text = Component.literal("Field Research").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
        if (families.isEmpty()) text.append(Component.literal("\nNothing researched yet. Win raids, search Laboratories, study Pokemon at a Research Bench.")
                .withStyle(ChatFormatting.GRAY));
        int shown = 0;
        for (var family : families) {
            if (shown++ == 12) { text.append(Component.literal("\n... and " + (families.size() - 12) + " more").withStyle(ChatFormatting.GRAY)); break; }
            text.append(Component.literal("\n" + FieldResearchRuntime.display(family) + " line: " + ledger.familyPoints(catalog, family) + "/"
                    + ResearchFamilies.FAMILY_MAX + " · level " + ledger.familyLevel(catalog, family)).withStyle(ChatFormatting.WHITE));
        }
        var drives = store.get().researchItems(player.getUUID(), FieldResearch.Item.DATA_DRIVE).getOrDefault("", 0);
        var samples = store.get().researchItems(player.getUUID(), FieldResearch.Item.BOSS_SAMPLE);
        text.append(Component.literal("\nData Drives: " + drives).withStyle(ChatFormatting.GRAY));
        if (!samples.isEmpty()) {
            var parts = new ArrayList<String>();
            samples.forEach((species, n) -> parts.add(FieldResearchRuntime.display(species) + " x" + n + " (" + ledger.bossPercentOf(species) + "%)"));
            text.append(Component.literal("\nBoss Samples: " + String.join(", ", parts)).withStyle(ChatFormatting.GRAY));
        }
        store.get().researchStudy(player.getUUID()).ifPresent(study -> text.append(Component.literal("\nOn the Bench: " + FieldResearchRuntime.display(study.species())
                + " (" + study.kind().name().toLowerCase(Locale.ROOT) + "), " + (study.finished(System.currentTimeMillis()) ? "ready to collect"
                : minutes(study.remainingMillis(System.currentTimeMillis())) + " left")).withStyle(ChatFormatting.GRAY)));
        source.sendSuccess(() -> text, false);
        return 1;
    }

    private static int family(CommandSourceStack source, String species) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        var store = AscensionApi.store();
        if (store.isEmpty()) return fail(source, "Ascension progression is not running.");
        var catalog = ResearchCatalog.get();
        var family = catalog.familyOf(known(species));
        if (family.isEmpty()) return fail(source, "Unknown species: " + species);
        ResearchLedger ledger = store.get().researchLedger(player.getUUID());
        var text = Component.literal(FieldResearchRuntime.display(family.get().id()) + " line: " + ledger.familyPoints(catalog, family.get().id()) + "/"
                + ResearchFamilies.FAMILY_MAX + ", level " + ledger.familyLevel(catalog, family.get().id())).withStyle(ChatFormatting.AQUA);
        for (var stage : family.get().stages()) {
            for (var s : stage) {
                text.append(Component.literal("\n  " + FieldResearchRuntime.display(s) + ": " + ledger.pointsOf(catalog, s) + "/" + family.get().shareOf(s)
                        + (ledger.bossPercentOf(s) > 0 ? " · boss research " + ledger.bossPercentOf(s) + "%" : "")).withStyle(ChatFormatting.WHITE));
            }
        }
        source.sendSuccess(() -> text, false);
        return 1;
    }

    private static int drive(CommandSourceStack source, String species) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        var store = AscensionApi.store();
        if (store.isEmpty()) return fail(source, "Ascension progression is not running.");
        try {
            var catalog = ResearchCatalog.get();
            String id = known(species);
            store.get().useDataDrive(UUID.randomUUID(), player.getUUID(), catalog, id);
            var ledger = store.get().researchLedger(player.getUUID());
            source.sendSuccess(() -> Component.literal("Data Drive read: " + FieldResearchRuntime.display(id) + " now " + ledger.pointsOf(catalog, id) + "/"
                    + catalog.shareOf(id) + " (family level " + ledger.levelForSpecies(catalog, id) + ").").withStyle(ChatFormatting.AQUA), false);
            return 1;
        } catch (ResearchException refused) {
            return fail(source, switch (refused.reason()) {
                case NO_ITEM -> "You have no Data Drive.";
                case SHARE_FULL -> "Your research on that species is already full: the drive is kept.";
                case UNKNOWN_SPECIES -> "Unknown species: " + species;
                default -> refused.getMessage();
            });
        }
    }

    // ---- admin ------------------------------------------------------------------------------------------------------------------------------

    private static int grant(CommandSourceStack source, ServerPlayer target, String species, int amount) {
        var store = AscensionApi.store();
        if (store.isEmpty()) return fail(source, "Ascension progression is not running.");
        try {
            store.get().grantResearch(UUID.randomUUID(), target.getUUID(), ResearchCatalog.get(), known(species), amount,
                    "operator grant by " + source.getTextName());
            source.sendSuccess(() -> Component.literal("Granted " + amount + " research on " + species + " to " + target.getGameProfile().getName()
                    + " (capped at the species' share)."), true);
            return 1;
        } catch (ResearchException refused) {
            return fail(source, refused.getMessage());
        }
    }

    private static int item(CommandSourceStack source, ServerPlayer target, FieldResearch.Item item, String species, int count) {
        var store = AscensionApi.store();
        if (store.isEmpty()) return fail(source, "Ascension progression is not running.");
        try {
            store.get().grantResearchItem(UUID.randomUUID(), target.getUUID(), ResearchCatalog.get(), item, item.perSpecies ? known(species) : "", count);
            source.sendSuccess(() -> Component.literal("Gave " + count + " " + item.id + (item.perSpecies ? " (" + species + ")" : "") + " to "
                    + target.getGameProfile().getName() + "."), true);
            return 1;
        } catch (ResearchException refused) {
            return fail(source, refused.getMessage());
        }
    }

    private static int study(CommandSourceStack source, String kindText, int slot) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        BenchStudies.Kind kind;
        try {
            kind = BenchStudies.Kind.valueOf(kindText.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException bad) {
            return fail(source, "Kind is quick, standard or analysis.");
        }
        var pokemon = Cobblemon.INSTANCE.getStorage().getParty(player).get(slot - 1);
        if (pokemon == null) return fail(source, "That party slot is empty.");
        try {
            var study = FieldResearchRuntime.start(player, pokemon, kind);
            source.sendSuccess(() -> Component.literal("Study started: " + FieldResearchRuntime.display(study.species()) + " (" + kindText + "), "
                    + minutes(study.remainingMillis(System.currentTimeMillis())) + ".").withStyle(ChatFormatting.AQUA), false);
            return 1;
        } catch (ResearchException refused) {
            return fail(source, "Refused: " + refused.detail());
        } catch (IllegalStateException refused) {
            return fail(source, refused.getMessage());
        }
    }

    private static int collect(CommandSourceStack source) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        try {
            var collected = FieldResearchRuntime.collect(player);
            source.sendSuccess(() -> Component.literal(collected.message()).withStyle(collected.paid() ? ChatFormatting.AQUA : ChatFormatting.YELLOW), false);
            return 1;
        } catch (ResearchException refused) {
            return fail(source, refused.reason() == ResearchException.Reason.NOT_FINISHED
                    ? "Not finished: " + minutes(Long.parseLong(refused.detail())) + " left." : "Nothing on the Bench.");
        } catch (IllegalStateException refused) {
            return fail(source, refused.getMessage());
        }
    }

    private static int recall(CommandSourceStack source) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        try {
            var study = FieldResearchRuntime.recall(player);
            source.sendSuccess(() -> Component.literal("Recalled " + FieldResearchRuntime.display(study.species()) + ": the study ended with nothing learned.")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 1;
        } catch (ResearchException refused) {
            return fail(source, "Nothing on the Bench.");
        } catch (IllegalStateException refused) {
            return fail(source, refused.getMessage());
        }
    }

    private static String minutes(long millis) {
        long minutes = (millis + 59_999) / 60_000;
        return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }
}
