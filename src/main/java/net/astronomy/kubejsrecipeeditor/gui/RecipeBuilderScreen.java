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
import net.astronomy.kubejsrecipeeditor.engine.RecipeJsonBuilder;
import net.astronomy.kubejsrecipeeditor.export.IngredientFormatter;
import net.astronomy.kubejsrecipeeditor.gui.GuiDescriptor;
import net.astronomy.kubejsrecipeeditor.gui.SlotDescriptor;
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
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
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
    private RecipePopup activePopup = null;

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

    // ─── Sequenced Assembly step editor ───────────────────────────────────────
    private static final int STEP_ROW_H   = 20;
    private static final int SEQ_HEADER_H = 18;
    private static final int SEQ_ADD_BTN_H = 16;

    private final List<SequenceStep> sequenceSteps = new ArrayList<>();

    private static class SequenceStep {
        String type;
        JsonObject rawStep;
        @Nullable SlotData slot; // ingredient slot, null if this step needs none

        SequenceStep(String type, JsonObject rawStep) {
            this.type = type;
            this.rawStep = rawStep;
        }

        boolean needsIngredient() {
            return type.equals("create:deploying") || type.equals("create:filling");
        }

        boolean needsFluid() {
            return type.equals("create:filling");
        }

        String displayName() {
            return switch (type) {
                case "create:pressing"  -> "Press";
                case "create:cutting"   -> "Cut";
                case "create:deploying" -> "Deploy";
                case "create:filling"   -> "Fill (Spout)";
                default -> type.contains(":") ? type.substring(type.indexOf(':') + 1) : type;
            };
        }
    }

    // Known Create step types — exception to no-hardcoding: part of recipe schema
    private static final List<String> CREATE_STEP_TYPES = List.of(
            "create:pressing", "create:cutting", "create:deploying", "create:filling");

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

        // Init sequence steps from template (only on first construction)
        initSequenceSteps();

        int bgW = recipePanelBgWidth();
        int bgH = recipePanelBgHeight();
        int extraParamCount = capturedLayout != null ? capturedLayout.extraParams().size() : 0;
        int extraRows = (isCompostingCategory || isFuelCategory ? 1 : 0) + extraParamCount;
        int seqH = isSequencedAssembly()
                ? SEQ_HEADER_H + Math.max(1, sequenceSteps.size()) * STEP_ROW_H + SEQ_ADD_BTN_H + 6
                : 0;
        this.imageWidth  = Math.max(240, bgW + PADDING * 2);
        this.imageHeight = TOP_BAR + PADDING + bgH + PADDING
                + (extraRows > 0 ? EXTRA_FIELD_H * extraRows + 4 : 10) + seqH + BOTTOM_BAR;
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
        if (isCompostingCategory || isFuelCategory) return CRAFT_CELL;
        IDrawable bg = resolveDrawableBackground();
        if (bg != null) return bg.getWidth();
        return category.getWidth();
    }

    private int recipePanelBgHeight() {
        if (isCompostingCategory || isFuelCategory) return CRAFT_CELL;
        IDrawable bg = resolveDrawableBackground();
        if (bg != null) return bg.getHeight();
        return category.getHeight();
    }

    /**
     * Returns true when the export template has a numeric (non-item) output, e.g. Mekanism
     * energy conversion where "output" holds an energy value instead of an item reference.
     * These recipes have no item output slot and no output slot should be required.
     */
    private boolean hasNonItemNumericOutput() {
        if (capturedLayout == null || capturedLayout.exportTemplate() == null) return false;
        JsonElement out = capturedLayout.exportTemplate().get("output");
        return out != null && out.isJsonPrimitive() && !out.getAsJsonPrimitive().isString();
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

        // Slot-centering correction: if interactive slots are clustered to one side of the
        // background (e.g. Create Manual Item Application), shift recipeX/recipeY so the
        // slot group appears centered in the window rather than the raw background center.
        if (capturedLayout != null && !capturedLayout.slots().isEmpty()
                && !isCompostingCategory && !isFuelCategory) {
            var interactive = capturedLayout.slots().stream()
                    .filter(s -> s.role() == RecipeIngredientRole.INPUT
                              || s.role() == RecipeIngredientRole.OUTPUT
                              || s.role() == RecipeIngredientRole.CATALYST)
                    .toList();
            if (!interactive.isEmpty()) {
                int ch = recipePanelBgHeight();
                // X correction
                int minJeiX = interactive.stream()
                        .mapToInt(SlotCapturingLayoutBuilder.CapturedSlot::x).min().orElse(0);
                int maxJeiX = interactive.stream()
                        .mapToInt(s -> s.x() + CRAFT_CELL).max().orElse(cw);
                int dx = cw / 2 - (minJeiX + maxJeiX) / 2;
                if (Math.abs(dx) > 4) recipeX += dx;
                // Y correction
                int minJeiY = interactive.stream()
                        .mapToInt(SlotCapturingLayoutBuilder.CapturedSlot::y).min().orElse(0);
                int maxJeiY = interactive.stream()
                        .mapToInt(s -> s.y() + CRAFT_CELL).max().orElse(ch);
                int dy = ch / 2 - (minJeiY + maxJeiY) / 2;
                if (Math.abs(dy) > 4) recipeY += dy;
            }
        }

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
                    case ENUM -> {
                        String cur = extraParamValues.getOrDefault(ep.key(), ep.defaultValueStr());
                        addRenderableWidget(
                                Button.builder(Component.literal(cur), btn -> cycleEnumParam(ep, btn))
                                        .pos(cx - 55, fy).size(110, 14).build());
                    }
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
        // Composting and fuel: single INPUT slot centered in the window.
        if (isCompostingCategory || isFuelCategory) {
            int slotX = leftPos + (imageWidth - CRAFT_CELL) / 2;
            int slotY = topPos + TOP_BAR + PADDING + 8;
            slots.add(new SlotData(slotX, slotY, CRAFT_CELL, CRAFT_CELL,
                    RecipeIngredientRole.INPUT, 0, 0));
            return;
        }

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
            SlotData newSlot = new SlotData(absX, absY, CRAFT_CELL, CRAFT_CELL, cap.role(), row, col);
            newSlot.jeiRelX = cap.x();
            newSlot.jeiRelY = cap.y();
            slots.add(newSlot);
        }

        // Synthetic INPUT slot when template has "ingredient" (singular) but no INPUT slots were
        // captured from JEI (e.g. Create Sequenced Assembly — the input item is RENDER_ONLY in JEI).
        if (capturedLayout != null && capturedLayout.exportTemplate() != null) {
            JsonObject tmpl = capturedLayout.exportTemplate();
            boolean needsSyntheticInput = tmpl.has("ingredient")
                    && !tmpl.has("ingredients")
                    && slots.stream().noneMatch(s -> s.role == RecipeIngredientRole.INPUT);
            if (needsSyntheticInput) {
                int synthX = recipeX - CRAFT_CELL - 6;
                int synthY = recipeY + (recipePanelBgHeight() - CRAFT_CELL) / 2;
                slots.add(new SlotData(synthX, synthY, CRAFT_CELL, CRAFT_CELL,
                        RecipeIngredientRole.INPUT, 0, 0));
            }
        }

        // If no OUTPUT slot was captured (e.g. grindstone uses RENDER_ONLY for output in JEI),
        // add a synthetic one to the right of the recipe panel.
        // Skip for recipes with numeric output (e.g. Mekanism energy conversion) — those have
        // no item output, only a configurable energy value via ExtraParam.
        if (!isCompostingCategory && !isFuelCategory && !hasNonItemNumericOutput()) {
            boolean hasOutput = slots.stream().anyMatch(s -> s.role == RecipeIngredientRole.OUTPUT);
            if (!hasOutput) {
                int synthX = recipeX + recipePanelBgWidth() + 8;
                int synthY = recipeY + (recipePanelBgHeight() - CRAFT_CELL) / 2;
                slots.add(new SlotData(synthX, synthY, CRAFT_CELL, CRAFT_CELL,
                        RecipeIngredientRole.OUTPUT, 0, 0));
            }
        }

        // Sequence Assembly: add ingredient slots for steps that need one.
        // Slots are positioned in the sequence section below the extra params area.
        if (isSequencedAssembly()) {
            int seqY = computeSeqSectionY();
            for (int i = 0; i < sequenceSteps.size(); i++) {
                SequenceStep step = sequenceSteps.get(i);
                if (!step.needsIngredient()) {
                    step.slot = null;
                    continue;
                }
                SlotData prevSlot = step.slot; // preserve ingredient from previous init() (resize)
                int slotX = leftPos + PADDING + 90;
                int slotY = seqY + SEQ_HEADER_H + i * STEP_ROW_H + (STEP_ROW_H - CRAFT_CELL) / 2;
                SlotData newSlot = new SlotData(slotX, slotY, CRAFT_CELL, CRAFT_CELL,
                        RecipeIngredientRole.INPUT, 0, 0);
                if (prevSlot != null) {
                    newSlot.ingredient = prevSlot.ingredient;
                    newSlot.isFluid    = prevSlot.isFluid;
                    newSlot.fluidId    = prevSlot.fluidId;
                    newSlot.fluidAmount = prevSlot.fluidAmount;
                }
                step.slot = newSlot;
                slots.add(newSlot);
            }
        }

        // Pre-fill fluid slots from the export template so the user can see and change them.
        preFillFluidSlots();
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

        // Sequence Assembly step editor section
        if (isSequencedAssembly()) {
            renderSequenceSection(g, mouseX, mouseY);
        }

        // Dragged item follows cursor
        if (isDragging && !draggingItem.isEmpty()) {
            g.renderItem(draggingItem, mouseX - 8, mouseY - 8);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawCenteredString(font, title, imageWidth / 2, 8, 0xFFFFFF);

        // Composting / fuel: hint when slot is empty
        if (isCompostingCategory || isFuelCategory) {
            boolean slotFilled = slots.stream().anyMatch(s -> s.role == RecipeIngredientRole.INPUT && !s.isEmpty());
            if (!slotFilled && statusMessage.isEmpty()) {
                g.drawCenteredString(font, "§7Drag an item from JEI", imageWidth / 2,
                        TOP_BAR + PADDING + CRAFT_CELL + 4, 0xFFAAAAAA);
            }
        }

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
                // BOOLEAN and ENUM values are shown on the toggle/cycle button itself
            }
        }

        // Sequence Assembly step labels
        if (isSequencedAssembly()) {
            int seqRelY = computeSeqSectionRelY();
            g.drawString(font, "Sequence:", PADDING + 2, seqRelY + 4, 0xFFAAAAAA, false);
            for (int i = 0; i < sequenceSteps.size(); i++) {
                SequenceStep step = sequenceSteps.get(i);
                int rowRelY = seqRelY + SEQ_HEADER_H + i * STEP_ROW_H;
                // Step label
                g.drawString(font, (i + 1) + ". " + step.displayName(),
                        PADDING + 2, rowRelY + 5, 0xFFFFFFFF, false);
                // "×" remove hint (right side)
                g.drawString(font, "[x]", imageWidth - PADDING - 20, rowRelY + 5,
                        0xFFFF6666, false);
            }
            // "+" add step hint (below last step)
            int addRelY = seqRelY + SEQ_HEADER_H + sequenceSteps.size() * STEP_ROW_H + 3;
            g.drawString(font, "[+ Add Step]", PADDING + 2, addRelY, 0xFF55FF55, false);
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
                if (slot.contains(mouseX, mouseY) && !slot.isEmpty()) {
                    if (slot.isFluid && slot.fluidId != null) {
                        java.util.List<Component> lines = java.util.List.of(
                            Component.literal(slot.fluidId.toString()),
                            Component.literal(slot.fluidAmount + " mB")
                                    .withStyle(s -> s.withColor(0xAAAAAA))
                        );
                        g.renderComponentTooltip(font, lines, mouseX, mouseY);
                    } else {
                        g.renderTooltip(font, slot.ingredient, mouseX, mouseY);
                    }
                    break;
                }
            }
        }
    }

    // ─── Sequence helpers ──────────────────────────────────────────────────────

    private boolean isSequencedAssembly() {
        if (capturedLayout == null || capturedLayout.exportTemplate() == null) return false;
        return capturedLayout.exportTemplate().has("sequence");
    }

    private void initSequenceSteps() {
        sequenceSteps.clear();
        if (capturedLayout == null || capturedLayout.exportTemplate() == null) return;
        JsonObject tmpl = capturedLayout.exportTemplate();
        if (!tmpl.has("sequence") || !tmpl.get("sequence").isJsonArray()) return;
        for (JsonElement el : tmpl.getAsJsonArray("sequence")) {
            if (!el.isJsonObject()) continue;
            JsonObject stepObj = el.getAsJsonObject();
            String type = stepObj.has("type") ? stepObj.get("type").getAsString() : "create:pressing";
            sequenceSteps.add(new SequenceStep(type, stepObj.deepCopy()));
        }
    }

    /** Y position (absolute screen) of the sequence section top. */
    private int computeSeqSectionY() {
        int extraRows = (isCompostingCategory || isFuelCategory ? 1 : 0)
                + (capturedLayout != null ? capturedLayout.extraParams().size() : 0);
        return topPos + TOP_BAR + PADDING + recipePanelBgHeight() + PADDING
                + (extraRows > 0 ? EXTRA_FIELD_H * extraRows + 4 : 10);
    }

    /** Y position relative to window top-left (for use inside renderLabels). */
    private int computeSeqSectionRelY() {
        int extraRows = (isCompostingCategory || isFuelCategory ? 1 : 0)
                + (capturedLayout != null ? capturedLayout.extraParams().size() : 0);
        return TOP_BAR + PADDING + recipePanelBgHeight() + PADDING
                + (extraRows > 0 ? EXTRA_FIELD_H * extraRows + 4 : 10);
    }

    /** Rebuilds the screen after steps are added/removed (updates height and re-init). */
    private void rebuildAfterStepChange() {
        int bgH = recipePanelBgHeight();
        int extraParamCount = capturedLayout != null ? capturedLayout.extraParams().size() : 0;
        int extraRows = (isCompostingCategory || isFuelCategory ? 1 : 0) + extraParamCount;
        int seqH = SEQ_HEADER_H + Math.max(1, sequenceSteps.size()) * STEP_ROW_H + SEQ_ADD_BTN_H + 6;
        this.imageHeight = TOP_BAR + PADDING + bgH + PADDING
                + (extraRows > 0 ? EXTRA_FIELD_H * extraRows + 4 : 10) + seqH + BOTTOM_BAR;
        this.init();
    }

    // ─── Fluid slot helpers ───────────────────────────────────────────────────

    /**
     * Pre-fills fluid slots from the export template's ingredient array so the user
     * can see the template's default fluid and change it by dropping a different bucket.
     */
    private void preFillFluidSlots() {
        if (capturedLayout == null || capturedLayout.exportTemplate() == null) return;
        JsonObject tmpl = capturedLayout.exportTemplate();
        Map<Integer, SlotData> byIdx = buildIngredientIndexMap();

        // Pass 1 — ingredients array (Create Mixing, etc.)
        if (tmpl.has("ingredients") && tmpl.get("ingredients").isJsonArray()) {
            JsonArray arr = tmpl.getAsJsonArray("ingredients");
            for (int i = 0; i < arr.size(); i++) {
                JsonElement entry = arr.get(i);
                SlotData slot = byIdx.get(i);
                if (slot == null || !entry.isJsonObject()) continue;
                JsonObject obj = entry.getAsJsonObject();
                if ((obj.has("fluid") || obj.has("fluidTag")) && !slot.isFluid) {
                    slot.isFluid = true;
                    String fluidKey = obj.has("fluid") ? obj.get("fluid").getAsString()
                            : obj.get("fluidTag").getAsString();
                    slot.fluidId     = ResourceLocation.tryParse(fluidKey);
                    slot.fluidAmount = obj.has("amount") ? obj.get("amount").getAsLong() : 1000L;
                }
            }
        }

        // Pass 2 — fluidIngredient field (Create Filling, etc.)
        // Maps to the first INPUT slot that was NOT already claimed by the ingredients index map.
        if (tmpl.has("fluidIngredient") && tmpl.get("fluidIngredient").isJsonObject()) {
            JsonObject fi = tmpl.getAsJsonObject("fluidIngredient");
            String fluidKey = fi.has("fluid")    ? fi.get("fluid").getAsString()
                            : fi.has("fluidTag") ? fi.get("fluidTag").getAsString()
                            : null;
            if (fluidKey != null) {
                java.util.Set<SlotData> claimed = new java.util.HashSet<>(byIdx.values());
                for (SlotData slot : slots) {
                    if (slot.role == RecipeIngredientRole.INPUT
                            && !claimed.contains(slot)
                            && !slot.isFluid) {
                        slot.isFluid     = true;
                        slot.fluidId     = ResourceLocation.tryParse(fluidKey);
                        slot.fluidAmount = fi.has("amount") ? fi.get("amount").getAsLong() : 1000L;
                        break;
                    }
                }
            }
        }
    }

    /**
     * Returns true if the template's ingredient at this slot's position is a fluid entry.
     * Used as fallback when GuiDescriptor is unavailable.
     */
    private boolean isTemplateFluidSlot(SlotData slot) {
        if (capturedLayout == null || capturedLayout.exportTemplate() == null) return false;
        JsonObject tmpl = capturedLayout.exportTemplate();
        if (!tmpl.has("ingredients") || !tmpl.get("ingredients").isJsonArray()) return false;
        Map<Integer, SlotData> byIdx = buildIngredientIndexMap();
        for (var entry : byIdx.entrySet()) {
            if (entry.getValue() == slot) {
                JsonArray arr = tmpl.getAsJsonArray("ingredients");
                int idx = entry.getKey();
                if (idx < arr.size() && arr.get(idx).isJsonObject()) {
                    JsonObject obj = arr.get(idx).getAsJsonObject();
                    return obj.has("fluid") || obj.has("fluidTag");
                }
            }
        }
        return false;
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

        if (slot.isFluid && slot.fluidId != null) {
            // Render bucket item (stored in ingredient) with blue "~" overlay for fluid mode
            if (!slot.ingredient.isEmpty()) {
                g.renderItem(slot.ingredient, ix, iy);
                g.renderItemDecorations(font, slot.ingredient, ix, iy);
            }
            g.drawString(font, "~", ix + 1, iy + 1, 0xFF55BBFF, false);
            if (hover) {
                g.fill(ix + 8, iy - 1, ix + 17, iy + 8, 0x88000000);
                g.drawString(font, "×", ix + 10, iy, 0xFFFF5555, false);
            }
            return;
        }

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
            RecipePopup popup = activePopup; // capture before calling — init() may null activePopup
            boolean consumed = popup.mouseClicked(mouseX, mouseY);
            if (activePopup == null || !popup.keepOpen()) activePopup = null;
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
                if (!slot.contains(mouseX, mouseY) || slot.isEmpty()) continue;
                if (slot.isFluid) {
                    activePopup = new FluidSelectionPopup(slot, width, height);
                } else {
                    activePopup = new TagSelectionPopup(slot, width, height);
                }
                return true;
            }
        }

        // Sequence Assembly section clicks
        if (isSequencedAssembly()) {
            int seqY = computeSeqSectionY();
            int seqX = leftPos + PADDING;
            int seqW = imageWidth - PADDING * 2;

            // Click on "×" to remove a step
            if (button == 0) {
                for (int i = 0; i < sequenceSteps.size(); i++) {
                    int rowY = seqY + SEQ_HEADER_H + i * STEP_ROW_H;
                    int xBtnX = leftPos + imageWidth - PADDING - 20;
                    if (mouseX >= xBtnX && mouseX < xBtnX + 20
                            && mouseY >= rowY && mouseY < rowY + STEP_ROW_H) {
                        SequenceStep removed = sequenceSteps.remove(i);
                        if (removed.slot != null) slots.remove(removed.slot);
                        Minecraft.getInstance().tell(this::rebuildAfterStepChange);
                        return true;
                    }
                }
                // Click on "[+ Add Step]" to open step type picker
                int addY = seqY + SEQ_HEADER_H + sequenceSteps.size() * STEP_ROW_H;
                if (mouseX >= seqX + PADDING && mouseX < seqX + PADDING + 80
                        && mouseY >= addY + 2 && mouseY < addY + SEQ_ADD_BTN_H - 2) {
                    activePopup = new StepTypePickerPopup(mouseX, mouseY, this::addSequenceStep);
                    return true;
                }
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

    /**
     * Called by the JEI ghost handler instead of directly setting slot.ingredient.
     * Detects bucket items dropped on fluid-accepting slots and converts them to fluid mode.
     * Also handles step ingredient slots for Sequenced Assembly.
     */
    public void acceptIngredient(SlotData slot, ItemStack stack) {
        // Check if this is a sequence step slot — step type determines fluid vs item
        for (SequenceStep step : sequenceSteps) {
            if (step.slot == slot) {
                if (step.needsFluid() && stack.getItem() instanceof net.minecraft.world.item.BucketItem bucket) {
                    var fluid = bucket.content;
                    if (fluid != net.minecraft.world.level.material.Fluids.EMPTY) {
                        slot.isFluid = true;
                        slot.fluidId = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid);
                        slot.fluidAmount = slot.isFluid ? slot.fluidAmount : 1000L;
                        slot.ingredient = stack.copy();
                        return;
                    }
                }
                slot.isFluid = false;
                slot.fluidId = null;
                slot.ingredient = stack.copy();
                return;
            }
        }

        if (stack.getItem() instanceof net.minecraft.world.item.BucketItem bucket) {
            var fluid = bucket.content;
            if (fluid != net.minecraft.world.level.material.Fluids.EMPTY) {
                // If the slot is already in fluid mode (pre-filled or previous drop), just
                // update the fluid ID without requiring a descriptor check.
                if (slot.isFluid) {
                    slot.fluidId = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid);
                    slot.ingredient = stack.copy();
                    return;
                }
                // Slot not yet in fluid mode: verify via descriptor or template
                SlotDescriptor desc = findDescriptorForSlot(slot);
                boolean fluidAccepted = (desc != null && desc.acceptsFluid()) || isTemplateFluidSlot(slot);
                if (fluidAccepted) {
                    slot.isFluid = true;
                    slot.fluidId = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid);
                    slot.fluidAmount = 1000L;
                    slot.ingredient = stack.copy();
                    return;
                }
            }
        }
        slot.isFluid = false;
        slot.fluidId = null;
        slot.ingredient = stack.copy();
    }

    private SlotDescriptor findDescriptorForSlot(SlotData slot) {
        GuiDescriptor desc = capturedLayout != null ? capturedLayout.guiDescriptor() : null;
        if (desc == null) return null;
        for (SlotDescriptor sd : desc.slots()) {
            if (sd.jeiX() == slot.jeiRelX && sd.jeiY() == slot.jeiRelY) return sd;
        }
        return null;
    }

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
            // Recipes with numeric output (e.g. Mekanism energy conversion) have no item output slot.
            if (!hasNonItemNumericOutput()) {
                boolean hasOutput = slots.stream().anyMatch(s -> s.role == RecipeIngredientRole.OUTPUT && !s.isEmpty());
                if (!hasOutput) { statusMessage = "Export failed: output slot is empty"; statusColor = 0xFF5555; return; }
            }
            if (!hasInput) { statusMessage = "Export failed: no ingredients defined"; statusColor = 0xFF5555; return; }
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

    // ─── Sequence section rendering ───────────────────────────────────────────

    private void renderSequenceSection(GuiGraphics g, int mouseX, int mouseY) {
        int seqY  = computeSeqSectionY();
        int seqW  = imageWidth - PADDING * 2;
        int seqH  = SEQ_HEADER_H + Math.max(1, sequenceSteps.size()) * STEP_ROW_H + SEQ_ADD_BTN_H;
        int seqX  = leftPos + PADDING;

        // Section background
        g.fill(seqX - 1, seqY - 1, seqX + seqW + 1, seqY + seqH + 1, 0xFF444444);
        g.fill(seqX, seqY, seqX + seqW, seqY + seqH, 0xFF252525);

        // Step rows: highlight "×" and "+" on hover, render ingredient slots
        for (int i = 0; i < sequenceSteps.size(); i++) {
            SequenceStep step = sequenceSteps.get(i);
            int rowY = seqY + SEQ_HEADER_H + i * STEP_ROW_H;
            boolean rowHover = mouseX >= seqX && mouseX < seqX + seqW
                    && mouseY >= rowY && mouseY < rowY + STEP_ROW_H;
            if (rowHover) g.fill(seqX + 1, rowY, seqX + seqW - 1, rowY + STEP_ROW_H, 0x22FFFFFF);
        }

        // "+" add step button background
        int addY = seqY + SEQ_HEADER_H + sequenceSteps.size() * STEP_ROW_H;
        boolean addHover = mouseX >= seqX + PADDING && mouseX < seqX + PADDING + 80
                && mouseY >= addY + 2 && mouseY < addY + SEQ_ADD_BTN_H - 2;
        g.fill(seqX + PADDING, addY + 2, seqX + PADDING + 80, addY + SEQ_ADD_BTN_H - 2,
                addHover ? 0x55558855 : 0x33335533);
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
        String chance = String.format(Locale.ROOT, "%.2f", compostChancePct / 100.0);
        return "    event.add(" + item + ", " + chance + ")";
    }

    private String buildFuel() {
        String item = slots.stream().filter(s -> s.role == RecipeIngredientRole.INPUT && !s.isEmpty())
                .findFirst().map(SlotData::toKubeJs).orElse("'minecraft:air'");
        int ticks = fuelBurnSecs * 20;
        return "    event.modify(" + item + ", item => { item.burnTime = " + ticks + " })";
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
        StringBuilder sb = new StringBuilder("    event.").append(method)
                .append("(").append(output).append(", ").append(input).append(")");

        if (method.equals("stonecutting")) return sb.toString();

        int defaultTime = switch (method) {
            case "blasting", "smoking" -> 100;
            case "campfireCooking"     -> 600;
            default                    -> 200;
        };

        // Try all codec field name variants (MC/NeoForge differ across versions)
        String cookingTimeVal = extraParamValues.get("cookingtime");
        if (cookingTimeVal == null) cookingTimeVal = extraParamValues.get("cookingTime");
        if (cookingTimeVal == null) cookingTimeVal = extraParamValues.get("cooking_time");
        int cookingTime = defaultTime;
        if (cookingTimeVal != null) {
            try { cookingTime = Integer.parseInt(cookingTimeVal); } catch (NumberFormatException ignored) {}
        }
        if (cookingTime != defaultTime) sb.append(".cookingTime(").append(cookingTime).append(")");

        String xpVal = extraParamValues.get("xp");
        if (xpVal == null) xpVal = extraParamValues.get("experience");
        double xp = 0.0;
        if (xpVal != null) {
            try { xp = Double.parseDouble(xpVal); } catch (NumberFormatException ignored) {}
        }
        if (xp != 0.0) sb.append(".xp(").append(String.format(Locale.ROOT, "%.2f", xp)).append(")");

        return sb.toString();
    }

    private String buildSmithing(String output) {
        // JEI may assign the upgrade template to CATALYST role; base/addition use INPUT
        String t = slots.stream()
                .filter(s -> s.role == RecipeIngredientRole.CATALYST && !s.isEmpty())
                .findFirst().map(SlotData::toKubeJs).orElse("'minecraft:air'");
        List<SlotData> ins = slots.stream().filter(s -> s.role == RecipeIngredientRole.INPUT).toList();
        String b = ins.size() > 0 && !ins.get(0).isEmpty() ? ins.get(0).toKubeJs() : "'minecraft:air'";
        String a = ins.size() > 1 && !ins.get(1).isEmpty() ? ins.get(1).toKubeJs() : "'minecraft:air'";
        // If no CATALYST slot was found, the template occupies INPUT[0] — shift the list
        if (t.equals("'minecraft:air'") && !b.equals("'minecraft:air'")) {
            t = b;
            b = a;
            a = ins.size() > 2 && !ins.get(2).isEmpty() ? ins.get(2).toKubeJs() : "'minecraft:air'";
        }
        return "    event.smithing(\n        " + t + ",\n        " + b + ",\n        " + a + ",\n        " + output + "\n    )";
    }

    private String buildCustom(ResourceLocation uid, String output, List<SlotData> inputs) {
        // First choice: RecipeJsonBuilder using GuiDescriptor (fluid-aware, schema-driven)
        GuiDescriptor descriptor = capturedLayout != null ? capturedLayout.guiDescriptor() : null;
        if (descriptor != null && capturedLayout.exampleRecipe() instanceof RecipeHolder<?> holder) {
            try {
                RegistryAccess regs = Minecraft.getInstance().level.registryAccess();
                DynamicOps<JsonElement> ops = regs.createSerializationContext(JsonOps.INSTANCE);
                @SuppressWarnings({"rawtypes", "unchecked"})
                JsonElement encoded = (JsonElement) ((com.mojang.serialization.Codec) holder.value().getSerializer().codec())
                        .encodeStart(ops, holder.value()).getOrThrow();
                if (encoded.isJsonObject()) {
                    JsonObject json = RecipeJsonBuilder.build(
                            encoded.getAsJsonObject(), descriptor, slots, extraParamValues);
                    com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
                    String indented = gson.toJson(json).lines()
                            .map(line -> "        " + line)
                            .collect(Collectors.joining("\n"));
                    return "    event.custom(\n" + indented + "\n    )";
                }
            } catch (Exception e) {
                KubeJsRecipeEditor.LOGGER.debug("RecipeJsonBuilder failed, falling back to codec approach: {}", e.getMessage());
            }
        }

        // Fallback: codec-template approach (preserves most fields, does not handle fluids in inputs)
        if (capturedLayout != null && capturedLayout.exampleRecipe() instanceof RecipeHolder<?> holder) {
            try {
                String result = buildCustomViaCodec(uid, holder, output, inputs);
                if (result != null) return result;
            } catch (Exception e) {
                KubeJsRecipeEditor.LOGGER.debug("Codec-based export failed, using hardcoded fallback: {}", e.getMessage());
            }
        }

        // No reliable template available — fail loudly rather than silently write wrong JSON
        throw new IllegalStateException(
                "codec unavailable for " + uid + " — try reloading resources (F3+T)");
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

        // Use the merged super-template if available; it covers ALL fields found across any sampled
        // recipe of this type (including optional fields like heat_requirement). Falls back to
        // reading the raw recipe JSON from the server data pack, then to live codec encoding.
        JsonObject json;
        if (capturedLayout != null && capturedLayout.exportTemplate() != null) {
            json = capturedLayout.exportTemplate().deepCopy();
        } else {
            // Try reading the raw JSON from the server data pack (works for all mods, singleplayer)
            JsonObject fromRM = readSingleRecipeFromRM(holder.id());
            if (fromRM != null) {
                json = fromRM;
            } else {
                // Last resort: live codec encoding
                RegistryAccess regs = Minecraft.getInstance().level.registryAccess();
                DynamicOps<JsonElement> ops = regs.createSerializationContext(JsonOps.INSTANCE);
                @SuppressWarnings({"rawtypes", "unchecked"})
                JsonElement encoded = (JsonElement) ((com.mojang.serialization.Codec) holder.value().getSerializer().codec())
                        .encodeStart(ops, holder.value())
                        .getOrThrow();
                if (!encoded.isJsonObject()) return null;
                json = encoded.getAsJsonObject().deepCopy();
            }
        }

        // ── Patch ingredient fields ─────────────────────────────────────────
        if (json.has("ingredients")) {
            JsonArray origIngredients = json.getAsJsonArray("ingredients");
            json.add("ingredients", buildIngredientArraySmart(origIngredients, inputs));
        } else if (json.has("ingredient")) {
            // Patch item ingredient (non-fluid inputs only)
            SlotData first = inputs.stream().filter(s -> !s.isEmpty() && !s.isFluid).findFirst().orElse(null);
            json.add("ingredient", first != null ? buildIngredientElement(first) : new JsonObject());
        } else if (json.has("base") || json.has("addition")) {
            // minecraft:grindstone style
            List<SlotData> nonEmpty = inputs.stream().filter(s -> !s.isEmpty()).toList();
            if (json.has("base"))
                json.add("base", !nonEmpty.isEmpty()
                        ? buildIngredientElement(nonEmpty.get(0)) : new JsonObject());
            if (json.has("addition"))
                json.add("addition", nonEmpty.size() > 1
                        ? buildIngredientElement(nonEmpty.get(1)) : new JsonObject());
        } else if (json.has("main_input") || json.has("extra_input")) {
            // mekanism:combining — two named item inputs
            List<SlotData> nonEmpty = inputs.stream().filter(s -> !s.isEmpty()).toList();
            if (json.has("main_input") && !nonEmpty.isEmpty()) {
                JsonObject obj = json.getAsJsonObject("main_input").deepCopy();
                patchIngredientIntoObject(obj, nonEmpty.get(0));
                json.add("main_input", obj);
            }
            if (json.has("extra_input") && nonEmpty.size() > 1) {
                JsonObject obj = json.getAsJsonObject("extra_input").deepCopy();
                patchIngredientIntoObject(obj, nonEmpty.get(1));
                json.add("extra_input", obj);
            }
        } else if (json.has("item_input") && json.get("item_input").isJsonObject()) {
            // mekanism mixed recipes (item_input + chemical_input) — only patch the item part
            SlotData first = inputs.stream().filter(s -> !s.isEmpty()).findFirst().orElse(null);
            if (first != null) {
                JsonObject obj = json.getAsJsonObject("item_input").deepCopy();
                patchIngredientIntoObject(obj, first);
                json.add("item_input", obj);
            }
        } else if (json.has("input") && json.get("input").isJsonObject()) {
            // mekanism:crushing, enriching, etc. — single item input (direct or nested wrapper)
            JsonObject inputObj = json.getAsJsonObject("input");
            SlotData first = inputs.stream().filter(s -> !s.isEmpty()).findFirst().orElse(null);
            if (first != null) {
                if (isItemIngredientObject(inputObj)) {
                    // Direct format: {"item": "..."} or {"tag": "..."}
                    JsonObject obj = inputObj.deepCopy();
                    patchIngredientIntoObject(obj, first);
                    json.add("input", obj);
                } else if (inputObj.has("ingredient") && inputObj.get("ingredient").isJsonObject()) {
                    // Mekanism nested format: {"ingredient": {"item": "..."}}
                    JsonObject obj = inputObj.deepCopy();
                    JsonObject inner = obj.getAsJsonObject("ingredient").deepCopy();
                    patchIngredientIntoObject(inner, first);
                    obj.add("ingredient", inner);
                    json.add("input", obj);
                }
            }
        }

        // ── Patch fluidIngredient (Create Filling and similar) ──────────────────
        // This is independent of the ingredients/ingredient block above since a recipe
        // can have BOTH "ingredient" (item) AND "fluidIngredient" (fluid).
        if (json.has("fluidIngredient") && json.get("fluidIngredient").isJsonObject()) {
            SlotData fluidSlot = inputs.stream()
                    .filter(s -> s.isFluid && s.fluidId != null)
                    .findFirst().orElse(null);
            if (fluidSlot != null) {
                JsonObject fi = json.getAsJsonObject("fluidIngredient").deepCopy();
                fi.remove("fluidTag"); // replace tag reference with explicit fluid ID
                fi.addProperty("fluid", fluidSlot.fluidId.toString());
                fi.addProperty("amount", fluidSlot.fluidAmount);
                json.add("fluidIngredient", fi);
            }
        }

        // ── Patch result fields ─────────────────────────────────────────────
        // Detect whether the mod uses "id" (MC 1.21+, Create, Mekanism) or "item" as the item key.
        String outId = stripKubeJsWrappers(outputKubeJs);
        SlotData outSlot = slots.stream()
                .filter(s -> s.role == RecipeIngredientRole.OUTPUT && !s.isEmpty())
                .findFirst().orElse(null);

        if (json.has("results") && json.get("results").isJsonArray()) {
            JsonArray origResults = json.getAsJsonArray("results");
            String itemKey = detectItemKey(origResults.isEmpty() ? null
                    : origResults.get(0).isJsonObject() ? origResults.get(0).getAsJsonObject() : null);
            JsonArray results = new JsonArray();
            JsonObject outObj = new JsonObject();
            outObj.addProperty(itemKey, outId);
            if (outSlot != null && outSlot.count > 1) outObj.addProperty("count", outSlot.count);
            String chanceVal = extraParamValues.get("result_chance");
            if (chanceVal != null) {
                try { outObj.addProperty("chance", Double.parseDouble(chanceVal)); }
                catch (NumberFormatException ignored) {}
            }
            results.add(outObj);
            json.add("results", results);
        } else if (json.has("result")) {
            if (json.get("result").isJsonObject()) {
                JsonObject outObj = json.getAsJsonObject("result").deepCopy();
                outObj.addProperty(detectItemKey(outObj), outId);
                if (outSlot != null && outSlot.count > 1) outObj.addProperty("count", outSlot.count);
                json.add("result", outObj);
            } else {
                json.addProperty("result", outId);
            }
        } else if (json.has("output")) {
            JsonElement outputEl = json.get("output");
            if (outputEl.isJsonPrimitive() && !outputEl.getAsJsonPrimitive().isString()) {
                // Numeric output (e.g. Mekanism energy amount) — leave verbatim here;
                // the ExtraParam apply loop below will update it with the user's value.
            } else if (outputEl.isJsonObject()) {
                JsonObject outObj = outputEl.getAsJsonObject();
                if (outObj.has("item") || outObj.has("id") || outObj.has("tag")) {
                    outObj = outObj.deepCopy();
                    outObj.addProperty(detectItemKey(outObj), outId);
                    if (outSlot != null && outSlot.count > 1) outObj.addProperty("count", outSlot.count);
                    json.add("output", outObj);
                }
                // Otherwise: Mekanism chemical/energy output object — leave verbatim
            } else {
                // String output: replace with item ID
                json.addProperty("output", outId);
            }
        }

        // ── Apply user-edited extra param values ────────────────────────────
        // NOTE: no "continue if key absent" — we WANT to add optional fields (e.g. heat_requirement)
        // even when the template example recipe didn't have them.
        if (capturedLayout != null) {
            for (ExtraParam ep : capturedLayout.extraParams()) {
                String val = extraParamValues.getOrDefault(ep.key(), ep.defaultValueStr());
                switch (ep.type()) {
                    case INT     -> json.addProperty(ep.key(), Integer.parseInt(val));
                    case FLOAT   -> {
                        // "result_chance" is a virtual param applied to results[], not a top-level field
                        if (!ep.key().equals("result_chance"))
                            json.addProperty(ep.key(), Double.parseDouble(val));
                    }
                    case BOOLEAN -> json.addProperty(ep.key(), Boolean.parseBoolean(val));
                    case STRING  -> json.addProperty(ep.key(), val);
                    case ENUM    -> {
                        if ("(none)".equals(val)) json.remove(ep.key());
                        else json.addProperty(ep.key(), val);
                    }
                }
            }
        }

        // ── Patch Sequenced Assembly steps ──────────────────────────────────────
        patchSequence(json);

        // ── Format as event.custom({...}) ───────────────────────────────────
        com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
        String pretty = gson.toJson(json);
        String indented = pretty.lines()
                .map(line -> "        " + line)
                .collect(Collectors.joining("\n"));
        return "    event.custom(\n" + indented + "\n    )";
    }

    /** Patches the "sequence" array in the export JSON with the user-edited steps. */
    private void patchSequence(JsonObject json) {
        if (sequenceSteps.isEmpty() || !json.has("sequence")) return;
        JsonArray newSeq = new JsonArray();
        for (SequenceStep step : sequenceSteps) {
            JsonObject stepObj = step.rawStep.deepCopy();
            stepObj.addProperty("type", step.type);
            if (step.slot != null && !step.slot.isEmpty()) {
                JsonObject ingr = new JsonObject();
                if (step.needsFluid() && step.slot.isFluid && step.slot.fluidId != null) {
                    ingr.addProperty("fluid", step.slot.fluidId.toString());
                    ingr.addProperty("amount", step.slot.fluidAmount);
                } else if (!step.slot.ingredient.isEmpty()) {
                    ingr.addProperty("item", step.slot.ingredient.getItem()
                            .builtInRegistryHolder().key().location().toString());
                }
                if (!ingr.isEmpty()) stepObj.add("ingredient", ingr);
            }
            newSeq.add(stepObj);
        }
        json.add("sequence", newSeq);
    }

    /** Builds a JsonArray of ingredient objects from non-empty input slots. */
    private JsonArray buildIngredientArray(List<SlotData> inputs) {
        JsonArray arr = new JsonArray();
        for (SlotData s : inputs) {
            if (!s.isEmpty()) arr.add(buildIngredientElement(s));
        }
        return arr;
    }

    /** Builds a single ingredient JsonElement for one slot (fluid, item, or tag). */
    private JsonElement buildIngredientElement(SlotData s) {
        if (s.isFluid && s.fluidId != null) return buildFluidElement(s);
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
     * Smart version of {@link #buildIngredientArray}: iterates the original codec array and
     * replaces item/tag entries with user inputs. Fluid template entries are replaced when the
     * user has set a fluid at the matching JEI position (via descriptor index map); otherwise
     * they are kept verbatim. Chemical/special entries are always kept verbatim.
     */
    private JsonArray buildIngredientArraySmart(JsonArray original, List<SlotData> inputs) {
        Map<Integer, SlotData> byIdx = buildIngredientIndexMap();
        List<SlotData> nonEmptyItems = inputs.stream().filter(s -> !s.isEmpty() && !s.isFluid).toList();
        int itemIdx = 0;
        JsonArray result = new JsonArray();
        int arrIdx = 0;
        for (JsonElement entry : original) {
            if (entry.isJsonObject()) {
                JsonObject obj = entry.getAsJsonObject();
                SlotData posSlot = byIdx.get(arrIdx);
                if (obj.has("fluid") || obj.has("fluidTag")) {
                    // Fluid entry: replace if user placed a fluid at matching position
                    if (posSlot != null && posSlot.isFluid && posSlot.fluidId != null) {
                        result.add(buildFluidElement(posSlot));
                    } else {
                        result.add(entry); // keep verbatim
                    }
                } else if (isItemIngredientEntry(obj)) {
                    // Item entry: position match first, then sequential fallback
                    if (posSlot != null && !posSlot.isEmpty() && !posSlot.isFluid) {
                        result.add(buildIngredientElement(posSlot));
                    } else if (itemIdx < nonEmptyItems.size()) {
                        result.add(buildIngredientElement(nonEmptyItems.get(itemIdx++)));
                    } else {
                        result.add(entry);
                    }
                } else {
                    result.add(entry); // chemical / special — keep verbatim
                }
            } else {
                result.add(entry);
            }
            arrIdx++;
        }
        return result;
    }

    /** Maps descriptor array indices for "ingredients" to matching SlotData by JEI position. */
    private Map<Integer, SlotData> buildIngredientIndexMap() {
        GuiDescriptor desc = capturedLayout != null ? capturedLayout.guiDescriptor() : null;
        if (desc == null) return Map.of();
        Map<Integer, SlotData> map = new LinkedHashMap<>();
        for (SlotDescriptor sd : desc.slots()) {
            if (!"ingredients".equals(sd.jsonField()) || sd.jsonArrayIndex() < 0) continue;
            for (SlotData slot : slots) {
                if (slot.jeiRelX == sd.jeiX() && slot.jeiRelY == sd.jeiY()) {
                    map.put(sd.jsonArrayIndex(), slot);
                    break;
                }
            }
        }
        return map;
    }

    private JsonElement buildFluidElement(SlotData s) {
        JsonObject obj = new JsonObject();
        if (s.fluidId != null) obj.addProperty("fluid", s.fluidId.toString());
        obj.addProperty("amount", s.fluidAmount);
        return obj;
    }

    /** Returns true if {@code obj} is a plain item/tag ingredient (replaceable by user input). */
    private static boolean isItemIngredientEntry(JsonObject obj) {
        if (obj.has("fluid") || obj.has("chemical")) return false;
        if (obj.has("amount")) return false; // neoforge fluid/tag-with-amount
        return true;
    }

    /**
     * Patches an existing ingredient JsonObject in-place: removes old item/tag keys and writes
     * the new ones from {@code s}. Preserves all other fields (e.g. Mekanism's "count").
     */
    private void patchIngredientIntoObject(JsonObject obj, SlotData s) {
        obj.remove("item");
        obj.remove("tag");
        if (s.useTag && s.selectedTag != null) {
            obj.addProperty("tag", s.selectedTag.toString());
        } else {
            obj.addProperty("item", s.ingredient.getItem()
                    .builtInRegistryHolder().key().location().toString());
        }
    }

    /** Returns true if {@code obj} is an item ingredient (has "item" or "tag" key, not a chemical/fluid). */
    private static boolean isItemIngredientObject(JsonObject obj) {
        return obj.has("item") || obj.has("tag");
    }

    /**
     * Detects whether a result/output JsonObject uses "id" (MC 1.21+, Create, Mekanism) or
     * "item" as the item ID key. Returns "item" if {@code obj} is null or ambiguous.
     */
    private static String detectItemKey(@Nullable JsonObject obj) {
        if (obj != null && obj.has("id") && !obj.has("item")) return "id";
        return "item";
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


    /** Reads the raw recipe JSON from the server's data pack (singleplayer only, any mod). */
    @Nullable
    private static JsonObject readSingleRecipeFromRM(ResourceLocation recipeId) {
        net.minecraft.server.MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return null;
        ResourceLocation jsonPath = ResourceLocation.fromNamespaceAndPath(
                recipeId.getNamespace(), "recipe/" + recipeId.getPath() + ".json");
        try {
            return server.getResourceManager().getResource(jsonPath)
                    .map(res -> {
                        try (java.io.InputStreamReader r = new java.io.InputStreamReader(res.open())) {
                            JsonElement el = com.google.gson.JsonParser.parseReader(r);
                            return el.isJsonObject() ? el.getAsJsonObject() : null;
                        } catch (Exception e) { return null; }
                    }).orElse(null);
        } catch (Exception e) { return null; }
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
        if (isFuelCategory)       return gameDir().resolve("kubejs/startup_scripts/fuel.js");
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
                    + "\n\nItemEvents.modification(event => {\n\n})\n";
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

    /** Called from StepTypePickerPopup when user picks a step type. */
    private void addSequenceStep(String stepType) {
        JsonObject raw = new JsonObject();
        raw.addProperty("type", stepType);
        sequenceSteps.add(new SequenceStep(stepType, raw));
        // Defer init() to next tick — calling it inside the popup callback causes NPE
        // because init() sets activePopup=null mid-handler.
        Minecraft.getInstance().tell(this::rebuildAfterStepChange);
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
        SlotData addedSlot = new SlotData(absX, absY, CRAFT_CELL, CRAFT_CELL, cap.role(), row, col);
        addedSlot.jeiRelX = cap.x();
        addedSlot.jeiRelY = cap.y();
        slots.add(addedSlot);
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
                int raw = Integer.parseInt(cur) + direction * step;
                if (ep.minBound() != Integer.MIN_VALUE) raw = Math.max(ep.minBound(), raw);
                extraParamValues.put(ep.key(), String.valueOf(raw));
            } else if (ep.type() == ExtraParam.Type.FLOAT) {
                double step = shift ? 1.0 : 0.1;
                extraParamValues.put(ep.key(), String.format(Locale.ROOT, "%.2f", Double.parseDouble(cur) + direction * step));
            }
        } catch (NumberFormatException ignored) {}
    }

    private void toggleExtraParam(ExtraParam ep, Button btn) {
        String cur = extraParamValues.getOrDefault(ep.key(), ep.defaultValueStr());
        String next = "true".equalsIgnoreCase(cur) ? "false" : "true";
        extraParamValues.put(ep.key(), next);
        btn.setMessage(Component.literal(next));
    }

    private void cycleEnumParam(ExtraParam ep, Button btn) {
        List<String> vals = ep.enumValues();
        if (vals.isEmpty()) return;
        String cur = extraParamValues.getOrDefault(ep.key(), ep.defaultValueStr());
        int idx = vals.indexOf(cur);
        String next = vals.get((idx + 1) % vals.size());
        extraParamValues.put(ep.key(), next);
        btn.setMessage(Component.literal(next));
    }
}
