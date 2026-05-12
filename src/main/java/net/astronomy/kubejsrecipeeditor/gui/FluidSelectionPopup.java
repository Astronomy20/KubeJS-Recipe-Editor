package net.astronomy.kubejsrecipeeditor.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public class FluidSelectionPopup implements RecipePopup {
    private static final int W             = 190;
    private static final int PAD           = 4;
    private static final int AMOUNT_ROW_H  = 20;
    private static final int FLUID_ROW_H   = 12;

    private final SlotData slot;
    private final int popX, popY, popH;
    private final int minusX, plusX;
    private boolean keepOpen = false;

    public FluidSelectionPopup(SlotData slot, int screenW, int screenH) {
        this.slot = slot;
        popH = PAD + AMOUNT_ROW_H + 2 + FLUID_ROW_H + PAD;
        popX = Math.min(slot.x + slot.w + 2, screenW - W - 4);
        popY = Math.min(slot.y, screenH - popH - 4);
        minusX = popX + 72;
        plusX  = popX + 104;
    }

    public void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
        // Background + border
        g.fill(popX - 1, popY - 1, popX + W + 1, popY + popH + 1, 0xFF555555);
        g.fill(popX, popY, popX + W, popY + popH, 0xFF1A1A1A);

        // Amount row
        int amtY = popY + PAD;
        g.drawString(font, "Amount (mB):", popX + PAD, amtY + 6, 0xFFAAAAAA, false);

        g.fill(minusX, amtY + 3, minusX + 14, amtY + 15, 0xFF444444);
        g.drawString(font, "-", minusX + 4, amtY + 5, 0xFFFFFFFF, false);

        String amtStr = String.valueOf(slot.fluidAmount);
        int aw = font.width(amtStr);
        g.drawString(font, amtStr, minusX + 18 + (24 - aw) / 2, amtY + 6, 0xFFFFFFFF, false);

        g.fill(plusX, amtY + 3, plusX + 14, amtY + 15, 0xFF444444);
        g.drawString(font, "+", plusX + 3, amtY + 5, 0xFFFFFFFF, false);

        // Separator
        g.fill(popX + PAD, popY + PAD + AMOUNT_ROW_H,
               popX + W - PAD, popY + PAD + AMOUNT_ROW_H + 1, 0xFF444444);

        // Fluid name
        String fluidName = slot.fluidId != null ? slot.fluidId.toString() : "unknown";
        g.drawString(font, fluidName, popX + PAD,
                popY + PAD + AMOUNT_ROW_H + 2 + 1, 0xFF55BBFF, false);
    }

    public boolean mouseClicked(int mouseX, int mouseY) {
        keepOpen = false;
        boolean shift = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        long step = shift ? 1000L : 100L;
        int amtY = popY + PAD;

        if (mouseX >= minusX && mouseX < minusX + 14 && mouseY >= amtY + 3 && mouseY < amtY + 15) {
            slot.fluidAmount = Math.max(100L, slot.fluidAmount - step);
            keepOpen = true;
            return true;
        }
        if (mouseX >= plusX && mouseX < plusX + 14 && mouseY >= amtY + 3 && mouseY < amtY + 15) {
            slot.fluidAmount = Math.min(128000L, slot.fluidAmount + step);
            keepOpen = true;
            return true;
        }
        return isInside(mouseX, mouseY);
    }

    public boolean keepOpen() { return keepOpen; }

    public boolean isInside(int x, int y) {
        return x >= popX && x < popX + W && y >= popY && y < popY + popH;
    }
}
