package net.astronomy.kubejsrecipeeditor.engine;

import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * A Fragment is a user-provided JSON file that extends or overrides the inferred template
 * for one or more recipe types. Allows adding fields not visible in default JSONs,
 * changing accepted types, and registering custom registry hints.
 *
 * File naming convention: "mekanism__crushing.json" → targets "mekanism:crushing"
 * "_global.json" → targets all types ("*")
 * "mekanism__.json" → targets all types in "mekanism:*" namespace
 */
public class Fragment {

    private static final Logger LOGGER = LogManager.getLogger("KubeJsRecipeEditor/Fragment");

    private final List<String> targets;           // typeUid patterns this fragment applies to
    private final int priority;                   // higher = applied later (wins conflicts)
    private final String description;
    private final String sourceFileName;

    // Parsed sections
    private final Map<String, JsonObject> addFields;       // key → FieldDescriptor JSON
    private final Map<String, JsonObject> overrideFields;  // key → partial override JSON
    private final List<String> removeFields;               // keys to remove
    private final Map<String, RegistryHint> registryHints; // jsonKey → hint

    public record RegistryHint(String registryKey, ContentType contentType, String displayLabel) {}

    private Fragment(List<String> targets, int priority, String description, String sourceFileName,
                     Map<String, JsonObject> addFields, Map<String, JsonObject> overrideFields,
                     List<String> removeFields, Map<String, RegistryHint> registryHints) {
        this.targets = targets;
        this.priority = priority;
        this.description = description;
        this.sourceFileName = sourceFileName;
        this.addFields = addFields;
        this.overrideFields = overrideFields;
        this.removeFields = removeFields;
        this.registryHints = registryHints;
    }

    /**
     * Parses a Fragment from a JSON object loaded from a fragment file.
     *
     * @param json           parsed JSON content of the fragment file
     * @param sourceFileName filename (e.g. "mekanism__crushing.json") for target inference
     */
    public static Fragment parse(JsonObject json, String sourceFileName) {
        JsonObject meta = json.has("_meta") ? json.getAsJsonObject("_meta") : new JsonObject();

        int priority = meta.has("priority") ? meta.get("priority").getAsInt() : 0;
        String description = meta.has("description") ? meta.get("description").getAsString() : "";

        // Parse targets — from _meta.targets or inferred from filename
        List<String> targets = new ArrayList<>();
        if (meta.has("targets")) {
            meta.getAsJsonArray("targets").forEach(t -> targets.add(t.getAsString()));
        }
        if (targets.isEmpty()) {
            targets.addAll(inferTargetsFromFilename(sourceFileName));
        }

        // Parse add_fields
        Map<String, JsonObject> addFields = new LinkedHashMap<>();
        if (json.has("add_fields")) {
            json.getAsJsonObject("add_fields").entrySet()
                .forEach(e -> addFields.put(e.getKey(), e.getValue().getAsJsonObject()));
        }

        // Parse override_fields
        Map<String, JsonObject> overrideFields = new LinkedHashMap<>();
        if (json.has("override_fields")) {
            json.getAsJsonObject("override_fields").entrySet()
                .forEach(e -> overrideFields.put(e.getKey(), e.getValue().getAsJsonObject()));
        }

        // Parse remove_fields
        List<String> removeFields = new ArrayList<>();
        if (json.has("remove_fields")) {
            json.getAsJsonArray("remove_fields").forEach(e -> removeFields.add(e.getAsString()));
        }

        // Parse add_registry_hints
        Map<String, RegistryHint> registryHints = new LinkedHashMap<>();
        if (json.has("add_registry_hints")) {
            json.getAsJsonObject("add_registry_hints").entrySet().forEach(e -> {
                JsonObject hint = e.getValue().getAsJsonObject();
                String regKey = hint.has("registry_key") ? hint.get("registry_key").getAsString() : "";
                String ctStr  = hint.has("contentType")  ? hint.get("contentType").getAsString()  : "CUSTOM";
                String label  = hint.has("display_label") ? hint.get("display_label").getAsString() : e.getKey();
                ContentType ct;
                try { ct = ContentType.valueOf(ctStr); }
                catch (Exception ex) { ct = ContentType.CUSTOM; }
                registryHints.put(e.getKey(), new RegistryHint(regKey, ct, label));
            });
        }

        return new Fragment(targets, priority, description, sourceFileName,
            addFields, overrideFields, removeFields, registryHints);
    }

    /**
     * Infers target patterns from the fragment filename.
     * "mekanism__crushing.json" → ["mekanism:crushing"]
     * "_global.json" → ["*"]
     * "mekanism__.json" → ["mekanism:*"]
     */
    public static List<String> inferTargetsFromFilename(String filename) {
        String name = filename.endsWith(".json") ? filename.substring(0, filename.length() - 5) : filename;
        if (name.equals("_global")) return List.of("*");
        if (name.contains("__")) {
            String[] parts = name.split("__", 2);
            String ns = parts[0];
            String path = parts[1];
            if (path.isEmpty()) return List.of(ns + ":*");
            return List.of(ns + ":" + path.replace("_", "/"));
        }
        return List.of("*");
    }

    /**
     * Returns true if this Fragment should be applied to the given recipe typeUid.
     */
    public boolean appliesTo(ResourceLocation typeUid) {
        String typeStr = typeUid.toString();
        for (String target : targets) {
            if (target.equals("*")) return true;
            if (target.endsWith(":*")) {
                String ns = target.substring(0, target.length() - 2);
                if (typeUid.getNamespace().equals(ns)) return true;
            }
            if (target.equals(typeStr)) return true;
        }
        return false;
    }

    // --- Accessors ---

    public int getPriority() { return priority; }
    public String getSourceFileName() { return sourceFileName; }
    public String getDescription() { return description; }
    public List<String> getTargets() { return targets; }
    public Map<String, JsonObject> getAddFields() { return addFields; }
    public Map<String, JsonObject> getOverrideFields() { return overrideFields; }
    public List<String> getRemoveFields() { return removeFields; }
    public Map<String, RegistryHint> getRegistryHints() { return registryHints; }
}
