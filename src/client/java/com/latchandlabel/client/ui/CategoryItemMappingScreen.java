package com.latchandlabel.client.ui;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.model.Category;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class CategoryItemMappingScreen extends Screen {
    private static final int PANEL_WIDTH = 380;
    private static final int PANEL_HEIGHT = 282;
    private static final int ICON_BUTTON_SIZE = 18;
    private static final int ICON_COLOR_GAP = 4;
    private static final int SLOT_SIZE = 18;
    private static final int GRID_COLUMNS = 9;
    private static final int GRID_ROWS = 9;
    private static final int PAGE_SIZE = GRID_COLUMNS * GRID_ROWS;
    private static final int COLOR_BUTTON_SIZE = 18;
    private static final int COLOR_SWATCH_SIZE = 14;
    private static final int COLOR_SWATCH_GAP = 2;
    private static final int COLOR_SWATCH_COLUMNS = 4;
    private static final int DELETE_CONFIRM_WIDTH = 260;
    private static final int DELETE_CONFIRM_HEIGHT = 88;
    private static final int DELETE_CONFIRM_BUTTON_WIDTH = 68;
    private static final int DELETE_CONFIRM_BUTTON_HEIGHT = 18;
    private static final int[] COLOR_PALETTE = {
            0xF9FFFE, // white
            0xF9801D, // orange
            0xC74EBD, // magenta
            0x3AB3DA, // light_blue
            0xFED83D, // yellow
            0x80C71F, // lime
            0xF38BAA, // pink
            0x474F52, // gray
            0x9D9D97, // light_gray
            0x169C9C, // cyan
            0x8932B8, // purple
            0x3C44AA, // blue
            0x835432, // brown
            0x5E7C16, // green
            0xB02E26, // red
            0x1D1D21  // black
    };

    private final Screen parent;
    private final String categoryId;
    private Category fallbackCategory;
    private final List<Identifier> filteredItemIds = new ArrayList<>();
    private final Map<Identifier, List<String>> itemTagSearchCache = new HashMap<>();
    private final Identifier airItemId = Registries.ITEM.getId(Items.AIR);
    private TextFieldWidget nameField;
    private TextFieldWidget searchField;
    private Text headerTitle;
    private int pageIndex;
    private int selectedColor;
    private Identifier selectedIconItemId;
    private boolean colorPickerOpen;
    private boolean deleteConfirmOpen;
    private boolean categoryDeleted;

    public CategoryItemMappingScreen(Screen parent, Category category) {
        super(Text.translatable("screen.latchlabel.category_items.title", category.name()));
        this.parent = parent;
        this.categoryId = category.id();
        this.fallbackCategory = category;
        this.selectedColor = category.color();
        this.selectedIconItemId = category.iconItemId();
        this.headerTitle = Text.translatable("screen.latchlabel.category_items.title", category.name());
    }

    @Override
    protected void init() {
        LatchLabelClientState.itemCategoryMappingService().refreshDefaultMappingsIfExpanded();

        Category active = activeCategory();
        selectedColor = active.color();
        selectedIconItemId = active.iconItemId();
        headerTitle = Text.translatable("screen.latchlabel.category_items.title", active.name());

        int panelLeft = (width - PANEL_WIDTH) / 2;
        int panelTop = (height - PANEL_HEIGHT) / 2;

        int nameFieldLeft = panelLeft + 10 + ICON_BUTTON_SIZE + ICON_COLOR_GAP + COLOR_BUTTON_SIZE + 6;
        int nameFieldWidth = PANEL_WIDTH - 20 - ICON_BUTTON_SIZE - ICON_COLOR_GAP - COLOR_BUTTON_SIZE - 6;
        nameField = new TextFieldWidget(textRenderer, nameFieldLeft, panelTop + 22, nameFieldWidth, 18, Text.empty());
        nameField.setText(active.name());
        nameField.setMaxLength(48);
        nameField.setChangedListener(this::onNameChanged);
        addDrawableChild(nameField);

        searchField = new TextFieldWidget(textRenderer, panelLeft + 10, panelTop + 46, PANEL_WIDTH - 20, 18, Text.empty());
        searchField.setChangedListener(value -> refilter());
        searchField.setMaxLength(80);
        addDrawableChild(searchField);
        setInitialFocus(searchField);
        searchField.setFocused(true);

        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.back"), button -> close())
                .dimensions(panelLeft + 10, panelTop + PANEL_HEIGHT - 24, 60, 16)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.latchlabel.category_items.delete"), button -> {
                    colorPickerOpen = false;
                    deleteConfirmOpen = true;
                })
                .dimensions(panelLeft + 76, panelTop + PANEL_HEIGHT - 24, 70, 16)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.literal("<"), button -> {
                    if (pageIndex > 0) {
                        pageIndex--;
                    }
                })
                .dimensions(panelLeft + PANEL_WIDTH - 80, panelTop + PANEL_HEIGHT - 24, 20, 16)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.literal(">"), button -> {
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
    public void close() {
        if (!categoryDeleted) {
            commitCategoryEdits();
        }
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xA0101010);

        int panelLeft = (width - PANEL_WIDTH) / 2;
        int panelTop = (height - PANEL_HEIGHT) / 2;
        int gridTop = panelTop + 72;
        int gridWidth = GRID_COLUMNS * SLOT_SIZE;
        int gridLeft = panelLeft + (PANEL_WIDTH - gridWidth) / 2;
        int iconButtonLeft = panelLeft + 10;
        int colorButtonLeft = iconButtonLeft + ICON_BUTTON_SIZE + ICON_COLOR_GAP;
        int colorButtonTop = panelTop + 22;
        int colorPickerTop = panelTop + 44;
        int iconButtonTop = colorButtonTop;
        int colorValue = selectedColor | 0xFF000000;
        boolean iconHovered = mouseX >= iconButtonLeft
                && mouseX <= iconButtonLeft + ICON_BUTTON_SIZE
                && mouseY >= iconButtonTop
                && mouseY <= iconButtonTop + ICON_BUTTON_SIZE;
        boolean colorHovered = mouseX >= colorButtonLeft
                && mouseX <= colorButtonLeft + COLOR_BUTTON_SIZE
                && mouseY >= colorButtonTop
                && mouseY <= colorButtonTop + COLOR_BUTTON_SIZE;

        context.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + PANEL_HEIGHT, 0xCC101010);
        context.drawStrokedRectangle(panelLeft, panelTop, PANEL_WIDTH, PANEL_HEIGHT, 0xFF3A3A3A);
        context.drawTextWithShadow(textRenderer, headerTitle, panelLeft + 10, panelTop + 8, 0xFFFFFFFF);
        context.fill(iconButtonLeft, iconButtonTop, iconButtonLeft + ICON_BUTTON_SIZE, iconButtonTop + ICON_BUTTON_SIZE, 0xCC2A2A2A);
        context.drawStrokedRectangle(iconButtonLeft, iconButtonTop, ICON_BUTTON_SIZE, ICON_BUTTON_SIZE, iconHovered ? 0xFFFFFFFF : 0xFF5A5A5A);
        if (selectedIconItemId != null && Registries.ITEM.containsId(selectedIconItemId)) {
            context.drawItem(new ItemStack(Registries.ITEM.get(selectedIconItemId)), iconButtonLeft + 1, iconButtonTop + 1);
        }
        context.fill(colorButtonLeft, colorButtonTop, colorButtonLeft + COLOR_BUTTON_SIZE, colorButtonTop + COLOR_BUTTON_SIZE, colorValue);
        context.drawStrokedRectangle(colorButtonLeft, colorButtonTop, COLOR_BUTTON_SIZE, COLOR_BUTTON_SIZE, colorHovered ? 0xFFFFFFFF : 0xFF5A5A5A);

        boolean suppressUnderlyingHover = deleteConfirmOpen
                || isWithinColorPicker(mouseX, mouseY, colorButtonLeft, colorPickerTop);
        int interactionMouseX = suppressUnderlyingHover ? Integer.MIN_VALUE : mouseX;
        int interactionMouseY = suppressUnderlyingHover ? Integer.MIN_VALUE : mouseY;
        super.render(context, interactionMouseX, interactionMouseY, delta);

        Identifier hoveredItemId = null;
        if (!deleteConfirmOpen) {
            int start = pageIndex * PAGE_SIZE;
            int end = Math.min(filteredItemIds.size(), start + PAGE_SIZE);
            for (int i = start; i < end; i++) {
                int slotIndex = i - start;
                Identifier itemId = filteredItemIds.get(i);
                if (drawSlot(context, gridLeft, gridTop, slotIndex, itemId, interactionMouseX, interactionMouseY)) {
                    hoveredItemId = itemId;
                }
            }
        }

        List<Text> hoveredTooltipLines = null;
        if (!deleteConfirmOpen && hoveredItemId != null) {
            ItemStack hoveredStack = new ItemStack(Registries.ITEM.get(hoveredItemId));
            hoveredTooltipLines = new ArrayList<>();
            hoveredTooltipLines.add(hoveredStack.getName());

            boolean mappedToCurrent = LatchLabelClientState.itemCategoryMappingService().isMappedToCategory(hoveredItemId, categoryId);
            if (!mappedToCurrent && isShiftDown()) {
                Optional<Category> mappedCategory = LatchLabelClientState.itemCategoryMappingService()
                        .categoryIdFor(hoveredItemId)
                        .flatMap(LatchLabelClientState.categoryStore()::getById);
                if (mappedCategory.isPresent()) {
                    Category category = mappedCategory.get();
                    hoveredTooltipLines.add(
                            Text.translatable("latchlabel.tooltip.category", category.name())
                                    .setStyle(Style.EMPTY.withColor(category.color()))
                    );
                }
            }
        }

        if (!deleteConfirmOpen && colorPickerOpen) {
            drawColorPicker(context, colorButtonLeft, colorPickerTop, mouseX, mouseY);
        }

        if (deleteConfirmOpen) {
            drawDeleteConfirmDialog(context, mouseX, mouseY);
        }

        if (hoveredTooltipLines != null) {
            context.drawTooltip(textRenderer, hoveredTooltipLines, Optional.empty(), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(Click click, boolean consumed) {
        if (deleteConfirmOpen) {
            return handleDeleteConfirmClick(click);
        }

        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (super.mouseClicked(click, consumed)) {
                return true;
            }
            return false;
        }

        int panelLeft = (width - PANEL_WIDTH) / 2;
        int panelTop = (height - PANEL_HEIGHT) / 2;
        int iconButtonLeft = panelLeft + 10;
        int colorButtonLeft = iconButtonLeft + ICON_BUTTON_SIZE + ICON_COLOR_GAP;
        int colorPickerTop = panelTop + 44;
        double mouseX = click.x();
        double mouseY = click.y();

        int pickedColor = colorAt(mouseX, mouseY, colorButtonLeft, colorPickerTop);
        if (pickedColor >= 0) {
            selectedColor = pickedColor & 0x00FFFFFF;
            commitCategoryEdits();
            colorPickerOpen = false;
            return true;
        }
        if (isWithinColorPicker(mouseX, mouseY, colorButtonLeft, colorPickerTop)) {
            return true;
        }

        if (super.mouseClicked(click, consumed)) {
            return true;
        }

        int gridTop = panelTop + 72;
        int gridWidth = GRID_COLUMNS * SLOT_SIZE;
        int gridLeft = panelLeft + (PANEL_WIDTH - gridWidth) / 2;
        int colorButtonTop = panelTop + 22;
        int iconButtonTop = colorButtonTop;

        if (isWithin(mouseX, mouseY, iconButtonLeft, iconButtonTop, iconButtonLeft + ICON_BUTTON_SIZE, iconButtonTop + ICON_BUTTON_SIZE)) {
            openIconPicker();
            return true;
        }

        if (isWithin(mouseX, mouseY, colorButtonLeft, colorButtonTop, colorButtonLeft + COLOR_BUTTON_SIZE, colorButtonTop + COLOR_BUTTON_SIZE)) {
            colorPickerOpen = !colorPickerOpen;
            return true;
        }
        colorPickerOpen = false;

        int slotIndex = slotIndexAt(mouseX, mouseY, gridLeft, gridTop);
        if (slotIndex < 0) {
            return false;
        }

        int itemIndex = (pageIndex * PAGE_SIZE) + slotIndex;
        if (itemIndex < 0 || itemIndex >= filteredItemIds.size()) {
            return false;
        }

        Category active = activeCategory();
        Identifier itemId = filteredItemIds.get(itemIndex);
        LatchLabelClientState.itemCategoryMappingService().toggleCategoryMembership(itemId, categoryId);
        if (client != null && client.inGameHud != null) {
            boolean on = LatchLabelClientState.itemCategoryMappingService().isMappedToCategory(itemId, categoryId);
            Text message = on
                    ? Text.translatable("latchlabel.mapping.added", itemId.toString(), active.name())
                    : Text.translatable("latchlabel.mapping.removed", itemId.toString());
            client.inGameHud.setOverlayMessage(message, false);
        }
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput keyInput) {
        if (deleteConfirmOpen) {
            if (keyInput.key() == GLFW.GLFW_KEY_ESCAPE) {
                deleteConfirmOpen = false;
                return true;
            }
            if (keyInput.key() == GLFW.GLFW_KEY_ENTER || keyInput.key() == GLFW.GLFW_KEY_KP_ENTER) {
                deleteCategoryAndClose();
                return true;
            }
            return true;
        }

        if ((keyInput.key() == GLFW.GLFW_KEY_ENTER || keyInput.key() == GLFW.GLFW_KEY_KP_ENTER)
                && nameField != null
                && nameField.isFocused()) {
            commitCategoryEdits();
            return true;
        }
        if (searchField != null && searchField.keyPressed(keyInput)) {
            return true;
        }
        if (keyInput.key() == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        return super.keyPressed(keyInput);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharInput charInput) {
        if (deleteConfirmOpen) {
            return true;
        }
        if (searchField != null && searchField.charTyped(charInput)) {
            return true;
        }
        return super.charTyped(charInput);
    }

    private void refilter() {
        String query = searchField == null ? "" : searchField.getText().toLowerCase(Locale.ROOT).trim();
        boolean tagQuery = query.startsWith("#");
        String normalizedTagQuery = tagQuery ? query.substring(1) : query;

        filteredItemIds.clear();
        for (Identifier itemId : Registries.ITEM.getIds()) {
            if (itemId == null || itemId.equals(airItemId)) {
                continue;
            }
            if (!matchesSearchQuery(itemId, query, normalizedTagQuery, tagQuery)) {
                continue;
            }
            filteredItemIds.add(itemId);
        }

        filteredItemIds.sort(Comparator.comparing(Identifier::toString));
        pageIndex = 0;
    }

    private boolean matchesSearchQuery(Identifier itemId, String query, String normalizedTagQuery, boolean tagQuery) {
        if (query.isEmpty()) {
            return true;
        }

        if (!tagQuery) {
            return itemId.toString().toLowerCase(Locale.ROOT).contains(query);
        }

        for (String tagId : itemTagIds(itemId)) {
            if (tagId.contains(normalizedTagQuery)) {
                return true;
            }
        }
        return false;
    }

    private List<String> itemTagIds(Identifier itemId) {
        return itemTagSearchCache.computeIfAbsent(itemId, id -> {
            if (!Registries.ITEM.containsId(id)) {
                return List.of();
            }
            ItemStack stack = new ItemStack(Registries.ITEM.get(id));
            return stack.getRegistryEntry()
                    .streamTags()
                    .map(tagKey -> tagKey.id().toString().toLowerCase(Locale.ROOT))
                    .sorted()
                    .toList();
        });
    }

    private boolean drawSlot(DrawContext context, int gridLeft, int gridTop, int slotIndex, Identifier itemId, int mouseX, int mouseY) {
        Category active = activeCategory();
        int col = slotIndex % GRID_COLUMNS;
        int row = slotIndex / GRID_COLUMNS;
        int x = gridLeft + (col * SLOT_SIZE);
        int y = gridTop + (row * SLOT_SIZE);
        int right = x + SLOT_SIZE;
        int bottom = y + SLOT_SIZE;
        boolean hovered = mouseX >= x && mouseX <= right && mouseY >= y && mouseY <= bottom;
        boolean mapped = LatchLabelClientState.itemCategoryMappingService().isMappedToCategory(itemId, categoryId);

        int baseBackground = mapped ? (0xCC000000 | (active.color() & 0x00FFFFFF)) : 0xCC2A2A2A;
        if (hovered) {
            baseBackground = mapped ? (0xEE000000 | (active.color() & 0x00FFFFFF)) : 0xCC3A3A3A;
        }
        context.fill(x, y, right, bottom, baseBackground);
        context.drawStrokedRectangle(x, y, SLOT_SIZE, SLOT_SIZE, mapped ? 0xFFFFFFFF : 0xFF5A5A5A);

        context.drawItem(new ItemStack(Registries.ITEM.get(itemId)), x + 1, y + 1);
        if (!mapped) {
            context.fill(x + 1, y + 1, right - 1, bottom - 1, 0x40000000);
        }

        return hovered;
    }

    private int maxPageIndex() {
        if (filteredItemIds.isEmpty()) {
            return 0;
        }
        return (filteredItemIds.size() - 1) / PAGE_SIZE;
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

    private void drawColorPicker(DrawContext context, int left, int top, int mouseX, int mouseY) {
        int rows = (int) Math.ceil((double) COLOR_PALETTE.length / COLOR_SWATCH_COLUMNS);
        int pickerWidth = (COLOR_SWATCH_COLUMNS * COLOR_SWATCH_SIZE) + ((COLOR_SWATCH_COLUMNS - 1) * COLOR_SWATCH_GAP) + 8;
        int pickerHeight = (rows * COLOR_SWATCH_SIZE) + ((rows - 1) * COLOR_SWATCH_GAP) + 8;
        context.fill(left, top, left + pickerWidth, top + pickerHeight, 0xFF101010);
        context.drawStrokedRectangle(left, top, pickerWidth, pickerHeight, 0xFF4A4A4A);

        for (int i = 0; i < COLOR_PALETTE.length; i++) {
            int row = i / COLOR_SWATCH_COLUMNS;
            int col = i % COLOR_SWATCH_COLUMNS;
            int swatchLeft = left + 4 + (col * (COLOR_SWATCH_SIZE + COLOR_SWATCH_GAP));
            int swatchTop = top + 4 + (row * (COLOR_SWATCH_SIZE + COLOR_SWATCH_GAP));
            int swatchColor = COLOR_PALETTE[i] | 0xFF000000;
            boolean hovered = isWithin(mouseX, mouseY, swatchLeft, swatchTop, swatchLeft + COLOR_SWATCH_SIZE, swatchTop + COLOR_SWATCH_SIZE);
            boolean selected = (COLOR_PALETTE[i] & 0x00FFFFFF) == (selectedColor & 0x00FFFFFF);

            context.fill(swatchLeft, swatchTop, swatchLeft + COLOR_SWATCH_SIZE, swatchTop + COLOR_SWATCH_SIZE, swatchColor);
            int border = selected ? 0xFFFFFFFF : (hovered ? 0xFFBDBDBD : 0xFF5A5A5A);
            context.drawStrokedRectangle(swatchLeft, swatchTop, COLOR_SWATCH_SIZE, COLOR_SWATCH_SIZE, border);
        }
    }

    private int colorAt(double mouseX, double mouseY, int left, int top) {
        if (!colorPickerOpen) {
            return -1;
        }

        for (int i = 0; i < COLOR_PALETTE.length; i++) {
            int row = i / COLOR_SWATCH_COLUMNS;
            int col = i % COLOR_SWATCH_COLUMNS;
            int swatchLeft = left + 4 + (col * (COLOR_SWATCH_SIZE + COLOR_SWATCH_GAP));
            int swatchTop = top + 4 + (row * (COLOR_SWATCH_SIZE + COLOR_SWATCH_GAP));
            if (isWithin(mouseX, mouseY, swatchLeft, swatchTop, swatchLeft + COLOR_SWATCH_SIZE, swatchTop + COLOR_SWATCH_SIZE)) {
                return COLOR_PALETTE[i] & 0x00FFFFFF;
            }
        }

        return -1;
    }

    private boolean isWithinColorPicker(double mouseX, double mouseY, int left, int top) {
        if (!colorPickerOpen) {
            return false;
        }

        int rows = (int) Math.ceil((double) COLOR_PALETTE.length / COLOR_SWATCH_COLUMNS);
        int pickerWidth = (COLOR_SWATCH_COLUMNS * COLOR_SWATCH_SIZE) + ((COLOR_SWATCH_COLUMNS - 1) * COLOR_SWATCH_GAP) + 8;
        int pickerHeight = (rows * COLOR_SWATCH_SIZE) + ((rows - 1) * COLOR_SWATCH_GAP) + 8;
        return isWithin(mouseX, mouseY, left, top, left + pickerWidth, top + pickerHeight);
    }

    private void onNameChanged(String value) {
        updateHeaderPreview(value);
        commitCategoryEdits();
    }

    private void commitCategoryEdits() {
        String rawName = nameField == null ? activeCategory().name() : nameField.getText();
        String normalizedName = rawName == null ? "" : rawName.trim();
        if (normalizedName.isEmpty()) {
            normalizedName = activeCategory().name();
        }

        int normalizedColor = selectedColor & 0x00FFFFFF;
        selectedColor = normalizedColor;
        Identifier iconItemId = selectedIconItemId;
        if (iconItemId == null || !Registries.ITEM.containsId(iconItemId)) {
            iconItemId = activeCategory().iconItemId();
        }
        selectedIconItemId = iconItemId;
        LatchLabelClientState.categoryStore().updateCategoryDetails(categoryId, normalizedName, normalizedColor, iconItemId);

        Category active = activeCategory();
        headerTitle = Text.translatable("screen.latchlabel.category_items.title", active.name());
    }

    private void updateHeaderPreview(String rawName) {
        String previewName = rawName == null ? "" : rawName.trim();
        if (previewName.isEmpty()) {
            previewName = activeCategory().name();
        }
        headerTitle = Text.translatable("screen.latchlabel.category_items.title", previewName);
    }

    private Category activeCategory() {
        return LatchLabelClientState.categoryStore().getById(categoryId)
                .map(category -> {
                    fallbackCategory = category;
                    return category;
                })
                .orElse(fallbackCategory);
    }

    private static boolean isWithin(double mouseX, double mouseY, int left, int top, int right, int bottom) {
        return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
    }

    private void openIconPicker() {
        colorPickerOpen = false;
        if (client == null) {
            return;
        }

        client.setScreen(new CategoryIconPickerScreen(
                this,
                activeCategory().name(),
                selectedIconItemId,
                pickedIcon -> {
                    selectedIconItemId = pickedIcon;
                    commitCategoryEdits();
                }
        ));
    }

    private boolean handleDeleteConfirmClick(Click click) {
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return true;
        }

        int boxLeft = (width - DELETE_CONFIRM_WIDTH) / 2;
        int boxTop = (height - DELETE_CONFIRM_HEIGHT) / 2;
        int buttonY = boxTop + DELETE_CONFIRM_HEIGHT - 26;
        int cancelX = boxLeft + DELETE_CONFIRM_WIDTH - DELETE_CONFIRM_BUTTON_WIDTH - 10;
        int deleteX = cancelX - DELETE_CONFIRM_BUTTON_WIDTH - 8;
        double mouseX = click.x();
        double mouseY = click.y();

        if (isWithin(mouseX, mouseY, deleteX, buttonY, deleteX + DELETE_CONFIRM_BUTTON_WIDTH, buttonY + DELETE_CONFIRM_BUTTON_HEIGHT)) {
            deleteCategoryAndClose();
            return true;
        }
        if (isWithin(mouseX, mouseY, cancelX, buttonY, cancelX + DELETE_CONFIRM_BUTTON_WIDTH, buttonY + DELETE_CONFIRM_BUTTON_HEIGHT)) {
            deleteConfirmOpen = false;
            return true;
        }

        if (!isWithin(mouseX, mouseY, boxLeft, boxTop, boxLeft + DELETE_CONFIRM_WIDTH, boxTop + DELETE_CONFIRM_HEIGHT)) {
            deleteConfirmOpen = false;
        }
        return true;
    }

    private void drawDeleteConfirmDialog(DrawContext context, int mouseX, int mouseY) {
        context.getMatrices().pushMatrix();
        context.fill(0, 0, width, height, 0xA0000000);

        int boxLeft = (width - DELETE_CONFIRM_WIDTH) / 2;
        int boxTop = (height - DELETE_CONFIRM_HEIGHT) / 2;
        int boxRight = boxLeft + DELETE_CONFIRM_WIDTH;
        int boxBottom = boxTop + DELETE_CONFIRM_HEIGHT;
        context.fill(boxLeft, boxTop, boxRight, boxBottom, 0xFF101010);
        context.drawStrokedRectangle(boxLeft, boxTop, DELETE_CONFIRM_WIDTH, DELETE_CONFIRM_HEIGHT, 0xFF5A5A5A);

        Text confirmText = Text.translatable("screen.latchlabel.category_items.delete_confirm", activeCategory().name());
        context.drawTextWithShadow(textRenderer, confirmText, boxLeft + 10, boxTop + 14, 0xFFFFFFFF);

        int buttonY = boxTop + DELETE_CONFIRM_HEIGHT - 26;
        int cancelX = boxLeft + DELETE_CONFIRM_WIDTH - DELETE_CONFIRM_BUTTON_WIDTH - 10;
        int deleteX = cancelX - DELETE_CONFIRM_BUTTON_WIDTH - 8;
        boolean deleteHovered = isWithin(mouseX, mouseY, deleteX, buttonY, deleteX + DELETE_CONFIRM_BUTTON_WIDTH, buttonY + DELETE_CONFIRM_BUTTON_HEIGHT);
        boolean cancelHovered = isWithin(mouseX, mouseY, cancelX, buttonY, cancelX + DELETE_CONFIRM_BUTTON_WIDTH, buttonY + DELETE_CONFIRM_BUTTON_HEIGHT);

        context.fill(deleteX, buttonY, deleteX + DELETE_CONFIRM_BUTTON_WIDTH, buttonY + DELETE_CONFIRM_BUTTON_HEIGHT, deleteHovered ? 0xFFB02E26 : 0xFF8E2620);
        context.drawStrokedRectangle(deleteX, buttonY, DELETE_CONFIRM_BUTTON_WIDTH, DELETE_CONFIRM_BUTTON_HEIGHT, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("screen.latchlabel.category_items.delete"), deleteX + (DELETE_CONFIRM_BUTTON_WIDTH / 2), buttonY + 5, 0xFFFFFFFF);

        context.fill(cancelX, buttonY, cancelX + DELETE_CONFIRM_BUTTON_WIDTH, buttonY + DELETE_CONFIRM_BUTTON_HEIGHT, cancelHovered ? 0xFF4A4A4A : 0xFF353535);
        context.drawStrokedRectangle(cancelX, buttonY, DELETE_CONFIRM_BUTTON_WIDTH, DELETE_CONFIRM_BUTTON_HEIGHT, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("gui.cancel"), cancelX + (DELETE_CONFIRM_BUTTON_WIDTH / 2), buttonY + 5, 0xFFFFFFFF);
        context.getMatrices().popMatrix();
    }

    private void deleteCategoryAndClose() {
        deleteConfirmOpen = false;
        String deletedCategoryName = activeCategory().name();
        LatchLabelClientState.itemCategoryMappingService().clearCategoryReferences(categoryId);
        LatchLabelClientState.tagStore().clearCategoryReferences(categoryId);
        boolean deleted = LatchLabelClientState.categoryStore().deleteCategory(categoryId);
        if (!deleted) {
            return;
        }

        categoryDeleted = true;
        if (client != null) {
            if (client.inGameHud != null) {
            client.inGameHud.setOverlayMessage(
                    Text.translatable("latchlabel.category.deleted", deletedCategoryName),
                    false
            );
            }
            client.setScreen(parent);
        }
    }

    private static boolean isShiftDown() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getWindow() == null) {
            return false;
        }
        return InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT)
                || InputUtil.isKeyPressed(client.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);
    }
}
