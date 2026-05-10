package net.astronomy.kubejsrecipeeditor.jei;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
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
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.core.HolderLookup;
import net.neoforged.neoforge.common.util.NeoForgeExtraCodecs;
import net.minecraft.world.item.crafting.Recipe;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.astronomy.kubejsrecipeeditor.KubeJsRecipeEditor;
import net.astronomy.kubejsrecipeeditor.gui.ExtraParam;
import net.astronomy.kubejsrecipeeditor.gui.RecipeBuilderScreen;
import net.astronomy.kubejsrecipeeditor.gui.RecipeTemplate;
import net.astronomy.kubejsrecipeeditor.gui.RecipeTemplateRegistry;
import net.astronomy.kubejsrecipeeditor.gui.SlotData;
import net.astronomy.kubejsrecipeeditor.gui.TagEditorScreen;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@JeiPlugin
public class JeiIntegration implements IModPlugin {
    @Nullable
    private static IJeiRuntime jeiRuntime;

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
        jeiRuntime = null;
    }

    /**
     * Captures slot positions from each JEI category via {@link SlotCapturingLayoutBuilder}
     * and stores drawable backgrounds for pixel-aligned recipe builder GUIs.
     */
    private static void populateRecipeTemplates(IJeiRuntime runtime) {
        RecipeTemplateRegistry.INSTANCE.clear();
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

        // Sample up to SCAN_LIMIT recipes to determine min/max input-slot count.
        final int SCAN_LIMIT = 30;
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

        // Capture from the recipe with the most slots so we have all possible positions.
        List<SlotCapturingLayoutBuilder.CapturedSlot> captured =
                SlotCapturingLayoutBuilder.capture(category, maxSlotRecipe, emptyFocus);
        if (captured.isEmpty()) return;

        // Detect extra params by aggregating across ALL sampled recipes so that
        // params present only in certain variants (e.g. heatRequirement) are captured.
        List<ExtraParam> extraParams = detectExtraParamsAggregated(samples);

        RecipeTemplate registryEntry = new RecipeTemplate(
                type,
                category.getTitle().getString(),
                category.getBackground(),
                category.getIcon(),
                captured,
                maxSlotRecipe,
                minInput,
                maxInput,
                extraParams);

        RecipeTemplateRegistry.INSTANCE.register(registryEntry);
    }

    /**
     * Merges extra params from all sampled recipes (first-seen value wins as default).
     * This ensures params present only in some variants of a recipe type are not missed.
     */
    private static List<ExtraParam> detectExtraParamsAggregated(List<Object> samples) {
        Map<String, ExtraParam> merged = new LinkedHashMap<>();
        for (Object recipe : samples) {
            for (ExtraParam ep : detectExtraParams(recipe)) {
                merged.putIfAbsent(ep.key(), ep); // first-seen default value wins
            }
        }
        return new ArrayList<>(merged.values());
    }

    /**
     * Serialize the recipe via its codec and pick out primitive fields that are NOT
     * part of the structural ingredient/result schema. These become editable extra params.
     */
    private static List<ExtraParam> detectExtraParams(Object recipe) {
        if (!(recipe instanceof RecipeHolder<?> holder)) return List.of();
        try {
            RegistryAccess regs = Minecraft.getInstance().level.registryAccess();
            DynamicOps<JsonElement> ops = regs.createSerializationContext(JsonOps.INSTANCE);
            @SuppressWarnings({"rawtypes", "unchecked"})
            JsonElement encoded = (JsonElement) ((com.mojang.serialization.Codec) holder.value().getSerializer().codec())
                    .encodeStart(ops, holder.value())
                    .getOrThrow();
            if (!encoded.isJsonObject()) return List.of();

            JsonObject obj = encoded.getAsJsonObject();
            List<ExtraParam> params = new ArrayList<>();
            for (var entry : obj.entrySet()) {
                String key = entry.getKey();
                if (ExtraParam.isStructuralKey(key)) continue;
                JsonElement val = entry.getValue();
                if (!val.isJsonPrimitive()) continue; // skip arrays / nested objects
                JsonPrimitive prim = val.getAsJsonPrimitive();
                ExtraParam.Type paramType;
                if (prim.isBoolean())     paramType = ExtraParam.Type.BOOLEAN;
                else if (prim.isString()) paramType = ExtraParam.Type.STRING;
                else {
                    // number: int if whole, float otherwise
                    double d = prim.getAsDouble();
                    paramType = (d == Math.floor(d)) ? ExtraParam.Type.INT : ExtraParam.Type.FLOAT;
                }
                params.add(new ExtraParam(key, paramType, prim.getAsString()));
            }
            return params;
        } catch (Exception e) {
            KubeJsRecipeEditor.LOGGER.debug("Extra param detection failed: {}", e.getMessage());
            return List.of();
        }
    }




    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // Ghost ingredient handler: lets the user drag items from JEI into recipe slots
        registration.addGhostIngredientHandler(RecipeBuilderScreen.class, new RecipeBuilderGhostHandler());

        // Ghost ingredient handler for the Tag Editor slot row
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

        // Container handler: tells JEI that RecipeBuilderScreen is a recognized container screen.
        registration.addGuiContainerHandler(RecipeBuilderScreen.class, new IGuiContainerHandler<RecipeBuilderScreen>() {
            @Override
            public List<Rect2i> getGuiExtraAreas(RecipeBuilderScreen screen) { return List.of(); }
        });

        // Container handler for TagEditorScreen — makes JEI show its panel alongside the tag editor.
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
