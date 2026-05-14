package net.astronomy.kubejsrecipeeditor.engine;

public enum ContentType {
    ITEM,
    ITEM_TAG,
    FLUID,
    FLUID_COMPOUND,     // neoforge:compound still+flowing
    CHEMICAL_GAS,       // Mekanism
    CHEMICAL_SLURRY,
    CHEMICAL_INFUSE,
    CHEMICAL_PIGMENT,
    CUSTOM,             // registro valido ma non riconosciuto
    UNKNOWN             // non trovato in nessun registro
}
