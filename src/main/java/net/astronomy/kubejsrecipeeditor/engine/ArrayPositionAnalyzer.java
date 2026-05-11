package net.astronomy.kubejsrecipeeditor.engine;

import com.google.gson.*;
import net.astronomy.kubejsrecipeeditor.gui.SlotDescriptor.SlotContentType;

import java.util.*;

public class ArrayPositionAnalyzer {

    public record PositionSchema(
            int position,
            int presentInNRecipes,
            int totalRecipes,
            Set<SlotContentType> accepts
    ) {
        public boolean isOptional() { return presentInNRecipes < totalRecipes; }
    }

    public List<PositionSchema> analyze(List<JsonObject> corpus, String arrayField) {
        int total = corpus.size();
        int maxLen = corpus.stream()
                .filter(j -> j.has(arrayField) && j.get(arrayField).isJsonArray())
                .mapToInt(j -> j.getAsJsonArray(arrayField).size())
                .max().orElse(0);

        List<PositionSchema> result = new ArrayList<>();
        for (int pos = 0; pos < maxLen; pos++) {
            int presentCount = 0;
            Set<SlotContentType> accepts = new LinkedHashSet<>();
            for (JsonObject recipe : corpus) {
                if (!recipe.has(arrayField) || !recipe.get(arrayField).isJsonArray()) continue;
                JsonArray arr = recipe.getAsJsonArray(arrayField);
                if (pos >= arr.size()) continue;
                presentCount++;
                classifyElement(arr.get(pos), accepts);
            }
            result.add(new PositionSchema(pos, presentCount, total, accepts));
        }
        return result;
    }

    public PositionSchema analyzeSingle(List<JsonObject> corpus, String field) {
        int total = corpus.size();
        int present = 0;
        Set<SlotContentType> accepts = new LinkedHashSet<>();
        for (JsonObject recipe : corpus) {
            if (!recipe.has(field)) continue;
            present++;
            classifyElement(recipe.get(field), accepts);
        }
        return new PositionSchema(0, present, total, accepts);
    }

    private void classifyElement(JsonElement el, Set<SlotContentType> accepts) {
        if (el == null || el.isJsonNull()) return;

        if (el.isJsonPrimitive()) {
            String s = el.getAsString();
            if (s.startsWith("#")) accepts.add(SlotContentType.TAG_ITEM);
            else accepts.add(SlotContentType.ITEM);
            return;
        }

        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            if (obj.has("fluid"))    accepts.add(SlotContentType.FLUID);
            if (obj.has("fluidTag")) accepts.add(SlotContentType.TAG_FLUID);
            if (obj.has("tag"))      accepts.add(SlotContentType.TAG_ITEM);
            if (obj.has("item"))     accepts.add(SlotContentType.ITEM);
            if (obj.has("id") && !obj.has("fluid") && !obj.has("item"))
                                     accepts.add(SlotContentType.ITEM);
            if (accepts.isEmpty())   accepts.add(SlotContentType.ITEM);
        }
    }
}
