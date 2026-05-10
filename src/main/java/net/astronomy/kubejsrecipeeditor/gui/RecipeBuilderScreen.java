package net.astronomy.kubejsrecipeeditor.gui;

import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.core.RegistryAccess;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.astronomy.kubejsrecipeeditor.KubeJsRecipeEditor;
import net.astronomy.kubejsrecipeeditor.export.IngredientFormatter;
import net.astronomy.kubejsrecipeeditor.jei.JeiIntegration;
import net.astronomy.kubejsrecipeeditor.jei.SlotCapturingLayoutBuilder;

import javax.annotation.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class RecipeBuilderScreen extends AbstractContainerScreen<RecipeBuilderMenu> {
    /** Vanilla GUI atlas sprite — same frame as inventory/crafting slots (empty slot, no items). */
    private static final ResourceLocation VANILLA_SLOT_SPRITE =
            ResourceLocation.withDefaultNamespace("container/slot");

    /** Panel fill behind JEI art (visible through transparency; replaces flat black when no texture). */
    private static final int RECIPE_PANEL_BASE_COLOR = 0xFFC6C6C6;

    private static final int TOP_BAR      = 24;
    private static final int BOTTOM_BAR   = 28;
    private static final int PADDING      = 8;
    private static final int EXTRA_FIELD_H = 22; // extra row for composting/fuel fields

    /** JEI recipe cell step for crafting-grid inference */
    private static final int CRAFT_CELL = 18;

    private final IRecipeCategory<?> category;
    private final @Nullable RecipeTemplate capturedLayout;
    private final boolean isCompostingCategory;
    private final boolean isFuelCategory;

    private final List<SlotData> slots = new ArrayList<>();

    private int recipeX, recipeY;
    private String statusMessage = "";
    private int    statusColor   = 0xFFFFFF;

    // Extra category-specific fields
    private int compostChancePct = 30;  // 1–100 %
    private int fuelBurnSecs    = 10;  // seconds; exported as ticks (×20)

    // ─── Drag state ───────────────────────────────────────────────────────────
    private SlotData  pendingDragSlot    = null;
    private SlotData  draggingSourceSlot = null;
    private ItemStack draggingItem       = ItemStack.EMPTY;
    private boolean   isDragging         = false;
    private double    dragStartX, dragStartY;

    // ─── Popup ────────────────────────────────────────────────────────────────
    private TagSelectionPopup activePopup = null;

    // ─── Variable slots ───────────────────────────────────────────────────────
    /** All INPUT captured slots from template, sorted top-left → bottom-right. */
    private final List<SlotCapturingLayoutBuilder.CapturedSlot> allCapturedInputSlots = new ArrayList<>();
    /** How many input slots are currently visible (grows via the "+" button). */
    private int activeInputSlotCount = 0;
    private int maxInputSlotCount    = 0;
    private @Nullable Button addSlotButton;

    // ─── Extra params ─────────────────────────────────────────────────────────
    /** Current user-edited values for codec-detected extra params. */
    private final Map<String, String> extraParamValues = new LinkedHashMap<>();

    // ═══════════════════════════════════════════════════════════════════════════

    public RecipeBuilderScreen(RecipeBuilderMenu menu, Inventory inventory, IRecipeCategory<?> category) {
        super(menu, inventory, Component.literal(category.getTitle().getString()));
        this.category = category;
        this.capturedLayout = RecipeTemplateRegistry.INSTANCE.get(category.getRecipeType()).orElse(null);
        this.isCompostingCategory = detectComposting(category);
        this.isFuelCategory       = detectFuel(category);

        // Slot-count range (stays valid across resize-triggered init() calls)
        this.activeInputSlotCount = capturedLayout != null ? capturedLayout.minInputSlots() : 0;
        this.maxInputSlotCount    = capturedLayout != null ? capturedLayout.maxInputSlots() : 0;

        // Populate extra param values from template defaults (only on first construction)
        if (capturedLayout != null) {
            capturedLayout.extraParams().forEach(p -> extraParamValues.put(p.key(), p.defaultValueStr()));
        }

        int bgW = recipePanelBgWidth();
        int bgH = recipePanelBgHeight();
        int extraParamCount = capturedLayout != null ? capturedLayout.extraParams().size() : 0;
        int extraRows = (isCompostingCategory || isFuelCategory ? 1 : 0) + extraParamCount;
        this.imageWidth  = Math.max(240, bgW + PADDING * 2);
        this.imageHeight = TOP_BAR + PADDING + bgH + PADDING
                + (extraRows > 0 ? EXTRA_FIELD_H * extraRows + 4 : 10) + BOTTOM_BAR;
    }

    private static boolean detectComposting(IRecipeCategory<?> cat) {
        String uid   = cat.getRecipeType().getUid().toString();
        String title = cat.getTitle().getString();
        return uid.contains("composting") || title.equalsIgnoreCase("composting");
    }

    private static boolean detectFuel(IRecipeCategory<?> cat) {
        String uid   = cat.getRecipeType().getUid().toString();
        String title = cat.getTitle().getString();
        return uid.contains("fuel") || title.equalsIgnoreCase("fuel");
    }

    public int getWinX() { return leftPos; }
    public int getWinY() { return topPos; }
    public int getWinW() { return imageWidth; }
    public int getWinH() { return imageHeight; }

    // ─── Init ─────────────────────────────────────────────────────────────────

    /**
     * Prefer the live JEI category drawable (same as in-game JEI); fall back to the captured template.
     */
    @SuppressWarnings("removal")
    private @Nullable IDrawable resolveDrawableBackground() {
        IDrawable live = category.getBackground();
        if (live != null) {
            return live;
        }
        if (capturedLayout != null) {
            return capturedLayout.background();
        }
        return null;
    }

    @SuppressWarnings("removal")
    private @Nullable IDrawable resolveDrawableIcon() {
        if (capturedLayout != null && capturedLayout.icon() != null) {
            return capturedLayout.icon();
        }
        return category.getIcon();
    }

    private int recipePanelBgWidth() {
        IDrawable bg = resolveDrawableBackground();
        if (bg != null) {
            return bg.getWidth();
        }
        return category.getWidth();
    }

    private int recipePanelBgHeight() {
        IDrawable bg = resolveDrawableBackground();
        if (bg != null) {
            return bg.getHeight();
        }
        return category.getHeight();
    }

    /** True when exporting uses shaped / shapeless crafting and slot positions form a grid. */
    private boolean usesCraftingInputGrid() {
        ResourceLocation uid = category.getRecipeType().getUid();
        if (!"minecraft".equals(uid.getNamespace())) return false;
        String path = uid.getPath();
        return "crafting".equals(path)
                || "crafting_shaped".equals(path)
                || "crafting_shapeless".equals(path);
    }

    @Override
    protected void init() {
        super.init();
        slots.clear();
        statusMessage  = "";
        statusColor    = 0xFFFFFF;
        activePopup    = null;
        resetDrag();

        int cw = recipePanelBgWidth();
        recipeX = leftPos + (imageWidth - cw) / 2;
        recipeY = topPos  + TOP_BAR + PADDING;

        buildSlotsFromCapture();

        // Home button
        addRenderableWidget(Button.builder(Component.literal("⌂"), btn -> goHome())
                .pos(leftPos + 4, topPos + 4).size(20, 16).build());

        // Variable-slot "+" button (positioned on the first hidden slot)
        if (maxInputSlotCount > (capturedLayout != null ? capturedLayout.minInputSlots() : 0)) {
            addSlotButton = Button.builder(Component.literal("+"), btn -> addNextInputSlot())
                    .pos(0, 0).size(14, 14).build();
            addRenderableWidget(addSlotButton);
            rebuildAddSlotButton();
        }

        // Extra field buttons for composting / fuel (shift-click = step 10)
        if (isCompostingCategory || isFuelCategory) {
            int fy = topPos + TOP_BAR + PADDING + recipePanelBgHeight() + PADDING + 4;
            int cx = leftPos + imageWidth / 2;
            addRenderableWidget(Button.builder(Component.literal("-"),
                            btn -> decrementField(Screen.hasShiftDown()))
                    .pos(cx - 28, fy).size(14, 14).build());
            addRenderableWidget(Button.builder(Component.literal("+"),
                            btn -> incrementField(Screen.hasShiftDown()))
                    .pos(cx + 14, fy).size(14, 14).build());
        }

        // Extra codec-param widgets (INT/FLOAT: -/+; BOOLEAN: toggle; STRING: read-only)
        if (capturedLayout != null && !capturedLayout.extraParams().isEmpty()) {
            int baseRow = (isCompostingCategory || isFuelCategory ? 1 : 0);
            int cx = leftPos + imageWidth / 2;
            for (int i = 0; i < capturedLayout.extraParams().size(); i++) {
                ExtraParam ep = capturedLayout.extraParams().get(i);
                int fy = topPos + TOP_BAR + PADDING + recipePanelBgHeight() + PADDING
                        + (baseRow + i) * EXTRA_FIELD_H + 4;
                switch (ep.type()) {
                    case INT, FLOAT -> {
                        addRenderableWidget(Button.builder(Component.literal("-"),
                                        btn -> adjustExtraParam(ep, -1, Screen.hasShiftDown()))
                                .pos(cx - 28, fy).size(14, 14).build());
                        addRenderableWidget(Button.builder(Component.literal("+"),
                                        btn -> adjustExtraParam(ep, +1, Screen.hasShiftDown()))
                                .pos(cx + 14, fy).size(14, 14).build());
                    }
                    case BOOLEAN -> addRenderableWidget(
                            Button.builder(Component.literal(extraParamValues.getOrDefault(ep.key(), ep.defaultValueStr())),
                                    btn -> toggleExtraParam(ep, btn))
                                    .pos(cx - 14, fy).size(28, 14).build());
                    case STRING -> {} // display-only
                }
            }
        }

        // Bottom buttons
        addRenderableWidget(Button.builder(Component.literal("Clear"), btn -> clearSlots())
                .pos(leftPos + imageWidth / 2 - 46, topPos + imageHeight - 22).size(44, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Export"), btn -> exportRecipe())
                .pos(leftPos + imageWidth / 2 + 2, topPos + imageHeight - 22).size(44, 16).build());
    }

    private void decrementField(boolean shift) {
        int step = shift ? 10 : 1;
        if (isCompostingCategory) compostChancePct = Math.max(1,   compostChancePct - step);
        if (isFuelCategory)       fuelBurnSecs    = Math.max(1,    fuelBurnSecs    - step);
    }

    private void incrementField(boolean shift) {
        int step = shift ? 10 : 1;
        if (isCompostingCategory) compostChancePct = Math.min(100,  compostChancePct + step);
        if (isFuelCategory)       fuelBurnSecs    = Math.min(1638, fuelBurnSecs    + step);
    }

    private void buildSlotsFromCapture() {
        if (capturedLayout == null) {
            if (JeiIntegration.isRuntimeAvailable()) {
                statusMessage = "Layout unavailable — try Resources Reload (F3+T)";
                statusColor = 0xFFFFAA55;
            }
            KubeJsRecipeEditor.LOGGER.debug("No captured layout for {}", category.getRecipeType().getUid());
            return;
        }

        statusMessage = "";
        statusColor   = 0xFFFFFF;

        List<SlotCapturingLayoutBuilder.CapturedSlot> caps = capturedLayout.slots();

        // Rebuild allCapturedInputSlots sorted top-left → bottom-right
        allCapturedInputSlots.clear();
        caps.stream()
                .filter(s -> s.role() == RecipeIngredientRole.INPUT)
                .sorted(Comparator.comparingInt(SlotCapturingLayoutBuilder.CapturedSlot::y)
                        .thenComparingInt(SlotCapturingLayoutBuilder.CapturedSlot::x))
                .forEach(allCapturedInputSlots::add);

        // Clamp activeInputSlotCount to valid range (handles re-init on resize)
        activeInputSlotCount = Math.max(capturedLayout.minInputSlots(),
                Math.min(activeInputSlotCount, allCapturedInputSlots.size()));

        int minInputX = allCapturedInputSlots.stream().mapToInt(SlotCapturingLayoutBuilder.CapturedSlot::x).min().orElse(0);
        int minInputY = allCapturedInputSlots.stream().mapToInt(SlotCapturingLayoutBuilder.CapturedSlot::y).min().orElse(0);
        boolean inferCraftingGrid = usesCraftingInputGrid() && !allCapturedInputSlots.isEmpty();

        int activeInputAdded = 0;
        for (SlotCapturingLayoutBuilder.CapturedSlot cap : caps) {
            if (cap.role() == RecipeIngredientRole.INPUT) {
                // Only add up to activeInputSlotCount input slots (sorted order preserved
                // because allCapturedInputSlots was sorted before we began iterating caps)
                int idxInSorted = allCapturedInputSlots.indexOf(cap);
                if (idxInSorted >= activeInputSlotCount) continue;
            }
            int absX = recipeX + cap.x();
            int absY = recipeY + cap.y();
            int row = 0, col = 0;
            if (inferCraftingGrid && cap.role() == RecipeIngredientRole.INPUT) {
                col = Math.max(0, (cap.x() - minInputX + CRAFT_CELL / 2) / CRAFT_CELL);
                row = Math.max(0, (cap.y() - minInputY + CRAFT_CELL / 2) / CRAFT_CELL);
            }
            slots.add(new SlotData(absX, absY, CRAFT_CELL, CRAFT_CELL, cap.role(), row, col));
        }
    }

    // ─── Rendering ────────────────────────────────────────────────────────────

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBg(g, partialTick, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        // Window chrome
        g.fill(leftPos + 3, topPos + 3, leftPos + imageWidth + 3, topPos + imageHeight + 3, 0x55000000);
        g.fill(leftPos - 1, topPos - 1, leftPos + imageWidth + 1, topPos + imageHeight + 1, 0xFF555555);
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF1E1E1E);
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + TOP_BAR, 0xFF2D2D2D);

        int pw = recipePanelBgWidth();
        int ph = recipePanelBgHeight();
        g.fill(recipeX, recipeY, recipeX + pw, recipeY + ph, RECIPE_PANEL_BASE_COLOR);

        // JEI category background texture only (no setRecipe / ghost ingredients / animated progress).
        IDrawable jeiBg = resolveDrawableBackground();
        if (jeiBg != null) {
            try {
                jeiBg.draw(g, recipeX, recipeY);
            } catch (Exception ignored) {}
        }
        
        // Draw dynamic recipe UI elements (arrows, flames, progress bars)
        if (capturedLayout != null && capturedLayout.exampleRecipe() != null) {
            try {
                @SuppressWarnings("unchecked")
                IRecipeCategory<Object> rawCat = (IRecipeCategory<Object>) category;
                
                IRecipeSlotsView dummyView = (IRecipeSlotsView) java.lang.reflect.Proxy.newProxyInstance(
                        IRecipeSlotsView.class.getClassLoader(),
                        new Class<?>[]{IRecipeSlotsView.class},
                        (proxy, method, args) -> {
                            if (method.getReturnType().equals(java.util.List.class)) return java.util.List.of();
                            if (method.getReturnType().equals(java.util.Optional.class)) return java.util.Optional.empty();
                            if (method.getReturnType() == boolean.class) return false;
                            if (method.getReturnType() == int.class) return 0;
                            return null;
                        }
                );

                g.pose().pushPose();
                g.pose().translate(recipeX, recipeY, 0);
                rawCat.draw(capturedLayout.exampleRecipe(), dummyView, g, mouseX - recipeX, mouseY - recipeY);
                g.pose().popPose();
            } catch (Exception ex) {
                // Ignore draw errors
            }
        }
        IDrawable catIcon = resolveDrawableIcon();
        if (catIcon != null && recipeY >= topPos + 16) {
            try {
                catIcon.draw(g, recipeX + 4, recipeY - 14);
            } catch (Exception ignored) {}
        }

        // Interactive slot contents
        for (SlotData slot : slots) {
            if (isDragging && slot == draggingSourceSlot) continue;
            renderSlot(g, slot, mouseX, mouseY);
        }

        // Dragged item follows cursor
        if (isDragging && !draggingItem.isEmpty()) {
            g.renderItem(draggingItem, mouseX - 8, mouseY - 8);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawCenteredString(font, title, imageWidth / 2, 8, 0xFFFFFF);

        // Composting / fuel row
        if (isCompostingCategory || isFuelCategory) {
            int fy = TOP_BAR + PADDING + recipePanelBgHeight() + PADDING + 7;
            String label = isCompostingCategory ? "Chance:" : "Burn time:";
            String value = isCompostingCategory ? compostChancePct + "%" : fuelBurnSecs + "s";
            g.drawString(font, label, PADDING + 2, fy, 0xFFAAAAAA, false);
            g.drawCenteredString(font, value, imageWidth / 2, fy, 0xFFFFFFFF);
        }

        // Extra codec-param rows
        if (capturedLayout != null && !capturedLayout.extraParams().isEmpty()) {
            int baseRow = (isCompostingCategory || isFuelCategory ? 1 : 0);
            for (int i = 0; i < capturedLayout.extraParams().size(); i++) {
                ExtraParam ep = capturedLayout.extraParams().get(i);
                int fy = TOP_BAR + PADDING + recipePanelBgHeight() + PADDING
                        + (baseRow + i) * EXTRA_FIELD_H + 7;
                String val = extraParamValues.getOrDefault(ep.key(), ep.defaultValueStr());
                g.drawString(font, ep.key() + ":", PADDING + 2, fy, 0xFFAAAAAA, false);
                if (ep.type() == ExtraParam.Type.INT || ep.type() == ExtraParam.Type.FLOAT) {
                    g.drawCenteredString(font, val, imageWidth / 2, fy, 0xFFFFFFFF);
                } else if (ep.type() == ExtraParam.Type.STRING) {
                    g.drawString(font, val, imageWidth / 2 - 14, fy, 0xFFFFFF55, false);
                }
                // BOOLEAN value is shown on the toggle button itself
            }
        }

        if (!statusMessage.isEmpty()) {
            g.drawCenteredString(font, Component.literal(statusMessage),
                    imageWidth / 2, imageHeight - BOTTOM_BAR - 8, statusColor);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        // Popup renders at high z so it sits above buttons, items, and text
        if (activePopup != null) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 300);
            activePopup.render(g, font, mouseX, mouseY);
            g.pose().popPose();
        }

        if (activePopup == null && !isDragging) {
            for (SlotData slot : slots) {
                if (slot.contains(mouseX, mouseY) && !slot.ingredient.isEmpty()) {
                    g.renderTooltip(font, slot.ingredient, mouseX, mouseY);
                    break;
                }
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static int itemX(SlotData slot) { return slot.x + (slot.w - 16) / 2; }
    private static int itemY(SlotData slot) { return slot.y + (slot.h - 16) / 2; }

    private void renderSlot(GuiGraphics g, SlotData slot, int mouseX, int mouseY) {
        int ix = itemX(slot);
        int iy = itemY(slot);
        boolean hover = slot.contains(mouseX, mouseY) && activePopup == null;

        g.blitSprite(VANILLA_SLOT_SPRITE, slot.x, slot.y, 18, 18);

        if (hover) g.fill(ix - 1, iy - 1, ix + 17, iy + 17, 0x55FFFFFF);

        if (!slot.ingredient.isEmpty()) {
            g.renderItem(slot.ingredient, ix, iy);
            g.renderItemDecorations(font, slot.ingredient, ix, iy);

            if (slot.useTag && slot.selectedTag != null) {
                g.drawString(font, "#", ix + 11, iy + 8, 0xFFFFFF00, false);
            }

            if (hover) {
                g.fill(ix + 8, iy - 1, ix + 17, iy + 8, 0x88000000);
                g.drawString(font, "×", ix + 10, iy, 0xFFFF5555, false);
            }
        }
    }

    // ─── Input ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int mouseX = (int) mx;
        int mouseY = (int) my;

        if (activePopup != null) {
            boolean consumed = activePopup.mouseClicked(mouseX, mouseY);
            if (!activePopup.keepOpen()) activePopup = null;
            if (consumed) return true;
        }

        if (button == 0) {
            for (SlotData slot : slots) {
                if (!slot.contains(mouseX, mouseY)) continue;
                if (!slot.ingredient.isEmpty() && isXArea(slot, mouseX, mouseY)) {
                    slot.clear();
                    statusMessage = "";
                    return true;
                }
                if (!slot.ingredient.isEmpty()) {
                    pendingDragSlot = slot;
                    dragStartX = mx;
                    dragStartY = my;
                    return true;
                }
            }
        }

        if (button == 1) {
            for (SlotData slot : slots) {
                if (!slot.contains(mouseX, mouseY) || slot.ingredient.isEmpty()) continue;
                activePopup = new TagSelectionPopup(slot, width, height);
                return true;
            }
        }

        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (button == 0 && pendingDragSlot != null) {
            double dist = Math.abs(mx - dragStartX) + Math.abs(my - dragStartY);
            if (dist > 3) {
                draggingSourceSlot = pendingDragSlot;
                draggingItem = draggingSourceSlot.ingredient.copy();
                draggingSourceSlot.ingredient = ItemStack.EMPTY;
                draggingSourceSlot.useTag = false;
                draggingSourceSlot.selectedTag = null;
                isDragging = true;
                pendingDragSlot = null;
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (button == 0) {
            if (isDragging) {
                SlotData target = findSlotAt((int) mx, (int) my);
                if (target != null && target != draggingSourceSlot) {
                    ItemStack swap = target.ingredient.copy();
                    target.ingredient = draggingItem;
                    target.useTag = false;
                    target.selectedTag = null;
                    draggingSourceSlot.ingredient = swap;
                }
                resetDrag();
                return true;
            }
            pendingDragSlot = null;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && activePopup != null) {
            activePopup = null;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private boolean isXArea(SlotData slot, int mouseX, int mouseY) {
        int ix = itemX(slot);
        int iy = itemY(slot);
        return mouseX >= ix + 8 && mouseX < ix + 16 && mouseY >= iy - 1 && mouseY < iy + 8;
    }

    private SlotData findSlotAt(int mouseX, int mouseY) {
        for (SlotData slot : slots) {
            if (slot.contains(mouseX, mouseY)) return slot;
        }
        return null;
    }

    private void resetDrag() {
        pendingDragSlot    = null;
        draggingSourceSlot = null;
        draggingItem       = ItemStack.EMPTY;
        isDragging         = false;
    }

    public List<SlotData> getInteractiveSlots() { return slots; }

    // ─── Actions ──────────────────────────────────────────────────────────────

    private void clearSlots() {
        slots.forEach(SlotData::clear);
        statusMessage = "";
    }

    private void goHome() {
        ModMenuScreen home = new ModMenuScreen();
        GuiSessionState.setLastCategory(null);
        GuiSessionState.setLastScreen(home);
        minecraft.setScreen(home);
    }

    private void exportRecipe() {
        boolean hasInput = slots.stream().anyMatch(s -> s.role == RecipeIngredientRole.INPUT && !s.isEmpty());

        if (isCompostingCategory || isFuelCategory) {
            if (!hasInput) { statusMessage = "Set an input item first"; statusColor = 0xFF5555; return; }
        } else {
            boolean hasOutput = slots.stream().anyMatch(s -> s.role == RecipeIngredientRole.OUTPUT && !s.isEmpty());
            if (!hasOutput) { statusMessage = "Export failed: output slot is empty"; statusColor = 0xFF5555; return; }
            if (!hasInput)  { statusMessage = "Export failed: no ingredients defined"; statusColor = 0xFF5555; return; }
        }

        try {
            Path outFile = resolveOutputFile();
            Files.createDirectories(outFile.getParent());
            String existing = Files.exists(outFile) ? Files.readString(outFile) : buildFileHeader();
            String trimmed  = existing.stripTrailing();
            if (trimmed.endsWith("})")) trimmed = trimmed.substring(0, trimmed.length() - 2);

            ResourceLocation uid = category.getRecipeType().getUid();
            SlotData outputSlot = slots.stream()
                    .filter(s -> s.role == RecipeIngredientRole.OUTPUT && !s.isEmpty())
                    .findFirst().orElse(null);
            boolean tagOutput = !isCompostingCategory && !isFuelCategory
                    && outputSlot != null && outputSlot.useTag && outputSlot.selectedTag != null;

            if (tagOutput) {
                List<SlotData> inputs = slots.stream().filter(s -> s.role == RecipeIngredientRole.INPUT).toList();
                List<ItemStack> outItems = getTagItems(outputSlot.selectedTag);
                if (outItems.isEmpty()) {
                    statusMessage = "Output tag is empty or not found";
                    statusColor = 0xFF5555;
                    return;
                }

                // Check if there is exactly one tag-input slot — if so, do paired expansion
                SlotData tagInputSlot = inputs.stream()
                        .filter(s -> !s.isEmpty() && s.useTag && s.selectedTag != null)
                        .findFirst().orElse(null);
                List<ItemStack> inItems = tagInputSlot != null
                        ? getTagItems(tagInputSlot.selectedTag) : List.of();
                boolean paired = !inItems.isEmpty();

                if (paired && inItems.size() != outItems.size()) {
                    statusMessage = "Tags have different sizes (" + inItems.size()
                            + " vs " + outItems.size() + ") — cannot pair";
                    statusColor = 0xFF5555;
                    return;
                }

                StringBuilder sb = new StringBuilder(trimmed);
                for (int i = 0; i < outItems.size(); i++) {
                    ItemStack outItem = outItems.get(i);
                    String outStr = IngredientFormatter.formatItemStack(outItem.copyWithCount(outputSlot.count));
                    String outPath = outItem.getItem().builtInRegistryHolder().key().location().getPath();

                    List<SlotData> effectiveInputs;
                    if (paired) {
                        // Replace the tag-input slot with the specific paired item
                        final int idx = i;
                        final SlotData tis = tagInputSlot;
                        effectiveInputs = inputs.stream()
                                .map(s -> s == tis ? withItem(s, inItems.get(idx)) : s)
                                .toList();
                    } else {
                        effectiveInputs = inputs;
                    }

                    String line = buildVanillaLine(uid, outStr, effectiveInputs)
                            + ".id('kjs:" + uid.getPath() + "/" + outPath + "')";
                    sb.append("\n    // ").append(outPath).append("\n").append(line).append("\n");
                }
                Files.writeString(outFile, sb.append("\n})\n").toString());
            } else {
                String js = buildKubeJs(uid, outputSlot);
                Files.writeString(outFile, trimmed + "\n    // " + recipeName() + "\n" + js + "\n\n})\n");
            }
            statusMessage = "Saved to " + Minecraft.getInstance().gameDirectory.toPath().relativize(outFile);
            statusColor = 0x55FF55;
        } catch (Exception e) {
            statusMessage = "Export failed: " + e.getMessage();
            statusColor = 0xFF5555;
        }
    }

    // ─── KubeJS builders ──────────────────────────────────────────────────────

    private String buildKubeJs(ResourceLocation uid, SlotData outputSlot) {
        if (isCompostingCategory) return buildComposting();
        if (isFuelCategory)       return buildFuel();

        String ns   = uid.getNamespace();
        String path = uid.getPath();
        String output = outputSlot != null ? outputSlot.toKubeJs() : "'minecraft:air'";
        List<SlotData> inputs = slots.stream().filter(s -> s.role == RecipeIngredientRole.INPUT).toList();

        String built = buildVanillaLine(uid, output, inputs);
        built += ".id('" + buildRecipeId(uid, outputSlot) + "')";
        return built;
    }

    private String buildVanillaLine(ResourceLocation uid, String output, List<SlotData> inputs) {
        String ns = uid.getNamespace(), path = uid.getPath();
        if (ns.equals("minecraft")) {
            return switch (path) {
                case "crafting", "crafting_shaped", "crafting_shapeless" -> buildCrafting(output, inputs);
                case "smelting"         -> buildCooking("smelting",        output, inputs);
                case "blasting"         -> buildCooking("blasting",        output, inputs);
                case "smoking"          -> buildCooking("smoking",         output, inputs);
                case "campfire_cooking" -> buildCooking("campfireCooking", output, inputs);
                case "stonecutting"     -> buildCooking("stonecutting",    output, inputs);
                case "smithing", "smithing_transform" -> buildSmithing(output);
                default -> buildCustom(uid, output, inputs);
            };
        }
        return buildCustom(uid, output, inputs);
    }

    /** Returns a copy of {@code src} with the ingredient replaced by {@code item} and useTag cleared. */
    private SlotData withItem(SlotData src, ItemStack item) {
        SlotData copy = new SlotData(src.x, src.y, src.w, src.h, src.role, src.gridRow, src.gridCol);
        copy.ingredient = item.copy();
        copy.useTag = false;
        copy.selectedTag = null;
        copy.count = src.count;
        return copy;
    }

    private List<ItemStack> getTagItems(ResourceLocation tagId) {
        try {
            var reg = Minecraft.getInstance().level.registryAccess();
            var key = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM, tagId);
            List<ItemStack> items = new ArrayList<>();
            reg.registryOrThrow(net.minecraft.core.registries.Registries.ITEM).getTag(key)
               .ifPresent(named -> named.forEach(h -> items.add(new ItemStack(h.value()))));
            return items;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String buildComposting() {
        String item = slots.stream().filter(s -> s.role == RecipeIngredientRole.INPUT && !s.isEmpty())
                .findFirst().map(SlotData::toKubeJs).orElse("'minecraft:air'");
        String chance = String.format("%.2f", compostChancePct / 100.0);
        return "    event.add(" + item + ", " + chance + ")";
    }

    private String buildFuel() {
        String item = slots.stream().filter(s -> s.role == RecipeIngredientRole.INPUT && !s.isEmpty())
                .findFirst().map(SlotData::toKubeJs).orElse("'minecraft:air'");
        return "    event.add(" + item + ", " + (fuelBurnSecs * 20) + ")";
    }

    private String buildCrafting(String output, List<SlotData> inputs) {
        if (inputs.stream().noneMatch(s -> !s.isEmpty())) return "    // No inputs";
        int maxRow = inputs.stream().mapToInt(s -> s.gridRow).max().orElse(0);
        int maxCol = inputs.stream().mapToInt(s -> s.gridCol).max().orElse(0);
        SlotData[][] grid = new SlotData[maxRow + 1][maxCol + 1];
        for (SlotData s : inputs) if (s.gridRow <= maxRow && s.gridCol <= maxCol) grid[s.gridRow][s.gridCol] = s;

        java.util.Map<String, Character> keyMap = new java.util.LinkedHashMap<>();
        char letter = 'A';
        for (SlotData[] row : grid)
            for (SlotData s : row)
                if (s != null && !s.isEmpty()) {
                    String key = s.toKubeJs();
                    if (!keyMap.containsKey(key)) keyMap.put(key, letter++);
                }

        StringBuilder sb = new StringBuilder("    event.shaped(").append(output).append(", [\n");
        for (SlotData[] row : grid) {
            sb.append("        '");
            for (SlotData s : row) sb.append(s == null || s.isEmpty() ? ' ' : keyMap.get(s.toKubeJs()));
            sb.append("',\n");
        }
        sb.append("    ], {\n");
        for (var e : keyMap.entrySet()) sb.append("        ").append(e.getValue()).append(": ").append(e.getKey()).append(",\n");
        sb.append("    })");
        return sb.toString();
    }

    private String buildCooking(String method, String output, List<SlotData> inputs) {
        String input = inputs.stream().filter(s -> !s.isEmpty()).findFirst()
                .map(SlotData::toKubeJs).orElse("'minecraft:air'");
        return "    event." + method + "(" + output + ", " + input + ")";
    }

    private String buildSmithing(String output) {
        List<SlotData> ins = slots.stream().filter(s -> s.role == RecipeIngredientRole.INPUT).toList();
        String t = ins.size() > 0 && !ins.get(0).isEmpty() ? ins.get(0).toKubeJs() : "'minecraft:air'";
        String b = ins.size() > 1 && !ins.get(1).isEmpty() ? ins.get(1).toKubeJs() : "'minecraft:air'";
        String a = ins.size() > 2 && !ins.get(2).isEmpty() ? ins.get(2).toKubeJs() : "'minecraft:air'";
        return "    event.smithing(\n        " + t + ",\n        " + b + ",\n        " + a + ",\n        " + output + "\n    )";
    }

    private String buildCustom(ResourceLocation uid, String output, List<SlotData> inputs) {
        // Prefer codec-template approach: uses the mod's actual field names (e.g. "results",
        // "ingredient") and preserves non-ingredient fields (sequence, loops, etc.) verbatim.
        if (capturedLayout != null && capturedLayout.exampleRecipe() instanceof RecipeHolder<?> holder) {
            try {
                String result = buildCustomViaCodec(uid, holder, output, inputs);
                if (result != null) return result;
            } catch (Exception e) {
                KubeJsRecipeEditor.LOGGER.debug("Codec-based export failed, using fallback: {}", e.getMessage());
            }
        }

        // Fallback: hardcoded structure (vanilla-style, always works)
        StringBuilder sb = new StringBuilder("    event.custom({\n");
        sb.append("        \"type\": \"").append(uid).append("\",\n");
        sb.append("        \"ingredients\": [\n");
        for (SlotData s : inputs) if (!s.isEmpty()) sb.append("            { \"item\": ").append(s.toKubeJs()).append(" },\n");
        sb.append("        ],\n");
        sb.append("        \"result\": { \"item\": ").append(output).append(" }");
        if (capturedLayout != null) {
            for (ExtraParam ep : capturedLayout.extraParams()) {
                String val = extraParamValues.getOrDefault(ep.key(), ep.defaultValueStr());
                String jsonVal = ep.type() == ExtraParam.Type.STRING ? "\"" + val + "\"" : val;
                sb.append(",\n        \"").append(ep.key()).append("\": ").append(jsonVal);
            }
        }
        sb.append("\n    })");
        return sb.toString();
    }

    /**
     * Builds {@code event.custom({...})} using the example recipe's codec JSON as a structural
     * template. Only the ingredient/result sub-trees are replaced with user-provided values;
     * every other field (sequence steps, loops, heatRequirement, etc.) is kept verbatim from
     * the codec — which means the emitted JSON will match exactly what the target mod expects.
     *
     * @return formatted JS string, or {@code null} if the codec cannot be used
     */
    @SuppressWarnings("unchecked")
    private @Nullable String buildCustomViaCodec(
            ResourceLocation uid, RecipeHolder<?> holder, String outputKubeJs, List<SlotData> inputs) {

        RegistryAccess regs = Minecraft.getInstance().level.registryAccess();
        DynamicOps<JsonElement> ops = regs.createSerializationContext(JsonOps.INSTANCE);
        @SuppressWarnings({"rawtypes", "unchecked"})
        JsonElement encoded = (JsonElement) ((com.mojang.serialization.Codec) holder.value().getSerializer().codec())
                .encodeStart(ops, holder.value())
                .getOrThrow();
        if (!encoded.isJsonObject()) return null;

        JsonObject json = encoded.getAsJsonObject().deepCopy();

        // ── Patch ingredient fields ─────────────────────────────────────────
        if (json.has("ingredients")) {
            json.add("ingredients", buildIngredientArray(inputs));
        } else if (json.has("ingredient")) {
            // single-ingredient slot
            SlotData first = inputs.stream().filter(s -> !s.isEmpty()).findFirst().orElse(null);
            json.add("ingredient", first != null ? buildIngredientElement(first) : new JsonObject());
        }

        // ── Patch result fields ─────────────────────────────────────────────
        // Keep count / nbt from the example so the mod serializer is happy;
        // only override the "item" / "id" key.
        String outId = stripKubeJsWrappers(outputKubeJs);
        if (json.has("results") && json.get("results").isJsonArray()) {
            JsonArray results = new JsonArray();
            JsonObject outObj = new JsonObject();
            outObj.addProperty("item", outId);
            SlotData outSlot = slots.stream()
                    .filter(s -> s.role == RecipeIngredientRole.OUTPUT && !s.isEmpty())
                    .findFirst().orElse(null);
            if (outSlot != null && outSlot.count > 1) outObj.addProperty("count", outSlot.count);
            results.add(outObj);
            json.add("results", results);
        } else if (json.has("result")) {
            if (json.get("result").isJsonObject()) {
                JsonObject outObj = json.getAsJsonObject("result").deepCopy();
                outObj.addProperty("item", outId);
                SlotData outSlot = slots.stream()
                        .filter(s -> s.role == RecipeIngredientRole.OUTPUT && !s.isEmpty())
                        .findFirst().orElse(null);
                if (outSlot != null && outSlot.count > 1) outObj.addProperty("count", outSlot.count);
                json.add("result", outObj);
            } else {
                json.addProperty("result", outId);
            }
        }

        // ── Apply user-edited extra param values ────────────────────────────
        if (capturedLayout != null) {
            for (ExtraParam ep : capturedLayout.extraParams()) {
                if (!json.has(ep.key())) continue;
                String val = extraParamValues.getOrDefault(ep.key(), ep.defaultValueStr());
                switch (ep.type()) {
                    case INT     -> json.addProperty(ep.key(), Integer.parseInt(val));
                    case FLOAT   -> json.addProperty(ep.key(), Double.parseDouble(val));
                    case BOOLEAN -> json.addProperty(ep.key(), Boolean.parseBoolean(val));
                    case STRING  -> json.addProperty(ep.key(), val);
                }
            }
        }

        // ── Format as event.custom({...}) ───────────────────────────────────
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
        String pretty = gson.toJson(json);
        String indented = pretty.lines()
                .map(line -> "        " + line)
                .collect(Collectors.joining("\n"));
        return "    event.custom(\n" + indented + "\n    )";
    }

    /** Builds a JsonArray of ingredient objects from non-empty input slots. */
    private JsonArray buildIngredientArray(List<SlotData> inputs) {
        JsonArray arr = new JsonArray();
        for (SlotData s : inputs) {
            if (!s.isEmpty()) arr.add(buildIngredientElement(s));
        }
        return arr;
    }

    /** Builds a single ingredient JsonElement for one slot (item or tag). */
    private JsonElement buildIngredientElement(SlotData s) {
        JsonObject obj = new JsonObject();
        if (s.useTag && s.selectedTag != null) {
            obj.addProperty("tag", s.selectedTag.toString());
        } else {
            obj.addProperty("item", s.ingredient.getItem()
                    .builtInRegistryHolder().key().location().toString());
        }
        return obj;
    }

    /**
     * Strips KubeJS wrappers to get a plain {@code namespace:path} item ID.
     * Handles: {@code 'id'}, {@code "id"}, {@code Item.of('id', N)}
     */
    private static String stripKubeJsWrappers(String s) {
        s = s.trim();
        // Item.of('id', count) or Item.of("id", count)
        if (s.startsWith("Item.of(")) {
            int q1 = s.indexOf('\'');
            int q2 = q1 < 0 ? -1 : s.indexOf('\'', q1 + 1);
            if (q1 >= 0 && q2 > q1) return s.substring(q1 + 1, q2);
        }
        return s.replace("'", "").replace("\"", "");
    }


    private String buildRecipeId(ResourceLocation uid, SlotData outputSlot) {
        String type = uid.getPath();
        if (outputSlot == null || outputSlot.isEmpty()) return "kjs:" + type + "/unnamed";
        String outPath = outputSlot.ingredient.getItem()
                .builtInRegistryHolder().key().location().getPath();
        return "kjs:" + type + "/" + outPath;
    }

    private Path resolveOutputFile() {
        if (isCompostingCategory) return gameDir().resolve("kubejs/server_scripts/composting.js");
        if (isFuelCategory)       return gameDir().resolve("kubejs/server_scripts/fuel.js");
        ResourceLocation uid = category.getRecipeType().getUid();
        String folder = uid.getNamespace().equals("minecraft") ? "vanilla" : uid.getNamespace();
        return gameDir().resolve("kubejs/server_scripts/" + folder + "/" + uid.getPath() + ".js");
    }

    private String buildFileHeader() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        if (isCompostingCategory) {
            return "// Auto-generated by KubeJS Recipe Editor\n// Generated: " + ts
                    + "\n\nServerEvents.compostableRecipes(event => {\n\n})\n";
        }
        if (isFuelCategory) {
            return "// Auto-generated by KubeJS Recipe Editor\n// Generated: " + ts
                    + "\n\nServerEvents.fuelBurnTime(event => {\n\n})\n";
        }
        ResourceLocation uid = category.getRecipeType().getUid();
        return "// Auto-generated by KubeJS Recipe Editor\n// Recipe type: " + uid
                + "\n// Generated: " + ts
                + "\n\nServerEvents.recipes(event => {\n\n})\n";
    }

    private String recipeName() {
        if (isCompostingCategory || isFuelCategory) {
            return slots.stream().filter(s -> s.role == RecipeIngredientRole.INPUT && !s.isEmpty())
                    .findFirst()
                    .map(s -> s.ingredient.getItem().builtInRegistryHolder().key().location().toString())
                    .orElse("custom");
        }
        return slots.stream().filter(s -> s.role == RecipeIngredientRole.OUTPUT && !s.isEmpty())
                .findFirst()
                .map(s -> s.ingredient.getItem().builtInRegistryHolder().key().location().toString())
                .orElse("custom");
    }

    private Path gameDir() {
        return Minecraft.getInstance().gameDirectory.toPath();
    }

    // ─── Variable slot helpers ─────────────────────────────────────────────────

    private void addNextInputSlot() {
        if (activeInputSlotCount >= maxInputSlotCount || activeInputSlotCount >= allCapturedInputSlots.size()) return;
        SlotCapturingLayoutBuilder.CapturedSlot cap = allCapturedInputSlots.get(activeInputSlotCount);
        int absX = recipeX + cap.x();
        int absY = recipeY + cap.y();
        int col = 0, row = 0;
        if (usesCraftingInputGrid() && !allCapturedInputSlots.isEmpty()) {
            int minX = allCapturedInputSlots.stream().mapToInt(SlotCapturingLayoutBuilder.CapturedSlot::x).min().orElse(0);
            int minY = allCapturedInputSlots.stream().mapToInt(SlotCapturingLayoutBuilder.CapturedSlot::y).min().orElse(0);
            col = Math.max(0, (cap.x() - minX + CRAFT_CELL / 2) / CRAFT_CELL);
            row = Math.max(0, (cap.y() - minY + CRAFT_CELL / 2) / CRAFT_CELL);
        }
        slots.add(new SlotData(absX, absY, CRAFT_CELL, CRAFT_CELL, cap.role(), row, col));
        activeInputSlotCount++;
        rebuildAddSlotButton();
    }

    private void rebuildAddSlotButton() {
        if (addSlotButton == null) return;
        boolean canAdd = activeInputSlotCount < maxInputSlotCount
                && activeInputSlotCount < allCapturedInputSlots.size();
        addSlotButton.visible = canAdd;
        if (canAdd) {
            SlotCapturingLayoutBuilder.CapturedSlot next = allCapturedInputSlots.get(activeInputSlotCount);
            addSlotButton.setX(recipeX + next.x() + 2);
            addSlotButton.setY(recipeY + next.y() + 2);
        }
    }

    // ─── Extra param helpers ───────────────────────────────────────────────────

    private void adjustExtraParam(ExtraParam ep, int direction, boolean shift) {
        try {
            String cur = extraParamValues.getOrDefault(ep.key(), ep.defaultValueStr());
            if (ep.type() == ExtraParam.Type.INT) {
                int step = shift ? 10 : 1;
                extraParamValues.put(ep.key(), String.valueOf(Integer.parseInt(cur) + direction * step));
            } else if (ep.type() == ExtraParam.Type.FLOAT) {
                double step = shift ? 1.0 : 0.1;
                extraParamValues.put(ep.key(), String.format("%.2f", Double.parseDouble(cur) + direction * step));
            }
        } catch (NumberFormatException ignored) {}
    }

    private void toggleExtraParam(ExtraParam ep, Button btn) {
        String cur = extraParamValues.getOrDefault(ep.key(), ep.defaultValueStr());
        String next = "true".equalsIgnoreCase(cur) ? "false" : "true";
        extraParamValues.put(ep.key(), next);
        btn.setMessage(Component.literal(next));
    }
}
