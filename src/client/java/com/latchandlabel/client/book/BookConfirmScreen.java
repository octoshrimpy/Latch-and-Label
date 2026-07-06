package com.latchandlabel.client.book;

import com.latchandlabel.client.McCompat;
import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.find.FindSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import com.latchandlabel.client.ui.GuiUtils;

public final class BookConfirmScreen extends Screen {
    private static final int PANEL_WIDTH = 280;
    private static final int PANEL_HEIGHT = 140;

    private final Mode mode;
    private int panelLeft;
    private int panelTop;

    public BookConfirmScreen(Mode mode) {
        super(Component.translatable(mode == Mode.IMPORT
                ? "screen.latchlabel.book.import_title"
                : "screen.latchlabel.book.export_title"));
        this.mode = mode;
    }

    @Override
    protected void init() {
        panelLeft = (width - PANEL_WIDTH) / 2;
        panelTop = (height - PANEL_HEIGHT) / 2;

        int buttonY = panelTop + PANEL_HEIGHT - 30;
        int gap = 10;

        if (mode == Mode.EXPORT_PICKER) {
            int buttonWidth = 80;
            int totalWidth = (buttonWidth * 3) + (gap * 2);
            int buttonX = panelLeft + (PANEL_WIDTH - totalWidth) / 2;

            addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                    .pos(buttonX, buttonY).size(buttonWidth, 20)
                    .build());

            addRenderableWidget(Button.builder(Component.translatable("screen.latchlabel.book.confirm_export_all"),
                            button -> runAction(Mode.EXPORT_ALL))
                    .pos(buttonX + buttonWidth + gap, buttonY).size(buttonWidth, 20)
                    .build());

            addRenderableWidget(Button.builder(Component.translatable("screen.latchlabel.book.confirm_export_nearby"),
                            button -> runAction(Mode.EXPORT_NEARBY))
                    .pos(buttonX + ((buttonWidth + gap) * 2), buttonY).size(buttonWidth, 20)
                    .build());

            // A writable book already holding tags (our export format is a writable book) can also be imported.
            if (heldBookHasTags()) {
                addRenderableWidget(Button.builder(Component.translatable("screen.latchlabel.book.confirm_import"),
                                button -> runAction(Mode.IMPORT))
                        .pos(panelLeft + (PANEL_WIDTH / 2) - 60, buttonY - 26).size(120, 20)
                        .build());
            }
            return;
        }

        int buttonWidth = 100;
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .pos(panelLeft + (PANEL_WIDTH / 2) - buttonWidth - (gap / 2), buttonY).size(buttonWidth, 20)
                .build());

        addRenderableWidget(Button.builder(confirmLabel(), button -> onConfirm())
                .pos(panelLeft + (PANEL_WIDTH / 2) + (gap / 2), buttonY).size(buttonWidth, 20)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xA0101010);
        context.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + PANEL_HEIGHT, 0xE0151515);
        GuiUtils.drawBorder(context, panelLeft, panelTop, PANEL_WIDTH, PANEL_HEIGHT, 0xFF3A3A3A);

        context.centeredText(font, title, panelLeft + (PANEL_WIDTH / 2), panelTop + 10, 0xFFFFFFFF);

        if (mode == Mode.IMPORT) {
            context.centeredText(font,
                    Component.translatable("screen.latchlabel.book.import_info"),
                    panelLeft + (PANEL_WIDTH / 2), panelTop + 48, 0xFFB0B0B0);
        } else {
            int tags = BookExportImportService.countCurrentTags();
            int cats = BookExportImportService.countCurrentCategories();
            context.centeredText(font,
                    Component.translatable("screen.latchlabel.book.export_info",
                            String.valueOf(tags), String.valueOf(cats)),
                    panelLeft + (PANEL_WIDTH / 2), panelTop + 40, 0xFFB0B0B0);

            if (mode != Mode.EXPORT_ALL) {
                Minecraft mc = Minecraft.getInstance();
                int nearbyTags = BookExportImportService.countCurrentNearbyTags(mc);
                int radius = FindSettings.defaultFindRadius();
                context.centeredText(font,
                        Component.translatable("screen.latchlabel.book.summary_export_nearby",
                                String.valueOf(nearbyTags), String.valueOf(cats), String.valueOf(radius)),
                        panelLeft + (PANEL_WIDTH / 2), panelTop + 58, 0xFFB0B0B0);
            }
            if (mode == Mode.EXPORT_PICKER && heldBookHasTags()) {
                context.centeredText(font,
                        Component.translatable("screen.latchlabel.book.import_available"),
                        panelLeft + (PANEL_WIDTH / 2), panelTop + 74, 0xFF7FD08A);
            }
        }

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    private void onConfirm() {
        if (mode == Mode.EXPORT_PICKER) {
            return;
        }
        runAction(mode);
    }

    private void runAction(Mode actionMode) {
        Minecraft mc = Minecraft.getInstance();
        Component resultMessage = Component.empty();

        switch (actionMode) {
            case EXPORT_ALL -> {
                BookExportImportService.ExportResult result = BookExportImportService.exportToHeldBook(
                        mc,
                        LatchLabelClientState.tagStore(),
                        LatchLabelClientState.categoryStore(),
                        LatchLabelClientState.itemCategoryMappingService()
                );
                resultMessage = result.message();
            }
            case EXPORT_NEARBY -> {
                BookExportImportService.ExportResult result = BookExportImportService.exportNearbyToHeldBook(
                        mc,
                        LatchLabelClientState.tagStore(),
                        LatchLabelClientState.categoryStore(),
                        LatchLabelClientState.itemCategoryMappingService()
                );
                resultMessage = result.message();
            }
            case IMPORT -> {
                BookExportImportService.ImportResult result = BookExportImportService.importFromHeldBook(
                        mc,
                        LatchLabelClientState.tagStore(),
                        LatchLabelClientState.categoryStore(),
                        LatchLabelClientState.itemCategoryMappingService()
                );
                resultMessage = result.message();
            }
            case EXPORT_PICKER -> {
                return;
            }
        }

        onClose();
        if (mc.player != null) {
            mc.player.sendSystemMessage(resultMessage);
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            McCompat.setScreen(minecraft, null);
        }
    }

    private static boolean heldBookHasTags() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && BookExportImportService.isLatchLabelBook(mc.player.getMainHandItem());
    }

    private Component confirmLabel() {
        return switch (mode) {
            case EXPORT_ALL -> Component.translatable("screen.latchlabel.book.confirm_export_all");
            case EXPORT_NEARBY -> Component.translatable("screen.latchlabel.book.confirm_export_nearby");
            case IMPORT -> Component.translatable("screen.latchlabel.book.confirm_import");
            case EXPORT_PICKER -> Component.empty();
        };
    }

    public enum Mode {
        EXPORT_PICKER,
        EXPORT_ALL,
        EXPORT_NEARBY,
        IMPORT
    }
}
