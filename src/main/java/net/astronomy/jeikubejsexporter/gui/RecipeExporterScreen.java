package net.astronomy.jeikubejsexporter.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.astronomy.jeikubejsexporter.export.RecipeExportManager;

public class RecipeExporterScreen extends Screen {
    private String statusMessage = "";
    private int statusColor = 0xFFFFFF;

    public RecipeExporterScreen() {
        super(Component.literal("JEI KubeJS Exporter"));
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(
                Component.literal("Export All Recipes"),
                btn -> exportAll()
        ).pos(width / 2 - 75, height / 2 - 10).size(150, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Close"),
                btn -> onClose()
        ).pos(width / 2 - 75, height / 2 + 15).size(150, 20).build());
    }

    private void exportAll() {
        try {
            RecipeExportManager.ExportResult result = RecipeExportManager.exportAll();
            statusMessage = "Exported " + result.categories() + " categories, "
                    + result.recipes() + " recipes, " + result.errors() + " errors";
            statusColor = result.errors() > 0 ? 0xFF5555 : 0x55FF55;
        } catch (Exception e) {
            statusMessage = "Export failed: " + e.getMessage();
            statusColor = 0xFF5555;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 20, 0xFFFFFF);
        if (!statusMessage.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal(statusMessage), width / 2, height / 2 + 45, statusColor);
        }
    }
}
