package net.astronomy.kubejsrecipeeditor.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import mezz.jei.api.recipe.IRecipeManager;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts all recipe JsonObjects from JEI runtime using RecipeSerializer codecs.
 * Uses ALL recipes of each type, not just a sample.
 */
public class RecipeJsonExtractor {

    private static final Logger LOGGER = LogManager.getLogger("KubeJsRecipeEditor/RecipeJsonExtractor");

    /**
     * For each RecipeType known to JEI, collects all recipe JsonObjects
     * by serializing them via their RecipeSerializer codec.
     *
     * @return map of typeUid → list of JsonObjects (corpus per recipe type)
     */
    public Map<ResourceLocation, List<JsonObject>> extractAll(
            IRecipeManager jeiRecipeManager,
            RegistryAccess registryAccess) {

        var ops = registryAccess.createSerializationContext(JsonOps.INSTANCE);
        Map<ResourceLocation, List<JsonObject>> result = new LinkedHashMap<>();

        jeiRecipeManager.createRecipeCategoryLookup().get().forEach(category -> {
            ResourceLocation typeUid = category.getRecipeType().getUid();
            List<JsonObject> corpus = new ArrayList<>();

            try {
                jeiRecipeManager.createRecipeLookup(category.getRecipeType()).get()
                    .forEach(recipe -> {
                        if (!(recipe instanceof RecipeHolder<?> holder)) return;
                        try {
                            @SuppressWarnings({"rawtypes", "unchecked"})
                            Codec<Object> codec = (Codec<Object>) holder.value().getSerializer().codec();
                            JsonElement el = codec.encodeStart(ops, holder.value()).getOrThrow();
                            if (el.isJsonObject()) corpus.add(el.getAsJsonObject());
                        } catch (Exception e) {
                            LOGGER.debug("Could not encode recipe for type {}: {}", typeUid, e.getMessage());
                        }
                    });
            } catch (Exception e) {
                LOGGER.debug("Could not extract recipes for category {}: {}", typeUid, e.getMessage());
            }

            if (!corpus.isEmpty()) result.put(typeUid, corpus);
        });

        LOGGER.debug("RecipeJsonExtractor: extracted {} recipe types", result.size());
        return result;
    }
}
