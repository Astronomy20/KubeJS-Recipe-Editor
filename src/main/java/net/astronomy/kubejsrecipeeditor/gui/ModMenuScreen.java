package net.astronomy.kubejsrecipeeditor.gui;

import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.astronomy.kubejsrecipeeditor.jei.JeiIntegration;

import java.util.*;
import java.util.stream.Stream;

public class ModMenuScreen extends Screen {
    private static final int TITLE_H   = 50;
    private static final int BUTTON_W  = 110;
    private static final int BUTTON_H  = 20;
    private static final int BUTTON_PAD = 2;
    private static final int SECTION_H = 16;
    private static final int MARGIN    = 8;

    // Sorted sections: "minecraft" first, rest alphabetically
    private final List<String> namespaces = new ArrayList<>();
    // namespace → list of categories
    private final Map<String, List<IRecipeCategory<?>>> sections = new LinkedHashMap<>();

    private int scrollOffset = 0;
    private int totalContentHeight = 0;
    private boolean draggingScrollbar = false;

    private String exportStatus = "";
    private int exportStatusColor = 0xFFFFFF;

    public ModMenuScreen() {
        super(Component.literal("KubeJS Recipe Editor"));
    }

    @Override
    protected void init() {
        namespaces.clear();
        sections.clear();

        IJeiRuntime runtime = JeiIntegration.getRuntime();
        if (runtime == null) return;

        Stream<IRecipeCategory<?>> categories = runtime.getRecipeManager()
                .createRecipeCategoryLookup().get();

        categories.forEach(cat -> {
            if (isTagBrowserCategory(cat)) return;
            // Only show categories that have a registered layout template (skip anvil, brewing, etc.)
            if (RecipeTemplateRegistry.INSTANCE.get(cat.getRecipeType()).isEmpty()) return;
            String ns = cat.getRecipeType().getUid().getNamespace();
            sections.computeIfAbsent(ns, k -> new ArrayList<>()).add(cat);
        });

        // Sort: "minecraft" first, then alphabetical
        namespaces.addAll(sections.keySet());
        namespaces.sort((a, b) -> {
            if (a.equals("minecraft")) return -1;
            if (b.equals("minecraft")) return 1;
            return a.compareTo(b);
        });

        computeTotalHeight();

        // Title bar layout: title at top, all three action buttons in the second row
        addRenderableWidget(Button.builder(Component.literal("Tags"), btn -> openTagEditor())
                .pos(4, 22).size(36, 14).build());

        addRenderableWidget(Button.builder(Component.literal("Browse"), btn -> openRecipeBrowser())
                .pos(width - 52, 22).size(48, 14).build());

        addRenderableWidget(Button.builder(Component.literal("Export All"), btn -> exportAll())
                .pos(width / 2 - 30, 22).size(60, 14).build());
    }

    private void exportAll() {
        exportStatus = "Exporting...";
        exportStatusColor = 0xFFFFFF;
        try {
            var result = net.astronomy.kubejsrecipeeditor.export.RecipeExportManager.exportAll();
            exportStatus = "Exported " + result.categories() + " types, " + result.recipes() + " recipes"
                    + (result.errors() > 0 ? ", " + result.errors() + " errors" : "");
            exportStatusColor = result.errors() > 0 ? 0xFFFF5555 : 0xFF55FF55;
        } catch (Exception e) {
            exportStatus = "Export failed: " + e.getMessage();
            exportStatusColor = 0xFFFF5555;
        }
    }

    private void openTagEditor() {
        GuiSessionState.setLastScreenType(GuiSessionState.LastScreenType.TAG_EDITOR);
        GuiSessionState.setLastCategory(null);
        RecipeBuilderMenu menu = new RecipeBuilderMenu(0, minecraft.player.getInventory());
        minecraft.setScreen(new TagEditorScreen(menu, minecraft.player.getInventory()));
    }

    private void openRecipeBrowser() {
        GuiSessionState.setLastScreenType(GuiSessionState.LastScreenType.RECIPE_BROWSER);
        GuiSessionState.setLastCategory(null);
        minecraft.setScreen(new RecipeBrowserScreen());
    }

    private void computeTotalHeight() {
        totalContentHeight = 0;
        for (String ns : namespaces) {
            totalContentHeight += SECTION_H;
            if (GuiSessionState.isSectionExpanded(ns)) {
                totalContentHeight += sectionButtonsHeight(sections.get(ns));
            }
        }
    }

    private int sectionButtonsHeight(List<IRecipeCategory<?>> cats) {
        int availableW = width - 2 * MARGIN - 10; // 10 for scrollbar
        int buttonsPerRow = Math.max(1, availableW / (BUTTON_W + BUTTON_PAD));
        int rows = (cats.size() + buttonsPerRow - 1) / buttonsPerRow;
        return rows * (BUTTON_H + BUTTON_PAD) + BUTTON_PAD;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        // Title row 1 (y=8); buttons at row 2 (y=22); status at row 3 (y=40, bottom of title bar)
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        if (!exportStatus.isEmpty()) {
            g.drawCenteredString(font, exportStatus, width / 2, 40, exportStatusColor);
        }

        // Scrollable content area
        int contentY = TITLE_H;
        int contentH = height - contentY;
        g.enableScissor(0, contentY, width - 10, height);

        int y = contentY - scrollOffset;
        int availableW = width - 2 * MARGIN - 10;
        int buttonsPerRow = Math.max(1, availableW / (BUTTON_W + BUTTON_PAD));

        for (String ns : namespaces) {
            // Section header
            String arrow = GuiSessionState.isSectionExpanded(ns) ? "▼" : "▶";
            String label = arrow + " " + displayName(ns);
            boolean headerHover = mouseX >= MARGIN && mouseX < width - 10
                    && mouseY >= y && mouseY < y + SECTION_H;
            g.fill(MARGIN, y, width - 10, y + SECTION_H, headerHover ? 0x44FFFFFF : 0x33FFFFFF);
            g.drawString(font, label, MARGIN + 2, y + 3, 0xFFFFFF);
            y += SECTION_H;

            if (GuiSessionState.isSectionExpanded(ns)) {
                List<IRecipeCategory<?>> cats = sections.get(ns);
                int col = 0;
                int rowY = y + BUTTON_PAD;
                for (IRecipeCategory<?> cat : cats) {
                    int bx = MARGIN + col * (BUTTON_W + BUTTON_PAD);
                    boolean btnHover = mouseX >= bx && mouseX < bx + BUTTON_W
                            && mouseY >= rowY && mouseY < rowY + BUTTON_H;
                    renderCategoryButton(g, cat, bx, rowY, btnHover);
                    col++;
                    if (col >= buttonsPerRow) {
                        col = 0;
                        rowY += BUTTON_H + BUTTON_PAD;
                    }
                }
                y += sectionButtonsHeight(cats);
            }
        }

        g.disableScissor();

        // Scrollbar
        renderScrollbar(g);
    }

    private void renderCategoryButton(GuiGraphics g, IRecipeCategory<?> cat, int x, int y, boolean hover) {
        int bg = hover ? 0x88AAAAAA : 0x55888888;
        g.fill(x, y, x + BUTTON_W, y + BUTTON_H, 0xFF222222);
        g.fill(x + 1, y + 1, x + BUTTON_W - 1, y + BUTTON_H - 1, bg);

        // Icon (16x16)
        if (cat.getIcon() != null) {
            try {
                cat.getIcon().draw(g, x + 2, y + 2);
            } catch (Exception ignored) {}
        }

        // Name truncated to fit
        String name = cat.getTitle().getString();
        int textX = x + 20;
        int maxW = BUTTON_W - 22;
        while (font.width(name) > maxW && name.length() > 3) {
            name = name.substring(0, name.length() - 1);
        }
        if (font.width(cat.getTitle().getString()) > maxW) name += "…";
        g.drawString(font, name, textX, y + (BUTTON_H - 8) / 2, 0xFFFFFF, false);
    }

    private void renderScrollbar(GuiGraphics g) {
        int contentY = TITLE_H;
        int contentH = height - contentY;
        if (totalContentHeight <= contentH) return;

        int barX = width - 8;
        int barH = Math.max(20, contentH * contentH / totalContentHeight);
        int barY = contentY + (int) ((long) scrollOffset * (contentH - barH) / (totalContentHeight - contentH));
        g.fill(barX, contentY, barX + 6, height, 0x55FFFFFF);
        g.fill(barX, barY, barX + 6, barY + barH, 0xCCFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int contentY = TITLE_H;
        if (mouseX >= width - 10 && mouseY >= contentY) {
            draggingScrollbar = true;
            return true;
        }

        // Find clicked element
        int y = contentY - scrollOffset;
        int availableW = width - 2 * MARGIN - 10;
        int buttonsPerRow = Math.max(1, availableW / (BUTTON_W + BUTTON_PAD));

        for (String ns : namespaces) {
            if (mouseY >= y && mouseY < y + SECTION_H) {
                GuiSessionState.toggleSection(ns);
                computeTotalHeight();
                return true;
            }
            y += SECTION_H;

            if (GuiSessionState.isSectionExpanded(ns)) {
                List<IRecipeCategory<?>> cats = sections.get(ns);
                int col = 0;
                int rowY = y + BUTTON_PAD;
                for (IRecipeCategory<?> cat : cats) {
                    int bx = MARGIN + col * (BUTTON_W + BUTTON_PAD);
                    if (mouseX >= bx && mouseX < bx + BUTTON_W
                            && mouseY >= rowY && mouseY < rowY + BUTTON_H) {
                        openRecipeBuilder(cat);
                        return true;
                    }
                    col++;
                    if (col >= buttonsPerRow) {
                        col = 0;
                        rowY += BUTTON_H + BUTTON_PAD;
                    }
                }
                y += sectionButtonsHeight(cats);
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingScrollbar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScrollbar) {
            scrollBy((int) dragY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollBy((int) (-scrollY * 14));
        return true;
    }

    private void scrollBy(int delta) {
        int contentH = height - TITLE_H;
        int maxScroll = Math.max(0, totalContentHeight - contentH);
        scrollOffset = Math.max(0, Math.min(scrollOffset + delta, maxScroll));
    }

    private void openRecipeBuilder(IRecipeCategory<?> category) {
        GuiSessionState.setLastCategory(category);
        GuiSessionState.setLastScreenType(GuiSessionState.LastScreenType.RECIPE_BUILDER);
        RecipeBuilderMenu menu = new RecipeBuilderMenu(0, minecraft.player.getInventory());
        RecipeBuilderScreen screen = new RecipeBuilderScreen(menu, minecraft.player.getInventory(), category);
        GuiSessionState.setLastScreen(screen);
        minecraft.setScreen(screen);
    }

    private String displayName(String namespace) {
        return namespace.equals("minecraft") ? "Vanilla (Minecraft)"
                : namespace.substring(0, 1).toUpperCase() + namespace.substring(1);
    }

    /** Returns true for JEI tag-browsing categories that are superseded by the Tag Editor. */
    private static boolean isTagBrowserCategory(IRecipeCategory<?> cat) {
        String uid   = cat.getRecipeType().getUid().toString().toLowerCase();
        String title = cat.getTitle().getString().toLowerCase();
        return uid.contains("item_tag") || uid.contains("block_tag") || uid.contains("fluid_tag")
            || uid.contains("tag_viewer") || uid.contains("tagsviewer")
            || title.equals("item tags") || title.equals("block tags") || title.equals("fluid tags");
    }

}
