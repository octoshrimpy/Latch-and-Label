package com.latchandlabel.client.book;

import com.latchandlabel.client.LatchLabelClientState;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;

public final class BookCommand {
    private BookCommand() {
    }

    public static void registerSubcommands(LiteralArgumentBuilder<FabricClientCommandSource> parent) {
        parent
                .then(ClientCommandManager.literal("export")
                        .executes(BookCommand::executeExport)
                )
                .then(ClientCommandManager.literal("import")
                        .executes(BookCommand::executeImport)
                );
    }

    private static int executeExport(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = context.getSource().getClient();
        BookExportImportService.ExportResult result = BookExportImportService.exportToHeldBook(
                client,
                LatchLabelClientState.tagStore(),
                LatchLabelClientState.categoryStore(),
                LatchLabelClientState.itemCategoryMappingService()
        );
        if (result.success()) {
            context.getSource().sendFeedback(result.message());
        } else {
            context.getSource().sendError(result.message());
        }
        return result.success() ? 1 : 0;
    }

    private static int executeImport(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = context.getSource().getClient();
        BookExportImportService.ImportResult result = BookExportImportService.importFromHeldBook(
                client,
                LatchLabelClientState.tagStore(),
                LatchLabelClientState.categoryStore(),
                LatchLabelClientState.itemCategoryMappingService()
        );
        if (result.success()) {
            context.getSource().sendFeedback(result.message());
        } else {
            context.getSource().sendError(result.message());
        }
        return result.success() ? 1 : 0;
    }
}
