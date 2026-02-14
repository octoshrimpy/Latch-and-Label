package com.latchandlabel.client.find;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.latchandlabel.client.LatchLabel;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class FindCommand {
    private static final String ARG_ITEM_ID = "itemid";
    private static final String ARG_RADIUS = "radius";

    private static final VariantMatcher VARIANT_MATCHER = new VariantMatcher();
    private static final FindScanService FIND_SCAN_SERVICE = new FindScanService();
    private static final boolean DEBUG_TIMINGS = Boolean.getBoolean("latchlabel.debug.timings");

    private FindCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                ClientCommandManager.literal("find")
                        .executes(FindCommand::executeNoArgs)
                        .then(ClientCommandManager.argument(ARG_ITEM_ID, StringArgumentType.word())
                                .suggests(FindCommand::suggestItems)
                                .executes(FindCommand::executeWithItem)
                                .then(ClientCommandManager.argument(ARG_RADIUS, IntegerArgumentType.integer(1, 256))
                                        .executes(FindCommand::executeWithItemAndRadius)
                                )
                        )
        );
    }

    private static int executeNoArgs(CommandContext<FabricClientCommandSource> context) {
        MinecraftClient client = context.getSource().getClient();
        if (client.player == null) {
            return 0;
        }

        Item mainhandItem = client.player.getMainHandStack().getItem();
        if (mainhandItem == Items.AIR) {
            context.getSource().sendError(Text.translatable("latchlabel.find.error_mainhand_empty"));
            return 0;
        }

        return runFind(context.getSource(), mainhandItem, FindSettings.defaultFindRadius());
    }

    private static int executeWithItem(CommandContext<FabricClientCommandSource> context) {
        Optional<Item> item = parseItemId(StringArgumentType.getString(context, ARG_ITEM_ID));
        if (item.isEmpty()) {
            context.getSource().sendError(Text.translatable("latchlabel.find.error_invalid_item"));
            return 0;
        }

        return runFind(context.getSource(), item.get(), FindSettings.defaultFindRadius());
    }

    private static int executeWithItemAndRadius(CommandContext<FabricClientCommandSource> context) {
        Optional<Item> item = parseItemId(StringArgumentType.getString(context, ARG_ITEM_ID));
        if (item.isEmpty()) {
            context.getSource().sendError(Text.translatable("latchlabel.find.error_invalid_item"));
            return 0;
        }

        int radius = IntegerArgumentType.getInteger(context, ARG_RADIUS);
        return runFind(context.getSource(), item.get(), radius);
    }

    private static int runFind(FabricClientCommandSource source, Item targetItem, int radius) {
        long startedAtNs = System.nanoTime();
        MinecraftClient client = source.getClient();
        if (client.world == null || client.player == null) {
            source.sendError(Text.translatable("latchlabel.find.error_world_unavailable"));
            return 0;
        }

        VariantMatcher.VariantMatchResult matchResult = VARIANT_MATCHER.resolve(targetItem);
        Identifier targetId = Registries.ITEM.getId(targetItem);
        List<FindScanService.FindMatch> results = FIND_SCAN_SERVICE.scan(client, targetItem, matchResult.matchSet(), radius);
        FindResultState.publish(results);
        if (!results.isEmpty()) {
            java.util.LinkedHashSet<com.latchandlabel.client.model.ChestKey> allMatchKeys = new java.util.LinkedHashSet<>();
            for (FindScanService.FindMatch result : results) {
                allMatchKeys.add(result.chestKey());
            }
            FindResultState.focusAll(allMatchKeys, FindResultState.getHighlightDurationMs());
        }
        if (client.inGameHud != null) {
            client.inGameHud.setOverlayMessage(
                    Text.translatable("latchlabel.find.feedback_results_count", results.size()),
                    false
            );
        }

        LatchLabel.LOGGER.info(
                "Find query: item={}, radius={}, variantsUsed={}, matchSetSize={}, results={}",
                targetId,
                radius,
                matchResult.usedVariants(),
                matchResult.matchSet().size(),
                results.size()
        );
        if (DEBUG_TIMINGS) {
            double elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000.0;
            LatchLabel.LOGGER.info("Find timing: {} ms", String.format("%.2f", elapsedMs));
        }

        return results.size();
    }

    private static Optional<Item> parseItemId(String rawItemId) {
        String normalized = rawItemId == null ? "" : rawItemId.trim().toLowerCase();
        Identifier itemId = Identifier.tryParse(normalized);
        if (itemId == null && !normalized.isEmpty() && normalized.indexOf(':') < 0) {
            itemId = Identifier.tryParse("minecraft:" + normalized);
        }
        if (itemId == null || !Registries.ITEM.containsId(itemId)) {
            return Optional.empty();
        }

        Item item = Registries.ITEM.get(itemId);
        if (item == Items.AIR) {
            return Optional.empty();
        }

        return Optional.of(item);
    }

    private static CompletableFuture<Suggestions> suggestItems(
            CommandContext<FabricClientCommandSource> context,
            SuggestionsBuilder builder
    ) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        boolean usingNamespace = remaining.indexOf(':') >= 0;

        for (Identifier itemId : Registries.ITEM.getIds()) {
            if (itemId == null) {
                continue;
            }

            String full = itemId.toString();
            if (full.startsWith(remaining)) {
                builder.suggest(full);
            }

            if (!usingNamespace && "minecraft".equals(itemId.getNamespace())) {
                String shortId = itemId.getPath();
                if (shortId.startsWith(remaining)) {
                    builder.suggest(shortId);
                }
            }
        }

        return builder.buildFuture();
    }
}
