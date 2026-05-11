package com.latchandlabel.client.inspect;

import com.latchandlabel.client.LatchLabel;
import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.input.ClientInputHandler;
import com.latchandlabel.client.tagging.StorageTagResolver;
import com.latchandlabel.client.targeting.ContainerTargeting;
import com.latchandlabel.client.model.Category;
import com.latchandlabel.client.model.ChestKey;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

// TODO: After ./gradlew genSources, verify:
//  1. HudElementRegistry.attachElementBefore() exact signature and DeltaTracker/RenderTickCounter param type
//  2. GuiGraphicsExtractor.guiWidth() / guiHeight() vs getScaledWindowWidth() / getScaledWindowHeight()
//  3. client.font vs client.font
//  4. VanillaHudElements import package

public final class FocusedTagBillboardRenderer {
    private static final int NORMAL_TEXT_COLOR = 0xFFFFFFFF;
    private static final int FULL_STORAGE_TEXT_COLOR = 0xFFFFD54A;

    private FocusedTagBillboardRenderer() {
    }

    public static void registerHud() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(LatchLabel.MOD_ID, "focused_tag_billboard"),
                (context, tickCounter) -> renderHud(context)
        );
    }

    private static void renderHud(GuiGraphicsExtractor context) {
        if (!ClientInputHandler.isInspectModeActive()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        FocusedCategory focused = resolveFocusedCategory(client);
        if (focused == null) {
            return;
        }

        Component text = Component.literal(focused.displayName());
        // TODO: verify guiWidth/guiHeight method names in GuiGraphicsExtractor 26.2
        int x = (context.guiWidth() / 2) - (client.font.width(text) / 2);
        int y = (context.guiHeight() / 2) + 20;
        int color = focused.isFull() ? FULL_STORAGE_TEXT_COLOR : NORMAL_TEXT_COLOR;
        context.text(client.font, text, x, y, color);
    }

    private static FocusedCategory resolveFocusedCategory(Minecraft client) {
        if (client == null || client.level == null || client.hitResult == null) {
            return null;
        }

        Optional<ChestKey> targeted = ContainerTargeting.getTargetedContainer(client);
        if (targeted.isEmpty()) {
            return null;
        }

        Optional<String> categoryId = StorageTagResolver.resolveCategoryId(LatchLabelClientState.tagStore(), client, targeted.get());
        if (categoryId.isEmpty()) {
            return null;
        }

        Optional<Category> category = LatchLabelClientState.categoryStore().getById(categoryId.get());
        if (category.isEmpty()) {
            return null;
        }

        if (!(client.hitResult instanceof BlockHitResult hitResult) || hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        ChestKey key = targeted.get();
        Vec3 center = faceCenter(key, hitResult.getDirection());
        return new FocusedCategory(category.get(), center, StorageFullness.isStorageFull(client.level, key.pos()));
    }

    private static Vec3 faceCenter(ChestKey key, Direction face) {
        double x = key.pos().getX() + 0.5 + (face.getStepX() * 0.501);
        double y = key.pos().getY() + 0.5 + (face.getStepY() * 0.501);
        double z = key.pos().getZ() + 0.5 + (face.getStepZ() * 0.501);
        return new Vec3(x, y, z);
    }

    private record FocusedCategory(Category category, Vec3 center, boolean isFull) {
        private String displayName() {
            return isFull ? "⚠️ " + category.name() + " ⚠️" : category.name();
        }
    }
}
