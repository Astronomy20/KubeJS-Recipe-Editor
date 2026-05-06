package net.astronomy.jeikubejsexporter.export.formatters;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.astronomy.jeikubejsexporter.export.IngredientFormatter;

public class ShapelessFormatter {

    public static String format(RecipeHolder<ShapelessRecipe> holder) {
        ShapelessRecipe recipe = holder.value();
        String output = IngredientFormatter.formatItemStack(
                recipe.getResultItem(Minecraft.getInstance().level.registryAccess()));

        StringBuilder sb = new StringBuilder();
        sb.append("    event.shapeless(").append(output).append(", [\n");
        for (Ingredient ing : recipe.getIngredients()) {
            sb.append("        ").append(IngredientFormatter.format(ing)).append(",\n");
        }
        sb.append("    ])");
        return sb.toString();
    }
}
