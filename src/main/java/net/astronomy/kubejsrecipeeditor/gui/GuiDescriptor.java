package net.astronomy.kubejsrecipeeditor.gui;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Complete descriptor for a recipe type GUI.
 * Contains typed slots and extra parameters with UI metadata.
 * Produced by GuiDescriptorBuilder, cached by DescriptorCache.
 */
public record GuiDescriptor(
        ResourceLocation recipeTypeUid,
        List<SlotDescriptor> slots,
        List<ParamDescriptor> extraParams
) {}
