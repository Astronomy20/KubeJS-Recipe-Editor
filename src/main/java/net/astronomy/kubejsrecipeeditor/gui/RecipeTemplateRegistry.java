package net.astronomy.kubejsrecipeeditor.gui;

import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Client-only registry of layouts captured from JEI at runtime. Cleared on JEI unload (e.g. F3+T).
 * Also stores the IRecipeCategory reference so ModMenuScreen can build its list from this registry
 * rather than from JEI's createRecipeCategoryLookup(), which may omit categories with no visible recipes.
 */
public final class RecipeTemplateRegistry {

    public static final RecipeTemplateRegistry INSTANCE = new RecipeTemplateRegistry();

    private final Map<RecipeType<?>, RecipeTemplate>     templates  = new LinkedHashMap<>();
    private final Map<RecipeType<?>, IRecipeCategory<?>> categories = new LinkedHashMap<>();

    private RecipeTemplateRegistry() {}

    /** Registers both the template and its JEI category (preferred overload). */
    public void register(RecipeTemplate template, IRecipeCategory<?> category) {
        templates.put(template.type(), template);
        categories.put(template.type(), category);
    }

    /** Registers the template without a category reference (legacy / fallback). */
    public void register(RecipeTemplate template) {
        templates.put(template.type(), template);
    }

    public Optional<RecipeTemplate> get(RecipeType<?> type) {
        return Optional.ofNullable(templates.get(type));
    }

    public Optional<IRecipeCategory<?>> getCategory(RecipeType<?> type) {
        return Optional.ofNullable(categories.get(type));
    }

    public Collection<RecipeTemplate> all() {
        return Collections.unmodifiableCollection(templates.values());
    }

    public void clear() {
        templates.clear();
        categories.clear();
    }

    public boolean isEmpty() {
        return templates.isEmpty();
    }
}
