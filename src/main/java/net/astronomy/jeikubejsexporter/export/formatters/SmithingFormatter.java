package net.astronomy.jeikubejsexporter.export.formatters;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.astronomy.jeikubejsexporter.export.GenericFormatter;
import net.astronomy.jeikubejsexporter.export.IngredientFormatter;

public class SmithingFormatter {

    public static String formatTransform(RecipeHolder<SmithingTransformRecipe> holder) {
        try {
            // Fields template/base/addition are package-private; extract via codec JSON
            JsonElement json = GenericFormatter.encodeRecipe(holder);
            if (!json.isJsonObject()) {
                return GenericFormatter.format(holder, false);
            }
            JsonObject obj = json.getAsJsonObject();

            if (!obj.has("template") || !obj.has("base") || !obj.has("addition")) {
                return GenericFormatter.format(holder, false);
            }

            String templateStr = IngredientFormatter.jsonToKubeJs(obj.get("template"));
            String baseStr = IngredientFormatter.jsonToKubeJs(obj.get("base"));
            String additionStr = IngredientFormatter.jsonToKubeJs(obj.get("addition"));
            String result = IngredientFormatter.formatItemStack(
                    holder.value().getResultItem(Minecraft.getInstance().level.registryAccess()));

            return "    event.smithing(\n" +
                   "        " + templateStr + ",\n" +
                   "        " + baseStr + ",\n" +
                   "        " + additionStr + ",\n" +
                   "        " + result + "\n" +
                   "    )";
        } catch (Exception e) {
            return GenericFormatter.format(holder, false);
        }
    }
}
