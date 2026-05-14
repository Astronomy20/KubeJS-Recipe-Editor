package net.astronomy.kubejsrecipeeditor.gui.widgets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

/** Enum cycle widget: < value > . Left-click advances, right-click goes back. */
public class CycleButtonWidget extends AbstractWidget {

    private final String jsonKey;
    private final List<String> values;
    private int currentIndex;
    private final Consumer<String> onChange;

    public CycleButtonWidget(int x, int y, int width, String jsonKey,
            List<String> values, String initialValue, Consumer<String> onChange) {
        super(x, y, width, 14, Component.empty());
        this.jsonKey = jsonKey;
        this.values = List.copyOf(values);
        this.onChange = onChange;
        this.currentIndex = values.isEmpty() ? 0 : Math.max(0, values.indexOf(initialValue));
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partial) {
        int bgColor = isHovered() ? 0xFF888888 : 0xFF555555;
        g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), bgColor);

        // < arrow left
        g.drawString(Minecraft.getInstance().font, "<", getX() + 3, getY() + 3, 0xFFCCCCCC, false);
        // > arrow right
        g.drawString(Minecraft.getInstance().font, ">", getX() + getWidth() - 9, getY() + 3, 0xFFCCCCCC, false);
        // Current value centered
        String current = values.isEmpty() ? "" : values.get(currentIndex);
        g.drawCenteredString(Minecraft.getInstance().font, current,
            getX() + getWidth() / 2, getY() + 3, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!isActive() || !isHovered()) return false;
        if (values.isEmpty()) return false;
        if (button == 0) cycle(1);    // left click: advance
        else if (button == 1) cycle(-1); // right click: back
        return true;
    }

    private void cycle(int direction) {
        if (values.isEmpty()) return;
        currentIndex = ((currentIndex + direction) % values.size() + values.size()) % values.size();
        if (onChange != null) onChange.accept(values.get(currentIndex));
    }

    public String getCurrentValue() {
        return values.isEmpty() ? "" : values.get(currentIndex);
    }

    public void setCurrentValue(String value) {
        int idx = values.indexOf(value);
        if (idx >= 0) currentIndex = idx;
    }

    public String getJsonKey() { return jsonKey; }

    @Override
    public void onClick(double x, double y) {}

    @Override
    protected void updateWidgetNarration(NarrationElementOutput n) {}
}
