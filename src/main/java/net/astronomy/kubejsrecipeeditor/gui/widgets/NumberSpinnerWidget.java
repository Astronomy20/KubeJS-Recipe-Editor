package net.astronomy.kubejsrecipeeditor.gui.widgets;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** Integer spinner: [-] [value] [+]. Shift×10, Ctrl×100. */
public class NumberSpinnerWidget extends AbstractWidget {

    private final String jsonKey;
    private final long min;
    private final long max;
    private long value;
    private final Consumer<Long> onChange;

    public NumberSpinnerWidget(int x, int y, int width, String jsonKey,
            long initialValue, long min, long max, Consumer<Long> onChange) {
        super(x, y, width, 14, Component.empty());
        this.jsonKey = jsonKey;
        this.value = clamp(initialValue, min, max);
        this.min = min;
        this.max = max;
        this.onChange = onChange;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
        // - button
        g.fill(getX(), getY(), getX() + 14, getY() + 14, isMinusHovered(mouseX, mouseY) ? 0xFFAAAAAA : 0xFF666666);
        g.drawCenteredString(net.minecraft.client.Minecraft.getInstance().font, "-",
            getX() + 7, getY() + 3, 0xFFFFFFFF);
        // value display
        String display = String.valueOf(value);
        int valueWidth = getWidth() - 28;
        int valueX = getX() + 14 + valueWidth / 2;
        g.drawCenteredString(net.minecraft.client.Minecraft.getInstance().font, display,
            valueX, getY() + 3, 0xFFFFFFFF);
        // + button
        g.fill(getX() + getWidth() - 14, getY(), getX() + getWidth(), getY() + 14,
            isPlusHovered(mouseX, mouseY) ? 0xFFAAAAAA : 0xFF666666);
        g.drawCenteredString(net.minecraft.client.Minecraft.getInstance().font, "+",
            getX() + getWidth() - 7, getY() + 3, 0xFFFFFFFF);
    }

    private boolean isMinusHovered(int mx, int my) {
        return mx >= getX() && mx < getX() + 14 && my >= getY() && my < getY() + 14;
    }
    private boolean isPlusHovered(int mx, int my) {
        return mx >= getX() + getWidth() - 14 && mx < getX() + getWidth() && my >= getY() && my < getY() + 14;
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        long step = Screen.hasControlDown() ? 100L : Screen.hasShiftDown() ? 10L : 1L;
        if (isMinusHovered((int) mouseX, (int) mouseY)) adjust(-step);
        else if (isPlusHovered((int) mouseX, (int) mouseY)) adjust(step);
    }

    private void adjust(long delta) {
        value = clamp(value + delta, min, max);
        if (onChange != null) onChange.accept(value);
    }

    private static long clamp(long v, long min, long max) {
        if (max > min) return Math.max(min, Math.min(max, v));
        return v;
    }

    public long getValue() { return value; }
    public void setValue(long v) { this.value = clamp(v, min, max); }
    public String getJsonKey() { return jsonKey; }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput n) {}
}
