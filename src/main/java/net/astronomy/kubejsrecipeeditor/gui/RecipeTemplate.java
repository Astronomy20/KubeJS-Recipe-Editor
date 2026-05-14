package net.astronomy.kubejsrecipeeditor.gui;

import com.google.gson.JsonObject;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.recipe.RecipeType;
import net.astronomy.kubejsrecipeeditor.engine.FieldDescriptor;
import net.astronomy.kubejsrecipeeditor.jei.SlotCapturingLayoutBuilder;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Snapshot of a JEI recipe category layout: clean background drawable, captured slot anchors,
 * input-slot count range (for variable-slot categories), extra codec parameters, and a merged
 * JSON export template built from all sampled recipes of this type.
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
        List<ExtraParam> extraParams,
        /**
         * Merged JSON template built by combining codec output of all sampled recipes.
         * Contains ALL fields seen across any recipe of this type (including optional ones like
         * heat_requirement). Used as the structural template in buildCustomViaCodec.
         * May be null for legacy cache entries or composting/fuel categories.
         */
        @Nullable JsonObject exportTemplate,
        /** GUI descriptor built by the template engine. Null until JEI runtime populates it. */
        @Nullable GuiDescriptor guiDescriptor,
        /** FieldDescriptor tree inferred by SchemaInferenceEngine. Null until Fase C populates it. */
        @Nullable FieldDescriptor.ObjectField fieldDescriptor
) {}
