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
        return ingredient.isEmpty();
    }

    public boolean contains(int mouseX, int mouseY) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** Returns the KubeJS ingredient string for export. */
    public String toKubeJs() {
        if (ingredient.isEmpty()) return "'minecraft:air'";
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
    }
}
