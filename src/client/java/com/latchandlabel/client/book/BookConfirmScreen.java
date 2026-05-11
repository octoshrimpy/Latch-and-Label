package com.latchandlabel.client.book;

import com.latchandlabel.client.LatchLabelClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import com.latchandlabel.client.ui.GuiUtils;

public final class BookConfirmScreen extends Screen {
    private static final int PANEL_WIDTH = 280;
    private static final int PANEL_HEIGHT = 120;

    private final Mode mode;
    private int panelLeft;
    private int panelTop;

    public BookConfirmScreen(Mode mode) {
        super(Component.translatable(mode == Mode.EXPORT
                ? "screen.latchlabel.book.export_title"
                : "screen.latchlabel.book.import_title"));
        this.mode = mode;
    }

    @Override
    protected void init() {
        panelLeft = (width - PANEL_WIDTH) / 2;
        panelTop = (height - PANEL_HEIGHT) / 2;

        int buttonWidth = 100;
        int buttonY = panelTop + PANEL_HEIGHT - 30;
        int gap = 12;

        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .pos(panelLeft + (PANEL_WIDTH / 2) - buttonWidth - (gap / 2), buttonY).size(buttonWidth, 20)
                .build());

        addRenderableWidget(Button.builder(Component.translatable(mode == Mode.EXPORT
                        ? "screen.latchlabel.book.confirm_export"
                        : "screen.latchlabel.book.confirm_import"), button -> onConfirm())
                .pos(panelLeft + (PANEL_WIDTH / 2) + (gap / 2), buttonY).size(buttonWidth, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xA0101010);
        context.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + PANEL_HEIGHT, 0xE0151515);
        GuiUtils.drawBorder(context, panelLeft, panelTop, PANEL_WIDTH, PANEL_HEIGHT, 0xFF3A3A3A);

        context.centeredText(font, title, panelLeft + (PANEL_WIDTH / 2), panelTop + 10, 0xFFFFFFFF);

        String infoLine;
        if (mode == Mode.EXPORT) {
            int tags = BookExportImportService.countCurrentTags();
            int cats = BookExportImportService.countCurrentCategories();
            infoLine = Component.translatable("screen.latchlabel.book.export_info",
                    String.valueOf(tags), String.valueOf(cats)).getString();
        } else {
            infoLine = Component.translatable("screen.latchlabel.book.import_info").getString();
        }
        context.centeredText(font, Component.literal(infoLine),
                panelLeft + (PANEL_WIDTH / 2), panelTop + 40, 0xFFB0B0B0);

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    private void onConfirm() {
        Minecraft mc = Minecraft.getInstance();
        Component resultMessage;

        if (mode == Mode.EXPORT) {
            BookExportImportService.ExportResult result = BookExportImportService.exportToHeldBook(
                    mc,
                    LatchLabelClientState.tagStore(),
                    LatchLabelClientState.categoryStore(),
                    LatchLabelClientState.itemCategoryMappingService()
            );
            resultMessage = result.message();
        } else {
            BookExportImportService.ImportResult result = BookExportImportService.importFromHeldBook(
                    mc,
                    LatchLabelClientState.tagStore(),
                    LatchLabelClientState.categoryStore(),
                    LatchLabelClientState.itemCategoryMappingService()
            );
            resultMessage = result.message();
        }

        onClose();
        if (mc.player != null) {
            mc.player.sendSystemMessage(resultMessage);
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.gui.setScreen(null);
        }
    }

    public enum Mode {
        EXPORT,
        IMPORT
    }
}
