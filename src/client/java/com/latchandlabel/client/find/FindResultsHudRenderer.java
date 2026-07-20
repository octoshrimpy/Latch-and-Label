package com.latchandlabel.client.find;

import com.latchandlabel.client.LatchLabel;
import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.model.Category;
import com.latchandlabel.client.ui.GuiUtils;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Small top-left HUD listing the active {@code /find} results: the query, the category it
 * belongs to (name + color swatch), how many chests matched (known vs likely), and the
 * distance + a compass arrow to the currently targeted chest. The panel border pulses in the
 * category's color. The target cursor is advanced with the find-cycle keybind.
 */
public final class FindResultsHudRenderer {
    private static final int X = 6;
    private static final int Y = 6;
    private static final int LINE_HEIGHT = 11;
    private static final int PAD = 4;
    private static final int SWATCH = 7;
    private static final int BG_COLOR = 0xC0101010;
    private static final int GOLD = 0xFFFFD84A;
    private static final int GOLD_RGB = 0xFFD84A;
    private static final int DIM = 0xFFDCDCDC;
    // U+2191..2199 arrows, indexed clockwise from north: ↑ ↗ → ↘ ↓ ↙ ← ↖
    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private FindResultsHudRenderer() {
    }

    public static void registerHud() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(LatchLabel.MOD_ID, "find_results"),
                (context, tickCounter) -> render(context)
        );
    }

    private static void render(GuiGraphicsExtractor context) {
        if (!FindResultState.hasActiveResults()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return;
        }

        List<FindScanService.FindMatch> results = FindResultState.getActiveResults();
        int known = 0;
        int stale = 0;
        for (FindScanService.FindMatch m : results) {
            switch (m.matchType()) {
                case KNOWN -> known++;
                case KNOWN_STALE -> stale++;
                default -> {
                }
            }
        }
        int likely = results.size() - known - stale;

        Optional<Category> category = FindResultState.queryCategoryId()
                .flatMap(id -> LatchLabelClientState.categoryStore().getById(id));

        Component header = Component.translatable("latchlabel.find.hud_header",
                FindResultState.queryLabel().orElse(Component.translatable("latchlabel.find.hud_query_fallback")),
                results.size());
        Component categoryLine = category
                .map(c -> Component.translatable("latchlabel.find.hud_category", c.name()))
                .orElse(null);
        Component target = targetLine(client);
        Component counts = stale > 0
                ? Component.translatable("latchlabel.find.hud_counts_stale", known, stale, likely)
                : Component.translatable("latchlabel.find.hud_counts", known, likely);

        List<Component> lines = new ArrayList<>();
        lines.add(header);
        if (categoryLine != null) {
            lines.add(categoryLine);
        }
        if (target != null) {
            lines.add(target);
        }
        lines.add(counts);

        int textWidth = 0;
        for (Component line : lines) {
            textWidth = Math.max(textWidth, client.font.width(line));
        }
        // category line is indented by a color swatch
        int contentWidth = categoryLine != null ? textWidth + SWATCH + 3 : textWidth;
        int boxW = contentWidth + PAD * 2;
        int boxH = lines.size() * LINE_HEIGHT + PAD * 2 - 2;

        context.fill(X, Y, X + boxW, Y + boxH, BG_COLOR);

        // Static category-colored border (the pulse lives on the found chests, not here).
        int borderRgb = category.map(Category::color).orElse(GOLD_RGB) & 0xFFFFFF;
        GuiUtils.drawBorder(context, X, Y, boxW, boxH, 0xFF000000 | borderRgb);

        int textX = X + PAD;
        int y = Y + PAD;
        for (Component line : lines) {
            if (line == categoryLine) {
                int swatchColor = 0xFF000000 | borderRgb;
                context.fill(textX, y, textX + SWATCH, y + SWATCH, swatchColor);
                GuiUtils.drawBorder(context, textX, y, SWATCH, SWATCH, 0xFF000000);
                context.text(client.font, line, textX + SWATCH + 3, y, 0xFFFFFFFF);
            } else if (line == header) {
                context.text(client.font, line, textX, y, GOLD);
            } else if (line == counts) {
                context.text(client.font, line, textX, y, DIM);
            } else {
                context.text(client.font, line, textX, y, 0xFFFFFFFF);
            }
            y += LINE_HEIGHT;
        }
    }

    private static Component targetLine(Minecraft client) {
        Optional<FindScanService.FindMatch> targetOpt = FindResultState.currentTarget();
        if (targetOpt.isEmpty()) {
            return null;
        }
        FindScanService.FindMatch target = targetOpt.get();
        BlockPos pos = target.chestKey().pos();
        double dx = (pos.getX() + 0.5) - client.player.getX();
        double dz = (pos.getZ() + 0.5) - client.player.getZ();
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = Mth.wrapDegrees(targetYaw - client.player.getYRot());
        int index = ((int) Math.round(relative / 45.0) + 8) % 8;
        return Component.translatable("latchlabel.find.hud_target",
                (int) Math.round(target.distance()), ARROWS[index]);
    }
}
