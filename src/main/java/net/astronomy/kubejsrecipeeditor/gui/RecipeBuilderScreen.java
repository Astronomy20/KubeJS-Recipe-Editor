package net.astronomy.kubejsrecipeeditor.gui;

import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawablesView;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.astronomy.kubejsrecipeeditor.KubeJsRecipeEditor;
import net.astronomy.kubejsrecipeeditor.export.IngredientFormatter;
import net.astronomy.kubejsrecipeeditor.jei.JeiIntegration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RecipeBuilderScreen extends AbstractContainerScreen<RecipeBuilderMenu> {
    private static final int TOP_BAR   = 24;
    private static final int BOTTOM_BAR = 28;
    private static final int PADDING    = 8;

    private final IRecipeCategory<?> category;
    private final List<SlotData> slots = new ArrayList<>();
    private final List<int[]> allSlotCovers = new ArrayList<>();
    private IRecipeLayoutDrawable<?> templateLayout;

    private int recipeX, recipeY;
    private String statusMessage = "";
    private int statusColor = 0xFFFFFF;

    public RecipeBuilderScreen(RecipeBuilderMenu menu, Inventory inventory, IRecipeCategory<?> category) {
        super(menu, inventory, Component.literal(category.getTitle().getString()));
        this.category = category;
        // imageWidth / imageHeight drive leftPos / topPos in init()
        this.imageWidth  = Math.max(240, category.getWidth()  + PADDING * 2);
        this.imageHeight = TOP_BAR + PADDING + category.getHeight() + PADDING + 10 + BOTTOM_BAR;
    }

    // Public getters used by JeiIntegration for addGuiContainerHandler
    public int getWinX() { return leftPos; }
    public int getWinY() { return topPos; }
    public int getWinW() { return imageWidth; }
    public int getWinH() { return imageHeight; }

    @Override
    protected void init() {
        super.init(); // sets leftPos = (width - imageWidth)/2, topPos = (height - imageHeight)/2

        slots.clear();
        allSlotCovers.clear();
        templateLayout = null;

        recipeX = leftPos + (imageWidth - category.getWidth()) / 2;
        recipeY = topPos + TOP_BAR + PADDING;

        buildSlotsFromJei();

        addRenderableWidget(Button.builder(Component.literal("⌂"), btn -> goHome())
                .pos(leftPos + 4, topPos + 4).size(20, 16).build());

        addRenderableWidget(Button.builder(Component.literal("Clear"), btn -> clearSlots())
                .pos(leftPos + imageWidth / 2 - 46, topPos + imageHeight - 22).size(44, 16).build());

        addRenderableWidget(Button.builder(Component.literal("Export"), btn -> exportRecipe())
                .pos(leftPos + imageWidth / 2 + 2, topPos + imageHeight - 22).size(44, 16).build());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void buildSlotsFromJei() {
        IJeiRuntime runtime = JeiIntegration.getRuntime();
        if (runtime == null) return;
        try {
            IFocusGroup emptyFocus = runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
            Optional template = runtime.getRecipeManager()
                    .createRecipeLookup((mezz.jei.api.recipe.RecipeType) category.getRecipeType())
                    .get().findFirst();
            if (template.isEmpty()) return;

            Optional layoutOpt = runtime.getRecipeManager()
                    .createRecipeLayoutDrawable((IRecipeCategory) category, template.get(), emptyFocus);
            if (layoutOpt.isEmpty()) return;

            IRecipeLayoutDrawable layout = (IRecipeLayoutDrawable) layoutOpt.get();
            layout.setPosition(recipeX, recipeY);
            templateLayout = layout;

            if (!(layout.getRecipeSlotsView() instanceof IRecipeSlotDrawablesView drawablesView)) return;

            for (IRecipeSlotDrawable slotDrawable : drawablesView.getSlots()) {
                RecipeIngredientRole role = slotDrawable.getRole();
                var rect = slotDrawable.getAreaIncludingBackground();
                int absX = recipeX + rect.getX();
                int absY = recipeY + rect.getY();

                allSlotCovers.add(new int[]{absX, absY, rect.getWidth(), rect.getHeight()});
                if (role == RecipeIngredientRole.RENDER_ONLY) continue;

                int row = rect.getHeight() > 0 ? rect.getY() / rect.getHeight() : 0;
                int col = rect.getWidth() > 0 ? rect.getX() / rect.getWidth() : 0;
                slots.add(new SlotData(absX, absY, rect.getWidth(), rect.getHeight(), role, row, col));
            }
        } catch (Exception e) {
            KubeJsRecipeEditor.LOGGER.error("Failed to build slots for {}: {}",
                    category.getRecipeType().getUid(), e.getMessage());
        }
    }

    // ─── Rendering ────────────────────────────────────────────────────────────

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Skip renderTransparentBackground() so the game world stays visible on the sides
        // (JEI needs that space). Only draw our window panel.
        renderBg(g, partialTick, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        // Window chrome
        g.fill(leftPos + 3, topPos + 3, leftPos + imageWidth + 3, topPos + imageHeight + 3, 0x55000000);
        g.fill(leftPos - 1, topPos - 1, leftPos + imageWidth + 1, topPos + imageHeight + 1, 0xFF555555);
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF1E1E1E);
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + TOP_BAR, 0xFF2D2D2D);

        // JEI recipe layout (grid + template items)
        if (templateLayout != null) {
            try { templateLayout.drawRecipe(g, mouseX, mouseY); } catch (Exception ignored) {}
        }

        // Cover the interior of every slot to hide template items, leaving JEI's 1px border visible
        for (int[] r : allSlotCovers) {
            g.fill(r[0] + 1, r[1] + 1, r[0] + r[2] - 1, r[1] + r[3] - 1, 0xFF3C3C3C);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // Custom title in the top bar (coords are relative to leftPos, topPos in AbstractContainerScreen)
        g.drawCenteredString(font, title, imageWidth / 2, 8, 0xFFFFFF);
        // Status message
        if (!statusMessage.isEmpty()) {
            g.drawCenteredString(font, Component.literal(statusMessage),
                    imageWidth / 2, imageHeight - BOTTOM_BAR - 8, statusColor);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick); // renderBackground + widgets + renderLabels

        // Interactive slots on top
        for (SlotData slot : slots) {
            renderSlot(g, slot, mouseX, mouseY);
        }

        // Item tooltips
        for (SlotData slot : slots) {
            if (slot.contains(mouseX, mouseY) && !slot.ingredient.isEmpty()) {
                g.renderTooltip(font, slot.ingredient, mouseX, mouseY);
                break;
            }
        }
    }

    private void renderSlot(GuiGraphics g, SlotData slot, int mouseX, int mouseY) {
        boolean hover = slot.contains(mouseX, mouseY);
        if (slot.role == RecipeIngredientRole.OUTPUT) {
            g.fill(slot.x - 1, slot.y - 1, slot.x + slot.w + 1, slot.y + slot.h + 1, 0x88FFAA00);
        }
        if (hover) {
            g.fill(slot.x + 1, slot.y + 1, slot.x + slot.w - 1, slot.y + slot.h - 1, 0x44FFFFFF);
        }
        if (!slot.ingredient.isEmpty()) {
            g.renderItem(slot.ingredient, slot.x, slot.y);
            g.renderItemDecorations(font, slot.ingredient, slot.x, slot.y);
            if (hover) {
                g.drawString(font, "×", slot.x + slot.w - 5, slot.y, 0xFFFF5555, false);
            }
        }
    }

    // ─── Input ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 || button == 1) {
            for (SlotData slot : slots) {
                if (slot.contains((int) mouseX, (int) mouseY)) {
                    slot.ingredient = ItemStack.EMPTY;
                    statusMessage = "";
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ─── Ghost ingredient handler access ─────────────────────────────────────

    public List<SlotData> getInteractiveSlots() {
        return slots;
    }

    // ─── Actions ─────────────────────────────────────────────────────────────

    private void clearSlots() {
        slots.forEach(s -> s.ingredient = ItemStack.EMPTY);
        statusMessage = "";
    }

    private void goHome() {
        ModMenuScreen home = new ModMenuScreen();
        GuiSessionState.setLastCategory(null);
        GuiSessionState.setLastScreen(home);
        minecraft.setScreen(home);
    }

    private void exportRecipe() {
        boolean hasOutput = slots.stream().anyMatch(s -> s.role == RecipeIngredientRole.OUTPUT && !s.isEmpty());
        if (!hasOutput) { statusMessage = "Export failed: output slot is empty"; statusColor = 0xFF5555; return; }
        boolean hasInput  = slots.stream().anyMatch(s -> s.role == RecipeIngredientRole.INPUT  && !s.isEmpty());
        if (!hasInput)  { statusMessage = "Export failed: no ingredients defined"; statusColor = 0xFF5555; return; }

        try {
            String js = buildKubeJs();
            Path outFile = resolveOutputFile();
            Files.createDirectories(outFile.getParent());

            String existing = Files.exists(outFile) ? Files.readString(outFile) : buildFileHeader();
            String trimmed  = existing.stripTrailing();
            if (trimmed.endsWith("})")) trimmed = trimmed.substring(0, trimmed.length() - 2);
            Files.writeString(outFile, trimmed + "\n    // " + recipeName() + "\n" + js + "\n\n})\n");

            Path rel = Minecraft.getInstance().gameDirectory.toPath().relativize(outFile);
            statusMessage = "Saved to " + rel;
            statusColor = 0x55FF55;
        } catch (Exception e) {
            statusMessage = "Export failed: " + e.getMessage();
            statusColor = 0xFF5555;
        }
    }

    // ─── KubeJS output builders ───────────────────────────────────────────────

    private String buildKubeJs() {
        ResourceLocation uid = category.getRecipeType().getUid();
        String ns   = uid.getNamespace();
        String path = uid.getPath();

        SlotData outputSlot = slots.stream()
                .filter(s -> s.role == RecipeIngredientRole.OUTPUT && !s.isEmpty())
                .findFirst().orElse(null);
        String output = outputSlot != null
                ? IngredientFormatter.formatItemStack(outputSlot.ingredient) : "'minecraft:air'";
        List<SlotData> inputs = slots.stream().filter(s -> s.role == RecipeIngredientRole.INPUT).toList();

        if (ns.equals("minecraft")) {
            return switch (path) {
                case "crafting", "crafting_shaped", "crafting_shapeless" -> buildCrafting(output, inputs);
                case "smelting"       -> buildCooking("smelting",       output, inputs);
                case "blasting"       -> buildCooking("blasting",       output, inputs);
                case "smoking"        -> buildCooking("smoking",        output, inputs);
                case "campfire_cooking" -> buildCooking("campfireCooking", output, inputs);
                case "stonecutting"   -> buildCooking("stonecutting",   output, inputs);
                case "smithing", "smithing_transform" -> buildSmithing(output);
                default               -> buildCustom(uid, output, inputs);
            };
        }
        return buildCustom(uid, output, inputs);
    }

    private String buildCrafting(String output, List<SlotData> inputs) {
        if (inputs.stream().noneMatch(s -> !s.isEmpty())) return "    // No inputs";
        int maxRow = inputs.stream().mapToInt(s -> s.gridRow).max().orElse(0);
        int maxCol = inputs.stream().mapToInt(s -> s.gridCol).max().orElse(0);
        ItemStack[][] grid = new ItemStack[maxRow + 1][maxCol + 1];
        for (var row : grid) java.util.Arrays.fill(row, ItemStack.EMPTY);
        for (SlotData s : inputs)
            if (s.gridRow <= maxRow && s.gridCol <= maxCol) grid[s.gridRow][s.gridCol] = s.ingredient;

        java.util.Map<String, Character> keyMap = new java.util.LinkedHashMap<>();
        char letter = 'A';
        for (var row : grid)
            for (var stack : row)
                if (!stack.isEmpty()) {
                    String id = stack.getItem().builtInRegistryHolder().key().location().toString();
                    if (!keyMap.containsKey(id)) keyMap.put(id, letter++);
                }

        StringBuilder sb = new StringBuilder("    event.shaped(").append(output).append(", [\n");
        for (var row : grid) {
            sb.append("        '");
            for (var stack : row) {
                if (stack.isEmpty()) sb.append(' ');
                else sb.append(keyMap.get(stack.getItem().builtInRegistryHolder().key().location().toString()));
            }
            sb.append("',\n");
        }
        sb.append("    ], {\n");
        for (var e : keyMap.entrySet())
            sb.append("        ").append(e.getValue()).append(": '").append(e.getKey()).append("',\n");
        sb.append("    })");
        return sb.toString();
    }

    private String buildCooking(String method, String output, List<SlotData> inputs) {
        String input = inputs.stream().filter(s -> !s.isEmpty()).findFirst()
                .map(s -> IngredientFormatter.formatItemStack(s.ingredient))
                .orElse("'minecraft:air'");
        return "    event." + method + "(" + output + ", " + input + ")";
    }

    private String buildSmithing(String output) {
        List<SlotData> ins = slots.stream().filter(s -> s.role == RecipeIngredientRole.INPUT).toList();
        String t = ins.size() > 0 && !ins.get(0).isEmpty() ? IngredientFormatter.formatItemStack(ins.get(0).ingredient) : "'minecraft:air'";
        String b = ins.size() > 1 && !ins.get(1).isEmpty() ? IngredientFormatter.formatItemStack(ins.get(1).ingredient) : "'minecraft:air'";
        String a = ins.size() > 2 && !ins.get(2).isEmpty() ? IngredientFormatter.formatItemStack(ins.get(2).ingredient) : "'minecraft:air'";
        return "    event.smithing(\n        " + t + ",\n        " + b + ",\n        " + a + ",\n        " + output + "\n    )";
    }

    private String buildCustom(ResourceLocation uid, String output, List<SlotData> inputs) {
        StringBuilder sb = new StringBuilder("    event.custom({\n");
        sb.append("        \"type\": \"").append(uid).append("\",\n");
        sb.append("        \"ingredients\": [\n");
        for (SlotData s : inputs)
            if (!s.isEmpty()) {
                String id = s.ingredient.getItem().builtInRegistryHolder().key().location().toString();
                sb.append("            { \"item\": \"").append(id).append("\" },\n");
            }
        sb.append("        ],\n");
        sb.append("        \"result\": { \"item\": ").append(output).append(" }\n    })");
        return sb.toString();
    }

    private Path resolveOutputFile() {
        ResourceLocation uid = category.getRecipeType().getUid();
        String folder = uid.getNamespace().equals("minecraft") ? "vanilla" : uid.getNamespace();
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("kubejs/server_scripts/" + folder + "/" + uid.getPath() + ".js");
    }

    private String buildFileHeader() {
        ResourceLocation uid = category.getRecipeType().getUid();
        return "// Auto-generated by KubeJS Recipe Editor\n"
                + "// Recipe type: " + uid + "\n"
                + "// Generated: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\n\n"
                + "ServerEvents.recipes(event => {\n\n})\n";
    }

    private String recipeName() {
        return slots.stream()
                .filter(s -> s.role == RecipeIngredientRole.OUTPUT && !s.isEmpty())
                .findFirst()
                .map(s -> s.ingredient.getItem().builtInRegistryHolder().key().location().toString())
                .orElse("custom");
    }
}
