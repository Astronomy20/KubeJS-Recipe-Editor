package net.astronomy.kubejsrecipeeditor.gui;

import java.util.List;

/**
 * Replaces ExtraParam for the template engine. Produced by JsonSchemaInferencer.
 * Contains all info needed to generate the correct UI widget.
 */
public record ParamDescriptor(
        String jsonKey,
        String displayLabel,
        ParamType type,
        String defaultValue,
        List<String> enumValues,
        double minValue,
        double maxValue,
        boolean optional,
        boolean readOnly
) {
    public enum ParamType { CONSTANT, BOOLEAN, ENUM, INTEGER, FLOAT, STRING }

    public static boolean isStructuralKey(String key) {
        return switch (key) {
            case "type", "group", "category",
                 "ingredients", "ingredient",
                 "results",     "result",
                 "pattern",     "key",
                 "base",        "template", "addition" -> true;
            default -> false;
        };
    }

    public static String humanizeKey(String key) {
        return java.util.Arrays.stream(key.split("(?=[A-Z])|_"))
                .filter(w -> !w.isEmpty())
                .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase())
                .collect(java.util.stream.Collectors.joining(" "));
    }
}
