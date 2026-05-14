package net.astronomy.kubejsrecipeeditor.jei;

import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotRichTooltipCallback;
import mezz.jei.api.gui.placement.HorizontalAlignment;
import mezz.jei.api.gui.placement.VerticalAlignment;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.level.material.Fluid;

import java.util.List;
import java.util.Optional;

/**
 * Returned from {@link SlotCapturingLayoutBuilder#addSlot}; records coordinates on {@link #setPosition}.
 */
@SuppressWarnings({"removal", "rawtypes"})
final class CapturingRecipeSlotBuilder implements IRecipeSlotBuilder {

    private static final int SLOT_DRAW_SIZE = 18;

    private final SlotCapturingLayoutBuilder parent;
    private final RecipeIngredientRole role;
    private boolean isFluid = false;
    private int mySlotIndex = -1;

    CapturingRecipeSlotBuilder(SlotCapturingLayoutBuilder parent, RecipeIngredientRole role) {
        this.parent = parent;
        this.role = role;
    }

    private void markFluid() {
        isFluid = true;
        if (mySlotIndex >= 0) parent.updateSlotFluid(mySlotIndex);
    }

    @Override
    public IRecipeSlotBuilder setPosition(int xPos, int yPos) {
        mySlotIndex = parent.recordSlotAndGetIndex(role, xPos, yPos, isFluid);
        return this;
    }

    @Override
    public IRecipeSlotBuilder setPosition(
            int areaX,
            int areaY,
            int areaWidth,
            int areaHeight,
            HorizontalAlignment horizontalAlignment,
            VerticalAlignment verticalAlignment) {
        int x = areaX + horizontalAlignment.getXPos(areaWidth, getWidth());
        int y = areaY + verticalAlignment.getYPos(areaHeight, getHeight());
        return setPosition(x, y);
    }

    @Override
    public int getWidth() {
        return SLOT_DRAW_SIZE;
    }

    @Override
    public int getHeight() {
        return SLOT_DRAW_SIZE;
    }

    @Override
    public IRecipeSlotBuilder addTooltipCallback(mezz.jei.api.gui.ingredient.IRecipeSlotTooltipCallback tooltipCallback) {
        return this;
    }

    @Override
    public IRecipeSlotBuilder addRichTooltipCallback(IRecipeSlotRichTooltipCallback tooltipCallback) {
        return this;
    }

    @Override
    public IRecipeSlotBuilder setSlotName(String slotName) {
        return this;
    }

    @Override
    public IRecipeSlotBuilder setStandardSlotBackground() {
        return this;
    }

    @Override
    public IRecipeSlotBuilder setOutputSlotBackground() {
        return this;
    }

    @Override
    public IRecipeSlotBuilder setBackground(IDrawable background, int xOffset, int yOffset) {
        return this;
    }

    @Override
    public IRecipeSlotBuilder setOverlay(IDrawable overlay, int xOffset, int yOffset) {
        return this;
    }

    @Override
    public IRecipeSlotBuilder setFluidRenderer(long capacity, boolean showCapacity, int width, int height) {
        markFluid();
        return this;
    }

    @Override
    public IRecipeSlotBuilder setCustomRenderer(IIngredientType ingredientType, IIngredientRenderer ingredientRenderer) {
        return this;
    }

    @Override
    public <I> IRecipeSlotBuilder addIngredient(IIngredientType<I> ingredientType, I ingredient) {
        return this;
    }

    @Override
    public <I> IRecipeSlotBuilder addIngredients(IIngredientType<I> ingredientType, List<I> ingredients) {
        return this;
    }

    @Override
    public IRecipeSlotBuilder addIngredientsUnsafe(List<?> ingredients) {
        return this;
    }

    @Override
    public IRecipeSlotBuilder addTypedIngredients(List<ITypedIngredient<?>> ingredients) {
        return this;
    }

    @Override
    public IRecipeSlotBuilder addOptionalTypedIngredients(List<Optional<ITypedIngredient<?>>> ingredients) {
        return this;
    }

    @Override
    public IRecipeSlotBuilder addFluidStack(Fluid fluid) {
        markFluid();
        return this;
    }

    @Override
    public IRecipeSlotBuilder addFluidStack(Fluid fluid, long amount) {
        markFluid();
        return this;
    }

    @Override
    public IRecipeSlotBuilder addFluidStack(Fluid fluid, long amount, DataComponentPatch component) {
        markFluid();
        return this;
    }
}
