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

        boolean isComposting = uid.contains("composting");
        boolean isFuel       = uid.contains("fuel");
        boolean isBrewing    = uid.contains("brewing");

        // Sample one recipe for the exampleRecipe reference.
        // Composting, fuel and brewing may have no JEI recipes but still need to be registered
        // because RecipeBuilderScreen provides its own dedicated UI for them.
        Object exampleRecipe = mgr.createRecipeLookup(type).get().findFirst().orElse(null);
        if (exampleRecipe == null && !isComposting && !isFuel && !isBrewing) return;

        // If the example recipe is not a RecipeHolder, the codec path cannot be used for export.
        // Skip these JEI-display-only categories (grindstone tool-repair, anvil, etc.) unless
        // they have their own dedicated export path.
        if (exampleRecipe != null && !(exampleRecipe instanceof RecipeHolder) && !isComposting && !isFuel && !isBrewing) {
            KubeJsRecipeEditor.LOGGER.debug(
                    "KRE: skipping JEI-only category (not a RecipeHolder, not composting/fuel/brewing): {}", uid);
            return;
        }

        // ── Cache hit ────────────────────────────────────────────────────────
        RecipeTemplateCacheManager.CachedEntry cached = cacheMap.get(uid);
        if (cached != null) {
            List<SlotCapturingLayoutBuilder.CapturedSlot> slots = cached.slots().stream()
                    .map(s -> new SlotCapturingLayoutBuilder.CapturedSlot(s.parsedRole(), s.x(), s.y()))
                    .toList();
            // Skip only if empty slots AND no codec template (truly display-only, no export possible).
            // RecipeHolder categories (e.g. Industrial Foregoing) may have 0 JEI slots but CAN be
            // exported via codec — they have a templateJson in the cache.
            // Composting/fuel/brewing always proceed regardless.
            if (slots.isEmpty() && !isComposting && !isFuel && !isBrewing && cached.templateJson() == null) return;

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

            RecipeTemplateRegistry.INSTANCE.register(template, category);
            KubeJsRecipeEditor.LOGGER.debug("KRE cache hit: {}", uid);
            return;
        }

        // ── Cache miss: full sampling — scan ALL recipes of this type ─────────
        List<Object> samples = mgr.createRecipeLookup(type).get().toList();
        if (samples.isEmpty() && !isComposting && !isFuel && !isBrewing) return;

        int minInput = Integer.MAX_VALUE;
        int maxInput = 0;
        Object maxSlotRecipe = samples.isEmpty() ? null : samples.get(0);

        for (Object recipe : samples) {
            List<SlotCapturingLayoutBuilder.CapturedSlot> s =
                    SlotCapturingLayoutBuilder.capture(category, recipe, emptyFocus);
            long cnt = s.stream().filter(c -> c.role() == RecipeIngredientRole.INPUT).count();
            if (cnt < minInput) minInput = (int) cnt;
            if (cnt > maxInput) { maxInput = (int) cnt; maxSlotRecipe = recipe; }
        }
        if (minInput == Integer.MAX_VALUE) minInput = 0; // no samples iterated

        // Skip display-only categories with 0 captured slots, UNLESS they are:
        // - a special type (composting, fuel, brewing) with dedicated UI
        // - a RecipeHolder category (can export via codec even with RENDER_ONLY JEI slots, e.g. IF)
        if (maxInput == 0 && !isComposting && !isFuel && !isBrewing
                && !(exampleRecipe instanceof RecipeHolder)) return;

        List<SlotCapturingLayoutBuilder.CapturedSlot> captured = maxSlotRecipe != null
                ? SlotCapturingLayoutBuilder.capture(category, maxSlotRecipe, emptyFocus)
                : List.of();
        // For RecipeHolder categories (e.g. Industrial Foregoing) that declare all slots as
        // RENDER_ONLY, captured is empty but the category is still exportable via codec.
        // Only hard-skip truly display-only categories (non-RecipeHolder, non-composting/fuel/brewing).
        if (captured.isEmpty() && !isComposting && !isFuel && !isBrewing && !(exampleRecipe instanceof RecipeHolder)) return;

        RegistryAccess regs = Minecraft.getInstance().level.registryAccess();

        // Build codec corpus (all recipes) — used for export template structure.
        List<JsonObject> corpus = encodeCorpus(samples, regs);

        // Wide RM corpus (all recipes, cheap JSON parsing) — used for ExtraParam detection.
        List<Object> wideRecipes = mgr.createRecipeLookup(type).get().toList();
        List<JsonObject> rmCorpus = readCorpusFromResourceManager(wideRecipes, Integer.MAX_VALUE);
        List<JsonObject> detectionCorpus = rmCorpus.isEmpty() ? corpus : rmCorpus;

        // Export template: codec corpus (authoritative field names) augmented with RM fields.
        JsonObject mergedTemplate = buildMergedExportTemplate(corpus, detectionCorpus);
        List<ExtraParam> extraParams = new ArrayList<>(detectExtraParamsFromCorpus(detectionCorpus));

        // Sequenced Assembly must not expose heatRequirement even if a stale corpus entry has it.
        if (mergedTemplate != null && mergedTemplate.has("sequence")) {
            extraParams.removeIf(ep ->
                    ep.key().equalsIgnoreCase("heatRequirement")
                    || ep.key().equalsIgnoreCase("heat_requirement"));
        }

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

        RecipeTemplateRegistry.INSTANCE.register(registryEntry, category);
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
                JsonElement val = entry.getValue();
                if (ExtraParam.isStructuralKey(key)) {
                    // Exception: numeric primitive structural keys are configurable params
                    // (e.g. Mekanism energy_conversion uses "output": 40000).
                    if (!val.isJsonPrimitive() || val.getAsJsonPrimitive().isString()) continue;
                }
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
                    // Allow upgrade from INT to FLOAT when a fractional value is found
                    // (e.g. smelting "experience": first recipe has 0.0 → INT, later 0.35 → upgrade to FLOAT)
                    if (!detectedType.containsKey(key) ||
                            (detectedType.get(key) == ExtraParam.Type.INT && numType == ExtraParam.Type.FLOAT)) {
                        detectedType.put(key, numType);
                    }
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
        List<JsonObject> fromRM = readCorpusFromResourceManager(recipes, Integer.MAX_VALUE);
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

    /**
     * Builds the export template starting from the codec corpus (authoritative field names/format),
     * then augments it with any additional fields found in the detection corpus (wider RM-based scan).
     * This ensures optional fields like heat_requirement appear in the template even when absent
     * from most codec-sampled recipes.
     */
    @Nullable
    private static JsonObject buildMergedExportTemplate(List<JsonObject> codecCorpus,
                                                         List<JsonObject> detectionCorpus) {
        JsonObject merged = new JsonObject();
        for (JsonObject obj : codecCorpus) {
            for (var e : obj.entrySet()) {
                if (!merged.has(e.getKey())) merged.add(e.getKey(), e.getValue());
            }
        }
        for (JsonObject obj : detectionCorpus) {
            for (var e : obj.entrySet()) {
                if (!merged.has(e.getKey())) merged.add(e.getKey(), e.getValue());
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

    /**
     * Deletes the on-disk cache and re-runs the full template population so all recipe
     * categories are re-sampled from scratch. Call this from the /kre regenerate_cache command.
     *
     * @return human-readable result message
     */
    public static String clearCacheAndReload() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        java.nio.file.Path gameDir = mc.gameDirectory.toPath();
        boolean deleted = RecipeTemplateCacheManager.deleteCache(gameDir);

        if (jeiRuntime == null) {
            return deleted
                    ? "Cache deleted. Open a world and wait for JEI to load to rebuild."
                    : "No cache file found. JEI runtime not yet available.";
        }

        // Re-populate fully (bypasses on-disk cache since file was deleted)
        populateRecipeTemplates(jeiRuntime);
        int count = RecipeTemplateRegistry.INSTANCE.all().size();
        return (deleted ? "Cache cleared." : "No cache file.") + " Reloaded " + count + " recipe templates.";
    }

    private static class RecipeBuilderGhostHandler implements IGhostIngredientHandler<RecipeBuilderScreen> {
        @Override
        public <I> List<Target<I>> getTargetsTyped(RecipeBuilderScreen gui, ITypedIngredient<I> ingredient, boolean doStart) {
            List<Target<I>> targets = new ArrayList<>();
            Object ing = ingredient.getIngredient();

            if (ing instanceof net.neoforged.neoforge.fluids.FluidStack fluidStack) {
                // Fluid ingredient: target all interactive slots — acceptFluidIngredient handles validation
                for (SlotData slot : gui.getInteractiveSlots()) {
                    targets.add(new Target<>() {
                        @Override public Rect2i getArea() { return new Rect2i(slot.x, slot.y, slot.w, slot.h); }
                        @Override public void accept(I i) {
                            if (i instanceof net.neoforged.neoforge.fluids.FluidStack fs)
                                gui.acceptFluidIngredient(slot, fs);
                        }
                    });
                }
                return targets;
            }

            if (!(ing instanceof ItemStack)) return targets;

            for (SlotData slot : gui.getInteractiveSlots()) {
                targets.add(new Target<>() {
                    @Override
                    public Rect2i getArea() {
                        return new Rect2i(slot.x, slot.y, slot.w, slot.h);
                    }
                    @Override
                    public void accept(I i) {
                        if (i instanceof ItemStack stack) gui.acceptIngredient(slot, stack);
                    }
                });
            }
            return targets;
        }

        @Override
        public void onComplete() {}
    }
}
