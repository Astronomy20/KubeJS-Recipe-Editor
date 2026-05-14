package net.astronomy.kubejsrecipeeditor.engine;

import net.astronomy.kubejsrecipeeditor.gui.SlotData;
import net.astronomy.kubejsrecipeeditor.gui.widgets.*;
import net.astronomy.kubejsrecipeeditor.jei.SlotCapturingLayoutBuilder.CapturedSlot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.function.Consumer;

/**
 * Translates a FieldDescriptor.ObjectField into GUI widgets per BACKEND_ARCHITECTURE §6.1.
 *
 * Mapping:
 *   ConstantField                  → nothing (hidden, copied verbatim in output JSON)
 *   ScalarField(INTEGER)           → NumberSpinnerWidget
 *   ScalarField(FLOAT)             → FloatSpinnerWidget
 *   ScalarField(BOOLEAN)           → ToggleWidget
 *   ScalarField(ENUM_STRING ≤4)    → CycleButtonWidget (with multi-button visual if desired)
 *   ScalarField(ENUM_STRING >4)    → CycleButtonWidget
 *   ScalarField(FREE_STRING)       → LabelWidget (read-only)
 *   IngredientField                → TypedSlotWidget (positioned via JEI-captured coords)
 *   ArrayField<IngredientField>    → List<TypedSlotWidget>
 *   ResourceField                  → LabelWidget (read-only resource location)
 *   ObjectField / ArrayField other → LabelWidget (read-only, "[complex field]")
 *   ChanceField                    → TypedSlotWidget + FloatSpinnerWidget
 *   PolymorphicField               → CycleButtonWidget for type selector
 */
public class DynamicGuiBuilder {

    /** Result of building the GUI: slot widgets (Zona 1) + param widgets (Zona 2). */
    public record GuiLayout(
        List<TypedSlotWidget> slotWidgets,       // ingredient/result slots
        List<AbstractWidget>  paramWidgets,       // extra param widgets (spinners, cycles, etc.)
        List<String>          paramJsonKeys       // parallel to paramWidgets — JSON key for each
    ) {}

    /**
     * Builds the full GUI layout from a FieldDescriptor tree and JEI-captured slot positions.
     *
     * @param root          ObjectField from TemplateRegistry (the full recipe schema)
     * @param capturedSlots JEI-captured slot positions (from SlotCapturingLayoutBuilder)
     * @param screenLeft    GUI left edge (for absolute positioning)
     * @param screenTop     GUI top edge
     * @param baseParamY    Y coordinate where Zona 2 (extra params) starts
     * @param paramWidth    Width available for param widgets
     * @param slotDataList  Mutable list that receives SlotData instances (one per slot widget)
     * @param paramValues   Map to store param values; populated with defaults on build
     * @param onSlotClick   Callback for when user clicks a slot widget
     */
    public GuiLayout build(
            FieldDescriptor.ObjectField root,
            List<CapturedSlot> capturedSlots,
            int screenLeft, int screenTop,
            int baseParamY, int paramWidth,
            List<SlotData> slotDataList,
            Map<String, String> paramValues,
            Consumer<TypedSlotWidget> onSlotClick) {

        List<TypedSlotWidget> slotWidgets = new ArrayList<>();
        List<AbstractWidget> paramWidgets = new ArrayList<>();
        List<String> paramJsonKeys = new ArrayList<>();

        // Separate input and output slots from JEI capture
        List<CapturedSlot> inputSlots = capturedSlots.stream()
            .filter(s -> s.role() == mezz.jei.api.recipe.RecipeIngredientRole.INPUT).toList();
        List<CapturedSlot> outputSlots = capturedSlots.stream()
            .filter(s -> s.role() == mezz.jei.api.recipe.RecipeIngredientRole.OUTPUT).toList();

        int inputIdx = 0, outputIdx = 0;

        for (var entry : root.children().entrySet()) {
            String key = entry.getKey();
            FieldDescriptor fd = entry.getValue();

            switch (fd) {
                case FieldDescriptor.ConstantField ignored -> {}  // hidden

                case FieldDescriptor.IngredientField igf -> {
                    // Single ingredient slot
                    boolean isOutput = isResultKey(key);
                    List<CapturedSlot> pool = isOutput ? outputSlots : inputSlots;
                    int idx = isOutput ? outputIdx : inputIdx;
                    if (idx < pool.size()) {
                        CapturedSlot cs = pool.get(idx);
                        Set<ContentType> types = resolveTypes(igf, cs);
                        SlotData data = new SlotData(
                            screenLeft + cs.x(), screenTop + cs.y(), 18, 18,
                            cs.role(), 0, 0);
                        if (cs.isFluid()) {
                            data.isFluid = true;
                            types = Set.of(ContentType.FLUID);
                        }
                        slotDataList.add(data);
                        slotWidgets.add(new TypedSlotWidget(
                            screenLeft + cs.x(), screenTop + cs.y(),
                            data, types, onSlotClick));
                        if (isOutput) outputIdx++; else inputIdx++;
                    }
                }

                case FieldDescriptor.ArrayField af
                        when af.elementDescriptor() instanceof FieldDescriptor.IngredientField igf -> {
                    // Array of ingredient slots
                    boolean isOutput = isResultKey(key);
                    List<CapturedSlot> pool = isOutput ? outputSlots : inputSlots;
                    int startIdx = isOutput ? outputIdx : inputIdx;
                    int count = Math.min(af.maxItems() > 0 ? af.maxItems() : pool.size() - startIdx,
                                         pool.size() - startIdx);
                    for (int i = 0; i < count; i++) {
                        CapturedSlot cs = pool.get(startIdx + i);
                        Set<ContentType> types = resolveTypes(igf, cs);
                        SlotData data = new SlotData(
                            screenLeft + cs.x(), screenTop + cs.y(), 18, 18,
                            cs.role(), 0, i);
                        if (cs.isFluid()) {
                            data.isFluid = true;
                            types = Set.of(ContentType.FLUID);
                        }
                        slotDataList.add(data);
                        slotWidgets.add(new TypedSlotWidget(
                            screenLeft + cs.x(), screenTop + cs.y(),
                            data, types, onSlotClick));
                    }
                    if (isOutput) outputIdx += count; else inputIdx += count;
                }

                case FieldDescriptor.ScalarField sf -> {
                    // Extra param widget
                    int paramY = baseParamY + paramWidgets.size() * 22;
                    int cx = screenLeft + paramWidth / 2;

                    AbstractWidget widget = switch (sf.scalarType()) {
                        case INTEGER -> {
                            long def = safeParseLong(sf.defaultValue(), (long) sf.min());
                            long min = (long) sf.min();
                            long max = sf.max() > sf.min() ? (long) sf.max() : Long.MAX_VALUE;
                            String stored = paramValues.computeIfAbsent(key, k -> String.valueOf(def));
                            long initVal = safeParseLong(stored, def);
                            var w = new NumberSpinnerWidget(cx - 55, paramY, 110, key, initVal, min, max,
                                v -> paramValues.put(key, String.valueOf(v)));
                            yield w;
                        }
                        case FLOAT -> {
                            double def = safeParseDouble(sf.defaultValue(), sf.min());
                            double min = sf.min();
                            double max = sf.max() > sf.min() ? sf.max() : Double.MAX_VALUE;
                            String stored = paramValues.computeIfAbsent(key, k -> String.valueOf(def));
                            double initVal = safeParseDouble(stored, def);
                            yield new FloatSpinnerWidget(cx - 55, paramY, 110, key, initVal, min, max,
                                v -> paramValues.put(key, String.valueOf(v)));
                        }
                        case BOOLEAN -> {
                            String stored = paramValues.computeIfAbsent(key,
                                k -> sf.defaultValue() != null ? sf.defaultValue() : "false");
                            boolean initVal = Boolean.parseBoolean(stored);
                            yield new ToggleWidget(cx - 55, paramY, 110, key, initVal,
                                v -> paramValues.put(key, String.valueOf(v)));
                        }
                        case ENUM_STRING -> {
                            String def = sf.defaultValue() != null ? sf.defaultValue()
                                : (sf.enumValues().isEmpty() ? "" : sf.enumValues().get(0));
                            String stored = paramValues.computeIfAbsent(key, k -> def);
                            List<String> vals = sf.enumValues().isEmpty()
                                ? List.of(stored) : sf.enumValues();
                            yield new CycleButtonWidget(cx - 55, paramY, 110, key, vals, stored,
                                v -> paramValues.put(key, v));
                        }
                        case FREE_STRING -> {
                            paramValues.computeIfAbsent(key,
                                k -> sf.defaultValue() != null ? sf.defaultValue() : "");
                            yield null; // read-only, no widget
                        }
                    };

                    if (widget != null) {
                        paramWidgets.add(widget);
                        paramJsonKeys.add(key);
                    }
                }

                default -> {} // ObjectField, ChanceField, etc. — not yet rendered
            }
        }

        return new GuiLayout(slotWidgets, paramWidgets, paramJsonKeys);
    }

    // --- Helpers ---

    private static final Set<String> RESULT_KEYS = Set.of(
        "result", "results", "output", "outputs", "outputs_item", "output_item");

    private static boolean isResultKey(String key) {
        return RESULT_KEYS.contains(key.toLowerCase());
    }

    private static Set<ContentType> resolveTypes(FieldDescriptor.IngredientField igf,
            CapturedSlot cs) {
        Set<ContentType> types = igf.acceptedTypes().isEmpty()
            ? new java.util.LinkedHashSet<>()
            : new java.util.LinkedHashSet<>(igf.acceptedTypes());
        // JEI's isFluid flag takes precedence over inferred types
        if (cs.isFluid()) {
            types.clear();
            types.add(ContentType.FLUID);
        }
        return types.isEmpty() ? Set.of(ContentType.ITEM, ContentType.ITEM_TAG) : types;
    }

    private static long safeParseLong(String s, long fallback) {
        if (s == null) return fallback;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return fallback; }
    }

    private static double safeParseDouble(String s, double fallback) {
        if (s == null) return fallback;
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return fallback; }
    }
}
