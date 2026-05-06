package net.astronomy.kubejsrecipeeditor.export.formatters;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.crafting.*;
import net.astronomy.kubejsrecipeeditor.export.IngredientFormatter;

public class CookingFormatter {

    public static String format(RecipeHolder<? extends AbstractCookingRecipe> holder) {
        AbstractCookingRecipe recipe = holder.value();
        String builderName = builderName(recipe);
        String output = IngredientFormatter.formatItemStack(
                recipe.getResultItem(Minecraft.getInstance().level.registryAccess()));
        String input = IngredientFormatter.format(recipe.getIngredients().get(0));

        StringBuilder sb = new StringBuilder();
        sb.append("    event.").append(builderName).append("(").append(output).append(", ").append(input).append(")");

        if (recipe.getExperience() != 0f) {
            sb.append(".xp(").append(recipe.getExperience()).append(")");
        }
        if (recipe.getCookingTime() != defaultTime(recipe)) {
            sb.append(".cookingTime(").append(recipe.getCookingTime()).append(")");
        }

        return sb.toString();
    }

    private static String builderName(AbstractCookingRecipe recipe) {
        return switch (recipe) {
            case BlastingRecipe ignored -> "blasting";
            case SmokingRecipe ignored -> "smoking";
            case CampfireCookingRecipe ignored -> "campfireCooking";
            default -> "smelting";
        };
    }

    private static int defaultTime(AbstractCookingRecipe recipe) {
        return switch (recipe) {
            case BlastingRecipe ignored -> 100;
            case SmokingRecipe ignored -> 100;
            case CampfireCookingRecipe ignored -> 600;
            default -> 200;
        };
    }
}
