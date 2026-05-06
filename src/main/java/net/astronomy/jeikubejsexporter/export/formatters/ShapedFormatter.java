package net.astronomy.jeikubejsexporter.export.formatters;

import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.astronomy.jeikubejsexporter.export.IngredientFormatter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ShapedFormatter {

    public static String format(RecipeHolder<ShapedRecipe> holder) {
        ShapedRecipe recipe = holder.value();
        int width = recipe.pattern.width();
        int height = recipe.pattern.height();
        List<Ingredient> ingredients = recipe.getIngredients();

        // Map JSON representation → assigned letter (deduplicates identical ingredients)
        Map<String, Character> jsonToLetter = new LinkedHashMap<>();
        Map<String, String> jsonToFormatted = new LinkedHashMap<>();
        char letter = 'A';

        for (Ingredient ing : ingredients) {
            if (ing.isEmpty()) continue;
            String key = ingredientKey(ing);
            if (!jsonToLetter.containsKey(key)) {
                jsonToLetter.put(key, letter++);
                jsonToFormatted.put(key, IngredientFormatter.format(ing));
            }
        }

        // Build pattern rows
        List<String> rows = new ArrayList<>();
        for (int row = 0; row < height; row++) {
            StringBuilder rowStr = new StringBuilder();
            for (int col = 0; col < width; col++) {
                Ingredient ing = ingredients.get(row * width + col);
                rowStr.append(ing.isEmpty() ? ' ' : jsonToLetter.get(ingredientKey(ing)));
            }
            rows.add(rowStr.toString());
        }

        String output = IngredientFormatter.formatItemStack(
                recipe.getResultItem(Minecraft.getInstance().level.registryAccess()));

        StringBuilder sb = new StringBuilder();
        sb.append("    event.shaped(").append(output).append(", [\n");
        for (String row : rows) {
            sb.append("        '").append(row).append("',\n");
        }
        sb.append("    ], {\n");
        for (Map.Entry<String, Character> entry : jsonToLetter.entrySet()) {
            sb.append("        ").append(entry.getValue()).append(": ")
              .append(jsonToFormatted.get(entry.getKey())).append(",\n");
        }
        sb.append("    })");
        return sb.toString();
    }

    private static String ingredientKey(Ingredient ing) {
        try {
            return Ingredient.CODEC.encodeStart(JsonOps.INSTANCE, ing).getOrThrow().toString();
        } catch (Exception e) {
            return ing.toString();
        }
    }
}
