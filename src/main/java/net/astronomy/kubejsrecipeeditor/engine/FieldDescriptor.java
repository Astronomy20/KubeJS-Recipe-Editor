package net.astronomy.kubejsrecipeeditor.engine;

import java.util.List;
import java.util.Map;
import java.util.Set;

public sealed interface FieldDescriptor permits
    FieldDescriptor.ConstantField,
    FieldDescriptor.ScalarField,
    FieldDescriptor.ResourceField,
    FieldDescriptor.IngredientField,
    FieldDescriptor.CompoundField,
    FieldDescriptor.ObjectField,
    FieldDescriptor.ArrayField,
    FieldDescriptor.ChanceField,
    FieldDescriptor.PolymorphicField,
    FieldDescriptor.SequenceField
{
    String key();
    boolean optional();
    String defaultValue();
    int presentInNOfTotal();

    record ConstantField(String key, String constantValue) implements FieldDescriptor {
        public boolean optional() { return false; }
        public String defaultValue() { return constantValue; }
        public int presentInNOfTotal() { return -1; }
    }

    record ScalarField(
        String key, boolean optional, String defaultValue, int presentInNOfTotal,
        ScalarType scalarType,
        double min, double max,
        List<String> enumValues
    ) implements FieldDescriptor {
        public enum ScalarType { INTEGER, FLOAT, BOOLEAN, ENUM_STRING, FREE_STRING }
    }

    record ResourceField(
        String key, boolean optional, String defaultValue, int presentInNOfTotal,
        ContentType expectedContentType,
        String registryKey
    ) implements FieldDescriptor {}

    record IngredientField(
        String key, boolean optional, String defaultValue, int presentInNOfTotal,
        Set<ContentType> acceptedTypes,
        Map<String, FieldDescriptor> subfields
    ) implements FieldDescriptor {}

    record ObjectField(
        String key, boolean optional, String defaultValue, int presentInNOfTotal,
        Map<String, FieldDescriptor> children,
        boolean collapsible
    ) implements FieldDescriptor {}

    record ArrayField(
        String key, boolean optional, String defaultValue, int presentInNOfTotal,
        FieldDescriptor elementDescriptor,
        int minItems, int maxItems
    ) implements FieldDescriptor {}

    record ChanceField(
        String key, boolean optional, String defaultValue, int presentInNOfTotal,
        IngredientField ingredient,
        ScalarField chance
    ) implements FieldDescriptor {}

    record PolymorphicField(
        String key, boolean optional, String defaultValue, int presentInNOfTotal,
        List<FieldDescriptor> variants
    ) implements FieldDescriptor {}

    record CompoundField(
        String key, boolean optional, String defaultValue, int presentInNOfTotal,
        List<FieldDescriptor> children
    ) implements FieldDescriptor {}

    record SequenceField(
        String key, boolean optional, String defaultValue, int presentInNOfTotal,
        FieldDescriptor stepDescriptor
    ) implements FieldDescriptor {}
}
