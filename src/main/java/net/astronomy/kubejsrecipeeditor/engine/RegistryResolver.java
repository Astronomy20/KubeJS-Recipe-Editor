package net.astronomy.kubejsrecipeeditor.engine;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Determines the ContentType of a resource by querying NeoForge registries at runtime.
 * Does not hardcode mod names: uses the registries themselves as the source of truth.
 */
public class RegistryResolver {

    private final RegistryAccess registryAccess;

    private final Map<String, ResourceKey<? extends Registry<?>>> jsonKeyToRegistry = new LinkedHashMap<>();
    private final Map<ResourceKey<? extends Registry<?>>, ContentType> customRegistryToContentType = new LinkedHashMap<>();

    public RegistryResolver(RegistryAccess registryAccess) {
        this.registryAccess = registryAccess;

        jsonKeyToRegistry.put("item",        Registries.ITEM);
        jsonKeyToRegistry.put("tag",         Registries.ITEM);
        jsonKeyToRegistry.put("fluid",       Registries.FLUID);
        jsonKeyToRegistry.put("fluidTag",    Registries.FLUID);

        registerChemicalKey("gas",         "mekanism:gas",         ContentType.CHEMICAL_GAS);
        registerChemicalKey("slurry",      "mekanism:slurry",      ContentType.CHEMICAL_SLURRY);
        registerChemicalKey("infuse_type", "mekanism:infuse_type", ContentType.CHEMICAL_INFUSE);
        registerChemicalKey("pigment",     "mekanism:pigment",     ContentType.CHEMICAL_PIGMENT);
    }

    private void registerChemicalKey(String jsonKey, String registryId, ContentType contentType) {
        ResourceKey<? extends Registry<?>> key = tryRegistry(registryId);
        jsonKeyToRegistry.put(jsonKey, key);
        ResourceKey<? extends Registry<?>> mapKey = key != null ? key
            : ResourceKey.createRegistryKey(ResourceLocation.parse(registryId));
        customRegistryToContentType.put(mapKey, contentType);
    }

    /**
     * Resolves a ResourceLocation string to its ContentType by querying registries.
     */
    public ContentType resolve(String resourceLocationStr) {
        ResourceLocation rl;
        try {
            rl = ResourceLocation.parse(resourceLocationStr);
        } catch (Exception e) {
            return ContentType.UNKNOWN;
        }

        try {
            if (registryAccess.registryOrThrow(Registries.ITEM).containsKey(rl))
                return ContentType.ITEM;
        } catch (Exception ignored) {}

        try {
            if (registryAccess.registryOrThrow(Registries.FLUID).containsKey(rl))
                return ContentType.FLUID;
        } catch (Exception ignored) {}

        for (var entry : customRegistryToContentType.entrySet()) {
            try {
                @SuppressWarnings("unchecked")
                var registry = registryAccess.registry((ResourceKey<Registry<Object>>) entry.getKey());
                if (registry.isPresent() && registry.get().containsKey(rl))
                    return entry.getValue();
            } catch (Exception ignored) {}
        }

        if (resourceLocationStr.contains(":")) return ContentType.CUSTOM;
        return ContentType.UNKNOWN;
    }

    /**
     * Resolves ContentType from a JSON key name (e.g. "fluid" → FLUID, "gas" → CHEMICAL_GAS).
     */
    public ContentType resolveByJsonKey(String jsonKey) {
        if (!jsonKeyToRegistry.containsKey(jsonKey.toLowerCase())) {
            return switch (jsonKey) {
                case "gas"         -> ContentType.CHEMICAL_GAS;
                case "slurry"      -> ContentType.CHEMICAL_SLURRY;
                case "infuse_type" -> ContentType.CHEMICAL_INFUSE;
                case "pigment"     -> ContentType.CHEMICAL_PIGMENT;
                default            -> ContentType.UNKNOWN;
            };
        }
        var registryKey = jsonKeyToRegistry.get(jsonKey.toLowerCase());
        if (registryKey == null) return ContentType.UNKNOWN;
        if (registryKey.equals(Registries.ITEM))  return ContentType.ITEM;
        if (registryKey.equals(Registries.FLUID)) return ContentType.FLUID;
        return customRegistryToContentType.getOrDefault(registryKey, ContentType.CUSTOM);
    }

    /** Returns the registry key string for a given ContentType, or null if not applicable. */
    @Nullable
    public String getRegistryKey(ContentType contentType) {
        return switch (contentType) {
            case ITEM, ITEM_TAG         -> "minecraft:item";
            case FLUID, FLUID_COMPOUND  -> "minecraft:fluid";
            case CHEMICAL_GAS           -> "mekanism:gas";
            case CHEMICAL_SLURRY        -> "mekanism:slurry";
            case CHEMICAL_INFUSE        -> "mekanism:infuse_type";
            case CHEMICAL_PIGMENT       -> "mekanism:pigment";
            default                     -> null;
        };
    }

    /** Adds a JSON key → registry mapping (called by Fragment.add_registry_hints). */
    public void registerJsonKeyMapping(String jsonKey,
            @Nullable ResourceKey<? extends Registry<?>> registry,
            ContentType contentType) {
        jsonKeyToRegistry.put(jsonKey, registry);
        if (registry != null) customRegistryToContentType.put(registry, contentType);
    }

    @Nullable
    private static ResourceKey<? extends Registry<?>> tryRegistry(String registryId) {
        try {
            return ResourceKey.createRegistryKey(ResourceLocation.parse(registryId));
        } catch (Exception e) {
            return null;
        }
    }
}
