package net.astronomy.kubejsrecipeeditor.gui;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.IContainerFactory;
import net.astronomy.kubejsrecipeeditor.ModMenuTypes;

public class RecipeBuilderMenu extends AbstractContainerMenu {
    public RecipeBuilderMenu(int containerId, Inventory playerInventory) {
        super(ModMenuTypes.RECIPE_BUILDER.get(), containerId);
        // Fake container — no slots. Exists only to satisfy AbstractContainerScreen
        // and allow JEI to show its ingredient panel alongside the screen.
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    public static final IContainerFactory<RecipeBuilderMenu> FACTORY =
            (containerId, inv, data) -> new RecipeBuilderMenu(containerId, inv);
}
