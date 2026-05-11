package net.astronomy.kubejsrecipeeditor.engine;

import com.google.gson.*;
import net.astronomy.kubejsrecipeeditor.gui.ParamDescriptor;

import java.util.*;

public class JsonSchemaInferencer {

    public enum FieldType {
        CONSTANT, BOOLEAN, INTEGER, FLOAT, ENUM, FREE_STRING, ARRAY, OBJECT, ABSENT, MIXED
    }

    public record FieldSchema(
            FieldType type,
            int presentCount,
            int totalCount,
            Set<JsonElement> values,
            double minNumeric,
            double maxNumeric,
            String mostCommonValue
    ) {
        public boolean isRequired()  { return presentCount == totalCount; }
        public boolean isOptional()  { return presentCount > 0 && presentCount < totalCount; }
        public boolean isEnum()      { return type == FieldType.ENUM; }
        public boolean isConstant()  { return type == FieldType.CONSTANT; }
    }

    private static final int ENUM_MAX_VALUES = 12;
    private static final int VALUES_CAP = 50;

    public Map<String, FieldSchema> infer(List<JsonObject> corpus) {
        if (corpus.isEmpty()) return Map.of();
        int total = corpus.size();
        Map<String, List<JsonElement>> collected = new LinkedHashMap<>();

        for (JsonObject json : corpus) {
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                String key = entry.getKey();
                if (ParamDescriptor.isStructuralKey(key)) continue;
                collected.computeIfAbsent(key, k -> new ArrayList<>()).add(entry.getValue());
            }
        }

        Map<String, FieldSchema> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<JsonElement>> entry : collected.entrySet()) {
            result.put(entry.getKey(), classifyField(entry.getValue(), total));
        }
        return result;
    }

    private FieldSchema classifyField(List<JsonElement> values, int total) {
        int presentCount = values.size();
        List<JsonElement> nonNull = values.stream()
                .filter(v -> v != null && !v.isJsonNull()).toList();

        if (nonNull.isEmpty()) return makeSchema(FieldType.ABSENT, presentCount, total, Set.of(), 0, 0, "");

        Set<JsonElement> distinct = new LinkedHashSet<>();
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (JsonElement v : nonNull) {
            if (distinct.size() < VALUES_CAP) distinct.add(v);
            freq.merge(v.toString(), 1, Integer::sum);
        }
        String mostCommon = freq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("");

        if (distinct.size() == 1) {
            return makeSchema(FieldType.CONSTANT, presentCount, total, distinct, 0, 0, mostCommon);
        }
        if (nonNull.stream().allMatch(v -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean())) {
            return makeSchema(FieldType.BOOLEAN, presentCount, total, distinct, 0, 0, mostCommon);
        }
        if (nonNull.stream().allMatch(v -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber())) {
            double min = nonNull.stream().mapToDouble(JsonElement::getAsDouble).min().orElse(0);
            double max = nonNull.stream().mapToDouble(JsonElement::getAsDouble).max().orElse(0);
            boolean allInt = nonNull.stream().allMatch(v -> {
                double d = v.getAsDouble();
                return d == Math.floor(d) && !Double.isInfinite(d);
            });
            return makeSchema(allInt ? FieldType.INTEGER : FieldType.FLOAT, presentCount, total, distinct, min, max, mostCommon);
        }
        if (nonNull.stream().allMatch(v -> v.isJsonPrimitive() && v.getAsJsonPrimitive().isString())) {
            FieldType ft = distinct.size() <= ENUM_MAX_VALUES ? FieldType.ENUM : FieldType.FREE_STRING;
            return makeSchema(ft, presentCount, total, distinct, 0, 0, mostCommon);
        }
        if (nonNull.stream().allMatch(JsonElement::isJsonArray)) {
            return makeSchema(FieldType.ARRAY, presentCount, total, distinct, 0, 0, mostCommon);
        }
        if (nonNull.stream().allMatch(JsonElement::isJsonObject)) {
            return makeSchema(FieldType.OBJECT, presentCount, total, distinct, 0, 0, mostCommon);
        }
        return makeSchema(FieldType.MIXED, presentCount, total, distinct, 0, 0, mostCommon);
    }

    private FieldSchema makeSchema(FieldType type, int present, int total,
            Set<JsonElement> values, double min, double max, String mostCommon) {
        return new FieldSchema(type, present, total, values, min, max, mostCommon);
    }
}
