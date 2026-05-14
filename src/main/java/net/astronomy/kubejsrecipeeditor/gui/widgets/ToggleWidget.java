package net.astronomy.kubejsrecipeeditor.gui.widgets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** Boolean toggle widget: shows current value as "true"/"false", click toggles. */
public class ToggleWidget extends AbstractWidget {

    private final String jsonKey;
    private boolean value;
    private final Consumer<Boolean> onChange;

    public ToggleWidget(int x, int y, int width, String jsonKey,
            boolean initialValue, Consumer<Boolean> onChange) {
        super(x, y, width, 14, Component.empty());
        this.jsonKey = jsonKey;
        this.value = initialValue;
        this.onChange = onChange;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int bgColor = value
            ? (isHovered() ? 0xFF33AA33 : 0xFF228822)
            : (isHovered() ? 0xFFAA3333 : 0xFF882222);
        g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), bgColor);
        g.drawCenteredString(Minecraft.getInstance().font,
            value ? "true" : "false",
            getX() + getWidth() / 2, getY() + 3, 0xFFFFFFFF);
    }

    @Override
    public void onClick(double x, double y) {
        value = !value;
        if (onChange != null) onChange.accept(value);
    }

    public boolean getValue() { return value; }
    public void setValue(boolean v) { this.value = v; }
    public String getJsonKey() { return jsonKey; }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput n) {}
}
