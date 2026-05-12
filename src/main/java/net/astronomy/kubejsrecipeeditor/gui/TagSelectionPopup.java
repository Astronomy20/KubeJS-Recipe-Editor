package net.astronomy.kubejsrecipeeditor.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TagSelectionPopup implements RecipePopup {
    private static final int ROW_H       = 12;
    private static final int PAD         = 4;
    private static final int W           = 190;
    private static final int COUNT_ROW_H = 20;

    private final SlotData slot;
    private final List<Entry> entries = new ArrayList<>();
    private final int popX, popY, popH;
    private final int minusX, plusX;

    private boolean keepOpen = false;

    public TagSelectionPopup(SlotData slot, int screenWidth, int screenHeight) {
        this.slot = slot;

        // First entry: use specific item (no tag)
        entries.add(new Entry(null, slot.ingredient));

        Set<ResourceLocation> seen = new HashSet<>();

        // Item tags
        Holder<? extends net.minecraft.world.item.Item> itemHolder =
                slot.ingredient.getItem().builtInRegistryHolder();
        itemHolder.tags().forEach(tag -> {
            if (seen.add(tag.location()))
                entries.add(new Entry(tag.location(), slot.ingredient));
        });

        // Block tags (if item can be placed as a block)
        if (slot.ingredient.getItem() instanceof BlockItem bi) {
            bi.getBlock().builtInRegistryHolder().tags().forEach(tag -> {
                if (seen.add(tag.location()))
                    entries.add(new Entry(tag.location(), slot.ingredient));
            });
        }

        // Fluid tags (if item is a fluid bucket)
        if (slot.ingredient.getItem() instanceof BucketItem bi) {
            bi.content.builtInRegistryHolder().tags().forEach(tag -> {
                if (seen.add(tag.location()))
                    entries.add(new Entry(tag.location(), slot.ingredient));
            });
        }

        popH = PAD + COUNT_ROW_H + 2 + entries.size() * ROW_H + PAD;

        popX = Math.min(slot.x + slot.w + 2, screenWidth - W - 4);
        popY = Math.min(slot.y, screenHeight - popH - 4);

        // [-] value [+] layout: 52px for label, then 14px button, gap, 14px button
        minusX = popX + 54;
        plusX  = popX + 86;
    }

    public void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
        // Background
        g.fill(popX - 1, popY - 1, popX + W + 1, popY + popH + 1, 0xFF555555);
        g.fill(popX, popY, popX + W, popY + popH, 0xFF1A1A1A);

        // Count row
        int countY = popY + PAD;
        g.drawString(font, "Count:", popX + PAD, countY + 6, 0xFFAAAAAA, false);

        g.fill(minusX, countY + 3, minusX + 14, countY + 15, 0xFF444444);
        g.drawString(font, "-", minusX + 4, countY + 5, 0xFFFFFFFF, false);

        String countStr = String.valueOf(slot.count);
        int cw = font.width(countStr);
        g.drawString(font, countStr, minusX + 18 + (14 - cw) / 2, countY + 6, 0xFFFFFFFF, false);

        g.fill(plusX, countY + 3, plusX + 14, countY + 15, 0xFF444444);
        g.drawString(font, "+", plusX + 3, countY + 5, 0xFFFFFFFF, false);

        // Separator
        g.fill(popX + PAD, popY + PAD + COUNT_ROW_H, popX + W - PAD, popY + PAD + COUNT_ROW_H + 1, 0xFF444444);

        // Tag / item entries
        int y = popY + PAD + COUNT_ROW_H + 2;
        for (Entry e : entries) {
            boolean hover = mouseX >= popX && mouseX < popX + W && mouseY >= y && mouseY < y + ROW_H;
            if (hover) g.fill(popX + 1, y, popX + W - 1, y + ROW_H, 0x55FFFFFF);

            boolean selected = e.isSelected(slot);
            int col = selected ? 0xFF55FF55 : 0xFFCCCCCC;
            String label = e.tagId == null
                    ? "> " + slot.ingredient.getHoverName().getString()
                    : e.tagId.toString();
            g.drawString(font, label, popX + PAD, y + 2, col, false);
            y += ROW_H;
        }
    }

    /** Returns true if the popup consumed the click. */
    public boolean mouseClicked(int mouseX, int mouseY) {
        keepOpen = false;
        boolean shift = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        int step = shift ? 10 : 1;
        int countY = popY + PAD;

        // Count [-] button
        if (mouseX >= minusX && mouseX < minusX + 14 && mouseY >= countY + 3 && mouseY < countY + 15) {
            slot.count = Math.max(1, slot.count - step);
            keepOpen = true;
            return true;
        }
        // Count [+] button
        if (mouseX >= plusX && mouseX < plusX + 14 && mouseY >= countY + 3 && mouseY < countY + 15) {
            slot.count = Math.min(64, slot.count + step);
            keepOpen = true;
            return true;
        }

        // Tag / item entry rows
        int y = popY + PAD + COUNT_ROW_H + 2;
        for (Entry e : entries) {
            if (mouseX >= popX && mouseX < popX + W && mouseY >= y && mouseY < y + ROW_H) {
                if (e.tagId == null) {
                    slot.useTag = false;
                    slot.selectedTag = null;
                } else {
                    slot.useTag = true;
                    slot.selectedTag = e.tagId;
                }
                return true;
            }
            y += ROW_H;
        }

        // Click outside → close without changing
        return isInside(mouseX, mouseY);
    }

    public boolean keepOpen() { return keepOpen; }

    public boolean isInside(int mouseX, int mouseY) {
        return mouseX >= popX && mouseX < popX + W && mouseY >= popY && mouseY < popY + popH;
    }

    private record Entry(ResourceLocation tagId, ItemStack item) {
        boolean isSelected(SlotData slot) {
            if (tagId == null) return !slot.useTag;
            return slot.useTag && tagId.equals(slot.selectedTag);
        }
    }
}
