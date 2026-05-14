package net.astronomy.kubejsrecipeeditor.engine;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable path to a field within a recipe JSON structure.
 * Example: FieldPath.of("ingredients", "0", "item")
 */
public final class FieldPath {

    private final List<String> segments;

    private FieldPath(List<String> segments) {
        this.segments = List.copyOf(segments);
    }

    public static FieldPath of(String... segments) {
        return new FieldPath(Arrays.asList(segments));
    }

    public static FieldPath root() {
        return new FieldPath(List.of());
    }

    public FieldPath append(String segment) {
        var newSegments = new java.util.ArrayList<>(segments);
        newSegments.add(segment);
        return new FieldPath(newSegments);
    }

    public List<String> segments() {
        return segments;
    }

    public boolean isEmpty() {
        return segments.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof FieldPath other)) return false;
        return segments.equals(other.segments);
    }

    @Override
    public int hashCode() {
        return Objects.hash(segments);
    }

    @Override
    public String toString() {
        return String.join(".", segments);
    }
}
