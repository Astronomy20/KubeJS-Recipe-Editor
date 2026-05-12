package net.astronomy.kubejsrecipeeditor.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;

public class FluidSelectionPopup implements RecipePopup {
    private static final int W             = 210;
    private static final int PAD           = 4;
    private static final int AMOUNT_ROW_H  = 20;
    private static final int FLUID_ROW_H   = 12;
    private static final int MODE_ROW_H    = 14;
    private static final int CLEAR_BTN_H   = 14;
    private static final int CLEAR_BTN_W   = 38;

    private final SlotData slot;
    private final int popX, popY, popH;
    private final int minusX, plusX;
    // Mode toggle button: switches between "fluid id" and "fluid tag" modes
    private final int modeBtnX, modeBtnY, modeBtnW;
    // Clear button: clears fluid
    private final int clearBtnX, clearBtnY;
    private boolean keepOpen = false;

    public FluidSelectionPopup(SlotData slot, int screenW, int screenH) {
        this.slot = slot;
        // Rows: amount row, separator, fluid-name row, separator, mode-toggle row, separator, clear-button row
        popH = PAD + AMOUNT_ROW_H + 2 + FLUID_ROW_H + 2 + MODE_ROW_H + 2 + CLEAR_BTN_H + PAD;
        popX = Math.min(slot.x + slot.w + 2, screenW - W - 4);
        popY = Math.min(slot.y, screenH - popH - 4);
        minusX = popX + 60;
        plusX  = popX + 60 + 14 + 30 + 2; // minus(14) + gap(30) + spacing(2)

        // Mode toggle — right-aligned inside popup
        modeBtnW = 60;
        modeBtnX = popX + W - PAD - modeBtnW;
        modeBtnY = popY + PAD + AMOUNT_ROW_H + 2 + FLUID_ROW_H + 4;

        // Clear button — left-aligned in last row
        clearBtnX = popX + PAD;
        clearBtnY = popY + PAD + AMOUNT_ROW_H + 2 + FLUID_ROW_H + 2 + MODE_ROW_H + 4;
    }

    public void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
        // Background + border
        g.fill(popX - 1, popY - 1, popX + W + 1, popY + popH + 1, 0xFF555555);
        g.fill(popX, popY, popX + W, popY + popH, 0xFF1A1A1A);

        // ── Amount row ──────────────────────────────────────────────────────
        int amtY = popY + PAD;
        g.drawString(font, "Amount (mB):", popX + PAD, amtY + 6, 0xFFAAAAAA, false);

        boolean minusHover = mouseX >= minusX && mouseX < minusX + 14 && mouseY >= amtY + 3 && mouseY < amtY + 15;
        g.fill(minusX, amtY + 3, minusX + 14, amtY + 15, minusHover ? 0xFF666666 : 0xFF444444);
        g.drawString(font, "-", minusX + 4, amtY + 5, 0xFFFFFFFF, false);

        String amtStr = String.valueOf(slot.fluidAmount);
        int aw = font.width(amtStr);
        g.drawString(font, amtStr, minusX + 14 + (30 - aw) / 2, amtY + 6, 0xFFFFFFFF, false);

        boolean plusHover = mouseX >= plusX && mouseX < plusX + 14 && mouseY >= amtY + 3 && mouseY < amtY + 15;
        g.fill(plusX, amtY + 3, plusX + 14, amtY + 15, plusHover ? 0xFF666666 : 0xFF444444);
        g.drawString(font, "+", plusX + 3, amtY + 5, 0xFFFFFFFF, false);

        // ── Separator ────────────────────────────────────────────────────────
        g.fill(popX + PAD, popY + PAD + AMOUNT_ROW_H,
               popX + W - PAD, popY + PAD + AMOUNT_ROW_H + 1, 0xFF444444);

        // ── Fluid name ───────────────────────────────────────────────────────
        int nameY = popY + PAD + AMOUNT_ROW_H + 2;
        String fluidName;
        if (slot.useFluidTag) {
            fluidName = slot.selectedFluidTag != null ? "#" + slot.selectedFluidTag : "(tag mode — no tag set)";
        } else {
            fluidName = slot.fluidId != null ? slot.fluidId.toString() : "(no fluid set)";
        }
        // Truncate if too wide
        String displayName = fluidName;
        int maxNameW = W - PAD * 2;
        while (font.width(displayName) > maxNameW && displayName.length() > 4) {
            displayName = displayName.substring(0, displayName.length() - 4) + "...";
        }
        g.drawString(font, displayName, popX + PAD, nameY + 1, 0xFF55BBFF, false);

        // ── Separator ────────────────────────────────────────────────────────
        g.fill(popX + PAD, nameY + FLUID_ROW_H + 1,
               popX + W - PAD, nameY + FLUID_ROW_H + 2, 0xFF444444);

        // ── Mode toggle button ───────────────────────────────────────────────
        String modeLbl = slot.useFluidTag ? "→ Fluid ID" : "→ Fluid Tag";
        boolean modeHover = mouseX >= modeBtnX && mouseX < modeBtnX + modeBtnW
                && mouseY >= modeBtnY && mouseY < modeBtnY + MODE_ROW_H;
        g.fill(modeBtnX, modeBtnY, modeBtnX + modeBtnW, modeBtnY + MODE_ROW_H,
                modeHover ? 0xFF556688 : 0xFF334455);
        g.drawCenteredString(font, modeLbl, modeBtnX + modeBtnW / 2, modeBtnY + 3, 0xFFCCDDFF);

        String modeLabel = slot.useFluidTag ? "Tag mode" : "ID mode";
        g.drawString(font, modeLabel, popX + PAD, modeBtnY + 3, 0xFFAAAAAA, false);

        // ── Separator ────────────────────────────────────────────────────────
        g.fill(popX + PAD, clearBtnY - 2,
               popX + W - PAD, clearBtnY - 1, 0xFF444444);

        // ── Clear button ─────────────────────────────────────────────────────
        boolean clearHover = mouseX >= clearBtnX && mouseX < clearBtnX + CLEAR_BTN_W
                && mouseY >= clearBtnY && mouseY < clearBtnY + CLEAR_BTN_H;
        g.fill(clearBtnX, clearBtnY, clearBtnX + CLEAR_BTN_W, clearBtnY + CLEAR_BTN_H,
                clearHover ? 0xFF884444 : 0xFF553333);
        g.drawCenteredString(font, "Clear", clearBtnX + CLEAR_BTN_W / 2, clearBtnY + 3, 0xFFFFAAAA);

        // Hint for steps
        int hintX = clearBtnX + CLEAR_BTN_W + 6;
        g.drawString(font, "Ctrl=×10  Shift=×1000", hintX, clearBtnY + 3, 0xFF666666, false);
    }

    public boolean mouseClicked(int mouseX, int mouseY) {
        keepOpen = false;
        boolean shift = Screen.hasShiftDown();
        boolean ctrl  = Screen.hasControlDown();
        long step = shift ? 1000L : ctrl ? 10L : 100L;
        int amtY = popY + PAD;

        // Minus button
        if (mouseX >= minusX && mouseX < minusX + 14 && mouseY >= amtY + 3 && mouseY < amtY + 15) {
            slot.fluidAmount = Math.max(1L, slot.fluidAmount - step);
            keepOpen = true;
            return true;
        }
        // Plus button
        if (mouseX >= plusX && mouseX < plusX + 14 && mouseY >= amtY + 3 && mouseY < amtY + 15) {
            slot.fluidAmount = Math.min(128000L, slot.fluidAmount + step);
            keepOpen = true;
            return true;
        }
        // Mode toggle button
        if (mouseX >= modeBtnX && mouseX < modeBtnX + modeBtnW
                && mouseY >= modeBtnY && mouseY < modeBtnY + MODE_ROW_H) {
            slot.useFluidTag = !slot.useFluidTag;
            // When switching to tag mode, try to derive a generic tag name from the fluidId
            if (slot.useFluidTag && slot.selectedFluidTag == null && slot.fluidId != null) {
                // Default heuristic: forge/c fluid tags often match the fluid namespace:path
                slot.selectedFluidTag = ResourceLocation.tryParse(
                        "c:" + slot.fluidId.getPath());
            }
            // When switching back to id mode, keep fluidId as-is
            keepOpen = true;
            return true;
        }
        // Clear button
        if (mouseX >= clearBtnX && mouseX < clearBtnX + CLEAR_BTN_W
                && mouseY >= clearBtnY && mouseY < clearBtnY + CLEAR_BTN_H) {
            // Clear fluid but keep the slot in fluid mode (isFluid stays true)
            slot.fluidId = null;
            slot.ingredient = net.minecraft.world.item.ItemStack.EMPTY;
            slot.useFluidTag = false;
            slot.selectedFluidTag = null;
            slot.fluidAmount = 1000L;
            keepOpen = false; // close popup after clearing
            return true;
        }
        return isInside(mouseX, mouseY);
    }

    public boolean keepOpen() { return keepOpen; }

    public boolean isInside(int x, int y) {
        return x >= popX && x < popX + W && y >= popY && y < popY + popH;
    }
}
