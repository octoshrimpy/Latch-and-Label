package com.latchandlabel.client.config;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.book.BookCommand;
import com.latchandlabel.client.data.TagScopeResolver;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.Optional;

/** Registers the {@code /latchlabel} client command tree (reload, config export/import, book). */
public final class ConfigCommand {
    private ConfigCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        var latchlabelLiteral = ClientCommandManager.literal("latchlabel")
                .then(ClientCommandManager.literal("reload")
                        .executes(context -> {
                            return reloadAll(context.getSource());
                        })
                )
                .then(ClientCommandManager.literal("world-profile")
                        .then(ClientCommandManager.literal("get")
                                .executes(context -> getWorldProfile(context.getSource()))
                        )
                        .then(ClientCommandManager.literal("set")
                                .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> setWorldProfile(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "name")
                                        ))
                                )
                        )
                        .then(ClientCommandManager.literal("clear")
                                .executes(context -> clearWorldProfile(context.getSource()))
                        )
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
                );

        BookCommand.registerSubcommands(latchlabelLiteral);
        dispatcher.register(latchlabelLiteral);
    }

    private static int reloadAll(FabricClientCommandSource source) {
        LatchLabelClientState.clientConfigManager().reload();
        LatchLabelClientState.dataManager().reloadFromDisk();
        LatchLabelClientState.itemCategoryMappingService().reload();
        source.sendFeedback(Component.translatable("latchlabel.config.reloaded"));
        return 1;
    }

    private static int exportProfile(FabricClientCommandSource source, String requestedName) {
        try {
            Path output = LatchLabelClientState.configProfileManager().exportProfile(requestedName);
            source.sendFeedback(Component.translatable("latchlabel.config.exported", output.toString()));
            return 1;
        } catch (IllegalStateException ex) {
            source.sendFeedback(Component.translatable("latchlabel.config.export_failed", ex.getMessage()));
            return 0;
        }
    }

    private static int importProfile(FabricClientCommandSource source, String requestedName) {
        try {
            Path input = LatchLabelClientState.configProfileManager().importProfile(requestedName);
            source.sendFeedback(Component.translatable("latchlabel.config.imported", input.toString()));
            return 1;
        } catch (IllegalStateException ex) {
            source.sendFeedback(Component.translatable("latchlabel.config.import_failed", ex.getMessage()));
            return 0;
        }
    }

    private static int getWorldProfile(FabricClientCommandSource source) {
        String serverScopeId = currentMultiplayerServerScopeId(source);
        if (serverScopeId == null) {
            source.sendFeedback(Component.translatable("latchlabel.world_profile.multiplayer_only"));
            return 0;
        }

        Optional<String> profile = MultiplayerWorldProfileSettings.profileForServerScope(serverScopeId);
        if (profile.isPresent()) {
            source.sendFeedback(Component.translatable("latchlabel.world_profile.current", profile.get(), serverScopeId));
        } else {
            source.sendFeedback(Component.translatable("latchlabel.world_profile.none", serverScopeId));
        }
        return 1;
    }

    private static int setWorldProfile(FabricClientCommandSource source, String requestedName) {
        String serverScopeId = currentMultiplayerServerScopeId(source);
        if (serverScopeId == null) {
            source.sendFeedback(Component.translatable("latchlabel.world_profile.multiplayer_only"));
            return 0;
        }

        String normalizedName = MultiplayerWorldProfileSettings.normalizeProfileName(requestedName);
        if (normalizedName == null) {
            source.sendFeedback(Component.translatable("latchlabel.world_profile.invalid"));
            return 0;
        }

        LatchLabelClientState.clientConfigManager().setMultiplayerWorldProfile(serverScopeId, normalizedName);
        refreshActiveScope();
        source.sendFeedback(Component.translatable("latchlabel.world_profile.set", normalizedName, serverScopeId));
        return 1;
    }

    private static int clearWorldProfile(FabricClientCommandSource source) {
        String serverScopeId = currentMultiplayerServerScopeId(source);
        if (serverScopeId == null) {
            source.sendFeedback(Component.translatable("latchlabel.world_profile.multiplayer_only"));
            return 0;
        }

        LatchLabelClientState.clientConfigManager().clearMultiplayerWorldProfile(serverScopeId);
        refreshActiveScope();
        source.sendFeedback(Component.translatable("latchlabel.world_profile.cleared", serverScopeId));
        return 1;
    }

    private static String currentMultiplayerServerScopeId(FabricClientCommandSource source) {
        return TagScopeResolver.resolveCurrentMultiplayerServerScopeId(source.getClient());
    }

    private static void refreshActiveScope() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null) {
            return;
        }
        TagScopeResolver.ResolvedScope resolvedScope = TagScopeResolver.resolveCurrentScope(client);
        LatchLabelClientState.dataManager().setActiveScopeId(
                resolvedScope.primaryScopeId(),
                resolvedScope.fallbackReadScopeIds()
        );
    }
}
