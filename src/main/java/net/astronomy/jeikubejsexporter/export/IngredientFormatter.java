package net.astronomy.jeikubejsexporter.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.astronomy.jeikubejsexporter.JeiKubeJsExporter;

public class IngredientFormatter {

    public static String format(Ingredient ingredient) {
        if (ingredient.isEmpty()) {
            return "'minecraft:air'";
        }
        try {
            JsonElement json = Ingredient.CODEC.encodeStart(JsonOps.INSTANCE, ingredient).getOrThrow();
            return jsonToKubeJs(json);
        } catch (Exception e) {
            JeiKubeJsExporter.LOGGER.warn("Ingredient codec failed, using fallback: {}", e.getMessage());
            return fallbackFromItems(ingredient);
        }
    }

    public static String jsonToKubeJs(JsonElement json) {
        if (json.isJsonPrimitive()) {
            String str = json.getAsString();
            // "#minecraft:logs" stays as-is (tag), "minecraft:stone" stays as-is (item)
            return "'" + str + "'";
        } else if (json.isJsonObject()) {
            JsonObject obj = json.getAsJsonObject();
            if (obj.has("tag")) {
                return "'#" + obj.get("tag").getAsString() + "'";
            } else if (obj.has("item")) {
                return "'" + obj.get("item").getAsString() + "'";
            }
            return "'" + obj + "'";
        } else if (json.isJsonArray()) {
            JsonArray arr = json.getAsJsonArray();
            if (arr.size() == 1) {
                return jsonToKubeJs(arr.get(0));
            }
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < arr.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(jsonToKubeJs(arr.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        return "'minecraft:air'";
    }

    private static String fallbackFromItems(Ingredient ingredient) {
        ItemStack[] items = ingredient.getItems();
        if (items.length == 0) return "'minecraft:air'";
        if (items.length == 1) return formatItemStack(items[0]);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(formatItemStack(items[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    public static String formatItemStack(ItemStack stack) {
        String id = stack.getItem().builtInRegistryHolder().key().location().toString();
        if (stack.getCount() > 1) {
            return "Item.of('" + id + "', " + stack.getCount() + ")";
        }
        return "'" + id + "'";
    }

    public static boolean recipeJsonContainsFluid(JsonElement json) {
        if (json.isJsonObject()) {
            JsonObject obj = json.getAsJsonObject();
            if (obj.has("fluid") || obj.has("fluidTag")) return true;
            for (var entry : obj.entrySet()) {
                if (recipeJsonContainsFluid(entry.getValue())) return true;
            }
        } else if (json.isJsonArray()) {
            for (JsonElement el : json.getAsJsonArray()) {
                if (recipeJsonContainsFluid(el)) return true;
            }
        }
        return false;
    }
}
