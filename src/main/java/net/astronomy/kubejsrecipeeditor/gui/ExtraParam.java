package net.astronomy.kubejsrecipeeditor.gui;

/**
 * An extra (non-ingredient/result) parameter detected from a recipe's codec JSON.
 * Stored in RecipeTemplate as a template; per-screen values are kept in RecipeBuilderScreen.
 */
public record ExtraParam(String key, ExtraParam.Type type, String defaultValueStr) {

    public enum Type { INT, FLOAT, BOOLEAN, STRING }

    /** True for key names that belong to the structural recipe schema, not extra params. */
    public static boolean isStructuralKey(String key) {
        return switch (key) {
            case "type",
                 "ingredients", "ingredient",
                 "results",     "result",
                 "pattern",     "key",
                 "base",        "template", "addition",
                 "group",       "category"  -> true;
            default -> false;
        };
    }
}
