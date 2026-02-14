package com.latchandlabel.client.config;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.latchandlabel.client.LatchLabelClientState;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import java.nio.file.Path;

public final class ConfigCommand {
    private ConfigCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                ClientCommandManager.literal("latchlabel")
                        .then(ClientCommandManager.literal("reload")
                                .executes(context -> {
                                    return reloadAll(context.getSource());
                                })
                        )
                        .then(ClientCommandManager.literal("config")
                                .then(ClientCommandManager.literal("reload")
                                        .executes(context -> reloadAll(context.getSource()))
                                )
                                .then(ClientCommandManager.literal("export")
                                        .executes(context -> exportProfile(context.getSource(), ""))
                                        .then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
                                                .executes(context -> exportProfile(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "path")
                                                ))
                                        )
                                )
                                .then(ClientCommandManager.literal("import")
                                        .then(ClientCommandManager.argument("path", StringArgumentType.greedyString())
                                                .executes(context -> importProfile(
                                                        context.getSource(),
                                                        StringArgumentType.getString(context, "path")
                                                ))
                                        )
                                )
                        )
        );
    }

    private static int reloadAll(FabricClientCommandSource source) {
        LatchLabelClientState.clientConfigManager().reload();
        LatchLabelClientState.dataManager().reloadFromDisk();
        LatchLabelClientState.itemCategoryMappingService().reload();
        source.sendFeedback(Text.translatable("latchlabel.config.reloaded"));
        return 1;
    }

    private static int exportProfile(FabricClientCommandSource source, String requestedName) {
        try {
            Path output = LatchLabelClientState.configProfileManager().exportProfile(requestedName);
            source.sendFeedback(Text.translatable("latchlabel.config.exported", output.toString()));
            return 1;
        } catch (IllegalStateException ex) {
            source.sendFeedback(Text.translatable("latchlabel.config.export_failed", ex.getMessage()));
            return 0;
        }
    }

    private static int importProfile(FabricClientCommandSource source, String requestedName) {
        try {
            Path input = LatchLabelClientState.configProfileManager().importProfile(requestedName);
            source.sendFeedback(Text.translatable("latchlabel.config.imported", input.toString()));
            return 1;
        } catch (IllegalStateException ex) {
            source.sendFeedback(Text.translatable("latchlabel.config.import_failed", ex.getMessage()));
            return 0;
        }
    }
}
