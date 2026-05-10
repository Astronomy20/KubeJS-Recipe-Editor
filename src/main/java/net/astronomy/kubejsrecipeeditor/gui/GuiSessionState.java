package net.astronomy.kubejsrecipeeditor.gui;

import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

public class GuiSessionState {
    public enum LastScreenType { HOME, RECIPE_BUILDER, TAG_EDITOR, RECIPE_BROWSER }

    private static Screen lastScreen = null;
    private static IRecipeCategory<?> lastCategory = null;
    private static LastScreenType lastScreenType = LastScreenType.HOME;
    private static final Map<String, Boolean> sectionExpanded = new HashMap<>();

    public static Screen getLastScreen() { return lastScreen; }
    public static void setLastScreen(Screen screen) { lastScreen = screen; }

    public static IRecipeCategory<?> getLastCategory() { return lastCategory; }
    public static void setLastCategory(IRecipeCategory<?> category) { lastCategory = category; }

    public static LastScreenType getLastScreenType() { return lastScreenType; }
    public static void setLastScreenType(LastScreenType type) { lastScreenType = type; }

    public static boolean isSectionExpanded(String namespace) {
        return sectionExpanded.getOrDefault(namespace, true);
    }

    public static void toggleSection(String namespace) {
        sectionExpanded.put(namespace, !isSectionExpanded(namespace));
    }
}
