package net.astronomy.kubejsrecipeeditor;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class KreConfig {
    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue SCAN_LIMIT;

    static {
        BUILDER.push("recipe_template");
        SCAN_LIMIT = BUILDER
                .comment(
                        "Number of example recipes sampled per JEI category on FIRST startup only.",
                        "Higher = better ENUM/bound detection; lower = faster first-launch sampling.",
                        "Changing this value has no effect until the cache file is deleted.",
                        "Range: 5-200. Default: 30."
                )
                .defineInRange("scan_limit", 30, 5, 200);
        BUILDER.pop();
        SPEC = BUILDER.build();
    }
}
