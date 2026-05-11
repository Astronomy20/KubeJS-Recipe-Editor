package net.astronomy.kubejsrecipeeditor.jei;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.astronomy.kubejsrecipeeditor.KreConfig;
import net.astronomy.kubejsrecipeeditor.KubeJsRecipeEditor;
import net.astronomy.kubejsrecipeeditor.engine.DescriptorCache;
import net.astronomy.kubejsrecipeeditor.engine.GuiDescriptorBuilder;
import net.astronomy.kubejsrecipeeditor.gui.ExtraParam;
import net.astronomy.kubejsrecipeeditor.gui.GuiDescriptor;
import net.astronomy.kubejsrecipeeditor.gui.RecipeBuilderScreen;
import net.astronomy.kubejsrecipeeditor.gui.RecipeTemplate;
import net.astronomy.kubejsrecipeeditor.gui.RecipeTemplateRegistry;
import net.astronomy.kubejsrecipeeditor.gui.SlotData;
import net.astronomy.kubejsrecipeeditor.gui.TagEditorScreen;

import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.*;

@JeiPlugin
public class JeiIntegration implements IModPlugin {
    @Nullable
    private static IJeiRuntime jeiRuntime;

    /** Loaded once per session at populateRecipeTemplates; mutated as cache misses are saved. */
    private static Map<String, RecipeTemplateCacheManager.CachedEntry> cacheMap = new LinkedHashMap<>();

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(KubeJsRecipeEditor.MOD_ID, "jei_plugin");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        jeiRuntime = runtime;
        populateRecipeTemplates(runtime);
        KubeJsRecipeEditor.LOGGER.debug("JEI runtime available; recipe templates registered: {}", RecipeTemplateRegistry.INSTANCE.all().size());
    }

    @Override
    public void onRuntimeUnavailable() {
        RecipeTemplateRegistry.INSTANCE.clear();
        DescriptorCache.INSTANCE.clear();
        jeiRuntime = null;
    }

    // ─── Template population ──────────────────────────────────────────────────

    private static void populateRecipeTemplates(IJeiRuntime runtime) {
        RecipeTemplateRegistry.INSTANCE.clear();
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        cacheMap = RecipeTemplateCacheManager.load(gameDir);

        IRecipeManager recipeManager = runtime.getRecipeManager();
        IFocusGroup emptyFocus = runtime.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();

        recipeManager.createRecipeCategoryLookup().get().forEach(categoryObj -> {
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                IRecipeCategory<?> rawCat = categoryObj;
                registerCategoryUnchecked(rawCat, recipeManager, emptyFocus);
            } catch (Exception ex) {
                KubeJsRecipeEditor.LOGGER.debug("Skipping recipe template: {}", ex.getMessage());
            }
        });
    }

    @SuppressWarnings("removal")
    private static void registerCategoryUnchecked(
            @SuppressWarnings("rawtypes") IRecipeCategory category,
            IRecipeManager mgr,
            IFocusGroup emptyFocus) {

        RecipeType<Object> type = category.getRecipeType();
        String uid = type.getUid().toString();

        // Always sample 1 recipe for exampleRecipe — it's a live JEI object, not persistable.
        Object exampleRecipe = mgr.createRecipeLookup(type).get().findFirst().orElse(null);
        if (exampleRecipe == null) return;

        // If the example recipe is not a RecipeHolder, the codec path cannot be used for export.
        // Skip these JEI-display-only categories (grindstone tool-repair, anvil, etc.) unless
        // they are composting or fuel, which have their own dedicated export path.
        boolean isComposting = uid.contains("composting");
        boolean isFuel       = uid.contains("fuel");
        if (!(exampleRecipe instanceof RecipeHolder) && !isComposting && !isFuel) {
            KubeJsRecipeEditor.LOGGER.debug(
                    "KRE: skipping JEI-only category (not a RecipeHolder, not composting/fuel): {}", uid);
            return;
        }

        // ── Cache hit ────────────────────────────────────────────────────────
        RecipeTemplateCacheManager.CachedEntry cached = cacheMap.get(uid);
        if (cached != null) {
            List<SlotCapturingLayoutBuilder.CapturedSlot> slots = cached.slots().stream()
                    .map(s -> new SlotCapturingLayoutBuilder.CapturedSlot(s.parsedRole(), s.x(), s.y()))
                    .toList();
            if (slots.isEmpty()) return;

            JsonObject exportTemplate = null;
            if (cached.templateJson() != null) {
                try { exportTemplate = JsonParser.parseString(cached.templateJson()).getAsJsonObject(); }
                catch (Exception ignored) {}
            }

            RecipeTemplate template = new RecipeTemplate(
                    type,
                    category.getTitle().getString(),
                    category.getBackground(),
                    category.getIcon(),
                    slots,
                    exampleRecipe,
                    cached.minInputSlots(),
                    cached.maxInputSlots(),
                    cached.extraParams(),
                    exportTemplate,
                    null);

            // Build GuiDescriptor for cache-hit templates (in-memory only, not persisted)
            try {
                RegistryAccess hitRegs = Minecraft.getInstance().level.registryAccess();
                List<Object> hitSamples = mgr.createRecipeLookup(type).get().limit(30).toList();
                List<JsonObject> corpus = encodeCorpus(hitSamples, hitRegs);
                if (!corpus.isEmpty()) {
                    GuiDescriptor descriptor = new GuiDescriptorBuilder().build(type.getUid(), corpus, slots);
                    DescriptorCache.INSTANCE.put(type.getUid(), descriptor);
                    // If exportTemplate was null (stale cache entry), derive it now from corpus
                    JsonObject effectiveTemplate = template.exportTemplate() != null
                            ? template.exportTemplate() : mergeCorpus(corpus);
                    template = new RecipeTemplate(
                            template.type(), template.title(), template.background(),
                            template.icon(), template.slots(), template.exampleRecipe(),
                            template.minInputSlots(), template.maxInputSlots(),
                            template.extraParams(), effectiveTemplate, descriptor);
                }
            } catch (Exception ex) {
                KubeJsRecipeEditor.LOGGER.debug("GuiDescriptor build failed (cache hit) for {}: {}", uid, ex.getMessage());
            }

            RecipeTemplateRegistry.INSTANCE.register(template);
            KubeJsRecipeEditor.LOGGER.debug("KRE cache hit: {}", uid);
            return;
        }

        // ── Cache miss: full sampling ─────────────────────────────────────────
        final int SCAN_LIMIT = KreConfig.SCAN_LIMIT.get();
        List<Object> samples = mgr.createRecipeLookup(type).get().limit(SCAN_LIMIT).toList();
        if (samples.isEmpty()) return;

        int minInput = Integer.MAX_VALUE;
        int maxInput = 0;
        Object maxSlotRecipe = samples.get(0);

        for (Object recipe : samples) {
            List<SlotCapturingLayoutBuilder.CapturedSlot> s =
                    SlotCapturingLayoutBuilder.capture(category, recipe, emptyFocus);
            long cnt = s.stream().filter(c -> c.role() == RecipeIngredientRole.INPUT).count();
            if (cnt < minInput) minInput = (int) cnt;
            if (cnt > maxInput) { maxInput = (int) cnt; maxSlotRecipe = recipe; }
        }
        if (maxInput == 0) return;

        List<SlotCapturingLayoutBuilder.CapturedSlot> captured =
                SlotCapturingLayoutBuilder.capture(category, maxSlotRecipe, emptyFocus);
        if (captured.isEmpty()) return;

        RegistryAccess regs = Minecraft.getInstance().level.registryAccess();

        // Build corpus ONCE — codec first, ResourceManager as fallback
        List<JsonObject> corpus = encodeCorpus(samples, regs);

        // Derive mergedTemplate and extraParams from the same corpus (no extra codec calls)
        JsonObject mergedTemplate = mergeCorpus(corpus);
        List<ExtraParam> extraParams = detectExtraParamsFromCorpus(corpus);
        String templateJsonStr = mergedTemplate != null ? new Gson().toJson(mergedTemplate) : null;

        // Persist to cache
        List<RecipeTemplateCacheManager.CachedSlot> cachedSlots = captured.stream()
                .map(s -> new RecipeTemplateCacheManager.CachedSlot(s.role().name(), s.x(), s.y()))
                .toList();
        RecipeTemplateCacheManager.CachedEntry newEntry =
                new RecipeTemplateCacheManager.CachedEntry(minInput, maxInput, cachedSlots, extraParams, templateJsonStr);
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        RecipeTemplateCacheManager.saveEntry(gameDir, uid, newEntry, cacheMap);

        RecipeTemplate registryEntry = new RecipeTemplate(
                type,
                category.getTitle().getString(),
                category.getBackground(),
                category.getIcon(),
                captured,
                maxSlotRecipe,
                minInput,
                maxInput,
                extraParams,
                mergedTemplate,
                null);

        // Build GuiDescriptor from the same corpus
        try {
            if (!corpus.isEmpty()) {
                GuiDescriptor descriptor = new GuiDescriptorBuilder().build(type.getUid(), corpus, captured);
                DescriptorCache.INSTANCE.put(type.getUid(), descriptor);
                registryEntry = new RecipeTemplate(
                        registryEntry.type(), registryEntry.title(), registryEntry.background(),
                        registryEntry.icon(), registryEntry.slots(), registryEntry.exampleRecipe(),
                        registryEntry.minInputSlots(), registryEntry.maxInputSlots(),
                        registryEntry.extraParams(), registryEntry.exportTemplate(), descriptor);
            }
        } catch (Exception ex) {
            KubeJsRecipeEditor.LOGGER.debug("GuiDescriptor build failed for {}: {}", uid, ex.getMessage());
        }

        RecipeTemplateRegistry.INSTANCE.register(registryEntry);
        KubeJsRecipeEditor.LOGGER.debug("KRE cache miss, sampled {} recipes: {}", samples.size(), uid);
    }

    // ─── Parameter detection ──────────────────────────────────────────────────

    /**
     * Detects extra (non-structural) parameters from a pre-built corpus of recipe JSON objects.
     * Works on raw JsonObjects so it doesn't need codec access.
     */
    private static List<ExtraParam> detectExtraParamsFromCorpus(List<JsonObject> corpus) {
        Map<String, List<String>>    stringValues  = new LinkedHashMap<>();
        Map<String, String>          firstValue    = new LinkedHashMap<>();
        Map<String, ExtraParam.Type> detectedType  = new LinkedHashMap<>();
        Map<String, Integer>         intMinBound   = new LinkedHashMap<>();
        Set<String>                  keysAbsent    = new LinkedHashSet<>();
        Set<String>                  allSeenKeys   = new LinkedHashSet<>();

        boolean hasResultChance = false;
        String  resultChanceDefault = "1.0";

        for (JsonObject obj : corpus) {
            for (String prevKey : allSeenKeys) {
                if (!obj.has(prevKey)) keysAbsent.add(prevKey);
            }

            // result_chance detection (nested in results array)
            if (!hasResultChance && obj.has("results") && obj.get("results").isJsonArray()) {
                JsonArray results = obj.getAsJsonArray("results");
                if (!results.isEmpty() && results.get(0).isJsonObject()) {
                    JsonObject firstResult = results.get(0).getAsJsonObject();
                    if (firstResult.has("chance") && firstResult.get("chance").isJsonPrimitive()) {
                        hasResultChance = true;
                        resultChanceDefault = firstResult.get("chance").getAsString();
                    } else if (firstResult.has("probability") && firstResult.get("probability").isJsonPrimitive()) {
                        hasResultChance = true;
                        resultChanceDefault = firstResult.get("probability").getAsString();
                    }
                }
            }

            // Top-level primitive fields only
            for (var entry : obj.entrySet()) {
                String key = entry.getKey();
                if (ExtraParam.isStructuralKey(key)) continue;
                JsonElement val = entry.getValue();
                if (!val.isJsonPrimitive()) continue;

                JsonPrimitive prim = val.getAsJsonPrimitive();
                allSeenKeys.add(key);
                firstValue.putIfAbsent(key, prim.getAsString());

                if (prim.isBoolean()) {
                    detectedType.putIfAbsent(key, ExtraParam.Type.BOOLEAN);
                } else if (prim.isString()) {
                    detectedType.putIfAbsent(key, ExtraParam.Type.STRING);
                    List<String> vals = stringValues.computeIfAbsent(key, k -> new ArrayList<>());
                    String strVal = prim.getAsString();
                    if (!vals.contains(strVal)) vals.add(strVal);
                } else {
                    double d = prim.getAsDouble();
                    ExtraParam.Type numType = (d == Math.floor(d) && !Double.isInfinite(d))
                            ? ExtraParam.Type.INT : ExtraParam.Type.FLOAT;
                    detectedType.putIfAbsent(key, numType);
                    if (numType == ExtraParam.Type.INT) {
                        intMinBound.merge(key, (int) d, Math::min);
                    }
                }
            }
        }

        List<ExtraParam> result = new ArrayList<>();
        for (String key : allSeenKeys) {
            ExtraParam.Type type = detectedType.getOrDefault(key, ExtraParam.Type.STRING);
            String defVal = firstValue.getOrDefault(key, "");
            switch (type) {
                case STRING -> {
                    List<String> vals = stringValues.getOrDefault(key, List.of());
                    boolean absent = keysAbsent.contains(key);
                    if (vals.size() >= 2 || absent) {
                        List<String> enumVals = new ArrayList<>();
                        if (absent) enumVals.add("(none)");
                        enumVals.addAll(vals);
                        result.add(new ExtraParam(key, ExtraParam.Type.ENUM,
                                absent ? "(none)" : defVal, enumVals, Integer.MIN_VALUE));
                    } else {
                        result.add(new ExtraParam(key, ExtraParam.Type.STRING, defVal));
                    }
                }
                case INT -> result.add(new ExtraParam(key, ExtraParam.Type.INT, defVal,
                        List.of(), intMinBound.getOrDefault(key, Integer.MIN_VALUE)));
                default  -> result.add(new ExtraParam(key, type, defVal));
            }
        }
        if (hasResultChance) {
            result.add(new ExtraParam("result_chance", ExtraParam.Type.FLOAT, resultChanceDefault));
        }
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static JsonElement encodeHolder(RecipeHolder<?> holder, DynamicOps<JsonElement> ops) {
        return (JsonElement) ((com.mojang.serialization.Codec) holder.value().getSerializer().codec())
                .encodeStart(ops, holder.value())
                .getOrThrow();
    }

    private static List<JsonObject> encodeCorpus(List<Object> recipes, RegistryAccess regs) {
        // Primary: codec encoding
        var ops = regs.createSerializationContext(JsonOps.INSTANCE);
        List<JsonObject> result = new ArrayList<>();
        for (Object recipe : recipes) {
            if (!(recipe instanceof RecipeHolder<?> holder)) continue;
            try {
                JsonElement el = encodeHolder(holder, ops);
                if (el.isJsonObject()) result.add(el.getAsJsonObject());
            } catch (Exception ignored) {}
        }
        if (!result.isEmpty()) return result;
        // Fallback: read raw JSON directly from server data packs (singleplayer only)
        List<JsonObject> fromRM = readCorpusFromResourceManager(recipes, KreConfig.SCAN_LIMIT.get());
        if (!fromRM.isEmpty()) {
            KubeJsRecipeEditor.LOGGER.debug("KRE: loaded {} recipe(s) from ResourceManager (codec unavailable)", fromRM.size());
        }
        return fromRM;
    }

    private static List<JsonObject> readCorpusFromResourceManager(List<Object> recipes, int limit) {
        net.minecraft.server.MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return List.of();
        net.minecraft.server.packs.resources.ResourceManager rm = server.getResourceManager();
        List<JsonObject> result = new ArrayList<>();
        for (Object recipe : recipes) {
            if (result.size() >= limit) break;
            if (!(recipe instanceof RecipeHolder<?> holder)) continue;
            ResourceLocation id = holder.id();
            ResourceLocation jsonPath = ResourceLocation.fromNamespaceAndPath(
                    id.getNamespace(), "recipe/" + id.getPath() + ".json");
            try {
                rm.getResource(jsonPath).ifPresent(res -> {
                    try (java.io.InputStreamReader reader =
                                 new java.io.InputStreamReader(res.open())) {
                        JsonElement el = JsonParser.parseReader(reader);
                        if (el.isJsonObject()) result.add(el.getAsJsonObject());
                    } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }
        return result;
    }

    @Nullable
    private static JsonObject mergeCorpus(List<JsonObject> corpus) {
        JsonObject merged = new JsonObject();
        for (JsonObject json : corpus) {
            for (var entry : json.entrySet()) {
                if (!merged.has(entry.getKey())) merged.add(entry.getKey(), entry.getValue());
            }
        }
        return merged.size() > 0 ? merged : null;
    }


    // ─── GUI handlers ─────────────────────────────────────────────────────────

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(RecipeBuilderScreen.class, new RecipeBuilderGhostHandler());

        registration.addGhostIngredientHandler(TagEditorScreen.class, new IGhostIngredientHandler<TagEditorScreen>() {
            @Override
            public <I> List<Target<I>> getTargetsTyped(TagEditorScreen gui, ITypedIngredient<I> ingredient, boolean doStart) {
                List<Target<I>> targets = new ArrayList<>();
                if (!(ingredient.getIngredient() instanceof ItemStack)) return targets;
                for (TagEditorScreen.SlotTarget st : gui.getSlotTargets()) {
                    final int index = st.index();
                    targets.add(new Target<>() {
                        @Override public Rect2i getArea() { return new Rect2i(st.x(), st.y(), 18, 18); }
                        @Override public void accept(I ing) {
                            if (ing instanceof ItemStack stack) gui.setSlotIngredient(index, stack);
                        }
                    });
                }
                return targets;
            }
            @Override public void onComplete() {}
        });

        registration.addGuiContainerHandler(RecipeBuilderScreen.class, new IGuiContainerHandler<RecipeBuilderScreen>() {
            @Override
            public List<Rect2i> getGuiExtraAreas(RecipeBuilderScreen screen) { return List.of(); }
        });

        registration.addGuiContainerHandler(TagEditorScreen.class, new IGuiContainerHandler<TagEditorScreen>() {
            @Override
            public List<Rect2i> getGuiExtraAreas(TagEditorScreen screen) { return List.of(); }
        });
    }

    @Nullable
    public static IJeiRuntime getRuntime() {
        return jeiRuntime;
    }

    public static boolean isRuntimeAvailable() {
        return jeiRuntime != null;
    }

    private static class RecipeBuilderGhostHandler implements IGhostIngredientHandler<RecipeBuilderScreen> {
        @Override
        public <I> List<Target<I>> getTargetsTyped(RecipeBuilderScreen gui, ITypedIngredient<I> ingredient, boolean doStart) {
            List<Target<I>> targets = new ArrayList<>();
            if (!(ingredient.getIngredient() instanceof ItemStack)) return targets;

            for (SlotData slot : gui.getInteractiveSlots()) {
                targets.add(new Target<>() {
                    @Override
                    public Rect2i getArea() {
                        return new Rect2i(slot.x, slot.y, slot.w, slot.h);
                    }
                    @Override
                    public void accept(I ing) {
                        if (ing instanceof ItemStack stack) slot.ingredient = stack.copy();
                    }
                });
            }
            return targets;
        }

        @Override
        public void onComplete() {}
    }
}
