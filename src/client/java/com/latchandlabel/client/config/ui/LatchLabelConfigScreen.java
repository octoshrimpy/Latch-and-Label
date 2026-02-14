package com.latchandlabel.client.config.ui;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.config.InspectSettings;
import com.latchandlabel.client.find.FindResultState;
import com.latchandlabel.client.find.FindSettings;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class LatchLabelConfigScreen extends Screen {
    private static final int MIN_HIGHLIGHT_SECONDS = 1;
    private static final int MAX_HIGHLIGHT_SECONDS = 120;

    private final Screen parent;

    public LatchLabelConfigScreen(Screen parent) {
        super(Text.translatable("screen.latchlabel.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int baseY = height / 2;

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.latchlabel.config.reload"), button -> {
                    LatchLabelClientState.clientConfigManager().reload();
                })
                .dimensions(centerX - 100, baseY - 10, 200, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("-"), button -> adjustHighlightDuration(-1))
                .dimensions(centerX - 100, baseY + 18, 20, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("+"), button -> adjustHighlightDuration(1))
                .dimensions(centerX + 80, baseY + 18, 20, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> close())
                .dimensions(centerX - 100, baseY + 46, 200, 20)
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
        super.render(context, mouseX, mouseY, delta);

        int centerX = width / 2;
        int y = height / 2 - 72;

        context.drawCenteredTextWithShadow(textRenderer, title, centerX, y, 0xFFFFFFFF);
        y += 16;
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.latchlabel.config.inspect_range", InspectSettings.inspectRange()), centerX, y, 0xFFCFCFCF);
        y += 12;
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.latchlabel.config.find_radius", FindSettings.defaultFindRadius()), centerX, y, 0xFFCFCFCF);
        y += 12;
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.latchlabel.config.highlight_duration", FindResultState.getHighlightDurationSeconds()), centerX, y, 0xFFCFCFCF);
        y += 12;
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.latchlabel.config.highlight_duration_hint"), centerX, y, 0xFF9A9A9A);
        y += 12;
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.latchlabel.config.variant_matching", String.valueOf(FindSettings.variantMatchingEnabled())), centerX, y, 0xFFCFCFCF);
        y += 12;
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.latchlabel.config.overlay_list", String.valueOf(FindSettings.enableFindOverlayList())), centerX, y, 0xFFCFCFCF);
    }

    private void adjustHighlightDuration(int delta) {
        int current = FindResultState.getHighlightDurationSeconds();
        int next = Math.max(MIN_HIGHLIGHT_SECONDS, Math.min(MAX_HIGHLIGHT_SECONDS, current + delta));
        if (next == current) {
            return;
        }
        LatchLabelClientState.clientConfigManager().setHighlightDurationSeconds(next);
    }
}
