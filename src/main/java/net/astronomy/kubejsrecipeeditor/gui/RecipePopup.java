package net.astronomy.kubejsrecipeeditor.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public interface RecipePopup {
    void render(GuiGraphics g, Font font, int mouseX, int mouseY);
    boolean mouseClicked(int mouseX, int mouseY);
    boolean keepOpen();
}
