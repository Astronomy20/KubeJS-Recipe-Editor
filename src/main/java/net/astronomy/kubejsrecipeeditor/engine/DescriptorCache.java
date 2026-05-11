package net.astronomy.kubejsrecipeeditor.engine;

import net.astronomy.kubejsrecipeeditor.gui.GuiDescriptor;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache for GuiDescriptors, one per recipe type per session.
 * Cleared when JEI runtime becomes unavailable (F3+T reload).
 */
public class DescriptorCache {
    public static final DescriptorCache INSTANCE = new DescriptorCache();

    private final Map<ResourceLocation, GuiDescriptor> memCache = new ConcurrentHashMap<>();

    private DescriptorCache() {}

    public Optional<GuiDescriptor> get(ResourceLocation typeUid) {
        return Optional.ofNullable(memCache.get(typeUid));
    }

    public void put(ResourceLocation typeUid, GuiDescriptor descriptor) {
        memCache.put(typeUid, descriptor);
    }

    public void clear() {
        memCache.clear();
    }
}
