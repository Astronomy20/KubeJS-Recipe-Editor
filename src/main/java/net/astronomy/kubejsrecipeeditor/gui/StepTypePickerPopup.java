package net.astronomy.kubejsrecipeeditor.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.function.Consumer;

/**
 * Popup for selecting a Sequenced Assembly step type.
 * Implements RecipePopup so it participates in the existing popup system.
 */
public class StepTypePickerPopup implements RecipePopup {

    private static final List<StepEntry> ENTRIES = List.of(
            new StepEntry("create:pressing",  "Press",        "Mechanical Press"),
            new StepEntry("create:cutting",   "Cut",          "Saw / Cutting Wheel"),
            new StepEntry("create:deploying", "Deploy",       "Deployer (needs item)"),
            new StepEntry("create:filling",   "Fill (Spout)", "Spout (needs fluid)")
    );

    private static final int W       = 160;
    private static final int ROW_H   = 16;
    private static final int PAD     = 4;

    private final int popX, popY, popH;
    private final Consumer<String> onSelect;
    private boolean keepOpen = true;

    public StepTypePickerPopup(int nearX, int nearY, Consumer<String> onSelect) {
        this.onSelect = onSelect;
        this.popH = PAD + ENTRIES.size() * ROW_H + PAD;
        // Position near the click, clamp to reasonable area
        this.popX = Math.min(nearX, 600 - W - 4);
        this.popY = Math.min(nearY, 400 - popH - 4);
    }

    @Override
    public void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
        // Border + background
        g.fill(popX - 1, popY - 1, popX + W + 1, popY + popH + 1, 0xFF555555);
        g.fill(popX, popY, popX + W, popY + popH, 0xFF1A1A1A);

        // Header
        g.drawString(font, "Choose step type:", popX + PAD, popY + PAD, 0xFFAAAAAA, false);

        int y = popY + PAD + 10;
        for (StepEntry entry : ENTRIES) {
            boolean hover = mouseX >= popX && mouseX < popX + W
                    && mouseY >= y && mouseY < y + ROW_H;
            if (hover) g.fill(popX + 1, y, popX + W - 1, y + ROW_H, 0x55FFFFFF);
            g.drawString(font, entry.label(), popX + PAD, y + 3, 0xFFFFFFFF, false);
            int descW = font.width(entry.desc());
            if (descW < W - PAD * 2 - font.width(entry.label()) - 6) {
                g.drawString(font, " - " + entry.desc(), popX + PAD + font.width(entry.label()),
                        y + 3, 0xFF888888, false);
            }
            y += ROW_H;
        }
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY) {
        int y = popY + PAD + 10;
        for (StepEntry entry : ENTRIES) {
            if (mouseX >= popX && mouseX < popX + W && mouseY >= y && mouseY < y + ROW_H) {
                onSelect.accept(entry.type());
                keepOpen = false;
                return true;
            }
            y += ROW_H;
        }
        // Click outside closes without selecting
        keepOpen = false;
        return isInside(mouseX, mouseY);
    }

    @Override
    public boolean keepOpen() { return keepOpen; }

    public boolean isInside(int x, int y) {
        return x >= popX && x < popX + W && y >= popY && y < popY + popH;
    }

    private record StepEntry(String type, String label, String desc) {}
}
