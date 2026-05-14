package net.astronomy.kubejsrecipeeditor.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.astronomy.kubejsrecipeeditor.engine.ContentType;
import net.astronomy.kubejsrecipeeditor.gui.*;
import net.astronomy.kubejsrecipeeditor.jei.SlotCapturingLayoutBuilder.CapturedSlot;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

public class GuiDescriptorBuilder {

    private final JsonSchemaInferencer inferencer = new JsonSchemaInferencer();
    private final ArrayPositionAnalyzer positionAnalyzer = new ArrayPositionAnalyzer();

    public GuiDescriptor build(
            ResourceLocation typeUid,
            List<JsonObject> corpus,
            List<CapturedSlot> jeiSlots) {

        if (corpus.isEmpty()) {
            return new GuiDescriptor(typeUid, List.of(), List.of());
        }

        List<String> ingredientFields = detectIngredientFields(corpus);
        List<String> resultFields     = detectResultFields(corpus);

        List<SlotDescriptor> slotDescriptors = buildSlotDescriptors(
                corpus, jeiSlots, ingredientFields, resultFields);

        Map<String, JsonSchemaInferencer.FieldSchema> schema = inferencer.infer(corpus);
        List<ParamDescriptor> extraParams = buildParamDescriptors(schema);

        return new GuiDescriptor(typeUid, slotDescriptors, extraParams);
    }

    // ── Ingredient field detection ──────────────────────────────────────────────

    private List<String> detectIngredientFields(List<JsonObject> corpus) {
        List<String> candidates = new ArrayList<>();
        Set<String> keys = collectAllKeys(corpus);
        for (String key : keys) {
            if (ParamDescriptor.isStructuralKey(key)
                    && !key.equals("ingredient") && !key.equals("ingredients")) continue;
            if (isIngredientLikeField(corpus, key)) candidates.add(key);
        }
        List<String> ordered = new ArrayList<>();
        for (String pref : List.of("ingredients", "ingredient")) {
            if (candidates.contains(pref)) ordered.add(pref);
        }
        candidates.stream().filter(c -> !ordered.contains(c)).forEach(ordered::add);
        return ordered;
    }

    private List<String> detectResultFields(List<JsonObject> corpus) {
        List<String> candidates = new ArrayList<>();
        Set<String> keys = collectAllKeys(corpus);
        for (String key : keys) {
            if (!key.equals("result") && !key.equals("results")
                    && !key.equals("output") && !key.equals("outputs")) continue;
            if (isResultLikeField(corpus, key)) candidates.add(key);
        }
        List<String> ordered = new ArrayList<>();
        for (String pref : List.of("result", "results", "output", "outputs")) {
            if (candidates.contains(pref)) ordered.add(pref);
        }
        return ordered;
    }

    private boolean isIngredientLikeField(List<JsonObject> corpus, String key) {
        long matchCount = corpus.stream().filter(j -> {
            if (!j.has(key)) return false;
            var v = j.get(key);
            if (v.isJsonArray()) {
                return v.getAsJsonArray().size() > 0 && hasIngredientKeys(v.getAsJsonArray().get(0));
            }
            return hasIngredientKeys(v);
        }).count();
        return matchCount > corpus.size() * 0.3;
    }

    private boolean isResultLikeField(List<JsonObject> corpus, String key) {
        long matchCount = corpus.stream().filter(j -> {
            if (!j.has(key)) return false;
            var v = j.get(key);
            if (v.isJsonArray()) {
                return v.getAsJsonArray().size() > 0 && hasIngredientKeys(v.getAsJsonArray().get(0));
            }
            return hasIngredientKeys(v) || (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString());
        }).count();
        return matchCount > corpus.size() * 0.3;
    }

    private boolean hasIngredientKeys(JsonElement el) {
        if (!el.isJsonObject()) return false;
        var obj = el.getAsJsonObject();
        if (obj.has("item") || obj.has("fluid") || obj.has("tag") || obj.has("id")) return true;
        // Mekanism nested wrapper: {"ingredient": {"item": "..."}}
        if (obj.has("ingredient") && obj.get("ingredient").isJsonObject()) {
            return hasIngredientKeys(obj.get("ingredient"));
        }
        return false;
    }

    // ── SlotDescriptor building ─────────────────────────────────────────────────

    private List<SlotDescriptor> buildSlotDescriptors(
            List<JsonObject> corpus,
            List<CapturedSlot> jeiSlots,
            List<String> ingredientFields,
            List<String> resultFields) {

        List<SlotDescriptor> result = new ArrayList<>();

        Map<String, List<ArrayPositionAnalyzer.PositionSchema>> ingredientPositions = new LinkedHashMap<>();
        for (String field : ingredientFields) {
            boolean isArray = corpus.stream().anyMatch(j -> j.has(field) && j.get(field).isJsonArray());
            ingredientPositions.put(field, isArray
                    ? positionAnalyzer.analyze(corpus, field)
                    : List.of(positionAnalyzer.analyzeSingle(corpus, field)));
        }

        Map<String, List<ArrayPositionAnalyzer.PositionSchema>> resultPositions = new LinkedHashMap<>();
        for (String field : resultFields) {
            boolean isArray = corpus.stream().anyMatch(j -> j.has(field) && j.get(field).isJsonArray());
            resultPositions.put(field, isArray
                    ? positionAnalyzer.analyze(corpus, field)
                    : List.of(positionAnalyzer.analyzeSingle(corpus, field)));
        }

        List<CapturedSlot> inputSlots = jeiSlots.stream()
                .filter(s -> s.role() == RecipeIngredientRole.INPUT).toList();
        List<CapturedSlot> outputSlots = jeiSlots.stream()
                .filter(s -> s.role() == RecipeIngredientRole.OUTPUT).toList();
        List<CapturedSlot> catalystSlots = jeiSlots.stream()
                .filter(s -> s.role() == RecipeIngredientRole.CATALYST).toList();

        int inputIdx = 0;
        for (String field : ingredientFields) {
            List<ArrayPositionAnalyzer.PositionSchema> positions = ingredientPositions.get(field);
            boolean isArray = positions.size() > 1
                    || corpus.stream().anyMatch(j -> j.has(field) && j.get(field).isJsonArray());
            for (int i = 0; i < positions.size() && inputIdx < inputSlots.size(); i++, inputIdx++) {
                var pos = positions.get(i);
                CapturedSlot cs = inputSlots.get(inputIdx);
                // JEI-captured isFluid takes precedence over corpus analysis
                Set<SlotDescriptor.SlotContentType> legacyAccepts = cs.isFluid()
                    ? Set.of(SlotDescriptor.SlotContentType.FLUID, SlotDescriptor.SlotContentType.TAG_FLUID)
                    : pos.accepts();
                Set<ContentType> newAccepts = cs.isFluid()
                    ? Set.of(ContentType.FLUID)
                    : toContentTypes(pos.accepts());
                result.add(new SlotDescriptor(cs.x(), cs.y(), cs.role(),
                        field, isArray ? i : -1, legacyAccepts, pos.isOptional(), newAccepts));
            }
        }

        int outputIdx = 0;
        for (String field : resultFields) {
            List<ArrayPositionAnalyzer.PositionSchema> positions = resultPositions.get(field);
            boolean isArray = positions.size() > 1
                    || corpus.stream().anyMatch(j -> j.has(field) && j.get(field).isJsonArray());
            for (int i = 0; i < positions.size() && outputIdx < outputSlots.size(); i++, outputIdx++) {
                var pos = positions.get(i);
                CapturedSlot cs = outputSlots.get(outputIdx);
                Set<SlotDescriptor.SlotContentType> legacyAccepts = cs.isFluid()
                    ? Set.of(SlotDescriptor.SlotContentType.FLUID, SlotDescriptor.SlotContentType.TAG_FLUID)
                    : pos.accepts();
                Set<ContentType> newAccepts = cs.isFluid()
                    ? Set.of(ContentType.FLUID)
                    : toContentTypes(pos.accepts());
                result.add(new SlotDescriptor(cs.x(), cs.y(), cs.role(),
                        field, isArray ? i : -1, legacyAccepts, pos.isOptional(), newAccepts));
            }
        }

        for (CapturedSlot cat : catalystSlots) {
            result.add(new SlotDescriptor(cat.x(), cat.y(), RecipeIngredientRole.CATALYST,
                    "catalyst", -1,
                    Set.of(SlotDescriptor.SlotContentType.ITEM, SlotDescriptor.SlotContentType.TAG_ITEM),
                    true, Set.of(ContentType.ITEM, ContentType.ITEM_TAG)));
        }

        return result;
    }

    // ── ParamDescriptor building ────────────────────────────────────────────────

    private List<ParamDescriptor> buildParamDescriptors(
            Map<String, JsonSchemaInferencer.FieldSchema> schema) {

        List<ParamDescriptor> result = new ArrayList<>();
        for (var entry : schema.entrySet()) {
            String key = entry.getKey();
            var fs = entry.getValue();
            if (fs.type() == JsonSchemaInferencer.FieldType.ABSENT) continue;

            ParamDescriptor.ParamType type = mapFieldType(fs.type());
            List<String> enumValues = fs.type() == JsonSchemaInferencer.FieldType.ENUM
                    ? fs.values().stream()
                        .map(e -> e.isJsonPrimitive() ? e.getAsString() : e.toString())
                        .sorted().toList()
                    : List.of();

            boolean readOnly = fs.type() == JsonSchemaInferencer.FieldType.CONSTANT
                    || fs.type() == JsonSchemaInferencer.FieldType.FREE_STRING
                    || fs.type() == JsonSchemaInferencer.FieldType.MIXED
                    || fs.type() == JsonSchemaInferencer.FieldType.OBJECT
                    || fs.type() == JsonSchemaInferencer.FieldType.ARRAY;

            result.add(new ParamDescriptor(
                    key,
                    ParamDescriptor.humanizeKey(key),
                    type,
                    fs.mostCommonValue(),
                    enumValues,
                    fs.minNumeric(),
                    fs.maxNumeric(),
                    fs.isOptional(),
                    readOnly));
        }
        return result;
    }

    private ParamDescriptor.ParamType mapFieldType(JsonSchemaInferencer.FieldType ft) {
        return switch (ft) {
            case CONSTANT -> ParamDescriptor.ParamType.CONSTANT;
            case BOOLEAN  -> ParamDescriptor.ParamType.BOOLEAN;
            case ENUM     -> ParamDescriptor.ParamType.ENUM;
            case INTEGER  -> ParamDescriptor.ParamType.INTEGER;
            case FLOAT    -> ParamDescriptor.ParamType.FLOAT;
            default       -> ParamDescriptor.ParamType.STRING;
        };
    }

    private Set<String> collectAllKeys(List<JsonObject> corpus) {
        Set<String> keys = new LinkedHashSet<>();
        corpus.forEach(j -> j.keySet().forEach(keys::add));
        return keys;
    }

    /** Translates legacy SlotContentType set to the new ContentType enum set. */
    private static Set<ContentType> toContentTypes(Set<SlotDescriptor.SlotContentType> old) {
        Set<ContentType> result = new LinkedHashSet<>();
        for (var sc : old) {
            switch (sc) {
                case ITEM     -> result.add(ContentType.ITEM);
                case TAG_ITEM -> result.add(ContentType.ITEM_TAG);
                case FLUID    -> result.add(ContentType.FLUID);
                case TAG_FLUID -> result.add(ContentType.FLUID); // treated as FLUID for drop validation
            }
        }
        return result;
    }
}
