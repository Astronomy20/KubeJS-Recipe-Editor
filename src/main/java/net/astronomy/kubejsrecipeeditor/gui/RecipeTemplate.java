package net.astronomy.kubejsrecipeeditor.gui;

import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.RecipeType;
import net.astronomy.kubejsrecipeeditor.jei.SlotCapturingLayoutBuilder;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Snapshot of a JEI recipe category layout: clean background drawable and captured slot anchors.
 */
public record RecipeTemplate(
        RecipeType<?> type,
        String title,
        @Nullable IDrawable background,
        @Nullable IDrawable icon,
        List<SlotCapturingLayoutBuilder.CapturedSlot> slots,
        @Nullable Object exampleRecipe
) {}
