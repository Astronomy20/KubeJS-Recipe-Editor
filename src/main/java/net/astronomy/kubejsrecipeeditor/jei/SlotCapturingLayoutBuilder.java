package net.astronomy.kubejsrecipeeditor.jei;

import mezz.jei.api.gui.builder.IIngredientAcceptor;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.gui.widgets.ISlottedWidgetFactory;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.category.IRecipeCategory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Intercepts {@link IRecipeCategory#setRecipe}'s slot declarations without rendering.
 * JEI 19.19+: slots are positioned via fluent {@link IRecipeSlotBuilder#setPosition}.
 */
public final class SlotCapturingLayoutBuilder implements IRecipeLayoutBuilder {

    public record CapturedSlot(RecipeIngredientRole role, int x, int y, boolean isFluid) {
        // backward-compat constructor without isFluid
        public CapturedSlot(RecipeIngredientRole role, int x, int y) {
            this(role, x, y, false);
        }
    }

    private final List<CapturedSlot> capturedSlots = new ArrayList<>();
    private final NoopInvisibleIngredientAcceptor invisibleAcceptor = new NoopInvisibleIngredientAcceptor();

    void recordSlot(RecipeIngredientRole role, int x, int y) {
        recordSlotAndGetIndex(role, x, y, false);
    }

    /** Called by CapturingRecipeSlotBuilder when it first records a position, returns the index. */
    int recordSlotAndGetIndex(RecipeIngredientRole role, int x, int y, boolean isFluid) {
        int idx = capturedSlots.size();
        capturedSlots.add(new CapturedSlot(role, x, y, isFluid));
        return idx;
    }

    /** Updates an already-recorded slot to mark it as a fluid slot. */
    void updateSlotFluid(int index) {
        if (index >= 0 && index < capturedSlots.size()) {
            CapturedSlot s = capturedSlots.get(index);
            capturedSlots.set(index, new CapturedSlot(s.role(), s.x(), s.y(), true));
        }
    }

    @Override
    public IRecipeSlotBuilder addSlot(RecipeIngredientRole role) {
        return new CapturingRecipeSlotBuilder(this, role);
    }

    @Deprecated(since = "19.19.3", forRemoval = true)
    @Override
    @SuppressWarnings("removal")
    public IRecipeSlotBuilder addSlotToWidget(RecipeIngredientRole role, ISlottedWidgetFactory widgetFactory) {
        return new CapturingRecipeSlotBuilder(this, role);
    }

    @Override
    public IIngredientAcceptor<?> addInvisibleIngredients(RecipeIngredientRole recipeIngredientRole) {
        return invisibleAcceptor;
    }

    @Override
    public void moveRecipeTransferButton(int posX, int posY) {}

    @Override
    public void createFocusLink(IIngredientAcceptor... slots) {}

    @Override
    public void setShapeless() {}

    @Override
    public void setShapeless(int posX, int posY) {}

    public List<CapturedSlot> getCapturedSlotsRaw() {
        return Collections.unmodifiableList(capturedSlots);
    }

    public List<CapturedSlot> getInteractiveSlotsDeduped() {
        Set<String> seen = new HashSet<>();
        List<CapturedSlot> out = new ArrayList<>();
        for (CapturedSlot s : capturedSlots) {
            if (s.role() == RecipeIngredientRole.RENDER_ONLY) continue;
            if (s.role() != RecipeIngredientRole.INPUT
                    && s.role() != RecipeIngredientRole.OUTPUT
                    && s.role() != RecipeIngredientRole.CATALYST) {
                continue;
            }
            String key = s.role() + ":" + s.x() + ":" + s.y();
            if (seen.add(key)) {
                out.add(s);
            }
        }
        return out;
    }

    public static <T> List<CapturedSlot> capture(
            IRecipeCategory<T> category,
            T exampleRecipe,
            IFocusGroup focuses) {

        SlotCapturingLayoutBuilder builder = new SlotCapturingLayoutBuilder();
        category.setRecipe(builder, exampleRecipe, focuses);
        return builder.getInteractiveSlotsDeduped();
    }
}
