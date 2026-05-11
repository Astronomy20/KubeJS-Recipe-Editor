package net.astronomy.kubejsrecipeeditor.gui;

import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.astronomy.kubejsrecipeeditor.export.IngredientFormatter;

public class SlotData {
    public final int x;
    public final int y;
    public final int w;
    public final int h;
    public final RecipeIngredientRole role;
    public final int gridRow;
    public final int gridCol;

    public ItemStack ingredient = ItemStack.EMPTY;
    public boolean useTag = false;
    public ResourceLocation selectedTag = null;
    public int count = 1;

    // Fluid support
    public boolean isFluid = false;
    public ResourceLocation fluidId = null;
    public long fluidAmount = 1000L;

    // JEI-relative coordinates for RecipeJsonBuilder matching (-1 = not from CapturedSlot)
    public int jeiRelX = -1;
    public int jeiRelY = -1;

    public SlotData(int x, int y, int w, int h, RecipeIngredientRole role, int gridRow, int gridCol) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.role = role;
        this.gridRow = gridRow;
        this.gridCol = gridCol;
    }

    public boolean isEmpty() {
        return ingredient.isEmpty() && !isFluid;
    }

    public boolean contains(int mouseX, int mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** Returns the KubeJS ingredient string for export. */
    public String toKubeJs() {
        if (ingredient.isEmpty() && !isFluid) return "'minecraft:air'";
        if (isFluid && fluidId != null) {
            // For vanilla builders; custom recipes use RecipeJsonBuilder directly
            return "Fluid.of('" + fluidId + "', " + fluidAmount + ")";
        }
        // Output slots must always be a concrete item — tags are only valid for inputs
        if (useTag && selectedTag != null && role != RecipeIngredientRole.OUTPUT)
            return "'#" + selectedTag + "'";
        return IngredientFormatter.formatItemStack(ingredient.copyWithCount(count));
    }

    public void clear() {
        ingredient = ItemStack.EMPTY;
        useTag = false;
        selectedTag = null;
        count = 1;
        isFluid = false;
        fluidId = null;
        fluidAmount = 1000L;
    }
}
