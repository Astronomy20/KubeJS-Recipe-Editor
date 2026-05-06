package net.astronomy.kubejsrecipeeditor;

import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.astronomy.kubejsrecipeeditor.gui.GuiSessionState;
import net.astronomy.kubejsrecipeeditor.gui.ModMenuScreen;
import net.astronomy.kubejsrecipeeditor.gui.RecipeBuilderMenu;
import net.astronomy.kubejsrecipeeditor.gui.RecipeBuilderScreen;

@EventBusSubscriber(modid = KubeJsRecipeEditor.MOD_ID, value = Dist.CLIENT)
public class ClientEvents {
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!ClientSetup.OPEN_EXPORTER_KEY.consumeClick() || mc.screen != null || mc.player == null) return;

        IRecipeCategory<?> lastCategory = GuiSessionState.getLastCategory();
        Screen toOpen;

        if (lastCategory != null) {
            // Recreate RecipeBuilderScreen fresh (Inventory reference must not be stale)
            RecipeBuilderMenu menu = new RecipeBuilderMenu(0, mc.player.getInventory());
            toOpen = new RecipeBuilderScreen(menu, mc.player.getInventory(), lastCategory);
        } else {
            toOpen = new ModMenuScreen();
        }

        GuiSessionState.setLastScreen(toOpen);
        mc.setScreen(toOpen);
    }
}
