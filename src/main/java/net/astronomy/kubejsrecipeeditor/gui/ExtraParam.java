package net.astronomy.kubejsrecipeeditor.gui;

import java.util.List;

/**
 * An extra (non-ingredient/result) parameter detected from a recipe's codec JSON.
 * Stored in RecipeTemplate as a template; per-screen values are kept in RecipeBuilderScreen.
 */
public record ExtraParam(
        String key,
        ExtraParam.Type type,
        String defaultValueStr,
        /** Non-empty only for ENUM: ordered list of allowed values (may include "(none)"). */
        List<String> enumValues,
        /** For INT: minimum allowed value. Integer.MIN_VALUE = unconstrained. */
        int minBound
) {
    public enum Type { INT, FLOAT, BOOLEAN, STRING, ENUM }

    /** Convenience constructor for non-ENUM, unconstrained params. */
    public ExtraParam(String key, Type type, String defaultValueStr) {
        this(key, type, defaultValueStr, List.of(), Integer.MIN_VALUE);
    }

    /** True for key names that belong to the structural recipe schema, not extra params. */
    public static boolean isStructuralKey(String key) {
        return switch (key) {
            // Vanilla / common
            case "type",
                 "ingredients", "ingredient",
                 "results",     "result",
                 "pattern",     "key",
                 "base",        "template", "addition",
                 "group",       "category"  -> true;
            // NeoForge metadata fields (not user-configurable recipe parameters)
            case "showNotification", "show_notification" -> true;
            // Mekanism — single/dual item inputs, chemical inputs, output
            case "input",        "output",
                 "main_input",   "extra_input",
                 "item_input",   "chemical_input",
                 "left_input",   "right_input"   -> true;
            default -> false;
        };
    }
}
