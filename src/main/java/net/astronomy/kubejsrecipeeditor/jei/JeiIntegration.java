package net.astronomy.kubejsrecipeeditor.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.astronomy.kubejsrecipeeditor.KubeJsRecipeEditor;
import net.astronomy.kubejsrecipeeditor.gui.RecipeBuilderScreen;
import net.astronomy.kubejsrecipeeditor.gui.RecipeTemplate;
import net.astronomy.kubejsrecipeeditor.gui.RecipeTemplateRegistry;
import net.astronomy.kubejsrecipeeditor.gui.SlotData;
import net.astronomy.kubejsrecipeeditor.gui.TagEditorScreen;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
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
        Optional<Object> example = pickExampleRecipe(type, mgr);
        if (example.isEmpty()) {
            return;
        }

        List<SlotCapturingLayoutBuilder.CapturedSlot> captured = SlotCapturingLayoutBuilder.capture(
                category,
                example.get(),
                emptyFocus);

        if (captured.isEmpty()) {
            return;
        }

        RecipeTemplate registryEntry = new RecipeTemplate(
                type,
                category.getTitle().getString(),
                category.getBackground(),
                category.getIcon(),
                captured,
                example.get());

        RecipeTemplateRegistry.INSTANCE.register(registryEntry);
    }

    /** Prefer a shaped grid for vanilla crafting categories so captured slots match distinct positions. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Optional<Object> pickExampleRecipe(RecipeType<?> rawType, IRecipeManager mgr) {
        RecipeType<Object> type = (RecipeType<Object>) rawType;
        final String typeUid = rawType.getUid().toString();
        Optional<Object> filtered = mgr.createRecipeLookup(type)
                .get()
                .filter(r -> {
                    if ("minecraft:crafting".equals(typeUid) || "crafting".equals(typeUid)) {
                        return (r instanceof RecipeHolder<?> holder)
                                && holder.value() instanceof ShapedRecipe;
                    }
                    return true;
                })
                .findFirst();

        return filtered.or(() -> mgr.createRecipeLookup(type).get().findFirst());
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
