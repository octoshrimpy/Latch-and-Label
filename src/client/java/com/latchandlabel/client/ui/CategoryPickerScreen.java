package com.latchandlabel.client.ui;

import com.latchandlabel.client.LatchLabelClientState;
import com.latchandlabel.client.model.Category;
import com.latchandlabel.client.model.ChestKey;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.registry.Registries;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

public final class CategoryPickerScreen extends Screen {
    private static final int PANEL_WIDTH = 420;
    private static final int PANEL_HEIGHT = 220;
    private static final int ROW_HEIGHT = 18;
    private static final int EDIT_BUTTON_WIDTH = 20;
    private static final int CATEGORY_COLUMNS = 2;
    private static final int CATEGORY_ROWS = 8;
    private static final int MAX_VISIBLE_CATEGORIES = CATEGORY_COLUMNS * CATEGORY_ROWS;
    private static final int COLUMN_GAP = 8;
    private static final int REMOVE_BUTTON_HEIGHT = 18;
    private static final int NEW_CATEGORY_COLOR = 0x9D9D97;
    private static final Identifier NEW_CATEGORY_ICON = Objects.requireNonNull(
            Identifier.tryParse("minecraft:writable_book"),
            "Invalid default icon identifier"
    );

    private final Screen parentScreen;
    private final ChestKey chestKey;
    private final Consumer<String> onSelect;
    private final Runnable onClear;
    private final Runnable onCancel;

    private final List<Category> filteredCategories = new ArrayList<>();
    private TextFieldWidget searchField;
    private String pendingSelectionId;
    private boolean completed;

    public CategoryPickerScreen(Screen parentScreen, ChestKey chestKey, Consumer<String> onSelect, Runnable onClear, Runnable onCancel) {
        super(Text.translatable("screen.latchlabel.category_picker.title"));
        this.parentScreen = parentScreen;
        this.chestKey = Objects.requireNonNull(chestKey, "chestKey");
        this.onSelect = Objects.requireNonNull(onSelect, "onSelect");
        this.onClear = Objects.requireNonNull(onClear, "onClear");
        this.onCancel = Objects.requireNonNull(onCancel, "onCancel");
    }

    @Override
    protected void init() {
        int panelLeft = (width - PANEL_WIDTH) / 2;
        int panelTop = (height - PANEL_HEIGHT) / 2;

        searchField = new TextFieldWidget(textRenderer, panelLeft + 10, panelTop + 22, PANEL_WIDTH - 20, 18, Text.empty());
        searchField.setChangedListener(value -> refilter());
        searchField.setMaxLength(64);

        addDrawableChild(searchField);
        setInitialFocus(searchField);
        searchField.setFocused(true);

        refilter();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xA0101010);

        int panelLeft = (width - PANEL_WIDTH) / 2;
        int panelTop = (height - PANEL_HEIGHT) / 2;
        int listTop = panelTop + 48;
        int removeButtonTop = panelTop + PANEL_HEIGHT - REMOVE_BUTTON_HEIGHT - 8;

        context.fill(panelLeft, panelTop, panelLeft + PANEL_WIDTH, panelTop + PANEL_HEIGHT, 0xCC101010);
        context.drawStrokedRectangle(panelLeft, panelTop, PANEL_WIDTH, PANEL_HEIGHT, 0xFF3A3A3A);

        context.drawTextWithShadow(textRenderer, title, panelLeft + 10, panelTop + 8, 0xFFFFFFFF);

        super.render(context, mouseX, mouseY, delta);

        List<Category> rows = visibleCategories();
        int listLeft = panelLeft + 10;
        int listWidth = PANEL_WIDTH - 20;
        int columnWidth = (listWidth - COLUMN_GAP) / CATEGORY_COLUMNS;
        for (int index = 0; index < MAX_VISIBLE_CATEGORIES; index++) {
            Category category = index < rows.size() ? rows.get(index) : null;
            drawCategoryCell(context, listLeft, columnWidth, listTop, index, category, mouseX, mouseY);
        }

        int removeButtonLeft = panelLeft + 10;
        int removeButtonRight = panelLeft + PANEL_WIDTH - 10;
        boolean removeButtonHovered = isWithin(mouseX, mouseY, removeButtonLeft, removeButtonTop, removeButtonRight, removeButtonTop + REMOVE_BUTTON_HEIGHT);
        boolean canClearCurrent = hasCurrentCategory();
        int removeButtonFill = canClearCurrent
                ? (removeButtonHovered ? 0xFF5A2323 : 0xFF432020)
                : 0xFF2A2A2A;
        int removeButtonStroke = canClearCurrent ? 0xFFBA5E5E : 0xFF555555;
        int removeButtonTextColor = canClearCurrent ? 0xFFFFFFFF : 0xFF888888;

        context.fill(removeButtonLeft, removeButtonTop, removeButtonRight, removeButtonTop + REMOVE_BUTTON_HEIGHT, removeButtonFill);
        context.drawStrokedRectangle(removeButtonLeft, removeButtonTop, removeButtonRight - removeButtonLeft, REMOVE_BUTTON_HEIGHT, removeButtonStroke);
        Text removeLabel = Text.translatable("screen.latchlabel.category_picker.remove_current");
        int centeredTextX = removeButtonLeft + ((removeButtonRight - removeButtonLeft) - textRenderer.getWidth(removeLabel)) / 2;
        context.drawTextWithShadow(textRenderer, removeLabel, centeredTextX, removeButtonTop + 5, removeButtonTextColor);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // We draw a custom translucent background in render(); skip Screen's blur pass to avoid
        // "Can only blur once per frame" when this picker is opened over another blurred screen.
    }

    @Override
    public boolean mouseClicked(Click click, boolean consumed) {
        if (super.mouseClicked(click, consumed)) {
            return true;
        }

        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }

        int panelLeft = (width - PANEL_WIDTH) / 2;
        int panelTop = (height - PANEL_HEIGHT) / 2;
        int listTop = panelTop + 48;
        int listLeft = panelLeft + 10;
        int listWidth = PANEL_WIDTH - 20;
        int columnWidth = (listWidth - COLUMN_GAP) / CATEGORY_COLUMNS;
        int removeButtonTop = panelTop + PANEL_HEIGHT - REMOVE_BUTTON_HEIGHT - 8;
        int removeButtonLeft = panelLeft + 10;
        int removeButtonRight = panelLeft + PANEL_WIDTH - 10;

        List<Category> rows = visibleCategories();

        for (int index = 0; index < MAX_VISIBLE_CATEGORIES; index++) {
            Category category = index < rows.size() ? rows.get(index) : null;
            int column = index % CATEGORY_COLUMNS;
            int row = index / CATEGORY_COLUMNS;
            int rowY = listTop + (row * ROW_HEIGHT);
            int rowLeft = listLeft + (column * (columnWidth + COLUMN_GAP));
            int rowRight = rowLeft + columnWidth;
            int editButtonX = rowRight - EDIT_BUTTON_WIDTH - 2;
            int buttonTop = rowY + 1;
            int buttonBottom = buttonTop + ROW_HEIGHT - 4;

            if (isWithin(mouseX, mouseY, editButtonX, buttonTop, editButtonX + EDIT_BUTTON_WIDTH, buttonBottom)) {
                if (client != null && category != null) {
                    client.setScreen(new CategoryItemMappingScreen(this, category));
                    return true;
                }
                if (category == null) {
                    createCategoryAndOpenEditor();
                }
                return true;
            }

            if (category != null && isCellHit(mouseX, mouseY, rowLeft, rowY, rowRight)) {
                selectAndClose(category.id());
                return true;
            }
        }

        if (isWithin(mouseX, mouseY, removeButtonLeft, removeButtonTop, removeButtonRight, removeButtonTop + REMOVE_BUTTON_HEIGHT)) {
            if (hasCurrentCategory()) {
                clearAndClose();
            }
            return true;
        }

        return false;
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

        if (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            clearAndClose();
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (pendingSelectionId != null) {
                selectAndClose(pendingSelectionId);
                return true;
            }
            if (!filteredCategories.isEmpty()) {
                selectAndClose(filteredCategories.get(0).id());
                return true;
            }
        }

        int digit = keyCode - GLFW.GLFW_KEY_1;
        if (digit >= 0 && digit <= 8) {
            if (digit < filteredCategories.size()) {
                selectAndClose(filteredCategories.get(digit).id());
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
        if (!completed) {
            onCancel.run();
        }
        returnToParent();
    }

    private void clearAndClose() {
        completed = true;
        onClear.run();
        returnToParent();
    }

    private void selectAndClose(String categoryId) {
        completed = true;
        onSelect.accept(categoryId);
        returnToParent();
    }

    private void refilter() {
        String query = searchField == null ? "" : searchField.getText().toLowerCase(Locale.ROOT).trim();

        filteredCategories.clear();
        for (Category category : LatchLabelClientState.categoryStore().listAll()) {
            if (!category.visible()) {
                continue;
            }

            if (query.isEmpty()
                    || category.name().toLowerCase(Locale.ROOT).contains(query)
                    || category.id().toLowerCase(Locale.ROOT).contains(query)) {
                filteredCategories.add(category);
            }
        }

        pendingSelectionId = filteredCategories.isEmpty() ? null : filteredCategories.get(0).id();
    }

    private void drawCategoryCell(
            DrawContext context,
            int left,
            int columnWidth,
            int listTop,
            int index,
            Category category,
            int mouseX,
            int mouseY
    ) {
        int column = index % CATEGORY_COLUMNS;
        int row = index / CATEGORY_COLUMNS;
        int y = listTop + (row * ROW_HEIGHT);
        int x = left + (column * (columnWidth + COLUMN_GAP));
        int rowRight = x + columnWidth;
        boolean hovered = isCellHit(mouseX, mouseY, x, y, rowRight);
        boolean emptySlot = category == null;

        int background = hovered ? 0x883A3A3A : (emptySlot ? 0x44202020 : 0x66202020);
        context.fill(x, y, rowRight, y + ROW_HEIGHT - 2, background);
        if (!emptySlot) {
            int color = category.color() | 0xFF000000;
            context.fill(x + 2, y + 3, x + 8, y + ROW_HEIGHT - 5, color);
        }

        int editButtonX = rowRight - EDIT_BUTTON_WIDTH - 2;
        int buttonTop = y + 1;
        int buttonBottom = buttonTop + ROW_HEIGHT - 4;
        context.fill(editButtonX, buttonTop, editButtonX + EDIT_BUTTON_WIDTH, buttonBottom, 0xFF2D2D2D);
        context.drawStrokedRectangle(editButtonX, buttonTop, EDIT_BUTTON_WIDTH, buttonBottom - buttonTop, 0xFF666666);
        if (emptySlot) {
            context.drawTextWithShadow(textRenderer, Text.literal("+"), editButtonX + 7, y + 4, 0xFFFFFFFF);
            return;
        }

        context.drawItem(new ItemStack(Items.WRITABLE_BOOK), editButtonX + 2, y + 1);
        if (Registries.ITEM.containsId(category.iconItemId())) {
            context.drawItem(new ItemStack(Registries.ITEM.get(category.iconItemId())), x + 10, y + 1);
        }

        int maxNameWidth = (editButtonX - 6) - (x + 30);
        String trimmedName = textRenderer.trimToWidth(category.name(), Math.max(0, maxNameWidth));
        context.drawTextWithShadow(textRenderer, Text.literal(trimmedName), x + 30, y + 4, 0xFFFFFFFF);
    }

    private static boolean isCellHit(double mouseX, double mouseY, int left, int y, int right) {
        int bottom = y + ROW_HEIGHT - 2;
        return mouseX >= left && mouseX <= right && mouseY >= y && mouseY <= bottom;
    }

    private static boolean isWithin(double mouseX, double mouseY, int left, int top, int right, int bottom) {
        return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
    }

    private List<Category> visibleCategories() {
        List<Category> rows = new ArrayList<>();
        int maxRows = MAX_VISIBLE_CATEGORIES;
        for (Category category : filteredCategories) {
            if (rows.size() >= maxRows) {
                break;
            }
            rows.add(category);
        }

        return rows;
    }

    private void returnToParent() {
        if (client != null) {
            client.setScreen(parentScreen);
        }
    }

    private void createCategoryAndOpenEditor() {
        Category created = LatchLabelClientState.categoryStore()
                .createCategory(Text.translatable("screen.latchlabel.category_picker.new_category_name").getString(), NEW_CATEGORY_COLOR, NEW_CATEGORY_ICON);
        refilter();
        if (client != null) {
            client.setScreen(new CategoryItemMappingScreen(this, created));
        }
    }

    private boolean hasCurrentCategory() {
        return LatchLabelClientState.tagStore().getTag(chestKey).isPresent();
    }

}
