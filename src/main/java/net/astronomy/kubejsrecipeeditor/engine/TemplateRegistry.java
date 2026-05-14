package net.astronomy.kubejsrecipeeditor.engine;

import com.google.gson.*;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Registry of SuperTemplates (inferred FieldDescriptor trees per recipe type).
 * Applies Fragment overrides at registration time.
 * Supports disk persistence in config/kubejsrecipeeditor/templates/ with modlist-hash invalidation.
 */
public class TemplateRegistry {

    public static final TemplateRegistry INSTANCE = new TemplateRegistry();

    private static final Logger LOGGER = LogManager.getLogger("KubeJsRecipeEditor/TemplateRegistry");
    private static final String ENGINE_VERSION = "1.0";
    private static final String TEMPLATES_DIR = "kubejsrecipeeditor/templates";
    private static final String FRAGMENTS_DIR = "kubejsrecipeeditor/templates/fragments";
    private static final String META_FILE = "_meta.json";

    private final Map<ResourceLocation, FieldDescriptor.ObjectField> templates = new LinkedHashMap<>();
    private final List<Fragment> fragments = new ArrayList<>();
    private @Nullable RegistryResolver registryResolver;

    private TemplateRegistry() {}

    /**
     * Sets the RegistryResolver used when applying registry hints from fragments.
     */
    public void setRegistryResolver(RegistryResolver resolver) {
        this.registryResolver = resolver;
    }

    /**
     * Registers a SuperTemplate for a recipe type, applying all loaded fragments.
     */
    public void register(ResourceLocation typeUid, FieldDescriptor.ObjectField root) {
        FieldDescriptor.ObjectField merged = applyFragments(typeUid, root);
        templates.put(typeUid, merged);
    }

    /**
     * Returns the SuperTemplate for a recipe type, or null if not registered.
     */
    @Nullable
    public FieldDescriptor.ObjectField getSuperTemplate(ResourceLocation typeUid) {
        return templates.get(typeUid);
    }

    /**
     * Returns all registered type UIDs.
     */
    public Set<ResourceLocation> getRegisteredTypes() {
        return Collections.unmodifiableSet(templates.keySet());
    }

    /**
     * Adds a Fragment to the registry. Fragments are applied when registering new templates
     * and can be re-applied to existing templates via reapplyFragments().
     */
    public void addFragment(Fragment fragment) {
        fragments.add(fragment);
        fragments.sort(Comparator.comparingInt(Fragment::getPriority));
    }

    /**
     * Clears all in-memory templates and fragments (called on JEI runtime unavailable).
     */
    public void clear() {
        templates.clear();
        fragments.clear();
        registryResolver = null;
    }

    // --- Fragment application ---

    private FieldDescriptor.ObjectField applyFragments(ResourceLocation typeUid,
            FieldDescriptor.ObjectField root) {
        List<Fragment> applicable = fragments.stream()
            .filter(f -> f.appliesTo(typeUid))
            .sorted(Comparator.comparingInt(Fragment::getPriority))
            .toList();

        if (applicable.isEmpty()) return root;

        Map<String, FieldDescriptor> children = new LinkedHashMap<>(root.children());

        for (Fragment fragment : applicable) {
            applyAddFields(fragment, children, typeUid);
            applyOverrideFields(fragment, children, typeUid);
            applyRemoveFields(fragment, children, typeUid);
            applyRegistryHints(fragment, typeUid);
        }

        return new FieldDescriptor.ObjectField(
            root.key(), root.optional(), root.defaultValue(), root.presentInNOfTotal(),
            children, root.collapsible()
        );
    }

    private void applyAddFields(Fragment fragment, Map<String, FieldDescriptor> children,
            ResourceLocation typeUid) {
        fragment.getAddFields().forEach((key, descriptorJson) -> {
            if (children.containsKey(key)) {
                LOGGER.warn("[{}] Fragment '{}': add_fields key '{}' already exists — skipped. Use override_fields to modify.",
                    typeUid, fragment.getSourceFileName(), key);
                return;
            }
            FieldDescriptor field = parseDescriptorJson(key, descriptorJson);
            if (field != null) children.put(key, field);
        });
    }

    private void applyOverrideFields(Fragment fragment, Map<String, FieldDescriptor> children,
            ResourceLocation typeUid) {
        fragment.getOverrideFields().forEach((key, overrides) -> {
            FieldDescriptor existing = children.get(key);
            if (existing == null) {
                LOGGER.warn("[{}] Fragment '{}': override_fields key '{}' not found in template — skipped.",
                    typeUid, fragment.getSourceFileName(), key);
                return;
            }
            children.put(key, applyOverride(existing, overrides, typeUid, fragment.getSourceFileName()));
        });
    }

    private void applyRemoveFields(Fragment fragment, Map<String, FieldDescriptor> children,
            ResourceLocation typeUid) {
        fragment.getRemoveFields().forEach(key -> {
            FieldDescriptor existing = children.get(key);
            if (existing == null) {
                LOGGER.warn("[{}] Fragment '{}': remove_fields key '{}' not found — skipped.",
                    typeUid, fragment.getSourceFileName(), key);
                return;
            }
            // Convert to ConstantField so the value is still copied in output JSON, but hidden from GUI
            String constantValue = existing.defaultValue() != null ? existing.defaultValue() : "null";
            children.put(key, new FieldDescriptor.ConstantField(key, constantValue));
        });
    }

    private void applyRegistryHints(Fragment fragment, ResourceLocation typeUid) {
        if (registryResolver == null) return;
        fragment.getRegistryHints().forEach((jsonKey, hint) -> {
            try {
                var regKey = net.minecraft.resources.ResourceKey.createRegistryKey(
                    ResourceLocation.parse(hint.registryKey()));
                registryResolver.registerJsonKeyMapping(jsonKey, regKey, hint.contentType());
            } catch (Exception e) {
                LOGGER.warn("[{}] Fragment '{}': invalid registry_key '{}' for hint '{}' — skipped.",
                    typeUid, fragment.getSourceFileName(), hint.registryKey(), jsonKey);
            }
        });
    }

    private FieldDescriptor applyOverride(FieldDescriptor existing, com.google.gson.JsonObject overrides,
            ResourceLocation typeUid, String fragmentFile) {
        // Only patch — cannot change the descriptor type
        return switch (existing) {
            case FieldDescriptor.IngredientField igf -> {
                Set<ContentType> acceptedTypes = new java.util.LinkedHashSet<>(igf.acceptedTypes());
                if (overrides.has("acceptedTypes")) {
                    overrides.getAsJsonArray("acceptedTypes").forEach(e -> {
                        try { acceptedTypes.add(ContentType.valueOf(e.getAsString())); }
                        catch (Exception ex) { LOGGER.warn("Unknown ContentType '{}' in fragment override", e.getAsString()); }
                    });
                }
                boolean optional = overrides.has("optional") ? overrides.get("optional").getAsBoolean() : igf.optional();
                String defaultValue = overrides.has("defaultValue") ? overrides.get("defaultValue").getAsString() : igf.defaultValue();
                yield new FieldDescriptor.IngredientField(igf.key(), optional, defaultValue,
                    igf.presentInNOfTotal(), acceptedTypes, igf.subfields());
            }
            case FieldDescriptor.ScalarField sf -> {
                boolean optional = overrides.has("optional") ? overrides.get("optional").getAsBoolean() : sf.optional();
                String defaultValue = overrides.has("defaultValue") ? overrides.get("defaultValue").getAsString() : sf.defaultValue();
                double min = overrides.has("min") ? overrides.get("min").getAsDouble() : sf.min();
                double max = overrides.has("max") ? overrides.get("max").getAsDouble() : sf.max();
                List<String> enumValues = new ArrayList<>(sf.enumValues());
                if (overrides.has("enumValues")) {
                    overrides.getAsJsonArray("enumValues").forEach(e -> {
                        String v = e.getAsString();
                        if (!enumValues.contains(v)) enumValues.add(v);
                    });
                }
                yield new FieldDescriptor.ScalarField(sf.key(), optional, defaultValue,
                    sf.presentInNOfTotal(), sf.scalarType(), min, max, enumValues);
            }
            default -> {
                boolean optional = overrides.has("optional") ? overrides.get("optional").getAsBoolean() : existing.optional();
                String defaultValue = overrides.has("defaultValue") ? overrides.get("defaultValue").getAsString() : existing.defaultValue();
                // For other types we can only patch optional/defaultValue
                // Deep replacement not supported — use add_fields + remove_fields instead
                if (optional != existing.optional() || !Objects.equals(defaultValue, existing.defaultValue())) {
                    LOGGER.debug("[{}] Fragment '{}': partial override applied to {} field '{}'",
                        typeUid, fragmentFile, existing.getClass().getSimpleName(), existing.key());
                }
                yield existing;
            }
        };
    }

    @Nullable
    private FieldDescriptor parseDescriptorJson(String key, com.google.gson.JsonObject json) {
        if (!json.has("descriptor")) {
            LOGGER.warn("Fragment add_fields entry '{}' missing 'descriptor' — skipped.", key);
            return null;
        }
        String descriptorType = json.get("descriptor").getAsString();
        boolean optional = json.has("optional") ? json.get("optional").getAsBoolean() : true;
        String defaultValue = json.has("defaultValue") ? json.get("defaultValue").getAsString() : null;

        try {
            return switch (descriptorType) {
                case "ScalarField" -> {
                    String scalarTypeStr = json.has("scalarType") ? json.get("scalarType").getAsString() : "FREE_STRING";
                    FieldDescriptor.ScalarField.ScalarType scalarType;
                    try { scalarType = FieldDescriptor.ScalarField.ScalarType.valueOf(scalarTypeStr); }
                    catch (Exception e) { scalarType = FieldDescriptor.ScalarField.ScalarType.FREE_STRING; }
                    double min = json.has("min") ? json.get("min").getAsDouble() : 0;
                    double max = json.has("max") ? json.get("max").getAsDouble() : 0;
                    List<String> enumVals = new ArrayList<>();
                    if (json.has("enumValues"))
                        json.getAsJsonArray("enumValues").forEach(e -> enumVals.add(e.getAsString()));
                    yield new FieldDescriptor.ScalarField(key, optional, defaultValue, 0,
                        scalarType, min, max, enumVals);
                }
                case "IngredientField" -> {
                    Set<ContentType> accepted = new LinkedHashSet<>();
                    if (json.has("acceptedTypes"))
                        json.getAsJsonArray("acceptedTypes").forEach(e -> {
                            try { accepted.add(ContentType.valueOf(e.getAsString())); }
                            catch (Exception ex) {}
                        });
                    yield new FieldDescriptor.IngredientField(key, optional, defaultValue, 0,
                        accepted, new LinkedHashMap<>());
                }
                case "ConstantField" -> new FieldDescriptor.ConstantField(key,
                    defaultValue != null ? defaultValue : "null");
                default -> {
                    LOGGER.warn("Fragment add_fields '{}': unsupported descriptor type '{}' — skipped.",
                        key, descriptorType);
                    yield null;
                }
            };
        } catch (Exception e) {
            LOGGER.warn("Fragment add_fields '{}': failed to parse descriptor — {}", key, e.getMessage());
            return null;
        }
    }

    // ── Disk persistence ──────────────────────────────────────────────────────

    /**
     * Computes SHA-256 hash of the sorted modid@version list.
     * Used to detect modlist changes and invalidate cached templates.
     */
    public static String computeModlistHash() {
        try {
            String modlist = net.neoforged.fml.ModList.get().getMods().stream()
                .map(m -> m.getModId() + "@" + m.getVersion())
                .sorted()
                .collect(Collectors.joining(","));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(modlist.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString().substring(0, 16); // first 16 chars is plenty for change detection
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Attempts to load all templates from disk.
     * Returns true if disk cache is valid (modlist hash matches and templates loaded),
     * false if regeneration is needed.
     *
     * @param configDir  Minecraft game directory config/ path parent
     * @param currentHash current modlist hash (from computeModlistHash())
     */
    public boolean loadFromDisk(Path configDir, String currentHash) {
        Path templatesDir = configDir.resolve("config").resolve(TEMPLATES_DIR);
        Path metaFile = templatesDir.resolve(META_FILE);

        if (!Files.exists(metaFile)) {
            LOGGER.debug("TemplateRegistry: no _meta.json found, regeneration needed");
            return false;
        }

        try {
            String metaContent = Files.readString(metaFile);
            JsonObject meta = JsonParser.parseString(metaContent).getAsJsonObject();
            String storedHash = meta.has("modlist_hash") ? meta.get("modlist_hash").getAsString() : "";

            if (!storedHash.equals(currentHash)) {
                LOGGER.debug("TemplateRegistry: modlist hash changed ({} → {}), regeneration needed",
                    storedHash.substring(0, Math.min(8, storedHash.length())),
                    currentHash.substring(0, Math.min(8, currentHash.length())));
                return false;
            }

            // Load all template files
            int loaded = 0;
            try (var walk = Files.walk(templatesDir, 2)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (!p.toString().endsWith(".json")) continue;
                    if (p.getFileName().toString().startsWith("_")) continue;
                    if (p.getParent().getFileName().toString().equals("fragments")) continue;

                    try {
                        String content = Files.readString(p);
                        JsonObject templateJson = JsonParser.parseString(content).getAsJsonObject();
                        String typeStr = templateJson.has("_meta")
                            ? templateJson.getAsJsonObject("_meta").get("type").getAsString()
                            : inferTypeFromPath(templatesDir, p);
                        if (typeStr == null) continue;

                        ResourceLocation typeUid = ResourceLocation.parse(typeStr);
                        FieldDescriptor.ObjectField root = FieldDescriptorSerializer
                            .deserializeFromTemplate(templateJson);
                        if (root != null) {
                            templates.put(typeUid, root);
                            loaded++;
                        }
                    } catch (Exception e) {
                        LOGGER.debug("Failed to load template {}: {}", p.getFileName(), e.getMessage());
                    }
                }
            }

            LOGGER.debug("TemplateRegistry: loaded {} templates from disk", loaded);
            return loaded > 0;

        } catch (Exception e) {
            LOGGER.warn("TemplateRegistry: failed to read from disk: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Saves all registered templates to disk and writes _meta.json.
     *
     * @param configDir   Minecraft game directory (parent of config/)
     * @param modlistHash current modlist hash
     */
    public void saveToDisk(Path configDir, String modlistHash) {
        Path templatesDir = configDir.resolve("config").resolve(TEMPLATES_DIR);
        String generatedAt = Instant.now().toString();
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        int saved = 0;
        for (var entry : templates.entrySet()) {
            ResourceLocation typeUid = entry.getKey();
            FieldDescriptor.ObjectField root = entry.getValue();

            try {
                // Path: templates/[namespace]/[path_with_slash_as_underscore].json
                Path typeDir = templatesDir.resolve(typeUid.getNamespace());
                Files.createDirectories(typeDir);
                String fileName = typeUid.getPath().replace("/", "_") + ".json";
                Path outFile = typeDir.resolve(fileName);

                JsonObject templateJson = FieldDescriptorSerializer.serializeAsTemplate(
                    root, typeUid.toString(), ENGINE_VERSION, generatedAt,
                    root.presentInNOfTotal(), modlistHash);
                Files.writeString(outFile, gson.toJson(templateJson), StandardCharsets.UTF_8);
                saved++;
            } catch (Exception e) {
                LOGGER.debug("Failed to save template for {}: {}", typeUid, e.getMessage());
            }
        }

        // Write _meta.json
        try {
            Files.createDirectories(templatesDir);
            JsonObject meta = new JsonObject();
            meta.addProperty("engine_version", ENGINE_VERSION);
            meta.addProperty("generated_at", generatedAt);
            meta.addProperty("modlist_hash", modlistHash);
            meta.addProperty("type_count", saved);

            JsonArray namespaces = new JsonArray();
            templates.keySet().stream()
                .map(ResourceLocation::getNamespace)
                .distinct().sorted()
                .forEach(namespaces::add);
            meta.add("namespaces", namespaces);

            Files.writeString(templatesDir.resolve(META_FILE),
                gson.toJson(meta), StandardCharsets.UTF_8);
            LOGGER.debug("TemplateRegistry: saved {} templates to disk", saved);
        } catch (Exception e) {
            LOGGER.warn("TemplateRegistry: failed to write _meta.json: {}", e.getMessage());
        }
    }

    /**
     * Scans the fragments directory and loads all valid Fragment files.
     * Fragments are applied when templates are registered.
     *
     * @param configDir Minecraft game directory (parent of config/)
     */
    public void loadFragments(Path configDir) {
        Path fragmentsDir = configDir.resolve("config").resolve(FRAGMENTS_DIR);
        if (!Files.exists(fragmentsDir)) return;

        int loaded = 0;
        try (var walk = Files.walk(fragmentsDir, 1)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (!p.toString().endsWith(".json")) continue;
                try {
                    String content = Files.readString(p);
                    JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                    Fragment f = Fragment.parse(json, p.getFileName().toString());
                    addFragment(f);
                    loaded++;
                } catch (Exception e) {
                    LOGGER.warn("Invalid fragment file {}: {}", p.getFileName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("TemplateRegistry: error reading fragments directory: {}", e.getMessage());
        }

        if (loaded > 0) LOGGER.debug("TemplateRegistry: loaded {} fragment(s)", loaded);
    }

    /**
     * Deletes all template files on disk to force full regeneration on next startup.
     * Fragments are NOT deleted.
     */
    public void invalidateDiskCache(Path configDir) {
        Path templatesDir = configDir.resolve("config").resolve(TEMPLATES_DIR);
        if (!Files.exists(templatesDir)) return;
        try (var walk = Files.walk(templatesDir, 2)) {
            walk.filter(p -> p.toString().endsWith(".json"))
                .filter(p -> !p.toString().contains("fragments"))
                .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
            LOGGER.debug("TemplateRegistry: disk cache invalidated");
        } catch (Exception e) {
            LOGGER.warn("TemplateRegistry: failed to invalidate disk cache: {}", e.getMessage());
        }
    }

    @Nullable
    private static String inferTypeFromPath(Path templatesDir, Path templateFile) {
        try {
            Path rel = templatesDir.relativize(templateFile);
            String namespace = rel.getName(0).toString();
            String fileName = rel.getName(1).toString();
            String path = fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
            path = path.replace("_", "/");
            return namespace + ":" + path;
        } catch (Exception e) {
            return null;
        }
    }
}
