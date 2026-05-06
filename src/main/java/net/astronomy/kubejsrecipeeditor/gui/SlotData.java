package net.astronomy.kubejsrecipeeditor.gui;

import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.world.item.ItemStack;

public class SlotData {
    public final int x;
    public final int y;
    public final int w;
    public final int h;
    public final RecipeIngredientRole role;
    public final int gridRow;
    public final int gridCol;
    public ItemStack ingredient = ItemStack.EMPTY;

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
}
