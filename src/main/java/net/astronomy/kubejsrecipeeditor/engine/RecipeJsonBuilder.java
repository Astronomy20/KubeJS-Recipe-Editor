package net.astronomy.kubejsrecipeeditor.engine;

import com.google.gson.*;
import net.astronomy.kubejsrecipeeditor.engine.ContentType;
import net.astronomy.kubejsrecipeeditor.gui.*;

import java.util.*;

/**
 * Reconstructs the recipe JSON from a codec template, replacing ingredient/result
 * fields with user-provided slot values. Uses SlotDescriptor to know exactly which
 * JSON field to modify and with which structure.
 */
public class RecipeJsonBuilder {

    /**
     * @param templateJson  Example recipe JSON (deepCopy before passing)
     * @param descriptor    GuiDescriptor with slot and param info
     * @param slots         All SlotData from RecipeBuilderScreen (input + output)
     * @param paramValues   Map of jsonKey → current user-edited value
     * @return Modified JSON ready for event.custom({...})
     */
    public static JsonObject build(
            JsonObject templateJson,
            GuiDescriptor descriptor,
            List<SlotData> slots,
            Map<String, String> paramValues) {

        JsonObject json = templateJson.deepCopy();

        // Group slots by jsonField
        Map<String, List<SlotWithDescriptor>> byField = new LinkedHashMap<>();
        for (SlotDescriptor sd : descriptor.slots()) {
            SlotData data = findMatchingSlot(slots, sd);
            if (data == null) continue;
            byField.computeIfAbsent(sd.jsonField(), k -> new ArrayList<>())
                   .add(new SlotWithDescriptor(sd, data));
        }

        // Replace each field
        for (var entry : byField.entrySet()) {
            String field = entry.getKey();
            List<SlotWithDescriptor> slotList = entry.getValue();

            boolean isArray = slotList.stream().anyMatch(s -> s.descriptor().jsonArrayIndex() >= 0);

            if (isArray) {
                JsonArray arr = new JsonArray();
                slotList.sort(Comparator.comparingInt(s -> s.descriptor().jsonArrayIndex()));
                for (SlotWithDescriptor swd : slotList) {
                    if (swd.data().isEmpty() && swd.descriptor().optional()) continue;
                    arr.add(buildElement(swd.data()));
                }
                json.add(field, arr);
            } else {
                SlotData data = slotList.get(0).data();
                if (!data.isEmpty()) {
                    json.add(field, buildElement(data));
                }
            }
        }

        // Apply extra param values
        for (ParamDescriptor pd : descriptor.extraParams()) {
            if (pd.readOnly()) continue;
            String val = paramValues.getOrDefault(pd.jsonKey(), pd.defaultValue());
            switch (pd.type()) {
                case INTEGER -> json.addProperty(pd.jsonKey(), parseLongSafe(val));
                case FLOAT   -> json.addProperty(pd.jsonKey(), parseDoubleSafe(val));
                case BOOLEAN -> json.addProperty(pd.jsonKey(), Boolean.parseBoolean(val));
                case ENUM, STRING -> json.addProperty(pd.jsonKey(), val);
                default -> {} // CONSTANT: do not touch
            }
        }

        return json;
    }

    private static JsonElement buildElement(SlotData data) {
        if (data.isEmpty()) return JsonNull.INSTANCE;

        JsonObject obj = new JsonObject();

        // Chemical types (Mekanism) — detected via contentType field
        ContentType ct = data.contentType;
        if (ct == ContentType.CHEMICAL_GAS || ct == ContentType.CHEMICAL_SLURRY
                || ct == ContentType.CHEMICAL_INFUSE || ct == ContentType.CHEMICAL_PIGMENT) {
            String chemKey = switch (ct) {
                case CHEMICAL_GAS    -> "gas";
                case CHEMICAL_SLURRY -> "slurry";
                case CHEMICAL_INFUSE -> "infuse_type";
                case CHEMICAL_PIGMENT -> "pigment";
                default -> "gas";
            };
            if (data.fluidId != null) obj.addProperty(chemKey, data.fluidId.toString());
            obj.addProperty("amount", data.fluidAmount);
            return obj;
        }

        if (data.isFluid) {
            if (data.useFluidTag && data.selectedFluidTag != null) {
                obj.addProperty("fluidTag", data.selectedFluidTag.toString());
            } else if (data.fluidId != null) {
                obj.addProperty("fluid", data.fluidId.toString());
            }
            obj.addProperty("amount", data.fluidAmount);
        } else if (data.useTag && data.selectedTag != null) {
            obj.addProperty("tag", data.selectedTag.toString());
            if (data.count > 1) obj.addProperty("count", data.count);
        } else {
            obj.addProperty("item", data.ingredient.getItem()
                    .builtInRegistryHolder().key().location().toString());
            if (data.count > 1) obj.addProperty("count", data.count);
        }
        return obj;
    }

    /** Matches by JEI-relative coordinates stored in SlotData.jeiRelX/jeiRelY. */
    private static SlotData findMatchingSlot(List<SlotData> slots, SlotDescriptor sd) {
        return slots.stream()
                .filter(s -> s.jeiRelX == sd.jeiX() && s.jeiRelY == sd.jeiY())
                .findFirst().orElse(null);
    }

    private record SlotWithDescriptor(SlotDescriptor descriptor, SlotData data) {}

    private static long parseLongSafe(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; }
    }

    private static double parseDoubleSafe(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0.0; }
    }
}
