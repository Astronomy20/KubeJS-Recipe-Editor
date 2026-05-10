package net.astronomy.kubejsrecipeeditor.gui;

import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.RecipeType;
import net.astronomy.kubejsrecipeeditor.jei.SlotCapturingLayoutBuilder;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Snapshot of a JEI recipe category layout: clean background drawable, captured slot anchors,
 * input-slot count range (for variable-slot categories), and extra codec parameters.
 */
public record RecipeTemplate(
        RecipeType<?> type,
        String title,
        @Nullable IDrawable background,
        @Nullable IDrawable icon,
        /** All slots captured from the recipe that has the MOST input slots. */
        List<SlotCapturingLayoutBuilder.CapturedSlot> slots,
        @Nullable Object exampleRecipe,
        /** Minimum INPUT slot count found across sampled recipes. */
        int minInputSlots,
        /** Maximum INPUT slot count found across sampled recipes. */
        int maxInputSlots,
        /** Extra primitive parameters detected from the codec JSON (e.g. loops, heatRequirement). */
        List<ExtraParam> extraParams
) {}
