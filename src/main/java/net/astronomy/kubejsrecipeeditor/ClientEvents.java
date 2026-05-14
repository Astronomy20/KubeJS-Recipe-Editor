package net.astronomy.kubejsrecipeeditor;

import net.minecraft.commands.Commands;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.astronomy.kubejsrecipeeditor.gui.GuiSessionState;
import net.astronomy.kubejsrecipeeditor.gui.GuiSessionState.LastScreenType;
import net.astronomy.kubejsrecipeeditor.gui.ModMenuScreen;
import net.astronomy.kubejsrecipeeditor.gui.RecipeBuilderMenu;
import net.astronomy.kubejsrecipeeditor.gui.RecipeBuilderScreen;
import net.astronomy.kubejsrecipeeditor.gui.RecipeBrowserScreen;
import net.astronomy.kubejsrecipeeditor.gui.TagEditorScreen;
import net.astronomy.kubejsrecipeeditor.jei.JeiIntegration;
import net.minecraft.network.chat.Component;

@EventBusSubscriber(modid = KubeJsRecipeEditor.MOD_ID, value = Dist.CLIENT)
public class ClientEvents {

    /**
     * /kre — opens the KubeJS Recipe Editor GUI.
     * Allows browsing recipe categories, building and exporting KubeJS recipes,
     * editing/creating tags, and browsing or removing any loaded recipe.
     */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("kre")
                .executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.tell(() -> {
                        if (mc.player != null && mc.screen == null)
                            mc.setScreen(new ModMenuScreen());
                    });
                    return 1;
                })
                .then(Commands.literal("regenerate_cache")
                    .executes(ctx -> {
                        Minecraft mc = Minecraft.getInstance();
                        mc.tell(() -> {
                            String result = JeiIntegration.clearCacheAndReload();
                            if (mc.player != null) {
                                mc.player.sendSystemMessage(
                                    Component.literal("[KRE] " + result));
                            }
                        });
                        return 1;
                    }))
                .then(Commands.literal("regenerate_templates")
                    .executes(ctx -> {
                        Minecraft mc = Minecraft.getInstance();
                        mc.tell(() -> {
                            String result = JeiIntegration.clearTemplatesAndReload();
                            if (mc.player != null) {
                                mc.player.sendSystemMessage(
                                    Component.literal("[KRE] " + result));
                            }
                        });
                        return 1;
                    }))
        );
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!ClientSetup.OPEN_EXPORTER_KEY.consumeClick() || mc.screen != null || mc.player == null) return;

        Screen toOpen = switch (GuiSessionState.getLastScreenType()) {
            case RECIPE_BUILDER -> {
                IRecipeCategory<?> cat = GuiSessionState.getLastCategory();
                if (cat != null) {
                    RecipeBuilderMenu menu = new RecipeBuilderMenu(0, mc.player.getInventory());
                    yield new RecipeBuilderScreen(menu, mc.player.getInventory(), cat);
                }
                yield new ModMenuScreen();
            }
            case TAG_EDITOR -> {
                RecipeBuilderMenu menu = new RecipeBuilderMenu(0, mc.player.getInventory());
                yield new TagEditorScreen(menu, mc.player.getInventory());
            }
            case RECIPE_BROWSER -> new RecipeBrowserScreen();
            default -> new ModMenuScreen();
        };

        GuiSessionState.setLastScreen(toOpen);
        mc.setScreen(toOpen);
    }
}
