package net.astronomy.kubejsrecipeeditor;

import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.Registries;
import net.astronomy.kubejsrecipeeditor.gui.RecipeBuilderMenu;

public class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, KubeJsRecipeEditor.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<RecipeBuilderMenu>> RECIPE_BUILDER =
            MENU_TYPES.register("recipe_builder",
                    () -> IMenuTypeExtension.create(RecipeBuilderMenu.FACTORY));
}
