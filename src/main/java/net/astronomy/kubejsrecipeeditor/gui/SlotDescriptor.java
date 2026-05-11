package net.astronomy.kubejsrecipeeditor.gui;

import mezz.jei.api.recipe.RecipeIngredientRole;
import net.astronomy.kubejsrecipeeditor.jei.SlotCapturingLayoutBuilder.CapturedSlot;

import java.util.Set;

/**
 * Extends CapturedSlot with information about the accepted content type
 * and the corresponding JSON field. Produced by GuiDescriptorBuilder.
 */
public record SlotDescriptor(
        int jeiX,
        int jeiY,
        RecipeIngredientRole role,
        String jsonField,
        int jsonArrayIndex,
        Set<SlotContentType> accepts,
        boolean optional
) {
    public enum SlotContentType { ITEM, TAG_ITEM, FLUID, TAG_FLUID }

    public boolean acceptsFluid() {
        return accepts.contains(SlotContentType.FLUID) || accepts.contains(SlotContentType.TAG_FLUID);
    }

    public static SlotDescriptor fromCaptured(CapturedSlot captured, String jsonField,
            int jsonArrayIndex, Set<SlotContentType> accepts, boolean optional) {
        return new SlotDescriptor(captured.x(), captured.y(), captured.role(),
                jsonField, jsonArrayIndex, accepts, optional);
    }
}
