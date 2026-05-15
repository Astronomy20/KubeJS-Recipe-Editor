package net.astronomy.kubejsrecipeeditor.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.astronomy.kubejsrecipeeditor.KubeJsRecipeEditor;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class TagEditorScreen extends AbstractContainerScreen<RecipeBuilderMenu> {

    private static final int TITLE_H     = 24;
    private static final int TABS_H      = 22;
    private static final int SEARCH_H    = 22;
    private static final int BOTTOM_H    = 28;
    private static final int SLOTS_H     = 20;
    private static final int SEL_LABEL_H = 14;
    private static final int PAD         = 6;
    private static final int ROW_H       = 12;
    private static final int CREATE_COLS = 9;

    private int tagType = 0;

    // Normal mode
    private final List<ResourceLocation> allTags      = new ArrayList<>();
    private final List<ResourceLocation> filteredTags = new ArrayList<>();
    private ResourceLocation selectedTag = null;
    private int tagScrollOffset = 0;
    private final ItemStack[] addSlots = new ItemStack[9];

    // Create mode
    private boolean createMode = false;
    private EditBox tagNameBox;
    private final ItemStack[] createSlots = new ItemStack[81];
    private int createRows = 4;
    private final int[] createSlotX = new int[CREATE_COLS];
    private int createSlotsY;

    // Edit mode (double-click on tag → view/edit its contents)
    private boolean editMode = false;
    private ResourceLocation editingTag = null;
    private final List<ItemStack> editItems         = new ArrayList<>();
    private final List<ItemStack> originalEditItems = new ArrayList<>();
    private int editScrollOffset = 0;

    // Double-click tracking
    private long lastTagClickTime = 0L;
    private int  lastTagClickIdx  = -1;

    // Scrollbar drag state (shared: which scrollbar is being dragged)
    private enum DragTarget { NONE, TAG_LIST, EDIT_GRID }
    private DragTarget scrollbarDragging    = DragTarget.NONE;
    private int        scrollbarDragStartY  = 0;
    private int        scrollbarDragStartOff = 0;

    private EditBox searchBox;
    private String statusMessage = "";
    private int    statusColor   = 0xFFFFFF;

    private int listX, listY, listW, listH;
    private int slotsRowY;
    private final int[] slotAbsX = new int[9];
    private int slotAbsY;

    public TagEditorScreen(RecipeBuilderMenu menu, Inventory inventory) {
        super(menu, inventory, Component.literal("Tag Editor"));
        Arrays.fill(addSlots,    ItemStack.EMPTY);
        Arrays.fill(createSlots, ItemStack.EMPTY);
    }

    public record SlotTarget(int x, int y, int index) {}

    public List<SlotTarget> getSlotTargets() {
        List<SlotTarget> out = new ArrayList<>();
        if (createMode) {
            int total = createRows * CREATE_COLS;
            for (int i = 0; i < total && i < createSlots.length; i++) {
                int col = i % CREATE_COLS, row = i / CREATE_COLS;
                out.add(new SlotTarget(createSlotX[col], createSlotsY + row * 20, i));
            }
        } else if (editMode) {
            int total = createRows * CREATE_COLS;
            for (int vis = 0; vis < total; vis++) {
                int col    = vis % CREATE_COLS, row = vis / CREATE_COLS;
                int absIdx = editScrollOffset * CREATE_COLS + vis;
                out.add(new SlotTarget(createSlotX[col], createSlotsY + row * 20, absIdx));
            }
        } else {
            for (int i = 0; i < 9; i++) out.add(new SlotTarget(slotAbsX[i], slotAbsY, i));
        }
        return out;
    }

    public void setSlotIngredient(int index, ItemStack stack) {
        if (createMode) {
            if (index >= 0 && index < createSlots.length) createSlots[index] = stack.copy();
        } else if (editMode) {
            while (editItems.size() <= index) editItems.add(ItemStack.EMPTY);
            editItems.set(index, stack.copy());
        } else {
            if (index >= 0 && index < 9) addSlots[index] = stack.copy();
        }
    }

    // ─── Init ────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        this.imageWidth  = Math.min(430, this.width  - 32);
        this.imageHeight = Math.min(295, this.height - 32);
        super.init();

        listX = leftPos + PAD;
        listY = topPos + TITLE_H + TABS_H + SEARCH_H;
        listW = imageWidth - PAD * 2;

        addRenderableWidget(Button.builder(Component.literal("⌂"), btn -> goHome())
                .pos(leftPos + 4, topPos + 4).size(16, 14).build());

        int tabY = topPos + TITLE_H + 4;
        int tabW = (imageWidth - PAD * 2 - 4) / 3;
        addRenderableWidget(Button.builder(Component.literal("Items"),  btn -> setType(0))
                .pos(leftPos + PAD,                   tabY).size(tabW, 14).build());
        addRenderableWidget(Button.builder(Component.literal("Blocks"), btn -> setType(1))
                .pos(leftPos + PAD + tabW + 2,        tabY).size(tabW, 14).build());
        addRenderableWidget(Button.builder(Component.literal("Fluids"), btn -> setType(2))
                .pos(leftPos + PAD + (tabW + 2) * 2,  tabY).size(tabW, 14).build());

        if (createMode) {
            int nameY = topPos + TITLE_H + TABS_H + 4;
            tagNameBox = new EditBox(font, listX + 36, nameY, listW - 36, 14,
                    Component.literal("namespace:tagname"));
            tagNameBox.setMaxLength(128);
            addRenderableWidget(tagNameBox);

            setupGrid();

            addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> exitCreateMode())
                    .pos(leftPos + imageWidth / 2 - 46, topPos + imageHeight - 22).size(44, 16).build());
            addRenderableWidget(Button.builder(Component.literal("Create"), btn -> doCreate())
                    .pos(leftPos + imageWidth / 2 + 2,  topPos + imageHeight - 22).size(44, 16).build());

        } else if (editMode) {
            setupGrid();

            addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> exitEditMode())
                    .pos(leftPos + imageWidth / 2 - 46, topPos + imageHeight - 22).size(44, 16).build());
            addRenderableWidget(Button.builder(Component.literal("Save"), btn -> saveEditedTag())
                    .pos(leftPos + imageWidth / 2 + 2,  topPos + imageHeight - 22).size(44, 16).build());

        } else {
            slotsRowY = topPos + imageHeight - BOTTOM_H - SLOTS_H - SEL_LABEL_H - 4;
            listH     = slotsRowY - listY - 4;

            int totalSlotsW = 9 * 20 - 2;
            int slotStartX  = leftPos + (imageWidth - totalSlotsW) / 2;
            for (int i = 0; i < 9; i++) slotAbsX[i] = slotStartX + i * 20;
            slotAbsY = slotsRowY + SEL_LABEL_H + 2;

            int searchY = topPos + TITLE_H + TABS_H + 4;
            searchBox = new EditBox(font, listX + 36, searchY, listW - 36, 14,
                    Component.literal("Search tags..."));
            searchBox.setMaxLength(64);
            searchBox.setResponder(text -> { filterTags(text); tagScrollOffset = 0; });
            addRenderableWidget(searchBox);

            addRenderableWidget(Button.builder(Component.literal("+"), btn -> enterCreateMode())
                    .pos(leftPos + imageWidth - 20, topPos + 4).size(16, 14).build());

            addRenderableWidget(Button.builder(Component.literal("Clear"), btn -> clearSlots())
                    .pos(leftPos + imageWidth / 2 - 46, topPos + imageHeight - 22).size(44, 16).build());
            addRenderableWidget(Button.builder(Component.literal("Export"), btn -> exportTag())
                    .pos(leftPos + imageWidth / 2 + 2,  topPos + imageHeight - 22).size(44, 16).build());

            loadTags();
        }
    }

    private void setupGrid() {
        int gridTop  = listY;
        int gridBot  = topPos + imageHeight - BOTTOM_H - 4;
        int gridH    = gridBot - gridTop;
        createRows   = Math.max(1, Math.min(9, gridH / 20));
        createSlotsY = gridTop + (gridH - createRows * 20) / 2;
        int totalW   = CREATE_COLS * 20 - 2;
        int startX   = leftPos + (imageWidth - totalW) / 2;
        for (int i = 0; i < CREATE_COLS; i++) createSlotX[i] = startX + i * 20;
    }

    // ─── Mode transitions ────────────────────────────────────────────────────

    private void enterCreateMode() {
        createMode = true;
        editMode   = false;
        Arrays.fill(createSlots, ItemStack.EMPTY);
        rebuildWidgets();
    }

    private void exitCreateMode() {
        createMode = false;
        rebuildWidgets();
    }

    private void enterEditMode(ResourceLocation tag) {
        editMode         = true;
        createMode       = false;
        editingTag       = tag;
        editScrollOffset = 0;
        loadEditItems();
        rebuildWidgets();
    }

    private void exitEditMode() {
        editMode   = false;
        editingTag = null;
        editItems.clear();
        rebuildWidgets();
    }

    // ─── Tag / item loading ──────────────────────────────────────────────────

    private void setType(int type) {
        tagType = type;
        if (editMode) {
            editScrollOffset = 0;
            loadEditItems();
        } else if (!createMode) {
            tagScrollOffset = 0;
            selectedTag     = null;
            loadTags();
        }
    }

    private void loadTags() {
        allTags.clear();
        try {
            var reg = Minecraft.getInstance().level.registryAccess();
            Stream<ResourceLocation> keys = switch (tagType) {
                case 1 -> reg.registryOrThrow(Registries.BLOCK).getTags().map(p -> p.getFirst().location());
                case 2 -> reg.registryOrThrow(Registries.FLUID).getTags().map(p -> p.getFirst().location());
                default -> reg.registryOrThrow(Registries.ITEM).getTags().map(p -> p.getFirst().location());
            };
            keys.sorted(Comparator.comparing(ResourceLocation::toString)).forEach(allTags::add);
        } catch (Exception e) {
            KubeJsRecipeEditor.LOGGER.error("Failed to load tags: {}", e.getMessage());
        }
        filterTags(searchBox != null ? searchBox.getValue() : "");
    }

    private void filterTags(String query) {
        filteredTags.clear();
        String q = query.toLowerCase();
        for (ResourceLocation rl : allTags)
            if (q.isEmpty() || rl.toString().contains(q)) filteredTags.add(rl);
    }

    private void loadEditItems() {
        editItems.clear();
        originalEditItems.clear();
        if (editingTag == null) return;
        try {
            var reg = Minecraft.getInstance().level.registryAccess();
            switch (tagType) {
                case 1 -> {
                    var key = TagKey.create(Registries.BLOCK, editingTag);
                    reg.registryOrThrow(Registries.BLOCK).getTag(key).ifPresent(named ->
                        named.forEach(h -> {
                            var item = h.value().asItem();
                            if (item != Items.AIR) editItems.add(new ItemStack(item));
                        })
                    );
                }
                case 2 -> { /* fluid→item mapping is complex; user can add manually */ }
                default -> {
                    var key = TagKey.create(Registries.ITEM, editingTag);
                    reg.registryOrThrow(Registries.ITEM).getTag(key).ifPresent(named ->
                        named.forEach(h -> editItems.add(new ItemStack(h.value())))
                    );
                }
            }
        } catch (Exception e) {
            KubeJsRecipeEditor.LOGGER.error("Failed to load items for tag {}: {}", editingTag, e.getMessage());
        }
        originalEditItems.addAll(editItems);
    }

    // ─── Rendering ───────────────────────────────────────────────────────────

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBg(g, partialTick, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fill(leftPos + 3, topPos + 3, leftPos + imageWidth + 3, topPos + imageHeight + 3, 0x55000000);
        g.fill(leftPos - 1, topPos - 1, leftPos + imageWidth + 1, topPos + imageHeight + 1, 0xFF555555);
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF1E1E1E);
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + TITLE_H, 0xFF2D2D2D);

        int tabY   = topPos + TITLE_H + 4;
        int tabW   = (imageWidth - PAD * 2 - 4) / 3;
        int tabHlX = leftPos + PAD + tagType * (tabW + 2);
        g.fill(tabHlX - 1, tabY - 1, tabHlX + tabW + 1, tabY + 15, 0x44FFFFFF);

        if (createMode || editMode) {
            for (int row = 0; row < createRows; row++)
                for (int col = 0; col < CREATE_COLS; col++)
                    g.fill(createSlotX[col], createSlotsY + row * 20,
                           createSlotX[col] + 18, createSlotsY + row * 20 + 18, 0xFF3C3C3C);
        } else {
            g.fill(listX, listY, listX + listW, listY + listH, 0xFF161616);
            for (int i = 0; i < 9; i++)
                g.fill(slotAbsX[i], slotAbsY, slotAbsX[i] + 18, slotAbsY + 18, 0xFF3C3C3C);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawCenteredString(font, title, imageWidth / 2, 8, 0xFFFFFF);

        if (createMode) {
            g.drawString(font, "Tag:", PAD, TITLE_H + TABS_H + 7, 0xFFAAAAAA, false);
        } else if (editMode) {
            g.drawString(font, "Editing:", PAD, TITLE_H + TABS_H + 7, 0xFFAAAAAA, false);
            int textX = PAD + font.width("Editing: ");
            String display = editingTag != null ? editingTag.toString() : "";
            int maxW = imageWidth - textX - PAD;
            while (font.width(display) > maxW && display.length() > 4)
                display = display.substring(0, display.length() - 1);
            g.drawString(font, display, textX, TITLE_H + TABS_H + 7, 0xFF55FFFF, false);
        } else {
            g.drawString(font, "Filter:", PAD, TITLE_H + TABS_H + 7, 0xFFAAAAAA, false);
            int selRelY = slotsRowY - topPos + 2;
            String selText  = selectedTag != null ? selectedTag.toString() : "(no tag selected)";
            int    selColor = selectedTag != null ? 0xFF55FFFF : 0xFF888888;
            g.drawString(font, "Tag: " + selText, PAD, selRelY, selColor, false);
        }

        if (!statusMessage.isEmpty()) {
            // Rendered above the button row to avoid overlap
            g.drawCenteredString(font, Component.literal(statusMessage),
                    imageWidth / 2, imageHeight - BOTTOM_H - 6, statusColor);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        if (createMode) {
            renderGridSlots(g, mouseX, mouseY, createSlots, createRows * CREATE_COLS);
        } else if (editMode) {
            renderEditSlots(g, mouseX, mouseY);
            renderEditScrollbar(g);
        } else {
            g.enableScissor(listX, listY, listX + listW, listY + listH);
            renderTagList(g, mouseX, mouseY);
            g.disableScissor();
            renderTagScrollbar(g);
            renderNormalSlots(g, mouseX, mouseY);
        }
    }

    private void renderTagList(GuiGraphics g, int mouseX, int mouseY) {
        int y = listY - tagScrollOffset;
        for (ResourceLocation rl : filteredTags) {
            if (y + ROW_H < listY) { y += ROW_H; continue; }
            if (y > listY + listH)  break;
            boolean sel   = rl.equals(selectedTag);
            boolean hover = mouseX >= listX && mouseX < listX + listW && mouseY >= y && mouseY < y + ROW_H;
            int bg = sel ? 0x44AAFFFF : (hover ? 0x33FFFFFF : 0);
            if (bg != 0) g.fill(listX, y, listX + listW, y + ROW_H, bg);
            g.drawString(font, rl.toString(), listX + 2, y + 2, sel ? 0xFF55FFFF : 0xFFCCCCCC, false);
            y += ROW_H;
        }
    }

    private void renderTagScrollbar(GuiGraphics g) {
        int total = filteredTags.size() * ROW_H;
        if (total <= listH) return;
        int barX = listX + listW - 5;
        int barH = Math.max(16, listH * listH / total);
        int barY = listY + (int) ((long) tagScrollOffset * (listH - barH) / Math.max(1, total - listH));
        g.fill(barX, listY, barX + 4, listY + listH, 0x55FFFFFF);
        g.fill(barX, barY, barX + 4, barY + barH, 0xCCFFFFFF);
    }

    private void renderNormalSlots(GuiGraphics g, int mouseX, int mouseY) {
        for (int i = 0; i < 9; i++) {
            if (addSlots[i].isEmpty()) continue;
            int ix = slotAbsX[i] + 1, iy = slotAbsY + 1;
            g.renderItem(addSlots[i], ix, iy);
            g.renderItemDecorations(font, addSlots[i], ix, iy);
            boolean hover = mouseX >= slotAbsX[i] && mouseX < slotAbsX[i] + 18
                         && mouseY >= slotAbsY && mouseY < slotAbsY + 18;
            if (hover) {
                g.fill(ix + 8, iy - 1, ix + 16, iy + 8, 0x88000000);
                g.drawString(font, "×", ix + 9, iy, 0xFFFF5555, false);
            }
        }
        for (int i = 0; i < 9; i++) {
            if (!addSlots[i].isEmpty() && mouseX >= slotAbsX[i] && mouseX < slotAbsX[i] + 18
                    && mouseY >= slotAbsY && mouseY < slotAbsY + 18) {
                g.renderTooltip(font, addSlots[i], mouseX, mouseY);
                break;
            }
        }
    }

    private void renderGridSlots(GuiGraphics g, int mouseX, int mouseY, ItemStack[] slots, int total) {
        for (int i = 0; i < total && i < slots.length; i++) {
            if (slots[i].isEmpty()) continue;
            int col = i % CREATE_COLS, row = i / CREATE_COLS;
            int sx = createSlotX[col] + 1, sy = createSlotsY + row * 20 + 1;
            g.renderItem(slots[i], sx, sy);
            g.renderItemDecorations(font, slots[i], sx, sy);
            boolean hover = mouseX >= createSlotX[col] && mouseX < createSlotX[col] + 18
                         && mouseY >= createSlotsY + row * 20 && mouseY < createSlotsY + row * 20 + 18;
            if (hover) {
                g.fill(sx + 8, sy - 1, sx + 16, sy + 8, 0x88000000);
                g.drawString(font, "×", sx + 9, sy, 0xFFFF5555, false);
            }
        }
        for (int i = 0; i < total && i < slots.length; i++) {
            if (slots[i].isEmpty()) continue;
            int col = i % CREATE_COLS, row = i / CREATE_COLS;
            if (mouseX >= createSlotX[col] && mouseX < createSlotX[col] + 18
             && mouseY >= createSlotsY + row * 20 && mouseY < createSlotsY + row * 20 + 18) {
                g.renderTooltip(font, slots[i], mouseX, mouseY);
                break;
            }
        }
    }

    private void renderEditSlots(GuiGraphics g, int mouseX, int mouseY) {
        int visTotal = createRows * CREATE_COLS;
        for (int vis = 0; vis < visTotal; vis++) {
            int absIdx = editScrollOffset * CREATE_COLS + vis;
            if (absIdx >= editItems.size()) break;
            ItemStack stack = editItems.get(absIdx);
            if (stack.isEmpty()) continue;
            int col = vis % CREATE_COLS, row = vis / CREATE_COLS;
            int sx = createSlotX[col] + 1, sy = createSlotsY + row * 20 + 1;
            g.renderItem(stack, sx, sy);
            g.renderItemDecorations(font, stack, sx, sy);
            boolean hover = mouseX >= createSlotX[col] && mouseX < createSlotX[col] + 18
                         && mouseY >= createSlotsY + row * 20 && mouseY < createSlotsY + row * 20 + 18;
            if (hover) {
                g.fill(sx + 8, sy - 1, sx + 16, sy + 8, 0x88000000);
                g.drawString(font, "×", sx + 9, sy, 0xFFFF5555, false);
            }
        }
        for (int vis = 0; vis < visTotal; vis++) {
            int absIdx = editScrollOffset * CREATE_COLS + vis;
            if (absIdx >= editItems.size()) break;
            if (editItems.get(absIdx).isEmpty()) continue;
            int col = vis % CREATE_COLS, row = vis / CREATE_COLS;
            if (mouseX >= createSlotX[col] && mouseX < createSlotX[col] + 18
             && mouseY >= createSlotsY + row * 20 && mouseY < createSlotsY + row * 20 + 18) {
                g.renderTooltip(font, editItems.get(absIdx), mouseX, mouseY);
                break;
            }
        }
    }

    private void renderEditScrollbar(GuiGraphics g) {
        int totalRows = Math.max(1, (editItems.size() + CREATE_COLS - 1) / CREATE_COLS);
        if (totalRows <= createRows) return;
        int gridH  = createRows * 20;
        int barX   = leftPos + imageWidth - PAD - 5;
        int barH   = Math.max(16, gridH * createRows / totalRows);
        int barY   = createSlotsY + (int) ((long) editScrollOffset * (gridH - barH) / (totalRows - createRows));
        g.fill(barX, createSlotsY, barX + 4, createSlotsY + gridH, 0x55FFFFFF);
        g.fill(barX, barY, barX + 4, barY + barH, 0xCCFFFFFF);
    }

    // ─── Input ───────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int mouseX = (int) mx, mouseY = (int) my;

        if (button == 0) {
            // Tag-list scrollbar
            if (!createMode && !editMode) {
                int total = filteredTags.size() * ROW_H;
                int barX  = listX + listW - 5;
                if (total > listH && mouseX >= barX && mouseX <= barX + 4
                        && mouseY >= listY && mouseY <= listY + listH) {
                    scrollbarDragging    = DragTarget.TAG_LIST;
                    scrollbarDragStartY  = mouseY;
                    scrollbarDragStartOff = tagScrollOffset;
                    return true;
                }
            }
            // Edit-grid scrollbar
            if (editMode) {
                int totalRows = Math.max(1, (editItems.size() + CREATE_COLS - 1) / CREATE_COLS);
                int gridH     = createRows * 20;
                int barX      = leftPos + imageWidth - PAD - 5;
                if (totalRows > createRows && mouseX >= barX && mouseX <= barX + 4
                        && mouseY >= createSlotsY && mouseY <= createSlotsY + gridH) {
                    scrollbarDragging    = DragTarget.EDIT_GRID;
                    scrollbarDragStartY  = mouseY;
                    scrollbarDragStartOff = editScrollOffset;
                    return true;
                }
            }
        }

        if (createMode) {
            int total = createRows * CREATE_COLS;
            for (int i = 0; i < total && i < createSlots.length; i++) {
                if (createSlots[i].isEmpty()) continue;
                int col = i % CREATE_COLS, row = i / CREATE_COLS;
                int sx = createSlotX[col], sy = createSlotsY + row * 20;
                if (mouseX >= sx + 9 && mouseX < sx + 17 && mouseY >= sy - 1 && mouseY < sy + 8) {
                    createSlots[i] = ItemStack.EMPTY;
                    return true;
                }
            }
        } else if (editMode) {
            int visTotal = createRows * CREATE_COLS;
            for (int vis = 0; vis < visTotal; vis++) {
                int absIdx = editScrollOffset * CREATE_COLS + vis;
                if (absIdx >= editItems.size()) break;
                if (editItems.get(absIdx).isEmpty()) continue;
                int col = vis % CREATE_COLS, row = vis / CREATE_COLS;
                int sx = createSlotX[col], sy = createSlotsY + row * 20;
                if (mouseX >= sx + 9 && mouseX < sx + 17 && mouseY >= sy - 1 && mouseY < sy + 8) {
                    editItems.remove(absIdx);
                    return true;
                }
            }
        } else {
            for (int i = 0; i < 9; i++) {
                if (!addSlots[i].isEmpty()
                        && mouseX >= slotAbsX[i] + 9 && mouseX < slotAbsX[i] + 17
                        && mouseY >= slotAbsY - 1 && mouseY < slotAbsY + 8) {
                    addSlots[i] = ItemStack.EMPTY;
                    return true;
                }
            }
            if (mouseX >= listX && mouseX < listX + listW && mouseY >= listY && mouseY < listY + listH) {
                int idx = (mouseY - listY + tagScrollOffset) / ROW_H;
                if (idx >= 0 && idx < filteredTags.size()) {
                    long now = System.currentTimeMillis();
                    if (idx == lastTagClickIdx && now - lastTagClickTime < 400) {
                        enterEditMode(filteredTags.get(idx));
                        lastTagClickIdx = -1;
                    } else {
                        selectedTag      = filteredTags.get(idx);
                        lastTagClickIdx  = idx;
                        lastTagClickTime = now;
                    }
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) scrollbarDragging = DragTarget.NONE;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == 0 && scrollbarDragging != DragTarget.NONE) {
            int delta = (int) my - scrollbarDragStartY;
            if (scrollbarDragging == DragTarget.TAG_LIST) {
                int total  = filteredTags.size() * ROW_H;
                int barH   = Math.max(16, listH * listH / total);
                int trackH = listH - barH;
                if (trackH > 0) {
                    int maxScroll = Math.max(0, total - listH);
                    tagScrollOffset = Math.max(0, Math.min(
                            scrollbarDragStartOff + (int) ((long) delta * maxScroll / trackH),
                            maxScroll));
                }
            } else {
                int totalRows    = Math.max(1, (editItems.size() + CREATE_COLS - 1) / CREATE_COLS);
                int gridH        = createRows * 20;
                int barH         = Math.max(16, gridH * createRows / totalRows);
                int trackH       = gridH - barH;
                int maxScrollRow = Math.max(0, totalRows - createRows);
                if (trackH > 0) {
                    editScrollOffset = Math.max(0, Math.min(
                            scrollbarDragStartOff + (int) ((long) delta * maxScrollRow / trackH),
                            maxScrollRow));
                }
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (editMode) {
            int totalRows    = Math.max(1, (editItems.size() + CREATE_COLS - 1) / CREATE_COLS);
            int maxScrollRow = Math.max(0, totalRows - createRows);
            editScrollOffset = Math.max(0, Math.min(editScrollOffset + (int) -scrollY, maxScrollRow));
            return true;
        }
        if (!createMode && mx >= listX && mx < listX + listW && my >= listY && my < listY + listH) {
            int total     = filteredTags.size() * ROW_H;
            int maxScroll = Math.max(0, total - listH);
            tagScrollOffset = Math.max(0, Math.min(tagScrollOffset + (int)(-scrollY * ROW_H * 3), maxScroll));
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
        // Route to focused EditBox first; then consume to prevent AbstractContainerScreen
        // from closing this screen when the KRE keybind key is pressed inside it.
        var focused = getFocused();
        if (focused != null && focused.keyPressed(keyCode, scanCode, modifiers)) return true;
        return true; // consume — don't let inventory-close or keybind fire
    }

    // ─── Actions ─────────────────────────────────────────────────────────────

    private void clearSlots() {
        Arrays.fill(addSlots, ItemStack.EMPTY);
        statusMessage = "";
    }

    private void goHome() {
        GuiSessionState.setLastScreenType(GuiSessionState.LastScreenType.HOME);
        GuiSessionState.setLastCategory(null);
        minecraft.setScreen(new ModMenuScreen());
    }

    private void exportTag() {
        if (selectedTag == null) {
            statusMessage = "Select a tag first";
            statusColor   = 0xFF5555;
            return;
        }
        writeTagJson(selectedTag, addSlots, addSlots.length, false);
    }

    private void doCreate() {
        if (tagNameBox == null) return;
        String name  = tagNameBox.getValue().trim();
        ResourceLocation tagId = ResourceLocation.tryParse(name);
        if (tagId == null || !name.contains(":")) {
            statusMessage = "Format: namespace:path";
            statusColor   = 0xFF5555;
            return;
        }
        writeTagJson(tagId, createSlots, createRows * CREATE_COLS, false);
        if (statusColor == 0x55FF55) exitCreateMode();
    }

    // ─── Tag save logic (smart diff) ─────────────────────────────────────────

    private void saveEditedTag() {
        if (editingTag == null) return;

        Set<String> currentIds  = collectIds(editItems);
        Set<String> originalIds = collectIds(originalEditItems);
        Set<String> toAdd    = diff(currentIds, originalIds);
        Set<String> toRemove = diff(originalIds, currentIds);

        if (toAdd.isEmpty() && toRemove.isEmpty()) {
            statusMessage = "No changes";
            statusColor   = 0xFFAAAAAA;
            exitEditMode();
            return;
        }
        try {
            Set<String> existingAdditions = readExistingAdditions(editingTag);
            Set<String> existingRemovals  = readExistingRemovals(editingTag);

            if (toRemove.isEmpty()) {
                // Additions only — append-only JSON; cancel any conflicting removals
                Set<String> newAdditions = new LinkedHashSet<>(existingAdditions);
                Set<String> newRemovals  = new LinkedHashSet<>(existingRemovals);
                toAdd.forEach(id -> { newRemovals.remove(id); newAdditions.add(id); });
                writeJsonFile(editingTag, newAdditions);
                writeScriptFile(editingTag, newRemovals, null); // keep removals, no full-replace
            } else {
                // Removals present — use removeAll+add for reliability against sub-tag inclusions.
                // The script takes full ownership of this tag; delete the JSON.
                deleteJsonFile(editingTag);
                writeScriptFile(editingTag, Set.of(), currentIds); // full-replace mode
            }
            statusMessage = "Saved — run /reload";
            statusColor   = 0x55FF55;
            exitEditMode();
        } catch (Exception e) {
            statusMessage = "Save failed: " + e.getMessage();
            statusColor   = 0xFF5555;
        }
    }

    private Set<String> collectIds(List<ItemStack> items) {
        Set<String> ids = new LinkedHashSet<>();
        for (ItemStack s : items) {
            if (s.isEmpty()) continue;
            String id = getRegistryId(s);
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> r = new LinkedHashSet<>(a); r.removeAll(b); return r;
    }

    // ── Read existing modifications ───────────────────────────────────────────

    private Set<String> readExistingAdditions(ResourceLocation tagId) {
        Path f = jsonFile(tagId);
        if (!Files.exists(f)) return new LinkedHashSet<>();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            Set<String> ids = new LinkedHashSet<>();
            if (root.has("values"))
                root.getAsJsonArray("values").forEach(el -> ids.add(el.getAsString()));
            return ids;
        } catch (Exception e) { return new LinkedHashSet<>(); }
    }

    private Set<String> readExistingRemovals(ResourceLocation tagId) {
        Path f = scriptFile();
        if (!Files.exists(f)) return new LinkedHashSet<>();
        try {
            Set<String> ids = new LinkedHashSet<>();
            String prefix = "event.remove('" + tagId + "', '";
            for (String line : Files.readAllLines(f)) {
                String t = line.strip();
                if (t.startsWith(prefix) && t.endsWith("')"))
                    ids.add(t.substring(prefix.length(), t.length() - 2));
            }
            return ids;
        } catch (Exception e) { return new LinkedHashSet<>(); }
    }

    // ── Write files ───────────────────────────────────────────────────────────

    /**
     * Writes (or deletes) the JSON additions file for {@code tagId}.
     * If {@code additions} is empty the file is deleted and empty parent dirs are pruned.
     */
    private void writeJsonFile(ResourceLocation tagId, Set<String> additions) throws Exception {
        Path f = jsonFile(tagId);
        if (additions.isEmpty()) { Files.deleteIfExists(f); pruneEmpty(f.getParent(), 4); return; }
        Files.createDirectories(f.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("replace", false);
        JsonArray arr = new JsonArray();
        additions.forEach(arr::add);
        root.add("values", arr);
        Files.writeString(f, new GsonBuilder().setPrettyPrinting().create().toJson(root));
    }

    /**
     * Updates the per-namespace/type removal script for this tag.
     *
     * <p>Two modes:
     * <ul>
     *   <li>If {@code fullWanted} is null: write individual {@code event.remove()} lines
     *       from {@code directRemovals} (classic approach for additions-only scenarios).</li>
     *   <li>If {@code fullWanted} is non-null: write {@code event.removeAll()} +
     *       {@code event.add()} with the complete desired list (handles sub-tag inclusions).</li>
     * </ul>
     */
    private void writeScriptFile(ResourceLocation tagId, Set<String> directRemovals,
                                  Set<String> fullWanted) throws Exception {
        Path f = scriptFile();

        // Parse all existing sections from the script (keyed by tagId string)
        java.util.Map<String, String> sections = parseScriptSections(f);

        String key = tagId.toString();
        boolean noContent = (fullWanted == null ? directRemovals : fullWanted).isEmpty();
        if (noContent) {
            sections.remove(key);
        } else {
            StringBuilder sb = new StringBuilder();
            if (fullWanted != null) {
                // removeAll + add (reliable against sub-tag inclusions)
                sb.append("\n    // ").append(key).append(" [managed]\n");
                sb.append("    event.removeAll('").append(key).append("')\n");
                sb.append("    event.add('").append(key).append("', [\n");
                fullWanted.forEach(id -> sb.append("        '").append(id).append("',\n"));
                sb.append("    ])\n");
            } else {
                // Individual removes only
                sb.append("\n    // ").append(key).append("\n");
                directRemovals.forEach(id ->
                    sb.append("    event.remove('").append(key).append("', '").append(id).append("')\n"));
            }
            sections.put(key, sb.toString());
        }

        if (sections.isEmpty()) {
            Files.deleteIfExists(f);
            pruneEmpty(f.getParent(), 3);
            return;
        }
        Files.createDirectories(f.getParent());
        StringBuilder out = new StringBuilder(
            "// Auto-generated by KubeJS Recipe Editor\n// Tag modifications\n\n"
            + "ServerEvents.tags('" + typeStr() + "', event => {");
        sections.values().forEach(out::append);
        out.append("\n})\n");
        Files.writeString(f, out.toString());
    }

    /** Parse existing script sections into a map of tagId → section-body. */
    private java.util.Map<String, String> parseScriptSections(Path f) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        if (!Files.exists(f)) return map;
        try {
            String content = Files.readString(f);
            // Each section starts with "    // tagId" or "    // tagId [managed]"
            String[] parts = content.split("(?=\\n    // [a-z0-9_.-]+:[a-z0-9_./-]+)");
            for (String part : parts) {
                String t = part.strip();
                if (t.startsWith("// ") || t.isEmpty() || t.startsWith("ServerEvents")) continue;
                // Extract tagId from first line  "    // namespace:path [optional]"
                int nl = part.indexOf('\n', part.indexOf("// ") + 3);
                if (nl == -1) continue;
                String header = part.substring(part.indexOf("// ") + 3, nl).trim();
                String tagKey = header.endsWith("[managed]")
                        ? header.substring(0, header.length() - "[managed]".length()).trim()
                        : header;
                map.put(tagKey, part.stripTrailing());
            }
        } catch (Exception e) { /* return empty map */ }
        return map;
    }

    private void deleteJsonFile(ResourceLocation tagId) throws Exception {
        Path f = jsonFile(tagId);
        Files.deleteIfExists(f);
        pruneEmpty(f.getParent(), 4);
    }

    /** Deletes {@code dir} if empty, then walks up {@code levels} parent directories doing the same. */
    private void pruneEmpty(Path dir, int levels) {
        for (int i = 0; i < levels && dir != null; i++, dir = dir.getParent()) {
            try {
                if (Files.isDirectory(dir)) {
                    try (var s = Files.list(dir)) { if (s.findAny().isEmpty()) Files.delete(dir); else break; }
                }
            } catch (Exception ignored) {}
        }
    }

    private String typeStr() {
        return switch (tagType) { case 1 -> "block"; case 2 -> "fluid"; default -> "item"; };
    }

    private Path jsonFile(ResourceLocation tagId) {
        return gameDir().resolve("kubejs/data/" + tagId.getNamespace()
                + "/tags/" + typeStr() + "/" + tagId.getPath() + ".json");
    }

    private Path scriptFile() {
        return gameDir().resolve("kubejs/server_scripts/tags/removals/"
                + editingTag.getNamespace() + "/" + typeStr() + ".js");
    }

    private String getRegistryId(ItemStack stack) {
        return switch (tagType) {
            case 1 -> {
                if (stack.getItem() instanceof BlockItem bi)
                    yield bi.getBlock().builtInRegistryHolder().key().location().toString();
                yield null;
            }
            case 2 -> {
                if (stack.getItem() instanceof BucketItem bi) {
                    var holder = bi.content.builtInRegistryHolder();
                    if (holder.key() != null) yield holder.key().location().toString();
                }
                yield stack.getItem().builtInRegistryHolder().key().location().toString();
            }
            default -> stack.getItem().builtInRegistryHolder().key().location().toString();
        };
    }

    /** Used by normal-mode Export and Create mode. Appends items, cancels conflicting removals. */
    private void writeTagJson(ResourceLocation tagId, ItemStack[] slots, int slotCount, boolean ignored) {
        List<String> newIds = new ArrayList<>();
        for (int i = 0; i < slotCount && i < slots.length; i++) {
            if (slots[i].isEmpty()) continue;
            String id = getRegistryId(slots[i]);
            if (id != null) newIds.add(id);
        }
        if (newIds.isEmpty()) { statusMessage = "Add at least one item"; statusColor = 0xFF5555; return; }
        try {
            // Merge with existing additions
            Set<String> additions = readExistingAdditions(tagId);
            additions.addAll(newIds);
            writeJsonFile(tagId, additions);

            // Cancel any removal-script entries for the items we just added
            Set<String> removals = readExistingRemovals(tagId);
            boolean changed = newIds.stream().map(removals::remove).reduce(false, Boolean::logicalOr);
            if (changed) writeScriptFile(tagId, removals, null);

            String rel = gameDir().relativize(jsonFile(tagId)).toString().replace('\\', '/');
            statusMessage = "Saved to " + rel;
            statusColor   = 0x55FF55;
        } catch (Exception e) {
            statusMessage = "Export failed: " + e.getMessage();
            statusColor   = 0xFF5555;
        }
    }

    private Path gameDir() { return Minecraft.getInstance().gameDirectory.toPath(); }
}
