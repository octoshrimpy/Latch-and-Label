package com.latchandlabel.client.config.ui;

import com.latchandlabel.client.McCompat;
import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.book.BookExportImportService;
import com.latchandlabel.client.config.ContainerDetectionSettings;
import com.latchandlabel.client.config.InspectSettings;
import com.latchandlabel.client.config.MoveSourceMode;
import com.latchandlabel.client.config.TransferSettings;
import com.latchandlabel.client.dump.DumpSettings;
import com.latchandlabel.client.find.FindSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import com.latchandlabel.client.ui.GuiUtils;

public final class LatchLabelConfigScreen extends Screen {
    private static final int MIN_INSPECT_RANGE = 1;
    private static final int MAX_INSPECT_RANGE = 32;
    private static final int MIN_FIND_RADIUS = 1;
    private static final int MAX_FIND_RADIUS = 256;
    private static final int MIN_DUMP_RANGE = 1;
    private static final int MAX_DUMP_RANGE = 128;
    private static final int MIN_FIND_TIMEOUT = 0;
    private static final int MAX_FIND_TIMEOUT = 600;
    private static final int MIN_DETECTION_THRESHOLD_PERCENT = 1;
    private static final int MAX_DETECTION_THRESHOLD_PERCENT = 100;

    private final Screen parent;

    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int labelX;
    private int controlX;
    private int rowY;
    private int rowHeight;
    private int controlWidth;

    private Button bordersAlwaysButton;
    private Button labelsOnLookButton;
    private Button variantMatchingButton;
    private Button slashFButton;
    private Button findKeybindButton;
    private Button moveSourceButton;
    private Button pullDestButton;

    public LatchLabelConfigScreen(Screen parent) {
        super(Component.translatable("screen.latchlabel.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.max(120, Math.min(520, width - 30));
        panelHeight = 428;
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;
        labelX = panelLeft + 16;
        controlWidth = 190;
        controlX = panelLeft + panelWidth - controlWidth - 16;
        rowY = panelTop + 30;
        rowHeight = 22;

        addReloadButtons();
        addInspectRangeControls();
        addBordersAlwaysControl();
        addLabelsOnLookControl();
        addFindRadiusControls();
        addFindTimeoutControls();
        addDumpRangeControls();
        addToggleControls();
        addDetectionThresholdControls();
        addBookButtons();

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .pos(panelLeft + panelWidth - 96, panelTop + panelHeight - 24).size(80, 20)
                .build());
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            McCompat.setScreen(minecraft, parent);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xA0101010);
        context.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xE0151515);
        GuiUtils.drawBorder(context, panelLeft, panelTop, panelWidth, panelHeight, 0xFF3A3A3A);

        context.centeredText(font, title, panelLeft + (panelWidth / 2), panelTop + 10, 0xFFFFFFFF);
        drawLabels(context);

        super.extractRenderState(context, mouseX, mouseY, delta);
    }

    private void drawLabels(GuiGraphicsExtractor context) {
        int y = panelTop + 36;
        context.text(font, Component.translatable("screen.latchlabel.config.reload_categories_label"), labelX, y, 0xFFE8E8E8, true);
        y += rowHeight;
        context.text(font, Component.translatable("screen.latchlabel.config.reload_tags_label"), labelX, y, 0xFFE8E8E8, true);
        y += rowHeight;
        context.text(font, Component.translatable("screen.latchlabel.config.inspect_range_label"), labelX, y, 0xFFE8E8E8, true);
        y += rowHeight;
        context.text(font, Component.translatable("screen.latchlabel.config.borders_always_label"), labelX, y, 0xFFE8E8E8, true);
        y += rowHeight;
        context.text(font, Component.translatable("screen.latchlabel.config.labels_on_look_label"), labelX, y, 0xFFE8E8E8, true);
        y += rowHeight;
        context.text(font, Component.translatable("screen.latchlabel.config.find_radius_label"), labelX, y, 0xFFE8E8E8, true);
        y += rowHeight;
        context.text(font, Component.translatable("screen.latchlabel.config.find_timeout_label"), labelX, y, 0xFFE8E8E8, true);
        y += rowHeight;
        context.text(font, Component.translatable("screen.latchlabel.config.dump_range_label"), labelX, y, 0xFFE8E8E8, true);
        y += rowHeight;
        context.text(font, Component.translatable("screen.latchlabel.config.variant_matching_label"), labelX, y, 0xFFE8E8E8, true);
        y += rowHeight;
        context.text(font, Component.translatable("screen.latchlabel.config.allow_f_label"), labelX, y, 0xFFE8E8E8, true);
        y += rowHeight;
        context.text(font, Component.translatable("screen.latchlabel.config.find_keybind_label"), labelX, y, 0xFFE8E8E8, true);
        y += rowHeight;
        context.text(font, Component.translatable("screen.latchlabel.config.move_source_label"), labelX, y, 0xFFE8E8E8, true);
        y += rowHeight;
        context.text(font, Component.translatable("screen.latchlabel.config.pull_dest_label"), labelX, y, 0xFFE8E8E8, true);
        y += rowHeight;
        context.text(font, Component.translatable("screen.latchlabel.config.detected_category_threshold_label"), labelX, y, 0xFFE8E8E8, true);
        y += rowHeight;
        context.text(font, Component.translatable("screen.latchlabel.config.book_export_label"), labelX, y, 0xFFE8E8E8, true);
        y += rowHeight;
        context.text(font, Component.translatable("screen.latchlabel.config.book_import_label"), labelX, y, 0xFFE8E8E8, true);
    }

    private void addReloadButtons() {
        addRenderableWidget(Button.builder(Component.translatable("screen.latchlabel.config.reload_categories"), button -> {
                    LatchLabelClientState.dataManager().reloadCategoriesFromDisk();
                })
                .pos(controlX, rowY).size(controlWidth, 20)
                .build());
        rowY += rowHeight;

        addRenderableWidget(Button.builder(Component.translatable("screen.latchlabel.config.reload_tags"), button -> {
                    LatchLabelClientState.dataManager().reloadTagsFromDisk();
                })
                .pos(controlX, rowY).size(controlWidth, 20)
                .build());
        rowY += rowHeight;
    }

    private void addInspectRangeControls() {
        addStepControl(
                rowY,
                InspectSettings.inspectRange(),
                delta -> {
                    int next = clamp(InspectSettings.inspectRange() + delta, MIN_INSPECT_RANGE, MAX_INSPECT_RANGE);
                    if (next != InspectSettings.inspectRange()) {
                        InspectSettings.setInspectRange(next);
                        saveConfig();
                    }
                    return InspectSettings.inspectRange();
                }
        );
        rowY += rowHeight;
    }

    private void addLabelsOnLookControl() {
        labelsOnLookButton = addRenderableWidget(Button.builder(toggleText(InspectSettings.labelsOnLook()), button -> {
                    InspectSettings.setLabelsOnLook(!InspectSettings.labelsOnLook());
                    saveConfig();
                    refreshButtonLabels();
                })
                .pos(controlX, rowY).size(controlWidth, 20)
                .build());
        rowY += rowHeight;
    }

    private void addBordersAlwaysControl() {
        bordersAlwaysButton = addRenderableWidget(Button.builder(toggleText(InspectSettings.bordersAlwaysVisible()), button -> {
                    InspectSettings.setBordersAlwaysVisible(!InspectSettings.bordersAlwaysVisible());
                    saveConfig();
                    refreshButtonLabels();
                })
                .pos(controlX, rowY).size(controlWidth, 20)
                .build());
        rowY += rowHeight;
    }

    private void addFindRadiusControls() {
        addStepControl(
                rowY,
                FindSettings.defaultFindRadius(),
                delta -> {
                    int next = clamp(FindSettings.defaultFindRadius() + delta, MIN_FIND_RADIUS, MAX_FIND_RADIUS);
                    if (next != FindSettings.defaultFindRadius()) {
                        FindSettings.setDefaultFindRadius(next);
                        saveConfig();
                    }
                    return FindSettings.defaultFindRadius();
                }
        );
        rowY += rowHeight;
    }

    private void addFindTimeoutControls() {
        addStepControl(
                rowY,
                FindSettings.findHighlightTimeoutSeconds(),
                delta -> {
                    int next = clamp(FindSettings.findHighlightTimeoutSeconds() + delta, MIN_FIND_TIMEOUT, MAX_FIND_TIMEOUT);
                    if (next != FindSettings.findHighlightTimeoutSeconds()) {
                        FindSettings.setFindHighlightTimeoutSeconds(next);
                        saveConfig();
                    }
                    return FindSettings.findHighlightTimeoutSeconds();
                }
        );
        rowY += rowHeight;
    }

    private void addDumpRangeControls() {
        addStepControl(
                rowY,
                DumpSettings.dumpRange(),
                delta -> {
                    int next = clamp(DumpSettings.dumpRange() + delta, MIN_DUMP_RANGE, MAX_DUMP_RANGE);
                    if (next != DumpSettings.dumpRange()) {
                        DumpSettings.setDumpRange(next);
                        saveConfig();
                    }
                    return DumpSettings.dumpRange();
                }
        );
        rowY += rowHeight;
    }

    private void addToggleControls() {
        variantMatchingButton = addRenderableWidget(Button.builder(toggleText(FindSettings.variantMatchingEnabled()), button -> {
                    FindSettings.setVariantMatchingEnabled(!FindSettings.variantMatchingEnabled());
                    saveConfig();
                    refreshButtonLabels();
                })
                .pos(controlX, rowY).size(controlWidth, 20)
                .build());
        rowY += rowHeight;

        slashFButton = addRenderableWidget(Button.builder(toggleText(FindSettings.allowSlashFCommand()), button -> {
                    FindSettings.setAllowSlashFCommand(!FindSettings.allowSlashFCommand());
                    saveConfig();
                    refreshButtonLabels();
                })
                .pos(controlX, rowY).size(controlWidth, 20)
                .build());
        rowY += rowHeight;

        findKeybindButton = addRenderableWidget(Button.builder(toggleText(FindSettings.allowFindKeybind()), button -> {
                    FindSettings.setAllowFindKeybind(!FindSettings.allowFindKeybind());
                    saveConfig();
                    refreshButtonLabels();
                })
                .pos(controlX, rowY).size(controlWidth, 20)
                .build());
        rowY += rowHeight;

        moveSourceButton = addRenderableWidget(Button.builder(moveSourceText(), button -> {
                    TransferSettings.setMoveSourceMode(nextMoveSourceMode(TransferSettings.moveSourceMode()));
                    saveConfig();
                    refreshButtonLabels();
                })
                .pos(controlX, rowY).size(controlWidth, 20)
                .build());
        rowY += rowHeight;

        pullDestButton = addRenderableWidget(Button.builder(pullDestText(), button -> {
                    TransferSettings.setPullDropsOnGround(!TransferSettings.pullDropsOnGround());
                    saveConfig();
                    refreshButtonLabels();
                })
                .pos(controlX, rowY).size(controlWidth, 20)
                .build());
        rowY += rowHeight;
    }

    private void addDetectionThresholdControls() {
        addStepControl(
                rowY,
                ContainerDetectionSettings.detectedCategoryThresholdPercent(),
                delta -> {
                    int next = clamp(
                            ContainerDetectionSettings.detectedCategoryThresholdPercent() + delta,
                            MIN_DETECTION_THRESHOLD_PERCENT,
                            MAX_DETECTION_THRESHOLD_PERCENT
                    );
                    if (next != ContainerDetectionSettings.detectedCategoryThresholdPercent()) {
                        ContainerDetectionSettings.setDetectedCategoryThresholdPercent(next);
                        saveConfig();
                    }
                    return ContainerDetectionSettings.detectedCategoryThresholdPercent();
                }
        );
        rowY += rowHeight;
    }

    private void addBookButtons() {
        addRenderableWidget(Button.builder(Component.translatable("screen.latchlabel.config.book_export"), button -> {
                    Minecraft mc = Minecraft.getInstance();
                    BookExportImportService.ExportResult result = BookExportImportService.exportToHeldBook(
                            mc,
                            LatchLabelClientState.tagStore(),
                            LatchLabelClientState.categoryStore(),
                            LatchLabelClientState.itemCategoryMappingService()
                    );
                    if (mc.player != null) {
                        mc.player.sendSystemMessage(result.message());
                    }
                })
                .pos(controlX, rowY).size(controlWidth, 20)
                .build());
        rowY += rowHeight;

        addRenderableWidget(Button.builder(Component.translatable("screen.latchlabel.config.book_import"), button -> {
                    Minecraft mc = Minecraft.getInstance();
                    BookExportImportService.ImportResult result = BookExportImportService.importFromHeldBook(
                            mc,
                            LatchLabelClientState.tagStore(),
                            LatchLabelClientState.categoryStore(),
                            LatchLabelClientState.itemCategoryMappingService()
                    );
                    if (mc.player != null) {
                        mc.player.sendSystemMessage(result.message());
                    }
                })
                .pos(controlX, rowY).size(controlWidth, 20)
                .build());
        rowY += rowHeight;
    }

    private void addStepControl(int y, int initialValue, StepValueUpdater updater) {
        int minusWidth = 20;
        int plusWidth = 20;
        int valueWidth = controlWidth - minusWidth - plusWidth - 6;
        int valueX = controlX + minusWidth + 3;

        Button valueButton = addRenderableWidget(Button.builder(Component.literal(String.valueOf(initialValue)), button -> {
                })
                .pos(valueX, y).size(valueWidth, 20)
                .build());
        valueButton.active = false;

        addRenderableWidget(Button.builder(Component.literal("-"), button -> {
                    int updated = updater.update(-1);
                    valueButton.setMessage(Component.literal(String.valueOf(updated)));
                })
                .pos(controlX, y).size(minusWidth, 20)
                .build());

        addRenderableWidget(Button.builder(Component.literal("+"), button -> {
                    int updated = updater.update(1);
                    valueButton.setMessage(Component.literal(String.valueOf(updated)));
                })
                .pos(controlX + controlWidth - plusWidth, y).size(plusWidth, 20)
                .build());
    }

    private void refreshButtonLabels() {
        if (bordersAlwaysButton != null) {
            bordersAlwaysButton.setMessage(toggleText(InspectSettings.bordersAlwaysVisible()));
        }
        if (labelsOnLookButton != null) {
            labelsOnLookButton.setMessage(toggleText(InspectSettings.labelsOnLook()));
        }
        if (variantMatchingButton != null) {
            variantMatchingButton.setMessage(toggleText(FindSettings.variantMatchingEnabled()));
        }
        if (slashFButton != null) {
            slashFButton.setMessage(toggleText(FindSettings.allowSlashFCommand()));
        }
        if (findKeybindButton != null) {
            findKeybindButton.setMessage(toggleText(FindSettings.allowFindKeybind()));
        }
        if (moveSourceButton != null) {
            moveSourceButton.setMessage(moveSourceText());
        }
        if (pullDestButton != null) {
            pullDestButton.setMessage(pullDestText());
        }
    }

    private static MoveSourceMode nextMoveSourceMode(MoveSourceMode current) {
        return switch (current) {
            case INVENTORY -> MoveSourceMode.INVENTORY_AND_HOTBAR;
            case INVENTORY_AND_HOTBAR -> MoveSourceMode.INVENTORY;
        };
    }

    private static Component toggleText(boolean enabled) {
        return Component.translatable(enabled ? "options.on" : "options.off");
    }

    private static Component moveSourceText() {
        return switch (TransferSettings.moveSourceMode()) {
            case INVENTORY -> Component.translatable("screen.latchlabel.config.move_source.inventory");
            case INVENTORY_AND_HOTBAR -> Component.translatable("screen.latchlabel.config.move_source.inventory_hotbar");
        };
    }

    private static Component pullDestText() {
        return Component.translatable(TransferSettings.pullDropsOnGround()
                ? "screen.latchlabel.config.pull_dest.ground"
                : "screen.latchlabel.config.pull_dest.inventory");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void saveConfig() {
        LatchLabelClientState.clientConfigManager().flushNow();
    }

    @FunctionalInterface
    private interface StepValueUpdater {
        int update(int delta);
    }
}
