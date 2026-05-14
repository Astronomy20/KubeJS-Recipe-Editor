package net.astronomy.kubejsrecipeeditor.engine;

import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.stream.*;

/**
 * Generates a FieldDescriptor tree from a corpus of JSON objects of the same recipe type.
 * Applies a deep-merge algorithm across all recipes to produce a unified schema.
 * Zero knowledge of specific mods: infers everything from JSON structure and registry queries.
 */
public class SchemaInferenceEngine {

    private final RegistryResolver registryResolver;

    public SchemaInferenceEngine(RegistryResolver registryResolver) {
        this.registryResolver = registryResolver;
    }

    /**
     * Main entry point. Takes all recipes of one type and produces a root ObjectField
     * representing the union of all observed structures.
     */
    public FieldDescriptor.ObjectField infer(ResourceLocation typeUid, List<JsonObject> corpus) {
        if (corpus.isEmpty()) {
            return new FieldDescriptor.ObjectField("root", false, null, 0,
                new LinkedHashMap<>(), false);
        }

        Map<String, List<JsonElement>> byKey = collectByKey(corpus);
        Map<String, FieldDescriptor> children = new LinkedHashMap<>();

        for (var entry : byKey.entrySet()) {
            FieldDescriptor descriptor = classifyField(entry.getKey(), entry.getValue(), corpus.size());
            children.put(entry.getKey(), descriptor);
        }

        return new FieldDescriptor.ObjectField("root", false, null, corpus.size(), children, false);
    }

    // --- Internal helpers ---

    private Map<String, List<JsonElement>> collectByKey(List<JsonObject> corpus) {
        Map<String, List<JsonElement>> result = new LinkedHashMap<>();
        for (JsonObject json : corpus) {
            for (var entry : json.entrySet()) {
                result.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue());
            }
        }
        return result;
    }

    private FieldDescriptor classifyField(String key, List<JsonElement> values, int total) {
        int presentCount = values.size();
        boolean optional = presentCount < total;
        List<JsonElement> nonNull = values.stream()
            .filter(v -> v != null && !v.isJsonNull()).toList();

        if (nonNull.isEmpty()) return new FieldDescriptor.ConstantField(key, "null");

        Set<String> distinct = nonNull.stream().map(JsonElement::toString)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinct.size() == 1) {
            return new FieldDescriptor.ConstantField(key, distinct.iterator().next());
        }

        boolean allPrimitive = nonNull.stream().allMatch(JsonElement::isJsonPrimitive);
        boolean allObject    = nonNull.stream().allMatch(JsonElement::isJsonObject);
        boolean allArray     = nonNull.stream().allMatch(JsonElement::isJsonArray);

        String mostCommon = findMostCommon(nonNull);

        if (allPrimitive) return classifyPrimitive(key, nonNull, optional, mostCommon, presentCount);
        if (allObject)    return classifyObject(key, nonNull, optional, mostCommon, presentCount, total);
        if (allArray)     return classifyArray(key, nonNull, optional, mostCommon, presentCount);

        return classifyPolymorphic(key, nonNull, optional, mostCommon, presentCount, total);
    }

    private FieldDescriptor classifyPrimitive(String key, List<JsonElement> values,
            boolean optional, String mostCommon, int present) {
        boolean allBool = values.stream().allMatch(v -> v.getAsJsonPrimitive().isBoolean());
        if (allBool) {
            return new FieldDescriptor.ScalarField(key, optional, mostCommon, present,
                FieldDescriptor.ScalarField.ScalarType.BOOLEAN, 0, 0, List.of());
        }

        boolean allNum = values.stream().allMatch(v -> v.getAsJsonPrimitive().isNumber());
        if (allNum) {
            double min = values.stream().mapToDouble(JsonElement::getAsDouble).min().orElse(0);
            double max = values.stream().mapToDouble(JsonElement::getAsDouble).max().orElse(0);
            boolean allInt = values.stream().allMatch(v -> {
                double d = v.getAsDouble();
                return d == Math.floor(d) && !Double.isInfinite(d);
            });
            return new FieldDescriptor.ScalarField(key, optional, mostCommon, present,
                allInt ? FieldDescriptor.ScalarField.ScalarType.INTEGER
                       : FieldDescriptor.ScalarField.ScalarType.FLOAT,
                min, max, List.of());
        }

        // Strings
        List<String> stringVals = values.stream().map(JsonElement::getAsString).toList();
        Set<String> enumVals = new LinkedHashSet<>(stringVals);

        if (enumVals.size() <= 12) {
            return new FieldDescriptor.ScalarField(key, optional, mostCommon, present,
                FieldDescriptor.ScalarField.ScalarType.ENUM_STRING, 0, 0,
                enumVals.stream().sorted().toList());
        }

        boolean allResLoc = stringVals.stream().allMatch(s -> s.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+"));
        if (allResLoc) {
            ContentType ct = registryResolver.resolve(stringVals.get(0));
            String regKey = registryResolver.getRegistryKey(ct);
            return new FieldDescriptor.ResourceField(key, optional, mostCommon, present, ct,
                regKey != null ? regKey : "");
        }

        return new FieldDescriptor.ScalarField(key, optional, mostCommon, present,
            FieldDescriptor.ScalarField.ScalarType.FREE_STRING, 0, 0, List.of());
    }

    private FieldDescriptor classifyObject(String key, List<JsonElement> values,
            boolean optional, String mostCommon, int present, int total) {
        List<JsonObject> objects = values.stream().map(JsonElement::getAsJsonObject).toList();

        Set<String> allKeys = new LinkedHashSet<>();
        objects.forEach(o -> allKeys.addAll(o.keySet()));

        // Detect ingredient pattern
        Set<ContentType> acceptedTypes = new LinkedHashSet<>();
        if (allKeys.contains("fluid") || allKeys.contains("fluidTag"))
            acceptedTypes.add(ContentType.FLUID);
        if (allKeys.contains("gas"))         acceptedTypes.add(ContentType.CHEMICAL_GAS);
        if (allKeys.contains("slurry"))      acceptedTypes.add(ContentType.CHEMICAL_SLURRY);
        if (allKeys.contains("infuse_type")) acceptedTypes.add(ContentType.CHEMICAL_INFUSE);
        if (allKeys.contains("pigment"))     acceptedTypes.add(ContentType.CHEMICAL_PIGMENT);
        if (allKeys.contains("item"))        acceptedTypes.add(ContentType.ITEM);
        if (allKeys.contains("tag"))         acceptedTypes.add(ContentType.ITEM_TAG);

        if (!acceptedTypes.isEmpty()) {
            Set<String> ingredientKeys = Set.of("item", "tag", "fluid", "fluidTag",
                "gas", "slurry", "infuse_type", "pigment", "type", "children");
            Map<String, FieldDescriptor> subfields = new LinkedHashMap<>();
            for (String subkey : allKeys) {
                if (ingredientKeys.contains(subkey)) continue;
                List<JsonElement> subVals = objects.stream()
                    .filter(o -> o.has(subkey)).map(o -> o.get(subkey)).toList();
                if (!subVals.isEmpty())
                    subfields.put(subkey, classifyField(subkey, subVals, objects.size()));
            }
            return new FieldDescriptor.IngredientField(key, optional, mostCommon, present,
                acceptedTypes, subfields);
        }

        // Check chance/probability output pattern
        boolean hasChance = allKeys.contains("chance") || allKeys.contains("probability");
        boolean hasResource = allKeys.contains("item") || allKeys.contains("id");
        if (hasChance && hasResource) {
            // Build as ChanceField if we can find an ingredient subfield
            // For now fall through to ObjectField — ChanceField needs explicit ingredient
        }

        // Generic nested object
        Map<String, List<JsonElement>> bySubKey = collectByKey(objects);
        Map<String, FieldDescriptor> children = new LinkedHashMap<>();
        for (var entry : bySubKey.entrySet()) {
            children.put(entry.getKey(),
                classifyField(entry.getKey(), entry.getValue(), objects.size()));
        }
        return new FieldDescriptor.ObjectField(key, optional, mostCommon, present, children, true);
    }

    private FieldDescriptor classifyArray(String key, List<JsonElement> values,
            boolean optional, String mostCommon, int present) {
        List<JsonArray> arrays = values.stream().map(JsonElement::getAsJsonArray).toList();
        int minItems = arrays.stream().mapToInt(JsonArray::size).min().orElse(0);
        int maxItems = arrays.stream().mapToInt(JsonArray::size).max().orElse(0);

        List<JsonElement> allElements = arrays.stream()
            .flatMap(arr -> StreamSupport.stream(arr.spliterator(), false)).toList();

        if (allElements.isEmpty()) {
            return new FieldDescriptor.ArrayField(key, optional, mostCommon, present,
                new FieldDescriptor.ScalarField("element", false, null, 0,
                    FieldDescriptor.ScalarField.ScalarType.FREE_STRING, 0, 0, List.of()),
                minItems, maxItems);
        }

        FieldDescriptor elementDescriptor = classifyField("element", allElements, allElements.size());
        return new FieldDescriptor.ArrayField(key, optional, mostCommon, present,
            elementDescriptor, minItems, maxItems);
    }

    private FieldDescriptor classifyPolymorphic(String key, List<JsonElement> values,
            boolean optional, String mostCommon, int present, int total) {
        List<JsonElement> objects = values.stream().filter(JsonElement::isJsonObject).toList();
        List<JsonElement> arrays  = values.stream().filter(JsonElement::isJsonArray).toList();
        List<JsonElement> prims   = values.stream().filter(JsonElement::isJsonPrimitive).toList();

        List<FieldDescriptor> variants = new ArrayList<>();
        if (!objects.isEmpty())
            variants.add(classifyObject(key, objects, false, mostCommon, objects.size(), total));
        if (!arrays.isEmpty())
            variants.add(classifyArray(key, arrays, false, mostCommon, arrays.size()));
        if (!prims.isEmpty())
            variants.add(classifyPrimitive(key, prims, false, mostCommon, prims.size()));

        return new FieldDescriptor.PolymorphicField(key, optional, mostCommon, present, variants);
    }

    private String findMostCommon(List<JsonElement> values) {
        return values.stream()
            .collect(Collectors.groupingBy(JsonElement::toString, Collectors.counting()))
            .entrySet().stream().max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey).orElse(null);
    }
}
