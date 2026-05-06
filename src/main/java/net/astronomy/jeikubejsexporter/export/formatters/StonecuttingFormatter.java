package net.astronomy.jeikubejsexporter.export.formatters;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.astronomy.jeikubejsexporter.export.IngredientFormatter;

public class StonecuttingFormatter {

    public static String format(RecipeHolder<StonecutterRecipe> holder) {
        StonecutterRecipe recipe = holder.value();
        ItemStack result = recipe.getResultItem(Minecraft.getInstance().level.registryAccess());
        String output = IngredientFormatter.formatItemStack(result);
        String input = IngredientFormatter.format(recipe.getIngredients().get(0));
        return "    event.stonecutting(" + output + ", " + input + ")";
    }
}
