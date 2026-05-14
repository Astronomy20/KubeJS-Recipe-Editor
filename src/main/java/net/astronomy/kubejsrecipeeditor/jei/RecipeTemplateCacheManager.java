package net.astronomy.kubejsrecipeeditor.jei;

import com.google.gson.*;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.astronomy.kubejsrecipeeditor.KubeJsRecipeEditor;
import net.astronomy.kubejsrecipeeditor.gui.ExtraParam;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Persists sampled RecipeTemplate data (slot positions, extra params, slot counts) to disk so
 * that the expensive codec-based sampling only runs once per category on first startup.
 *
 * Cache is automatically discarded when:
 * - The cache format version changes (CACHE_VERSION constant)
 * - The KubeJS Recipe Editor mod version changes
 * - The installed mod list changes (any mod added, removed, or updated)
 *
 * Cache file: <gameDir>/config/kubejsrecipeeditor_cache.json
 */
public final class RecipeTemplateCacheManager {

    private static final int CACHE_VERSION = 8;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record CachedSlot(String role, int x, int y) {
        public RecipeIngredientRole parsedRole() {
            return switch (role) {
                case "OUTPUT"   -> RecipeIngredientRole.OUTPUT;
                case "CATALYST" -> RecipeIngredientRole.CATALYST;
                default         -> RecipeIngredientRole.INPUT;
            };
        }
    }

    public record CachedEntry(
            int minInputSlots,
            int maxInputSlots,
            List<CachedSlot> slots,
            List<ExtraParam> extraParams,
            /** Merged JSON template built from all sampled recipes. May be null for legacy entries. */
            @javax.annotation.Nullable String templateJson
    ) {
        /** Backwards-compatible constructor (no templateJson). */
        public CachedEntry(int minInputSlots, int maxInputSlots,
                           List<CachedSlot> slots, List<ExtraParam> extraParams) {
            this(minInputSlots, maxInputSlots, slots, extraParams, null);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Deletes the cache file from disk. Returns true if the file existed and was deleted. */
    public static boolean deleteCache(Path gameDir) {
        try {
            return Files.deleteIfExists(cacheFile(gameDir));
        } catch (Exception e) {
            KubeJsRecipeEditor.LOGGER.warn("KRE: failed to delete cache: {}", e.getMessage());
            return false;
        }
    }

    /** Loads the cache from disk. Returns an empty (mutable) map if the file is absent, corrupt,
     *  or if the KRE version or mod list has changed since the cache was written. */
    public static Map<String, CachedEntry> load(Path gameDir) {
        Path file = cacheFile(gameDir);
        if (!Files.exists(file)) return new LinkedHashMap<>();
        try {
            String json = Files.readString(file);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            // Format version check
            if (!root.has("version") || root.get("version").getAsInt() != CACHE_VERSION) {
                KubeJsRecipeEditor.LOGGER.info("KRE cache format version mismatch — discarding");
                return new LinkedHashMap<>();
            }

            // KRE mod version check
            String currentKreVer = currentKreVersion();
            if (root.has("kreVersion")) {
                String cachedKreVer = root.get("kreVersion").getAsString();
                if (!currentKreVer.equals(cachedKreVer)) {
                    KubeJsRecipeEditor.LOGGER.info(
                            "KRE version changed ({} → {}) — discarding cache",
                            cachedKreVer, currentKreVer);
                    return new LinkedHashMap<>();
                }
            }

            // Mod list hash check
            int currentHash = currentModListHash();
            if (root.has("modListHash")) {
                int cachedHash = root.get("modListHash").getAsInt();
                if (cachedHash != currentHash) {
                    KubeJsRecipeEditor.LOGGER.info(
                            "KRE mod list changed (hash {} → {}) — discarding cache",
                            cachedHash, currentHash);
                    return new LinkedHashMap<>();
                }
            }

            Map<String, CachedEntry> result = new LinkedHashMap<>();
            JsonObject entries = root.getAsJsonObject("entries");
            for (var e : entries.entrySet()) {
                CachedEntry entry = deserializeEntry(e.getValue().getAsJsonObject());
                if (entry != null) result.put(e.getKey(), entry);
            }
            KubeJsRecipeEditor.LOGGER.info("KRE cache loaded: {} categories", result.size());
            return result;
        } catch (Exception ex) {
            KubeJsRecipeEditor.LOGGER.warn("KRE cache read failed ({}), starting fresh", ex.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /**
     * Writes a single entry into the cache file (creates the file if absent, merges otherwise).
     * Thread-safety: called from the client thread only.
     */
    public static void saveEntry(Path gameDir, String uid, CachedEntry entry,
                                 Map<String, CachedEntry> liveMap) {
        liveMap.put(uid, entry);
        persist(gameDir, liveMap);
    }

    // ── Fingerprint helpers ───────────────────────────────────────────────────

    private static String currentKreVersion() {
        return ModList.get()
                .getModContainerById(KubeJsRecipeEditor.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    /** Sorted hash of all installed mod IDs and versions. Changes when any mod is added/removed/updated. */
    private static int currentModListHash() {
        String fingerprint = ModList.get().getMods().stream()
                .map(m -> m.getModId() + ":" + m.getVersion())
                .sorted()
                .collect(Collectors.joining(","));
        return fingerprint.hashCode();
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private static void persist(Path gameDir, Map<String, CachedEntry> map) {
        Path file = cacheFile(gameDir);
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("version", CACHE_VERSION);
            root.addProperty("kreVersion", currentKreVersion());
            root.addProperty("modListHash", currentModListHash());
            JsonObject entries = new JsonObject();
            for (var e : map.entrySet()) entries.add(e.getKey(), serializeEntry(e.getValue()));
            root.add("entries", entries);
            Files.writeString(file, GSON.toJson(root));
        } catch (IOException ex) {
            KubeJsRecipeEditor.LOGGER.warn("KRE cache write failed: {}", ex.getMessage());
        }
    }

    private static JsonObject serializeEntry(CachedEntry entry) {
        JsonObject obj = new JsonObject();
        obj.addProperty("minInputSlots", entry.minInputSlots());
        obj.addProperty("maxInputSlots", entry.maxInputSlots());

        JsonArray slots = new JsonArray();
        for (CachedSlot s : entry.slots()) {
            JsonObject so = new JsonObject();
            so.addProperty("role", s.role());
            so.addProperty("x", s.x());
            so.addProperty("y", s.y());
            slots.add(so);
        }
        obj.add("slots", slots);

        JsonArray params = new JsonArray();
        for (ExtraParam ep : entry.extraParams()) {
            JsonObject po = new JsonObject();
            po.addProperty("key", ep.key());
            po.addProperty("type", ep.type().name());
            po.addProperty("default", ep.defaultValueStr());
            po.addProperty("minBound", ep.minBound());
            JsonArray ev = new JsonArray();
            ep.enumValues().forEach(ev::add);
            po.add("enumValues", ev);
            params.add(po);
        }
        obj.add("extraParams", params);
        if (entry.templateJson() != null) obj.addProperty("templateJson", entry.templateJson());
        return obj;
    }

    private static CachedEntry deserializeEntry(JsonObject obj) {
        try {
            int minInput = obj.get("minInputSlots").getAsInt();
            int maxInput = obj.get("maxInputSlots").getAsInt();

            List<CachedSlot> slots = new ArrayList<>();
            for (JsonElement se : obj.getAsJsonArray("slots")) {
                JsonObject so = se.getAsJsonObject();
                slots.add(new CachedSlot(
                        so.get("role").getAsString(),
                        so.get("x").getAsInt(),
                        so.get("y").getAsInt()
                ));
            }

            List<ExtraParam> params = new ArrayList<>();
            for (JsonElement pe : obj.getAsJsonArray("extraParams")) {
                JsonObject po = pe.getAsJsonObject();
                String key      = po.get("key").getAsString();
                ExtraParam.Type type = ExtraParam.Type.valueOf(po.get("type").getAsString());
                String def      = po.get("default").getAsString();
                int minBound    = po.has("minBound") ? po.get("minBound").getAsInt() : Integer.MIN_VALUE;
                List<String> ev = new ArrayList<>();
                po.getAsJsonArray("enumValues").forEach(e -> ev.add(e.getAsString()));
                params.add(new ExtraParam(key, type, def, ev, minBound));
            }

            String templateJson = obj.has("templateJson") ? obj.get("templateJson").getAsString() : null;
            return new CachedEntry(minInput, maxInput, slots, params, templateJson);
        } catch (Exception ex) {
            KubeJsRecipeEditor.LOGGER.debug("KRE cache entry parse error: {}", ex.getMessage());
            return null;
        }
    }

    private static Path cacheFile(Path gameDir) {
        return gameDir.resolve("config/kubejsrecipeeditor/kubejsrecipeeditor_cache.json");
    }
}
