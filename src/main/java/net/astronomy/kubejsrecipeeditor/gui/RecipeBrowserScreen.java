package net.astronomy.kubejsrecipeeditor.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.astronomy.kubejsrecipeeditor.KubeJsRecipeEditor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Scrollable browser of all loaded MC recipes.
 * - KubeJS recipes (found in our scripts): click × → confirm popup → write event.remove to removals/ (script untouched)
 * - Other recipes: click × → write event.remove to removals/ directly, no popup
 * Pending-removal entries stay in the list with gray overlay + green ✔ (click to revert before /reload).
 * After /reload the recipe vanishes from the runtime and the session cache entry is pruned automatically.
 */
public class RecipeBrowserScreen extends Screen {

    private static final int TITLE_H  = 24;
    private static final int SEARCH_H = 24;
    private static final int ROW_H    = 20;
    private static final int MARGIN   = 6;

    private record RecipeEntry(ResourceLocation id, ItemStack output) {}

    private final List<RecipeEntry> allRecipes      = new ArrayList<>();
    private final List<RecipeEntry> filteredRecipes = new ArrayList<>();

    // Static: persists across screen open/close; entries pruned after /reload (recipe gone from runtime)
    private static final Set<ResourceLocation> removedInSession = new HashSet<>();

    private EditBox searchBox;
    private int scrollOffset = 0;
    private String statusMessage = "";
    private int    statusColor   = 0xFFFFFF;
    private int listY, listH;

    // Confirmation popup state for KubeJS recipes (null = no popup)
    private ResourceLocation pendingId = null;

    public RecipeBrowserScreen() {
        super(Component.literal("Recipe Browser"));
    }

    // ─── Init ────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();
        listY = TITLE_H + SEARCH_H;
        listH = height - listY;

        addRenderableWidget(Button.builder(Component.literal("⌂"), btn -> goHome())
                .pos(4, 6).size(16, 14).build());

        searchBox = new EditBox(font, MARGIN + 32, TITLE_H + 5, width - MARGIN * 2 - 32, 14,
                Component.literal("Filter by ID or name..."));
        searchBox.setMaxLength(80);
        searchBox.setResponder(text -> { filterRecipes(text); scrollOffset = 0; });
        addWidget(searchBox);

        loadRecipes();
    }

    private void loadRecipes() {
        allRecipes.clear();
        try {
            var level = Minecraft.getInstance().level;
            if (level == null) return;
            var reg = level.registryAccess();
            level.getRecipeManager().getRecipes().stream()
                    .sorted(Comparator.comparing(h -> h.id().toString()))
                    .forEach(h -> {
                        ItemStack out = ItemStack.EMPTY;
                        try { out = h.value().getResultItem(reg); } catch (Exception ignored) {}
                        allRecipes.add(new RecipeEntry(h.id(), out));
                    });
        } catch (Exception e) {
            KubeJsRecipeEditor.LOGGER.error("Failed to load recipe list: {}", e.getMessage());
        }
        // If a cached recipe is no longer in the runtime (e.g. after /reload removed it), drop it
        Set<ResourceLocation> currentIds = allRecipes.stream()
                .map(RecipeEntry::id).collect(Collectors.toSet());
        removedInSession.removeIf(id -> !currentIds.contains(id));

        filterRecipes(searchBox != null ? searchBox.getValue() : "");
    }

    private void filterRecipes(String query) {
        filteredRecipes.clear();
        String q = query.toLowerCase();
        for (RecipeEntry e : allRecipes) {
            String id  = e.id().toString().toLowerCase();
            String out = e.output().isEmpty() ? "" : e.output().getHoverName().getString().toLowerCase();
            if (q.isEmpty() || id.contains(q) || out.contains(q)) filteredRecipes.add(e);
        }
    }

    // ─── Rendering ───────────────────────────────────────────────────────────

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {}

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xFF1A1A1A);

        g.fill(0, 0, width, TITLE_H, 0xFF2D2D2D);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);

        g.fill(0, TITLE_H, width, listY, 0xFF1E1E1E);
        g.drawString(font, "Filter:", MARGIN, TITLE_H + 8, 0xFFAAAAAA, false);
        searchBox.render(g, mouseX, mouseY, partialTick);

        g.fill(0, listY, width, height, 0xFF161616);
        g.enableScissor(0, listY, width - 8, height);
        int y = listY - scrollOffset;
        int xBtnX = width - 8 - 16;
        for (RecipeEntry entry : filteredRecipes) {
            if (y + ROW_H < listY) { y += ROW_H; continue; }
            if (y > height)        break;

            boolean removed  = removedInSession.contains(entry.id());
            boolean rowHover = !removed && pendingId == null
                            && mouseX >= 0 && mouseX < width - 8
                            && mouseY >= y && mouseY < y + ROW_H;
            if (rowHover) g.fill(0, y, width - 8, y + ROW_H, 0x33FFFFFF);

            if (!entry.output().isEmpty()) g.renderItem(entry.output(), MARGIN, y + 2);

            String idText = entry.id().toString();
            int maxW = width - MARGIN * 2 - 20 - 24;
            while (font.width(idText) > maxW && idText.length() > 4)
                idText = idText.substring(0, idText.length() - 1);
            if (!idText.equals(entry.id().toString())) idText += "...";

            int textColor = removed ? 0xFF555555 : 0xFFDDDDDD;
            g.drawString(font, idText, MARGIN + 20, y + (ROW_H - 8) / 2, textColor, false);

            if (removed) {
                g.fill(0, y, width - 8, y + ROW_H, 0x99000000);
                // Clickable green tick — reverts the pending removal
                boolean tickHover = pendingId == null
                        && mouseX >= xBtnX && mouseX < xBtnX + 14
                        && mouseY >= y + 3  && mouseY < y + ROW_H - 3;
                g.fill(xBtnX, y + 3, xBtnX + 14, y + ROW_H - 3,
                        tickHover ? 0xAA226622 : 0x55226622);
                g.drawString(font, "+", xBtnX + 4, y + (ROW_H - 8) / 2, 0xFF55FF55, false);
            } else {
                boolean xHover = pendingId == null
                        && mouseX >= xBtnX && mouseX < xBtnX + 14
                        && mouseY >= y + 3  && mouseY < y + ROW_H - 3;
                g.fill(xBtnX, y + 3, xBtnX + 14, y + ROW_H - 3,
                        xHover ? 0xAA882222 : 0x55882222);
                g.drawString(font, "x", xBtnX + 4, y + (ROW_H - 8) / 2, 0xFFFF5555, false);
            }

            y += ROW_H;
        }
        g.disableScissor();

        renderScrollbar(g);

        if (!statusMessage.isEmpty()) {
            g.fill(0, height - 14, width, height, 0xFF1A1A1A);
            g.drawCenteredString(font, Component.literal(statusMessage),
                    width / 2, height - 11, statusColor);
        }

        super.render(g, mouseX, mouseY, partialTick);

        // Confirmation popup rendered on top of everything
        if (pendingId != null) renderConfirmPopup(g, mouseX, mouseY);
    }

    private void renderScrollbar(GuiGraphics g) {
        int total = filteredRecipes.size() * ROW_H;
        if (total <= listH) return;
        int barX = width - 7;
        int barH = Math.max(20, listH * listH / total);
        int barY = listY + (int) ((long) scrollOffset * (listH - barH) / (total - listH));
        g.fill(barX, listY, barX + 6, height, 0x55FFFFFF);
        g.fill(barX, barY, barX + 6, barY + barH, 0xCCFFFFFF);
    }

    private void renderConfirmPopup(GuiGraphics g, int mouseX, int mouseY) {
        int popW = 240, popH = 60;
        int popX = (width  - popW) / 2;
        int popY = (height - popH) / 2;

        g.pose().pushPose();
        g.pose().translate(0, 0, 300);

        g.fill(popX - 1, popY - 1, popX + popW + 1, popY + popH + 1, 0xFF888888);
        g.fill(popX, popY, popX + popW, popY + popH, 0xFF2A2A2A);

        g.drawCenteredString(font, "Add event.remove for this recipe?", width / 2, popY + 6, 0xFFFFFFFF);

        String idShort = pendingId.toString();
        if (font.width(idShort) > popW - 8)
            idShort = "..." + idShort.substring(idShort.length() - 30);
        g.drawCenteredString(font, idShort, width / 2, popY + 18, 0xFFAAAAAA);

        int btnY  = popY + popH - 18;
        int delX  = popX + 10;
        int canX  = popX + popW - 80 - 10;
        boolean delHover = mouseX >= delX && mouseX < delX + 80 && mouseY >= btnY && mouseY < btnY + 14;
        boolean canHover = mouseX >= canX && mouseX < canX + 80 && mouseY >= btnY && mouseY < btnY + 14;
        g.fill(delX, btnY, delX + 80, btnY + 14, delHover ? 0xFFAA3333 : 0xFF882222);
        g.fill(canX, btnY, canX + 80, btnY + 14, canHover ? 0xFF888888 : 0xFF555555);
        g.drawCenteredString(font, "Remove", delX + 40, btnY + 3, 0xFFFFFFFF);
        g.drawCenteredString(font, "Cancel", canX + 40, btnY + 3, 0xFFDDDDDD);

        g.pose().popPose();
    }

    // ─── Input ───────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int mouseX = (int) mx, mouseY = (int) my;

        if (pendingId != null) {
            return handlePopupClick(mouseX, mouseY);
        }

        int xBtnX = width - 8 - 16;
        if (mouseX >= xBtnX && mouseX < xBtnX + 14 && mouseY >= listY) {
            int idx = (mouseY - listY + scrollOffset) / ROW_H;
            if (idx >= 0 && idx < filteredRecipes.size()) {
                RecipeEntry entry = filteredRecipes.get(idx);
                if (removedInSession.contains(entry.id())) {
                    handleRevert(entry);
                } else {
                    handleRemove(entry);
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private boolean handlePopupClick(int mouseX, int mouseY) {
        int popW = 240, popH = 60;
        int popX = (width  - popW) / 2;
        int popY = (height - popH) / 2;
        int btnY = popY + popH - 18;
        int delX = popX + 10;
        int canX = popX + popW - 80 - 10;

        if (mouseX >= delX && mouseX < delX + 80 && mouseY >= btnY && mouseY < btnY + 14) {
            writeRemovalScript(pendingId);
            removedInSession.add(pendingId);
            String path = shortPath(gameDir().resolve(
                    "kubejs/server_scripts/removals/" + pendingId.getNamespace() + ".js").toString());
            statusMessage = "Removed from " + path;
            statusColor   = 0x55FF55;
            pendingId = null;
            return true;
        }
        if (mouseX >= canX && mouseX < canX + 80 && mouseY >= btnY && mouseY < btnY + 14) {
            pendingId = null;
            return true;
        }
        // Click outside popup = cancel
        if (mouseX < popX || mouseX > popX + popW || mouseY < popY || mouseY > popY + popH) {
            pendingId = null;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (pendingId != null) return true;
        int maxScroll = Math.max(0, filteredRecipes.size() * ROW_H - listH);
        scrollOffset  = Math.max(0, Math.min(scrollOffset + (int)(-scrollY * ROW_H * 3), maxScroll));
        return true;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ─── Removal / revert logic ───────────────────────────────────────────────

    private void handleRemove(RecipeEntry entry) {
        String outputId = getOutputItemId(entry);
        String marker   = "    // " + (outputId != null ? outputId : entry.id().toString());

        if (findMarkerInScripts(marker) != null) {
            // KubeJS recipe — confirm before writing removal (script is left untouched)
            pendingId = entry.id();
        } else {
            // External recipe — write removal directly
            writeRemovalScript(entry.id());
            removedInSession.add(entry.id());
            String path = shortPath(gameDir().resolve(
                    "kubejs/server_scripts/removals/" + entry.id().getNamespace() + ".js").toString());
            statusMessage = "Removed from " + path;
            statusColor   = 0x55FF55;
        }
    }

    private void handleRevert(RecipeEntry entry) {
        // Remove the event.remove block from removals/<namespace>.js
        String marker = "    // " + entry.id();
        Path removalsFile = gameDir().resolve(
                "kubejs/server_scripts/removals/" + entry.id().getNamespace() + ".js");
        if (Files.exists(removalsFile)) {
            removeMarkerFromFile(marker, removalsFile.toString());
        }
        removedInSession.remove(entry.id());
        statusMessage = "Reverted: " + entry.id();
        statusColor   = 0xFFAA44;
    }

    private String getOutputItemId(RecipeEntry entry) {
        if (!entry.output().isEmpty()) {
            return entry.output().getItem().builtInRegistryHolder().key().location().toString();
        }
        var level = Minecraft.getInstance().level;
        if (level == null) return null;
        try {
            return level.getRecipeManager().getRecipes().stream()
                    .filter(h -> h.id().equals(entry.id()))
                    .findFirst()
                    .flatMap(h -> {
                        try {
                            ItemStack out = h.value().getResultItem(level.registryAccess());
                            if (!out.isEmpty())
                                return Optional.of(out.getItem().builtInRegistryHolder()
                                        .key().location().toString());
                        } catch (Exception ignored) {}
                        return Optional.empty();
                    })
                    .orElse(null);
        } catch (Exception e) { return null; }
    }

    /** Scans kubejs/server_scripts (excluding removals/) for {@code marker} at a line start. */
    private String findMarkerInScripts(String marker) {
        Path scriptDir = gameDir().resolve("kubejs/server_scripts");
        if (!Files.exists(scriptDir)) return null;
        try (var walk = Files.walk(scriptDir)) {
            for (Path file : walk.filter(p -> {
                String ps = p.toString().replace('\\', '/');
                return ps.endsWith(".js") && !ps.contains("/removals/");
            }).toList()) {
                String content = Files.readString(file);
                int search = 0;
                while (true) {
                    int pos = content.indexOf(marker, search);
                    if (pos == -1) break;
                    if (pos == 0 || content.charAt(pos - 1) == '\n') return file.toString();
                    search = pos + 1;
                }
            }
        } catch (IOException e) {
            KubeJsRecipeEditor.LOGGER.error("Script scan failed: {}", e.getMessage());
        }
        return null;
    }

    /** Removes the script block starting at {@code marker} (comment + event call up to next blank line). */
    private boolean removeMarkerFromFile(String marker, String filePath) {
        try {
            Path   file    = Path.of(filePath);
            String content = Files.readString(file);

            int pos = -1, search = 0;
            while (true) {
                int found = content.indexOf(marker, search);
                if (found == -1) break;
                if (found == 0 || content.charAt(found - 1) == '\n') { pos = found; break; }
                search = found + 1;
            }
            if (pos == -1) return false;

            int lineEnd  = content.indexOf('\n', pos);
            if (lineEnd == -1) return false;
            int blockEnd = content.indexOf("\n\n", lineEnd);
            int cutEnd   = (blockEnd == -1) ? content.length() : blockEnd + 2;

            String updated = content.substring(0, pos) + content.substring(cutEnd);
            while (updated.contains("\n\n\n")) updated = updated.replace("\n\n\n", "\n\n");
            Files.writeString(file, updated);
            return true;
        } catch (IOException e) {
            KubeJsRecipeEditor.LOGGER.error("Failed to remove from file: {}", e.getMessage());
            return false;
        }
    }

    private void writeRemovalScript(ResourceLocation id) {
        try {
            Path dir  = gameDir().resolve("kubejs/server_scripts/removals");
            Path file = dir.resolve(id.getNamespace() + ".js");
            Files.createDirectories(dir);
            String existing = Files.exists(file) ? Files.readString(file) : buildRemovalHeader(id.getNamespace());
            String trimmed  = existing.stripTrailing();
            if (trimmed.endsWith("})")) trimmed = trimmed.substring(0, trimmed.length() - 2);
            Files.writeString(file, trimmed + "\n    // " + id
                    + "\n    event.remove({ id: '" + id + "' })\n\n})\n");
        } catch (IOException e) {
            statusMessage = "Write failed: " + e.getMessage();
            statusColor   = 0xFF5555;
        }
    }

    private String buildRemovalHeader(String namespace) {
        return "// Auto-generated by KubeJS Recipe Editor\n// Removals for: " + namespace
                + "\n// Generated: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                + "\n\nServerEvents.recipes(event => {\n\n})\n";
    }

    private void goHome() {
        GuiSessionState.setLastScreenType(GuiSessionState.LastScreenType.HOME);
        GuiSessionState.setLastCategory(null);
        minecraft.setScreen(new ModMenuScreen());
    }

    private Path   gameDir()           { return Minecraft.getInstance().gameDirectory.toPath(); }
    private String shortPath(String a) {
        String s = a.replace('\\', '/');
        int i = s.lastIndexOf("kubejs/");
        return i != -1 ? s.substring(i) : a;
    }
}
