package net.astronomy.kubejsrecipeeditor.gui.widgets;

import com.mojang.blaze3d.systems.RenderSystem;
import net.astronomy.kubejsrecipeeditor.engine.ContentType;
import net.astronomy.kubejsrecipeeditor.gui.SlotData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.function.Consumer;

/**
 * A slot widget that knows its accepted ContentType(s) and renders content appropriately.
 * Border color is determined by acceptedTypes per BACKEND_ARCHITECTURE §6.4.
 */
public class TypedSlotWidget extends AbstractWidget {

    private static final ResourceLocation SLOT_SPRITE =
        ResourceLocation.withDefaultNamespace("container/slot");

    // Border colors per ContentType (ARGB)
    public static final int COLOR_ITEM   = 0xFFD0D0D0; // white-grey
    public static final int COLOR_FLUID  = 0xFF5588FF; // blue
    public static final int COLOR_CHEM   = 0xFFAA55FF; // purple
    public static final int COLOR_MIXED  = 0xFF55DDDD; // cyan
    public static final int COLOR_CUSTOM = 0xFF888888; // grey
    public static final int COLOR_INVALID = 0xFFFF3333; // red (drop rejected)

    private final SlotData data;
    private final Set<ContentType> acceptedTypes;
    private final Consumer<TypedSlotWidget> onClickCallback;

    private boolean showInvalidHighlight = false;

    public TypedSlotWidget(int x, int y, SlotData data, Set<ContentType> acceptedTypes,
            Consumer<TypedSlotWidget> onClickCallback) {
        super(x, y, 18, 18, Component.empty());
        this.data = data;
        this.acceptedTypes = acceptedTypes;
        this.onClickCallback = onClickCallback;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // Slot background
        g.blitSprite(SLOT_SPRITE, getX(), getY(), 18, 18);

        int ix = getX() + 1;
        int iy = getY() + 1;

        // Hover highlight
        if (isHovered()) g.fill(ix - 1, iy - 1, ix + 17, iy + 17, 0x55FFFFFF);

        // Invalid drop highlight
        if (showInvalidHighlight) {
            g.fill(ix - 1, iy - 1, ix + 17, iy + 17, 0x66FF0000);
        }

        // Border color line (bottom of slot, 2px)
        int borderColor = getBorderColor();
        g.fill(getX() + 1, getY() + 16, getX() + 17, getY() + 18, borderColor);

        // Content
        if (!data.isEmpty()) {
            renderContent(g, ix, iy);
        } else {
            renderEmptyHint(g, ix, iy);
        }

        // Hover X button (clear slot)
        if (isHovered() && !data.isEmpty()) {
            g.fill(ix + 8, iy - 1, ix + 17, iy + 8, 0x88000000);
            g.drawString(Minecraft.getInstance().font, "×", ix + 10, iy, 0xFFFF5555, false);
        }
    }

    private int getBorderColor() {
        if (showInvalidHighlight) return COLOR_INVALID;
        if (acceptedTypes.contains(ContentType.FLUID) && acceptedTypes.contains(ContentType.ITEM))
            return COLOR_MIXED;
        if (acceptedTypes.contains(ContentType.FLUID)) return COLOR_FLUID;
        if (acceptedTypes.contains(ContentType.CHEMICAL_GAS)
                || acceptedTypes.contains(ContentType.CHEMICAL_SLURRY)
                || acceptedTypes.contains(ContentType.CHEMICAL_INFUSE)
                || acceptedTypes.contains(ContentType.CHEMICAL_PIGMENT)) return COLOR_CHEM;
        if (acceptedTypes.contains(ContentType.CUSTOM) || acceptedTypes.isEmpty()) return COLOR_CUSTOM;
        return COLOR_ITEM;
    }

    private void renderContent(GuiGraphics g, int ix, int iy) {
        if (data.isFluid) {
            renderFluid(g, ix, iy);
        } else {
            // Item or item-tag
            if (!data.ingredient.isEmpty()) {
                g.renderItem(data.ingredient, ix, iy);
                g.renderItemDecorations(Minecraft.getInstance().font, data.ingredient, ix, iy);
            }
            if (data.useTag && data.selectedTag != null) {
                g.drawString(Minecraft.getInstance().font, "#", ix + 11, iy + 8, 0xFFFFFF00, false);
            }
        }
    }

    private void renderFluid(GuiGraphics g, int ix, int iy) {
        if (data.useFluidTag && data.selectedFluidTag != null) {
            g.fill(ix, iy, ix + 16, iy + 16, 0x4455AAFF);
            g.drawString(Minecraft.getInstance().font, "#", ix + 3, iy + 4, 0xFFFFFF55, false);
            return;
        }
        if (data.fluidId == null) {
            g.fill(ix, iy, ix + 16, iy + 16, 0x2255AAFF);
            return;
        }
        try {
            Fluid fluid = net.minecraft.core.registries.BuiltInRegistries.FLUID.get(data.fluidId);
            if (fluid == null || fluid == Fluids.EMPTY) {
                g.fill(ix, iy, ix + 16, iy + 16, 0x4455AAFF);
                return;
            }
            FluidStack fs = new FluidStack(fluid, (int) Math.min(data.fluidAmount, Integer.MAX_VALUE));
            IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fluid.getFluidType());
            ResourceLocation texture = ext.getStillTexture();
            int tint = ext.getTintColor();
            int r = (tint >> 16) & 0xFF;
            int gr = (tint >> 8) & 0xFF;
            int b = tint & 0xFF;
            int a = (tint >> 24) & 0xFF; if (a == 0) a = 255;
            TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texture);
            RenderSystem.setShaderColor(r / 255f, gr / 255f, b / 255f, a / 255f);
            g.blit(ix, iy, 0, 16, 16, sprite);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        } catch (Exception ignored) {
            g.fill(ix, iy, ix + 16, iy + 16, 0x8055AAFF);
        }
    }

    private void renderEmptyHint(GuiGraphics g, int ix, int iy) {
        // Show a faint indicator for what this slot expects
        if (acceptedTypes.contains(ContentType.FLUID)) {
            g.fill(ix, iy, ix + 16, iy + 16, 0x1555AAFF);
        }
        // Chemical slots: faint purple
        if (acceptedTypes.contains(ContentType.CHEMICAL_GAS)
                || acceptedTypes.contains(ContentType.CHEMICAL_SLURRY)) {
            g.fill(ix, iy, ix + 16, iy + 16, 0x15AA55FF);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {}

    @Override
    public void onClick(double x, double y) {
        if (onClickCallback != null) onClickCallback.accept(this);
    }

    /** Sets the invalid highlight (shown when a drag of wrong type is rejected). */
    public void setInvalidHighlight(boolean invalid) {
        this.showInvalidHighlight = invalid;
    }

    public SlotData getData() { return data; }
    public Set<ContentType> getAcceptedTypes() { return acceptedTypes; }

    /** Returns true if this slot accepts the given ContentType. */
    public boolean accepts(ContentType type) {
        return acceptedTypes.isEmpty()
            || acceptedTypes.contains(type)
            || acceptedTypes.contains(ContentType.CUSTOM);
    }
}
