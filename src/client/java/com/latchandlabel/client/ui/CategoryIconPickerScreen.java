package com.latchandlabel.client.ui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphics;
// TODO: verify Click package after ./gradlew genSources — was net.minecraft.client.gui.Click in 1.21.11
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

public final class CategoryIconPickerScreen extends Screen {
    private static final int PANEL_WIDTH = 340;
    private static final int PANEL_HEIGHT = 250;
    private static final int SLOT_SIZE = 18;
    private static final int GRID_COLUMNS = 10;
    private static final int GRID_ROWS = 9;
    private static final int PAGE_SIZE = GRID_COLUMNS * GRID_ROWS;

    private final Screen parent;
    private final Consumer<ResourceLocation> onSelect;
    private final List<ResourceLocation> filteredItemIds = new ArrayList<>();
    private final ResourceLocation airItemId = BuiltInRegistries.ITEM.getKey(Items.AIR).location();

    private EditBox searchField;
    private int pageIndex;
    private ResourceLocation selectedItemId;

    public CategoryIconPickerScreen(Screen parent, String categoryName, ResourceLocation currentIconId, Consumer<ResourceLocation> onSelect) {
        super(Component.translatable("screen.latchlabel.category_icon_picker.title", categoryName));
        this.parent = parent;
        this.onSelect = Objects.requireNonNull(onSelect, "onSelect");
        this.selectedItemId = currentIconId;
    }

    @Override
    protected void init() {
        int panelLeft = (width - PANEL_WIDTH) / 2;
        int panelTop = (height - PANEL_HEIGHT) / 2;

        searchField = new EditBox(textRenderer, panelLeft + 10, panelTop + 22, PANEL_WIDTH - 20, 18, .Component.empty());
        searchField.setChangedListener(value -> refilter());
        searchField.setMaxLength(80);
        addDrawableChild(searchField);
        setInitialFocus(searchField);
        searchField.setFocused(true);

        addDrawableChild(Button.builder(Component.translatable("gui.back"), button -> close())
                .dimensions(panelLeft + 10, panelTop + PANEL_HEIGHT - 24, 60, 16)
                .build());

        addDrawableChild(Button.builder(Component.literal("<"), button -> {
                    if (pageIndex > 0) {
                        pageIndex--;
                    }
                })
                .dimensions(panelLeft + PANEL_WIDTH - 80, panelTop + PANEL_HEIGHT - 24, 20, 16)
                .build());

        addDrawableChild(Button.builder(Component.literal(">"), button -> {
                    int maxPage = maxPageIndex();
                    if (pageIndex < maxPage) {
                        pageIndex++;
                    }
                })
                .dimensions(panelLeft + PANEL_WIDTH - 56, panelTop + PANEL_HEIGHT - 24, 20, 16)
                .build());

        refilter();
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xA0101010);

        int panelLeft = (width - PANEL_WIDTH) / 2;
        int panelTop = (height - PANEL_HEIGHT) / 2;
        int gridTop = panelTop + 48;
        int gridWidth = GRID_COLUMNS * SLOT_SIZE;
        int gridLeft = panelLeft + (PANEL_WIDTH - gridWidth) / 2;

        context.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + PANEL_HEIGHT, 0xCC101010);
        context.drawStrokedRectangle(panelLeft, panelTop, PANEL_WIDTH, PANEL_HEIGHT, 0xFF3A3A3A);
        context.drawString(textRenderer, title, panelLeft + 10, panelTop + 8, 0xFFFFFFFF, true);

        super.render(context, mouseX, mouseY, delta);

        ResourceLocation hoveredItemId = null;
        int start = pageIndex * PAGE_SIZE;
        int end = Math.min(filteredItemIds.size(), start + PAGE_SIZE);
        for (int i = start; i < end; i++) {
            int slotIndex = i - start;
            ResourceLocation itemId = filteredItemIds.get(i);
            if (drawSlot(context, gridLeft, gridTop, slotIndex, itemId, mouseX, mouseY)) {
                hoveredItemId = itemId;
            }
        }

        if (hoveredItemId != null) {
            context.drawTooltip(textRenderer, Component.literal(hoveredItemId.toString()), mouseX, mouseY);
        }
    }

    @Override
    public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float delta) {
        // We draw a custom translucent background in render(); skip Screen's blur pass.
    }

    @Override
    public boolean mouseClicked(Click click, boolean consumed) {
        if (super.mouseClicked(click, consumed)) {
            return true;
        }
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }

        int panelLeft = (width - PANEL_WIDTH) / 2;
        int panelTop = (height - PANEL_HEIGHT) / 2;
        int gridTop = panelTop + 48;
        int gridWidth = GRID_COLUMNS * SLOT_SIZE;
        int gridLeft = panelLeft + (PANEL_WIDTH - gridWidth) / 2;

        int slotIndex = slotIndexAt(click.x(), click.y(), gridLeft, gridTop);
        if (slotIndex < 0) {
            return false;
        }

        int itemIndex = (pageIndex * PAGE_SIZE) + slotIndex;
        if (itemIndex < 0 || itemIndex >= filteredItemIds.size()) {
            return false;
        }

        selectAndClose(filteredItemIds.get(itemIndex));
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        if (searchField != null && searchField.keyPressed(keyInput)) {
            return true;
        }

        int keyCode = keyInput.key();
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            ResourceLocation firstItem = firstVisibleItemId();
            if (firstItem != null) {
                selectAndClose(firstItem);
                return true;
            }
        }

        return super.keyPressed(keyInput);
    }

    @Override
    public boolean charTyped(CharInput charInput) {
        if (searchField != null && searchField.charTyped(charInput)) {
            return true;
        }
        return super.charTyped(charInput);
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    private void selectAndClose(ResourceLocation itemId) {
        selectedItemId = itemId;
        onSelect.accept(itemId);
        close();
    }

    private void refilter() {
        String query = searchField == null ? "" : searchField.getText().toLowerCase(Locale.ROOT).trim();

        filteredItemIds.clear();
        for (ResourceLocation itemId : BuiltInRegistries.ITEM.keySet()) {
            if (itemId == null || itemId.equals(airItemId)) {
                continue;
            }
            String id = itemId.toString().toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && !id.contains(query)) {
                continue;
            }
            filteredItemIds.add(itemId);
        }

        filteredItemIds.sort(Comparator.comparing(ResourceLocation::toString));
        pageIndex = 0;
    }

    private boolean drawSlot(GuiGraphics context, int gridLeft, int gridTop, int slotIndex, ResourceLocation itemId, int mouseX, int mouseY) {
        int col = slotIndex % GRID_COLUMNS;
        int row = slotIndex / GRID_COLUMNS;
        int x = gridLeft + (col * SLOT_SIZE);
        int y = gridTop + (row * SLOT_SIZE);
        int right = x + SLOT_SIZE;
        int bottom = y + SLOT_SIZE;
        boolean hovered = mouseX >= x && mouseX <= right && mouseY >= y && mouseY <= bottom;
        boolean selected = itemId.equals(selectedItemId);

        context.fill(x, y, right, bottom, hovered ? 0xCC3A3A3A : 0xCC2A2A2A);
        context.drawStrokedRectangle(x, y, SLOT_SIZE, SLOT_SIZE, selected ? 0xFFFFFFFF : 0xFF5A5A5A);
        context.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(itemId)), x + 1, y + 1);
        return hovered;
    }

    private int maxPageIndex() {
        if (filteredItemIds.isEmpty()) {
            return 0;
        }
        return (filteredItemIds.size() - 1) / PAGE_SIZE;
    }

    private ResourceLocation firstVisibleItemId() {
        if (filteredItemIds.isEmpty()) {
            return null;
        }
        int index = pageIndex * PAGE_SIZE;
        if (index < 0 || index >= filteredItemIds.size()) {
            return filteredItemIds.get(0);
        }
        return filteredItemIds.get(index);
    }

    private static int slotIndexAt(double mouseX, double mouseY, int gridLeft, int gridTop) {
        int relX = (int) Math.floor(mouseX) - gridLeft;
        int relY = (int) Math.floor(mouseY) - gridTop;
        if (relX < 0 || relY < 0) {
            return -1;
        }

        int col = relX / SLOT_SIZE;
        int row = relY / SLOT_SIZE;
        if (col < 0 || col >= GRID_COLUMNS || row < 0 || row >= GRID_ROWS) {
            return -1;
        }

        return row * GRID_COLUMNS + col;
    }
}
