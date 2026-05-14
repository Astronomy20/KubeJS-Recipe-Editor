package net.astronomy.kubejsrecipeeditor.engine;

import com.google.gson.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Bidirectional JSON serialization for FieldDescriptor trees (SuperTemplate format).
 * Used by TemplateRegistry to persist and reload schema information from disk.
 */
public final class FieldDescriptorSerializer {

    private static final Logger LOGGER = LogManager.getLogger("KubeJsRecipeEditor/FieldDescriptorSerializer");

    private FieldDescriptorSerializer() {}

    // ── Serialization ──────────────────────────────────────────────────────────

    public static JsonObject serialize(FieldDescriptor fd) {
        JsonObject obj = new JsonObject();
        obj.addProperty("descriptor", discriminator(fd));
        obj.addProperty("key", fd.key());
        obj.addProperty("optional", fd.optional());
        if (fd.defaultValue() != null) obj.addProperty("defaultValue", fd.defaultValue());
        obj.addProperty("presentInNOfTotal", fd.presentInNOfTotal());

        switch (fd) {
            case FieldDescriptor.ConstantField cf ->
                obj.addProperty("constantValue", cf.constantValue());

            case FieldDescriptor.ScalarField sf -> {
                obj.addProperty("scalarType", sf.scalarType().name());
                obj.addProperty("min", sf.min());
                obj.addProperty("max", sf.max());
                JsonArray enumArr = new JsonArray();
                sf.enumValues().forEach(enumArr::add);
                obj.add("enumValues", enumArr);
            }

            case FieldDescriptor.ResourceField rf -> {
                obj.addProperty("expectedContentType", rf.expectedContentType().name());
                if (rf.registryKey() != null) obj.addProperty("registryKey", rf.registryKey());
            }

            case FieldDescriptor.IngredientField igf -> {
                JsonArray types = new JsonArray();
                igf.acceptedTypes().forEach(t -> types.add(t.name()));
                obj.add("acceptedTypes", types);
                JsonObject subfields = new JsonObject();
                igf.subfields().forEach((k, v) -> subfields.add(k, serialize(v)));
                obj.add("subfields", subfields);
            }

            case FieldDescriptor.ObjectField of -> {
                obj.addProperty("collapsible", of.collapsible());
                JsonObject children = new JsonObject();
                of.children().forEach((k, v) -> children.add(k, serialize(v)));
                obj.add("children", children);
            }

            case FieldDescriptor.ArrayField af -> {
                obj.add("elementDescriptor", serialize(af.elementDescriptor()));
                obj.addProperty("minItems", af.minItems());
                obj.addProperty("maxItems", af.maxItems());
            }

            case FieldDescriptor.ChanceField cf -> {
                obj.add("ingredient", serialize(cf.ingredient()));
                obj.add("chance", serialize(cf.chance()));
            }

            case FieldDescriptor.PolymorphicField pf -> {
                JsonArray variants = new JsonArray();
                pf.variants().forEach(v -> variants.add(serialize(v)));
                obj.add("variants", variants);
            }

            case FieldDescriptor.CompoundField cf -> {
                JsonArray children = new JsonArray();
                cf.children().forEach(c -> children.add(serialize(c)));
                obj.add("children", children);
            }

            case FieldDescriptor.SequenceField sf ->
                obj.add("stepDescriptor", serialize(sf.stepDescriptor()));
        }
        return obj;
    }

    /** Serializes an ObjectField as the "fields" map of a SuperTemplate file. */
    public static JsonObject serializeAsTemplate(FieldDescriptor.ObjectField root,
            String typeUid, String engineVersion, String generatedAt,
            int sampleCount, String modlistHash) {
        JsonObject out = new JsonObject();

        JsonObject meta = new JsonObject();
        meta.addProperty("type", typeUid);
        meta.addProperty("engine_version", engineVersion);
        meta.addProperty("generated_at", generatedAt);
        meta.addProperty("sample_count", sampleCount);
        meta.addProperty("modlist_hash", modlistHash);
        out.add("_meta", meta);

        JsonObject fields = new JsonObject();
        root.children().forEach((k, v) -> fields.add(k, serialize(v)));
        out.add("fields", fields);

        return out;
    }

    // ── Deserialization ────────────────────────────────────────────────────────

    @Nullable
    public static FieldDescriptor deserialize(JsonObject obj) {
        if (!obj.has("descriptor")) return null;
        String type = obj.get("descriptor").getAsString();
        String key = obj.has("key") ? obj.get("key").getAsString() : "unknown";
        boolean optional = obj.has("optional") && obj.get("optional").getAsBoolean();
        String defaultValue = obj.has("defaultValue") ? obj.get("defaultValue").getAsString() : null;
        int present = obj.has("presentInNOfTotal") ? obj.get("presentInNOfTotal").getAsInt() : 0;

        try {
            return switch (type) {
                case "ConstantField" -> new FieldDescriptor.ConstantField(key,
                    obj.has("constantValue") ? obj.get("constantValue").getAsString() : "null");

                case "ScalarField" -> {
                    var scalarType = parseEnum(FieldDescriptor.ScalarField.ScalarType.class,
                        obj, "scalarType", FieldDescriptor.ScalarField.ScalarType.FREE_STRING);
                    double min = obj.has("min") ? obj.get("min").getAsDouble() : 0;
                    double max = obj.has("max") ? obj.get("max").getAsDouble() : 0;
                    List<String> enumVals = new ArrayList<>();
                    if (obj.has("enumValues"))
                        obj.getAsJsonArray("enumValues").forEach(e -> enumVals.add(e.getAsString()));
                    yield new FieldDescriptor.ScalarField(key, optional, defaultValue, present,
                        scalarType, min, max, enumVals);
                }

                case "ResourceField" -> {
                    ContentType ct = parseEnum(ContentType.class, obj, "expectedContentType",
                        ContentType.UNKNOWN);
                    String regKey = obj.has("registryKey") ? obj.get("registryKey").getAsString() : "";
                    yield new FieldDescriptor.ResourceField(key, optional, defaultValue, present,
                        ct, regKey);
                }

                case "IngredientField" -> {
                    Set<ContentType> accepted = new LinkedHashSet<>();
                    if (obj.has("acceptedTypes"))
                        obj.getAsJsonArray("acceptedTypes").forEach(e -> {
                            try { accepted.add(ContentType.valueOf(e.getAsString())); }
                            catch (Exception ex) {}
                        });
                    Map<String, FieldDescriptor> subfields = new LinkedHashMap<>();
                    if (obj.has("subfields"))
                        obj.getAsJsonObject("subfields").entrySet().forEach(e -> {
                            FieldDescriptor sub = deserialize(e.getValue().getAsJsonObject());
                            if (sub != null) subfields.put(e.getKey(), sub);
                        });
                    yield new FieldDescriptor.IngredientField(key, optional, defaultValue, present,
                        accepted, subfields);
                }

                case "ObjectField" -> {
                    boolean collapsible = obj.has("collapsible") && obj.get("collapsible").getAsBoolean();
                    Map<String, FieldDescriptor> children = new LinkedHashMap<>();
                    if (obj.has("children"))
                        obj.getAsJsonObject("children").entrySet().forEach(e -> {
                            FieldDescriptor child = deserialize(e.getValue().getAsJsonObject());
                            if (child != null) children.put(e.getKey(), child);
                        });
                    yield new FieldDescriptor.ObjectField(key, optional, defaultValue, present,
                        children, collapsible);
                }

                case "ArrayField" -> {
                    FieldDescriptor elem = obj.has("elementDescriptor")
                        ? deserialize(obj.getAsJsonObject("elementDescriptor")) : null;
                    if (elem == null) elem = new FieldDescriptor.ScalarField("element",
                        false, null, 0, FieldDescriptor.ScalarField.ScalarType.FREE_STRING,
                        0, 0, List.of());
                    int minI = obj.has("minItems") ? obj.get("minItems").getAsInt() : 0;
                    int maxI = obj.has("maxItems") ? obj.get("maxItems").getAsInt() : 0;
                    yield new FieldDescriptor.ArrayField(key, optional, defaultValue, present,
                        elem, minI, maxI);
                }

                case "ChanceField" -> {
                    FieldDescriptor.IngredientField ingr = obj.has("ingredient")
                        ? (FieldDescriptor.IngredientField) deserialize(obj.getAsJsonObject("ingredient"))
                        : null;
                    FieldDescriptor.ScalarField chance = obj.has("chance")
                        ? (FieldDescriptor.ScalarField) deserialize(obj.getAsJsonObject("chance"))
                        : null;
                    if (ingr == null || chance == null) yield null;
                    yield new FieldDescriptor.ChanceField(key, optional, defaultValue, present,
                        ingr, chance);
                }

                case "PolymorphicField" -> {
                    List<FieldDescriptor> variants = new ArrayList<>();
                    if (obj.has("variants"))
                        obj.getAsJsonArray("variants").forEach(e -> {
                            FieldDescriptor v = deserialize(e.getAsJsonObject());
                            if (v != null) variants.add(v);
                        });
                    yield new FieldDescriptor.PolymorphicField(key, optional, defaultValue, present,
                        variants);
                }

                case "CompoundField" -> {
                    List<FieldDescriptor> children = new ArrayList<>();
                    if (obj.has("children"))
                        obj.getAsJsonArray("children").forEach(e -> {
                            FieldDescriptor c = deserialize(e.getAsJsonObject());
                            if (c != null) children.add(c);
                        });
                    yield new FieldDescriptor.CompoundField(key, optional, defaultValue, present,
                        children);
                }

                case "SequenceField" -> {
                    FieldDescriptor step = obj.has("stepDescriptor")
                        ? deserialize(obj.getAsJsonObject("stepDescriptor")) : null;
                    if (step == null) step = new FieldDescriptor.ScalarField("step",
                        false, null, 0, FieldDescriptor.ScalarField.ScalarType.FREE_STRING,
                        0, 0, List.of());
                    yield new FieldDescriptor.SequenceField(key, optional, defaultValue, present, step);
                }

                default -> {
                    LOGGER.warn("Unknown FieldDescriptor type '{}' for key '{}' — skipped", type, key);
                    yield null;
                }
            };
        } catch (Exception e) {
            LOGGER.warn("Failed to deserialize FieldDescriptor '{}' for key '{}': {}", type, key, e.getMessage());
            return null;
        }
    }

    /** Deserializes the "fields" section of a SuperTemplate file into an ObjectField. */
    @Nullable
    public static FieldDescriptor.ObjectField deserializeFromTemplate(JsonObject templateJson) {
        if (!templateJson.has("fields")) return null;
        JsonObject fields = templateJson.getAsJsonObject("fields");
        Map<String, FieldDescriptor> children = new LinkedHashMap<>();
        for (var entry : fields.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            FieldDescriptor fd = deserialize(entry.getValue().getAsJsonObject());
            if (fd != null) children.put(entry.getKey(), fd);
        }
        if (children.isEmpty()) return null;
        return new FieldDescriptor.ObjectField("root", false, null, 0, children, false);
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String discriminator(FieldDescriptor fd) {
        return fd.getClass().getSimpleName();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> cls, JsonObject obj, String key, E fallback) {
        if (!obj.has(key)) return fallback;
        try { return Enum.valueOf(cls, obj.get(key).getAsString()); }
        catch (Exception e) { return fallback; }
    }
}
