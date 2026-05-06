package net.astronomy.kubejsrecipeeditor.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.astronomy.kubejsrecipeeditor.KubeJsRecipeEditor;

public class GenericFormatter {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static String format(RecipeHolder<?> holder, boolean fluidRecipe) {
        try {
            JsonElement json = encodeRecipe(holder);
            ensureTypeField(json, holder);
            String jsonStr = GSON.toJson(json);
            // Indent each line by 4 spaces to nest inside the event callback
            String indented = "    " + jsonStr.replace("\n", "\n    ");
            StringBuilder sb = new StringBuilder();
            if (fluidRecipe) {
                sb.append("    // [FLUID RECIPE]\n");
            }
            sb.append("    event.custom(").append(indented).append(")");
            return sb.toString();
        } catch (Exception e) {
            KubeJsRecipeEditor.LOGGER.error("Failed to encode recipe {}: {}", holder.id(), e.getMessage());
            return "    // ERROR: " + e.getMessage().replace("\n", " ");
        }
    }

    public static JsonElement encodeRecipe(RecipeHolder<?> holder) {
        RegistryOps<JsonElement> ops = RegistryOps.create(
                JsonOps.INSTANCE, Minecraft.getInstance().level.registryAccess());
        return encodeUnchecked(holder.value().getSerializer(), holder, ops);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Recipe<?>> JsonElement encodeUnchecked(
            RecipeSerializer<?> serializer, RecipeHolder<?> holder, RegistryOps<JsonElement> ops) {
        RecipeSerializer<T> typed = (RecipeSerializer<T>) serializer;
        // MapCodec.codec() produces a Codec<T> suitable for encodeStart
        return typed.codec().codec().encodeStart(ops, (T) holder.value()).getOrThrow();
    }

    private static void ensureTypeField(JsonElement json, RecipeHolder<?> holder) {
        if (!json.isJsonObject()) return;
        JsonObject obj = json.getAsJsonObject();
        if (obj.has("type")) return;
        var key = BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer());
        if (key != null) {
            obj.addProperty("type", key.toString());
        }
    }
}
