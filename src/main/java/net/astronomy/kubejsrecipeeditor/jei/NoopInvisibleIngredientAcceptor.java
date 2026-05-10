package net.astronomy.kubejsrecipeeditor.jei;

import mezz.jei.api.gui.builder.IIngredientAcceptor;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.level.material.Fluid;

import java.util.List;
import java.util.Optional;

/**
 * Swallows invisible lookup-only ingredients during layout capture.
 */
public final class NoopInvisibleIngredientAcceptor implements IIngredientAcceptor<NoopInvisibleIngredientAcceptor> {

    @Override
    public <I> NoopInvisibleIngredientAcceptor addIngredient(IIngredientType<I> ingredientType, I ingredient) {
        return this;
    }

    @Override
    public <I> NoopInvisibleIngredientAcceptor addIngredients(IIngredientType<I> ingredientType, List<I> ingredients) {
        return this;
    }

    @Override
    public NoopInvisibleIngredientAcceptor addIngredientsUnsafe(List<?> ingredients) {
        return this;
    }

    @Override
    public NoopInvisibleIngredientAcceptor addTypedIngredients(List<ITypedIngredient<?>> ingredients) {
        return this;
    }

    @Override
    public NoopInvisibleIngredientAcceptor addOptionalTypedIngredients(List<Optional<ITypedIngredient<?>>> ingredients) {
        return this;
    }

    @Override
    public NoopInvisibleIngredientAcceptor addFluidStack(Fluid fluid) {
        return this;
    }

    @Override
    public NoopInvisibleIngredientAcceptor addFluidStack(Fluid fluid, long amount) {
        return this;
    }

    @Override
    public NoopInvisibleIngredientAcceptor addFluidStack(Fluid fluid, long amount, DataComponentPatch componentPatch) {
        return this;
    }
}
