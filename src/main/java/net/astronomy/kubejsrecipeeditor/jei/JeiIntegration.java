package net.astronomy.kubejsrecipeeditor.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.astronomy.kubejsrecipeeditor.KubeJsRecipeEditor;
import net.astronomy.kubejsrecipeeditor.gui.RecipeBuilderScreen;
import net.astronomy.kubejsrecipeeditor.gui.SlotData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

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
        KubeJsRecipeEditor.LOGGER.debug("JEI runtime available");
    }

    @Override
    public void onRuntimeUnavailable() {
        jeiRuntime = null;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // Ghost ingredient handler: lets the user drag items from JEI into recipe slots
        registration.addGhostIngredientHandler(RecipeBuilderScreen.class, new RecipeBuilderGhostHandler());

        // Container handler: tells JEI that RecipeBuilderScreen is a recognized container screen.
        // JEI will show its ingredient panel alongside it, positioned to the right of the window.
        registration.addGuiContainerHandler(RecipeBuilderScreen.class, new IGuiContainerHandler<RecipeBuilderScreen>() {
            @Override
            public List<Rect2i> getGuiExtraAreas(RecipeBuilderScreen screen) {
                return List.of(); // no extra areas beyond the standard container window
            }
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
