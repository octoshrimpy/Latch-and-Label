package com.latchandlabel.client.config.ui;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.config.InspectActivationMode;
import com.latchandlabel.client.config.InspectSettings;
import com.latchandlabel.client.find.FindResultState;
import com.latchandlabel.client.find.FindSettings;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class LatchLabelConfigScreen extends Screen {
    private static final int MIN_INSPECT_RANGE = 1;
    private static final int MAX_INSPECT_RANGE = 32;
    private static final int MIN_FIND_RADIUS = 1;
    private static final int MAX_FIND_RADIUS = 256;
    private static final int MIN_HIGHLIGHT_SECONDS = 1;
    private static final int MAX_HIGHLIGHT_SECONDS = 120;

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

    private ButtonWidget inspectModeButton;
    private ButtonWidget variantMatchingButton;
    private ButtonWidget overlayListButton;
    private ButtonWidget slashFButton;
    private ButtonWidget findKeybindButton;

    public LatchLabelConfigScreen(Screen parent) {
        super(Text.translatable("screen.latchlabel.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(520, width - 30);
        panelHeight = 272;
        panelLeft = (width - panelWidth) / 2;
        panelTop = (height - panelHeight) / 2;
        labelX = panelLeft + 16;
        controlWidth = 190;
        controlX = panelLeft + panelWidth - controlWidth - 16;
        rowY = panelTop + 30;
        rowHeight = 22;

        addReloadButton();
        addInspectRangeControls();
        addInspectModeControl();
        addFindRadiusControls();
        addHighlightDurationControls();
        addToggleControls();

        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> close())
                .dimensions(panelLeft + panelWidth - 96, panelTop + panelHeight - 24, 80, 20)
                .build());
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xA0101010);
        context.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xE0151515);
        context.drawStrokedRectangle(panelLeft, panelTop, panelWidth, panelHeight, 0xFF3A3A3A);

        context.drawCenteredTextWithShadow(textRenderer, title, panelLeft + (panelWidth / 2), panelTop + 10, 0xFFFFFFFF);
        drawLabels(context);

        super.render(context, mouseX, mouseY, delta);
    }

    private void drawLabels(DrawContext context) {
        int y = panelTop + 36;
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.latchlabel.config.reload_label"), labelX, y, 0xFFE8E8E8);
        y += rowHeight;
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.latchlabel.config.inspect_range_label"), labelX, y, 0xFFE8E8E8);
        y += rowHeight;
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.latchlabel.config.inspect_mode_label"), labelX, y, 0xFFE8E8E8);
        y += rowHeight;
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.latchlabel.config.find_radius_label"), labelX, y, 0xFFE8E8E8);
        y += rowHeight;
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.latchlabel.config.highlight_duration_label"), labelX, y, 0xFFE8E8E8);
        y += rowHeight;
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.latchlabel.config.variant_matching_label"), labelX, y, 0xFFE8E8E8);
        y += rowHeight;
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.latchlabel.config.overlay_list_label"), labelX, y, 0xFFE8E8E8);
        y += rowHeight;
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.latchlabel.config.allow_f_label"), labelX, y, 0xFFE8E8E8);
        y += rowHeight;
        context.drawTextWithShadow(textRenderer, Text.translatable("screen.latchlabel.config.find_keybind_label"), labelX, y, 0xFFE8E8E8);
    }

    private void addReloadButton() {
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.latchlabel.config.reload"), button -> {
                    LatchLabelClientState.clientConfigManager().reload();
                    if (client != null) {
                        client.setScreen(new LatchLabelConfigScreen(parent));
                    }
                })
                .dimensions(controlX, rowY, controlWidth, 20)
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

    private void addInspectModeControl() {
        inspectModeButton = addDrawableChild(ButtonWidget.builder(inspectModeText(), button -> {
                    InspectSettings.setActivationMode(nextInspectMode(InspectSettings.activationMode()));
                    saveConfig();
                    refreshButtonLabels();
                })
                .dimensions(controlX, rowY, controlWidth, 20)
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

    private void addHighlightDurationControls() {
        addStepControl(
                rowY,
                FindResultState.getHighlightDurationSeconds(),
                delta -> {
                    int next = clamp(FindResultState.getHighlightDurationSeconds() + delta, MIN_HIGHLIGHT_SECONDS, MAX_HIGHLIGHT_SECONDS);
                    if (next != FindResultState.getHighlightDurationSeconds()) {
                        FindResultState.setHighlightDurationSeconds(next);
                        saveConfig();
                    }
                    return FindResultState.getHighlightDurationSeconds();
                }
        );
        rowY += rowHeight;
    }

    private void addToggleControls() {
        variantMatchingButton = addDrawableChild(ButtonWidget.builder(toggleText(FindSettings.variantMatchingEnabled()), button -> {
                    FindSettings.setVariantMatchingEnabled(!FindSettings.variantMatchingEnabled());
                    saveConfig();
                    refreshButtonLabels();
                })
                .dimensions(controlX, rowY, controlWidth, 20)
                .build());
        rowY += rowHeight;

        overlayListButton = addDrawableChild(ButtonWidget.builder(toggleText(FindSettings.enableFindOverlayList()), button -> {
                    FindSettings.setEnableFindOverlayList(!FindSettings.enableFindOverlayList());
                    saveConfig();
                    refreshButtonLabels();
                })
                .dimensions(controlX, rowY, controlWidth, 20)
                .build());
        rowY += rowHeight;

        slashFButton = addDrawableChild(ButtonWidget.builder(toggleText(FindSettings.allowSlashFCommand()), button -> {
                    FindSettings.setAllowSlashFCommand(!FindSettings.allowSlashFCommand());
                    saveConfig();
                    refreshButtonLabels();
                })
                .dimensions(controlX, rowY, controlWidth, 20)
                .build());
        rowY += rowHeight;

        findKeybindButton = addDrawableChild(ButtonWidget.builder(toggleText(FindSettings.allowFindKeybind()), button -> {
                    FindSettings.setAllowFindKeybind(!FindSettings.allowFindKeybind());
                    saveConfig();
                    refreshButtonLabels();
                })
                .dimensions(controlX, rowY, controlWidth, 20)
                .build());
    }

    private void addStepControl(int y, int initialValue, StepValueUpdater updater) {
        int minusWidth = 20;
        int plusWidth = 20;
        int valueWidth = controlWidth - minusWidth - plusWidth - 6;
        int valueX = controlX + minusWidth + 3;

        ButtonWidget valueButton = addDrawableChild(ButtonWidget.builder(Text.literal(String.valueOf(initialValue)), button -> {
                })
                .dimensions(valueX, y, valueWidth, 20)
                .build());
        valueButton.active = false;

        addDrawableChild(ButtonWidget.builder(Text.literal("-"), button -> {
                    int updated = updater.update(-1);
                    valueButton.setMessage(Text.literal(String.valueOf(updated)));
                })
                .dimensions(controlX, y, minusWidth, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("+"), button -> {
                    int updated = updater.update(1);
                    valueButton.setMessage(Text.literal(String.valueOf(updated)));
                })
                .dimensions(controlX + controlWidth - plusWidth, y, plusWidth, 20)
                .build());
    }

    private void refreshButtonLabels() {
        if (inspectModeButton != null) {
            inspectModeButton.setMessage(inspectModeText());
        }
        if (variantMatchingButton != null) {
            variantMatchingButton.setMessage(toggleText(FindSettings.variantMatchingEnabled()));
        }
        if (overlayListButton != null) {
            overlayListButton.setMessage(toggleText(FindSettings.enableFindOverlayList()));
        }
        if (slashFButton != null) {
            slashFButton.setMessage(toggleText(FindSettings.allowSlashFCommand()));
        }
        if (findKeybindButton != null) {
            findKeybindButton.setMessage(toggleText(FindSettings.allowFindKeybind()));
        }
    }

    private static InspectActivationMode nextInspectMode(InspectActivationMode current) {
        return switch (current) {
            case ALT_ONLY -> InspectActivationMode.SHIFT_ONLY;
            case SHIFT_ONLY -> InspectActivationMode.ALT_OR_SHIFT;
            case ALT_OR_SHIFT -> InspectActivationMode.ALT_ONLY;
        };
    }

    private static Text inspectModeText() {
        return switch (InspectSettings.activationMode()) {
            case ALT_ONLY -> Text.translatable("screen.latchlabel.config.inspect_mode.alt_only");
            case SHIFT_ONLY -> Text.translatable("screen.latchlabel.config.inspect_mode.shift_only");
            case ALT_OR_SHIFT -> Text.translatable("screen.latchlabel.config.inspect_mode.both");
        };
    }

    private static Text toggleText(boolean enabled) {
        return Text.translatable(enabled ? "options.on" : "options.off");
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
