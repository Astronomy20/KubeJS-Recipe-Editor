package net.astronomy.jeikubejsexporter.export;

import com.google.gson.JsonElement;
import net.minecraft.world.item.crafting.*;
import net.astronomy.jeikubejsexporter.export.formatters.*;

public class KubeJsFormatter {

    @SuppressWarnings("unchecked")
    public static String format(RecipeHolder<?> holder) {
        // Check for fluid ingredients in the encoded JSON before dispatching
        try {
            JsonElement json = GenericFormatter.encodeRecipe(holder);
            if (IngredientFormatter.recipeJsonContainsFluid(json)) {
                return GenericFormatter.format(holder, true);
            }
        } catch (Exception ignored) {
            // If encoding fails here, GenericFormatter.format() will handle the error properly
        }

        Recipe<?> recipe = holder.value();

        try {
            if (recipe instanceof ShapedRecipe) {
                return ShapedFormatter.format((RecipeHolder<ShapedRecipe>) holder);
            } else if (recipe instanceof ShapelessRecipe) {
                return ShapelessFormatter.format((RecipeHolder<ShapelessRecipe>) holder);
            } else if (recipe instanceof AbstractCookingRecipe) {
                return CookingFormatter.format((RecipeHolder<AbstractCookingRecipe>) holder);
            } else if (recipe instanceof StonecutterRecipe) {
                return StonecuttingFormatter.format((RecipeHolder<StonecutterRecipe>) holder);
            } else if (recipe instanceof SmithingTransformRecipe) {
                return SmithingFormatter.formatTransform((RecipeHolder<SmithingTransformRecipe>) holder);
            } else {
                return GenericFormatter.format(holder, false);
            }
        } catch (Exception e) {
            return "    // ERROR: " + e.getMessage().replace("\n", " ");
        }
    }
}
