package net.astronomy.kubejsrecipeeditor.gui.widgets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** Float spinner: [-] [value] [+]. Shift×1.0, Ctrl×10.0. */
public class FloatSpinnerWidget extends AbstractWidget {

    private final String jsonKey;
    private final double min;
    private final double max;
    private double value;
    private final Consumer<Double> onChange;

    public FloatSpinnerWidget(int x, int y, int width, String jsonKey,
            double initialValue, double min, double max, Consumer<Double> onChange) {
        super(x, y, width, 14, Component.empty());
        this.jsonKey = jsonKey;
        this.value = clamp(initialValue, min, max);
        this.min = min;
        this.max = max;
        this.onChange = onChange;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
        g.fill(getX(), getY(), getX() + 14, getY() + 14, isMinusHovered(mouseX, mouseY) ? 0xFFAAAAAA : 0xFF666666);
        g.drawCenteredString(Minecraft.getInstance().font, "-", getX() + 7, getY() + 3, 0xFFFFFFFF);
        String display = String.format("%.2f", value);
        int valueWidth = getWidth() - 28;
        g.drawCenteredString(Minecraft.getInstance().font, display,
            getX() + 14 + valueWidth / 2, getY() + 3, 0xFFFFFFFF);
        g.fill(getX() + getWidth() - 14, getY(), getX() + getWidth(), getY() + 14,
            isPlusHovered(mouseX, mouseY) ? 0xFFAAAAAA : 0xFF666666);
        g.drawCenteredString(Minecraft.getInstance().font, "+", getX() + getWidth() - 7, getY() + 3, 0xFFFFFFFF);
    }

    private boolean isMinusHovered(int mx, int my) {
        return mx >= getX() && mx < getX() + 14 && my >= getY() && my < getY() + 14;
    }
    private boolean isPlusHovered(int mx, int my) {
        return mx >= getX() + getWidth() - 14 && mx < getX() + getWidth() && my >= getY() && my < getY() + 14;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        double step = Screen.hasControlDown() ? 10.0 : Screen.hasShiftDown() ? 1.0 : 0.1;
        if (isMinusHovered((int) mouseX, (int) mouseY)) adjust(-step);
        else if (isPlusHovered((int) mouseX, (int) mouseY)) adjust(step);
    }

    private void adjust(double delta) {
        value = Math.round(clamp(value + delta, min, max) * 100.0) / 100.0;
        if (onChange != null) onChange.accept(value);
    }

    private static double clamp(double v, double min, double max) {
        if (max > min) return Math.max(min, Math.min(max, v));
        return v;
    }

    public double getValue() { return value; }
    public void setValue(double v) { this.value = clamp(v, min, max); }
    public String getJsonKey() { return jsonKey; }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput n) {}
}
