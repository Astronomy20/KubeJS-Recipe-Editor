package net.astronomy.kubejsrecipeeditor.gui;

import mezz.jei.api.recipe.RecipeType;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Client-only registry of layouts captured from JEI at runtime. Cleared on JEI unload (e.g. F3+T).
 */
public final class RecipeTemplateRegistry {

    public static final RecipeTemplateRegistry INSTANCE = new RecipeTemplateRegistry();

    private final Map<RecipeType<?>, RecipeTemplate> templates = new LinkedHashMap<>();

    private RecipeTemplateRegistry() {}

    public void register(RecipeTemplate template) {
        templates.put(template.type(), template);
    }

    public Optional<RecipeTemplate> get(RecipeType<?> type) {
        return Optional.ofNullable(templates.get(type));
    }

    public Collection<RecipeTemplate> all() {
        return Collections.unmodifiableCollection(templates.values());
    }

    public void clear() {
        templates.clear();
    }

    public boolean isEmpty() {
        return templates.isEmpty();
    }
}
