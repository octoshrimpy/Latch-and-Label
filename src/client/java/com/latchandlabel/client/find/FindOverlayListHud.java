package com.latchandlabel.client.find;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class FindOverlayListHud {
    private static final int PANEL_WIDTH = 220;
    private static final int ROW_HEIGHT = 14;
    private static final int MAX_ROWS = 8;

    private static boolean leftMouseWasDown;
    private static boolean escapeWasDown;
    private static long dismissedGeneration = -1L;

    private FindOverlayListHud() {
    }

    public static void register() {
        HudRenderCallback.EVENT.register(FindOverlayListHud::render);
        ClientTickEvents.END_CLIENT_TICK.register(FindOverlayListHud::onEndTick);
    }

    private static void render(DrawContext context, RenderTickCounter tickCounter) {
        if (!FindSettings.enableFindOverlayList()) {
            return;
        }

        List<FindResultState.ActiveFindResult> active = visibleResults();
        if (active.isEmpty()) {
            return;
        }

        long generation = FindResultState.currentGeneration();
        if (dismissedGeneration == generation) {
            return;
        }

        int panelHeight = 24 + (active.size() * ROW_HEIGHT);
        int x = context.getScaledWindowWidth() - PANEL_WIDTH - 8;
        int y = 8;

        context.fill(x, y, x + PANEL_WIDTH, y + panelHeight, 0xB0101010);
        context.drawStrokedRectangle(x, y, PANEL_WIDTH, panelHeight, 0xFF424242);
        context.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, Text.translatable("latchlabel.find.overlay.title"), x + 8, y + 6, 0xFFFFFFFF);

        for (int i = 0; i < active.size(); i++) {
            FindScanService.FindMatch match = active.get(i).match();
            int rowY = y + 20 + (i * ROW_HEIGHT);

            int rowBg = (i % 2 == 0) ? 0x55222222 : 0x55303030;
            if (FindResultState.isFocused(match.chestKey())) {
                rowBg = 0x886A4A14;
            }
            context.fill(x + 4, rowY - 1, x + PANEL_WIDTH - 4, rowY + ROW_HEIGHT - 1, rowBg);

            Text typeText = match.matchType() == FindScanService.MatchType.EXACT
                    ? Text.translatable("latchlabel.find.result_exact")
                    : Text.translatable("latchlabel.find.result_variant");
            context.drawTextWithShadow(
                    MinecraftClient.getInstance().textRenderer,
                    Text.translatable(
                            "latchlabel.find.overlay.entry",
                            match.chestKey().pos().toShortString(),
                            String.format("%.1f", match.distance()),
                            typeText
                    ),
                    x + 8,
                    rowY + 2,
                    0xE8E8E8
            );
        }
    }

    private static void onEndTick(MinecraftClient client) {
        if (client == null || client.getWindow() == null) {
            leftMouseWasDown = false;
            escapeWasDown = false;
            return;
        }

        if (!FindSettings.enableFindOverlayList()) {
            leftMouseWasDown = false;
            escapeWasDown = false;
            return;
        }

        List<FindResultState.ActiveFindResult> active = visibleResults();
        if (active.isEmpty()) {
            leftMouseWasDown = false;
            escapeWasDown = false;
            return;
        }

        long generation = FindResultState.currentGeneration();
        long windowHandle = client.getWindow().getHandle();

        boolean escapeDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        if (escapeDown && !escapeWasDown) {
            dismissedGeneration = generation;
        }
        escapeWasDown = escapeDown;

        if (dismissedGeneration == generation) {
            leftMouseWasDown = false;
            return;
        }

        boolean leftDown = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (leftDown && !leftMouseWasDown && client.currentScreen == null) {
            int mouseX = (int) Math.round(client.mouse.getX() * client.getWindow().getScaledWidth() / (double) client.getWindow().getWidth());
            int mouseY = (int) Math.round(client.mouse.getY() * client.getWindow().getScaledHeight() / (double) client.getWindow().getHeight());

            int panelHeight = 24 + (active.size() * ROW_HEIGHT);
            int panelX = client.getWindow().getScaledWidth() - PANEL_WIDTH - 8;
            int panelY = 8;

            if (mouseX >= panelX && mouseX <= panelX + PANEL_WIDTH && mouseY >= panelY && mouseY <= panelY + panelHeight) {
                int row = (mouseY - (panelY + 20)) / ROW_HEIGHT;
                if (row >= 0 && row < active.size()) {
                    FindScanService.FindMatch selected = active.get(row).match();
                    FindResultState.focus(selected.chestKey(), 2200L);
                }
            }
        }
        leftMouseWasDown = leftDown;
    }

    private static List<FindResultState.ActiveFindResult> visibleResults() {
        List<FindResultState.ActiveFindResult> results = new ArrayList<>(FindResultState.getActiveResults());
        results.sort(Comparator.comparingDouble(result -> result.match().distance()));
        if (results.size() > MAX_ROWS) {
            return List.copyOf(results.subList(0, MAX_ROWS));
        }
        return List.copyOf(results);
    }
}
