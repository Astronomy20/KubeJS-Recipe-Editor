package net.astronomy.kubejsrecipeeditor.export;

import com.google.gson.JsonElement;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.crafting.*;
import net.astronomy.kubejsrecipeeditor.export.formatters.*;

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

        // If the result ItemStack has non-standard data components, vanilla formatters
        // cannot encode them correctly — redirect to GenericFormatter.
        try {
            var result = holder.value().getResultItem(Minecraft.getInstance().level.registryAccess());
            if (!result.isEmpty() && !result.getComponentsPatch().isEmpty()) {
                return GenericFormatter.format(holder, false);
            }
        } catch (Exception ignored) {}

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
