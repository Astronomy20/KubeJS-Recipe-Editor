package net.astronomy.kubejsrecipeeditor.engine;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the value a user has entered into a slot or widget in the recipe builder GUI.
 */
public class UserValue {

    private ContentType contentType = ContentType.UNKNOWN;
    private @Nullable ResourceLocation resourceId;
    private @Nullable String rawString;    // for scalar string values
    private long amount = 1;
    private int count = 1;
    private int selectedVariantIndex = 0;
    private boolean empty = true;

    private @Nullable UserValue ingredientSubValue;
    private @Nullable UserValue chanceSubValue;
    private final Map<String, UserValue> namedSubValues = new HashMap<>();
    private final List<UserValue> listValues = new ArrayList<>();

    private UserValue() {}

    public static UserValue empty() {
        return new UserValue();
    }

    public static UserValue ofItem(ResourceLocation itemId, int count) {
        UserValue v = new UserValue();
        v.contentType = ContentType.ITEM;
        v.resourceId = itemId;
        v.count = count;
        v.empty = false;
        return v;
    }

    public static UserValue ofItemTag(ResourceLocation tagId) {
        UserValue v = new UserValue();
        v.contentType = ContentType.ITEM_TAG;
        v.resourceId = tagId;
        v.empty = false;
        return v;
    }

    public static UserValue ofFluid(ResourceLocation fluidId, long amount) {
        UserValue v = new UserValue();
        v.contentType = ContentType.FLUID;
        v.resourceId = fluidId;
        v.amount = amount;
        v.empty = false;
        return v;
    }

    public static UserValue ofChemical(ContentType chemType, ResourceLocation chemId, long amount) {
        UserValue v = new UserValue();
        v.contentType = chemType;
        v.resourceId = chemId;
        v.amount = amount;
        v.empty = false;
        return v;
    }

    public static UserValue ofString(String value) {
        UserValue v = new UserValue();
        v.rawString = value;
        v.empty = value == null || value.isBlank();
        return v;
    }

    public String asString() {
        if (rawString != null) return rawString;
        if (resourceId != null) return resourceId.toString();
        return "";
    }

    public boolean isEmpty() { return empty; }
    public ContentType getContentType() { return contentType; }
    public @Nullable ResourceLocation getResourceId() { return resourceId; }
    public long getAmount() { return amount; }
    public int getCount() { return count; }
    public int getSelectedVariantIndex() { return selectedVariantIndex; }
    public void setSelectedVariantIndex(int idx) { this.selectedVariantIndex = idx; }

    public @Nullable UserValue getIngredient() { return ingredientSubValue; }
    public void setIngredient(UserValue v) { this.ingredientSubValue = v; if (!v.isEmpty()) empty = false; }

    public @Nullable UserValue getChance() { return chanceSubValue; }
    public void setChance(UserValue v) { this.chanceSubValue = v; if (!v.isEmpty()) empty = false; }

    public UserValue getSubValue(String key) {
        UserValue sub = namedSubValues.get(key);
        return sub != null ? sub : empty();
    }

    public void putSubValue(String key, UserValue value) {
        namedSubValues.put(key, value);
        if (value != null && !value.isEmpty()) empty = false;
    }

    public List<UserValue> asList() {
        return listValues;
    }

    public void addListItem(UserValue item) {
        listValues.add(item);
        if (!item.isEmpty()) empty = false;
    }
}
