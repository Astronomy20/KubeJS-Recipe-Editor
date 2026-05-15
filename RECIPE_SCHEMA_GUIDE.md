# Bundled Schema Guide

This document explains how to write a **bundled schema** for a recipe type so that
KubeJS Recipe Editor can build a proper GUI for it, validate ingredient types, and
reconstruct the correct JSON on export.

Bundled schemas live inside the mod JAR and are applied automatically.
They override the runtime inference engine for known mod types, guaranteeing
complete and correct field coverage even when optional fields are absent from
most recipe JSON files.

---

## 1. Priority levels

| Priority | Source | Location |
|---|---|---|
| 1 (lowest) | Runtime inference | Generated at game load from actual recipe JSON corpus |
| 2 | **Bundled schema** | `resources/kubejsrecipeeditor/bundled_schemas/<namespace>/<type_path>.json` |
| 3 (highest) | User fragment | `config/kubejsrecipeeditor/templates/fragments/` |

Bundled schemas are applied when a mod is present and override the inferred template.
User fragments override everything.

---

## 2. File location and naming

```
src/main/resources/kubejsrecipeeditor/bundled_schemas/
├── create/
│   ├── mixing.json        ← recipe type create:mixing
│   └── pressing.json
├── mekanism/
│   ├── crushing.json      ← recipe type mekanism:crushing
│   └── reaction.json
└── appeng/
    └── inscriber.json     ← recipe type appeng:inscriber
```

Rule: replace `:` with `/` between namespace and path.
If the path contains `/` (e.g. `mekanism:reaction/basic`), use nested directories:
`bundled_schemas/mekanism/reaction/basic.json`.

---

## 3. Top-level structure

```json
{
  "_meta": {
    "type": "create:mixing",
    "source": "bundled",
    "mod_version_target": "mc1.21.1/dev",
    "engine_version": "1.0",
    "_note": "Optional free-text note visible in debug output."
  },
  "fields": {
    "type":  { "descriptor": "ConstantField", "constantValue": "create:mixing" },
    "...":   { ... }
  }
}
```

| `_meta` key | Required | Description |
|---|---|---|
| `type` | yes | The full recipe type ID, e.g. `create:mixing` |
| `source` | yes | Must be exactly `"bundled"` |
| `mod_version_target` | yes | Branch, tag, or version used when the schema was written |
| `engine_version` | yes | Must be `"1.0"` |
| `_note` | no | Free-text note; shown in debug logs |

The `fields` object maps each JSON field name to a **FieldDescriptor**.
The field named `"type"` should always be present as a `ConstantField`.

---

## 4. FieldDescriptor types

### 4.1 `ConstantField`

A value that is always the same — the user never edits it.
The GUI hides it; the JSON output always copies it from the template.

```json
{ "descriptor": "ConstantField", "constantValue": "create:mixing" }
```

**Always use this for the `"type"` field.**
Also use it for internal flags or version markers that should be written as-is.

---

### 4.2 `ScalarField`

A single editable value: integer, float, boolean, or string.

```json
{
  "descriptor": "ScalarField",
  "scalarType": "INTEGER",
  "optional": true,
  "defaultValue": "100",
  "min": 0,
  "max": 72000
}
```

| `scalarType` | GUI widget | Notes |
|---|---|---|
| `INTEGER` | Spinner `[-] N [+]` | `min`/`max` required |
| `FLOAT` | Spinner `[-] N.N [+]` | `min`/`max` required |
| `BOOLEAN` | Toggle button | `defaultValue`: `"true"` or `"false"` |
| `ENUM_STRING` | Cycle button or dropdown | Requires `enumValues` list |
| `FREE_STRING` | Read-only label (yellow) | Use for resource locations that vary per recipe |

```json
{
  "descriptor": "ScalarField",
  "scalarType": "ENUM_STRING",
  "optional": true,
  "defaultValue": "none",
  "enumValues": ["none", "heated", "superheated"]
}
```

---

### 4.3 `IngredientField`

An ingredient slot in the GUI — accepts items, fluids, or mod-specific chemicals.
Rendered as a coloured square the user can drag JEI entries into.

```json
{
  "descriptor": "IngredientField",
  "optional": false,
  "acceptedTypes": ["ITEM", "ITEM_TAG"],
  "subfields": {
    "count": {
      "descriptor": "ScalarField",
      "scalarType": "INTEGER",
      "optional": true,
      "defaultValue": "1",
      "min": 1,
      "max": 64
    }
  }
}
```

**`acceptedTypes`** — one or more of:

| Value | Slot colour | When to use |
|---|---|---|
| `"ITEM"` | White | Single item |
| `"ITEM_TAG"` | White | Item tag (`#minecraft:logs`) |
| `"FLUID"` | Blue | Fluid (use with `amount` subfield) |
| `"FLUID_COMPOUND"` | Blue | NeoForge compound fluid ingredient |
| `"CHEMICAL_GAS"` | Purple | Mekanism Gas / unified Chemical (1.21.x) |
| `"CHEMICAL_SLURRY"` | Purple | Mekanism Slurry |
| `"CHEMICAL_INFUSE"` | Purple | Mekanism InfuseType |
| `"CHEMICAL_PIGMENT"` | Purple | Mekanism Pigment |
| `"CUSTOM"` | Grey | Unknown registry resource |

A slot that accepts both items and fluids uses `["ITEM", "ITEM_TAG", "FLUID"]` and renders cyan.

**`subfields`** — extra scalar fields attached to the ingredient object in JSON
(e.g. `count`, `amount`, `chance`). Each value is a `ScalarField` definition.

---

### 4.4 `ArrayField`

A dynamic list of the same element type, rendered with `[+]` / `[-]` buttons.

```json
{
  "descriptor": "ArrayField",
  "optional": false,
  "elementDescriptor": { ... },
  "minItems": 1,
  "maxItems": 9
}
```

The `elementDescriptor` can be any FieldDescriptor (most commonly `IngredientField`).

---

### 4.5 `ObjectField`

A nested JSON object rendered as a collapsible panel with labelled children.

```json
{
  "descriptor": "ObjectField",
  "optional": false,
  "collapsible": true,
  "children": {
    "key1": { ... },
    "key2": { ... }
  }
}
```

---

### 4.6 `ChanceField`

A compound field combining one ingredient slot with a float probability slider.
Renders as slot + slider `0.0 → 1.0`.

```json
{
  "descriptor": "ChanceField",
  "optional": true,
  "ingredient": {
    "descriptor": "IngredientField",
    "optional": false,
    "acceptedTypes": ["ITEM"],
    "subfields": {}
  },
  "chance": {
    "descriptor": "ScalarField",
    "scalarType": "FLOAT",
    "min": 0.0,
    "max": 1.0,
    "defaultValue": "0.5"
  }
}
```

---

## 5. Step-by-step: from recipe JSON to schema

**Step 1 — Find a real recipe JSON** for the type you want to document.
Look in the mod's source under `src/main/resources/data/<namespace>/recipes/`
or extract it from the JAR.

**Step 2 — Find the serializer** (usually a `MapCodec` or `RecordCodecBuilder`
in a `*Serializer.java` file). This reveals every field, including optional ones
that don't appear in the default recipes.

**Step 3 — Map each JSON key** to a FieldDescriptor using the rules in §4:
- Constant string = `ConstantField`
- Int/float/bool = `ScalarField`
- `{"item": ...}` / `{"tag": ...}` = `IngredientField(ITEM, ITEM_TAG)`
- `{"fluid": ..., "amount": N}` = `IngredientField(FLUID)` + `amount` subfield
- `{"chemical": ..., "amount": N}` (Mekanism 1.21.x) = `IngredientField(CHEMICAL_GAS)`
- Array of objects = `ArrayField`
- Nested object = `ObjectField`
- Item + float probability = `ChanceField`

**Step 4 — Note optionality** from the codec (`optionalFieldOf` in Mojang codecs,
or nullable/`Optional<>` in the serializer). Set `"optional": true` and supply a
`"defaultValue"` where one exists.

**Step 5 — Write the file** to
`bundled_schemas/<namespace>/<type_path>.json` and rebuild.

---

## 6. Worked examples

### Example A — Create Crushing (simple item → items with chance)

Recipe JSON:
```json
{
  "type": "create:crushing",
  "ingredients": [{ "item": "minecraft:iron_ore" }],
  "results": [
    { "item": "create:crushed_raw_iron", "count": 1 },
    { "item": "create:crushed_raw_iron", "chance": 0.75 }
  ],
  "processingTime": 150
}
```

Schema (`bundled_schemas/create/crushing.json`):
```json
{
  "_meta": {
    "type": "create:crushing",
    "source": "bundled",
    "mod_version_target": "mc1.21.1/dev",
    "engine_version": "1.0"
  },
  "fields": {
    "type": { "descriptor": "ConstantField", "constantValue": "create:crushing" },
    "ingredients": {
      "descriptor": "ArrayField",
      "optional": false,
      "elementDescriptor": {
        "descriptor": "IngredientField",
        "optional": false,
        "acceptedTypes": ["ITEM", "ITEM_TAG"],
        "subfields": {}
      },
      "minItems": 1,
      "maxItems": 1
    },
    "results": {
      "descriptor": "ArrayField",
      "optional": false,
      "elementDescriptor": {
        "descriptor": "IngredientField",
        "optional": false,
        "acceptedTypes": ["ITEM"],
        "subfields": {
          "count":  { "descriptor": "ScalarField", "scalarType": "INTEGER", "optional": true, "defaultValue": "1", "min": 1, "max": 64 },
          "chance": { "descriptor": "ScalarField", "scalarType": "FLOAT",   "optional": true, "defaultValue": "1.0", "min": 0.0, "max": 1.0 }
        }
      },
      "minItems": 1,
      "maxItems": 6
    },
    "processing_time": {
      "descriptor": "ScalarField",
      "scalarType": "INTEGER",
      "optional": true,
      "defaultValue": "150",
      "min": 0,
      "max": 72000
    }
  }
}
```

---

### Example B — Create Mixing (items + fluids, enum heat requirement)

Recipe JSON:
```json
{
  "type": "create:mixing",
  "ingredients": [
    { "item": "minecraft:iron_ingot" },
    { "fluid": "minecraft:water", "amount": 250 }
  ],
  "results": [{ "item": "create:andesite_alloy" }],
  "heatRequirement": "heated",
  "processingTime": 100
}
```

The serializer (`ProcessingRecipeParams.CODEC`) also accepts
`"superheated"` and the default `"none"` for `heatRequirement`,
and fluid outputs in `results` — none of which appear in this example.
The schema captures all possibilities:

```json
{
  "_meta": { "type": "create:mixing", "source": "bundled",
             "mod_version_target": "mc1.21.1/dev", "engine_version": "1.0" },
  "fields": {
    "type": { "descriptor": "ConstantField", "constantValue": "create:mixing" },
    "ingredients": {
      "descriptor": "ArrayField", "optional": false,
      "elementDescriptor": {
        "descriptor": "IngredientField", "optional": false,
        "acceptedTypes": ["ITEM", "ITEM_TAG", "FLUID"],
        "subfields": {
          "amount": { "descriptor": "ScalarField", "scalarType": "INTEGER",
                      "optional": true, "defaultValue": "1000", "min": 1, "max": 2147483647 }
        }
      },
      "minItems": 1, "maxItems": 9
    },
    "results": {
      "descriptor": "ArrayField", "optional": false,
      "elementDescriptor": {
        "descriptor": "IngredientField", "optional": false,
        "acceptedTypes": ["ITEM", "FLUID"],
        "subfields": {
          "count":  { "descriptor": "ScalarField", "scalarType": "INTEGER", "optional": true, "defaultValue": "1", "min": 1, "max": 64 },
          "chance": { "descriptor": "ScalarField", "scalarType": "FLOAT",   "optional": true, "defaultValue": "1.0", "min": 0.0, "max": 1.0 },
          "amount": { "descriptor": "ScalarField", "scalarType": "INTEGER", "optional": true, "defaultValue": "1000", "min": 1, "max": 2147483647 }
        }
      },
      "minItems": 1, "maxItems": 9
    },
    "processing_time": {
      "descriptor": "ScalarField", "scalarType": "INTEGER",
      "optional": true, "defaultValue": "100", "min": 0, "max": 72000
    },
    "heat_requirement": {
      "descriptor": "ScalarField", "scalarType": "ENUM_STRING",
      "optional": true, "defaultValue": "none",
      "enumValues": ["none", "heated", "superheated"]
    }
  }
}
```

---

### Example C — Mekanism Crushing (ItemStackIngredient → ItemStack)

Recipe JSON:
```json
{
  "type": "mekanism:crushing",
  "input": { "ingredient": { "item": "minecraft:iron_ore" }, "count": 1 },
  "output": { "id": "mekanism:dust_iron", "count": 4 }
}
```

Mekanism uses `SizedIngredient.FLAT_CODEC` for inputs: a flat structure with
an item/tag key and an optional `count`. Outputs use `ItemStack` with `id`+`count`.

```json
{
  "_meta": { "type": "mekanism:crushing", "source": "bundled",
             "mod_version_target": "1.21.x", "engine_version": "1.0" },
  "fields": {
    "type":   { "descriptor": "ConstantField", "constantValue": "mekanism:crushing" },
    "input": {
      "descriptor": "IngredientField", "optional": false,
      "acceptedTypes": ["ITEM", "ITEM_TAG"],
      "subfields": {
        "count": { "descriptor": "ScalarField", "scalarType": "INTEGER",
                   "optional": true, "defaultValue": "1", "min": 1, "max": 64 }
      }
    },
    "output": {
      "descriptor": "IngredientField", "optional": false,
      "acceptedTypes": ["ITEM"],
      "subfields": {
        "count": { "descriptor": "ScalarField", "scalarType": "INTEGER",
                   "optional": true, "defaultValue": "1", "min": 1, "max": 64 }
      }
    }
  }
}
```

---

### Example D — Mekanism Reaction (multi-type: item + fluid + chemical → optional outputs)

Recipe JSON:
```json
{
  "type": "mekanism:reaction",
  "item_input":     { "ingredient": { "tag": "forge:dusts/sulfur" }, "count": 1 },
  "fluid_input":    { "fluid": "minecraft:water", "amount": 100 },
  "chemical_input": { "chemical": "mekanism:hydrogen", "amount": 100 },
  "duration":       100,
  "item_output":    { "id": "minecraft:gunpowder", "count": 1 }
}
```

`chemical_output` is absent here but is a valid optional field (see serializer).
Both `item_output` and `chemical_output` can be absent only if the other is present.

```json
{
  "_meta": {
    "type": "mekanism:reaction", "source": "bundled",
    "mod_version_target": "1.21.x", "engine_version": "1.0",
    "_note": "At least one of item_output or chemical_output must be present."
  },
  "fields": {
    "type":            { "descriptor": "ConstantField", "constantValue": "mekanism:reaction" },
    "item_input":      { "descriptor": "IngredientField", "optional": false,
                         "acceptedTypes": ["ITEM", "ITEM_TAG"],
                         "subfields": { "count": { "descriptor": "ScalarField", "scalarType": "INTEGER",
                                                   "optional": true, "defaultValue": "1", "min": 1, "max": 64 } } },
    "fluid_input":     { "descriptor": "IngredientField", "optional": false,
                         "acceptedTypes": ["FLUID"],
                         "subfields": { "amount": { "descriptor": "ScalarField", "scalarType": "INTEGER",
                                                    "optional": false, "defaultValue": "100", "min": 1, "max": 10000000 } } },
    "chemical_input":  { "descriptor": "IngredientField", "optional": false,
                         "acceptedTypes": ["CHEMICAL_GAS"],
                         "subfields": { "amount": { "descriptor": "ScalarField", "scalarType": "INTEGER",
                                                    "optional": false, "defaultValue": "100", "min": 1, "max": 10000000 } } },
    "energy_required": { "descriptor": "ScalarField", "scalarType": "INTEGER",
                         "optional": true, "defaultValue": "0", "min": 0, "max": 2147483647 },
    "duration":        { "descriptor": "ScalarField", "scalarType": "INTEGER",
                         "optional": false, "defaultValue": "100", "min": 1, "max": 1000000 },
    "item_output":     { "descriptor": "IngredientField", "optional": true,
                         "acceptedTypes": ["ITEM"],
                         "subfields": { "count": { "descriptor": "ScalarField", "scalarType": "INTEGER",
                                                   "optional": true, "defaultValue": "1", "min": 1, "max": 64 } } },
    "chemical_output": { "descriptor": "IngredientField", "optional": true,
                         "acceptedTypes": ["CHEMICAL_GAS"],
                         "subfields": { "amount": { "descriptor": "ScalarField", "scalarType": "INTEGER",
                                                    "optional": false, "defaultValue": "1000", "min": 1, "max": 10000000 } } }
  }
}
```

---

### Example E — Farmers Delight Cutting (item + tool → chance outputs, sound)

Recipe JSON:
```json
{
  "type": "farmersdelight:cutting",
  "ingredients": [{ "item": "minecraft:melon" }],
  "tool":        { "tag": "forge:tools/knives" },
  "result": [
    { "item": "minecraft:melon_slice", "count": 3 },
    { "item": "minecraft:melon_slice", "count": 1, "chance": 0.5 }
  ],
  "sound": "minecraft:block.wood.break"
}
```

The `result` list is an `ArrayField` of `ChanceField` entries (each result may
have an optional `chance`). The `sound` is a ResourceLocation free string.

```json
{
  "_meta": { "type": "farmersdelight:cutting", "source": "bundled",
             "mod_version_target": "1.21", "engine_version": "1.0" },
  "fields": {
    "type": { "descriptor": "ConstantField", "constantValue": "farmersdelight:cutting" },
    "ingredients": {
      "descriptor": "ArrayField", "optional": false,
      "elementDescriptor": {
        "descriptor": "IngredientField", "optional": false,
        "acceptedTypes": ["ITEM", "ITEM_TAG"], "subfields": {}
      },
      "minItems": 1, "maxItems": 1
    },
    "tool": {
      "descriptor": "IngredientField", "optional": false,
      "acceptedTypes": ["ITEM", "ITEM_TAG"], "subfields": {}
    },
    "result": {
      "descriptor": "ArrayField", "optional": false,
      "elementDescriptor": {
        "descriptor": "ChanceField", "optional": false,
        "ingredient": {
          "descriptor": "IngredientField", "optional": false,
          "acceptedTypes": ["ITEM"],
          "subfields": {
            "count": { "descriptor": "ScalarField", "scalarType": "INTEGER",
                       "optional": true, "defaultValue": "1", "min": 1, "max": 64 }
          }
        },
        "chance": {
          "descriptor": "ScalarField", "scalarType": "FLOAT",
          "optional": true, "defaultValue": "1.0", "min": 0.0, "max": 1.0
        }
      },
      "minItems": 1, "maxItems": 4
    },
    "sound": {
      "descriptor": "ScalarField", "scalarType": "FREE_STRING",
      "optional": true, "defaultValue": "minecraft:block.wood.break"
    }
  }
}
```

---

### Example F — AE2 Inscriber (optional top/bottom, mode enum)

Recipe JSON:
```json
{
  "type": "appeng:inscriber",
  "middle": { "item": "minecraft:diamond" },
  "top":    { "item": "ae2:silicon_press" },
  "result": { "item": "ae2:printed_silicon" },
  "mode":   "press"
}
```

`top` and `bottom` are optional. `mode` can be `"inscribe"` or `"press"`.

```json
{
  "_meta": { "type": "appeng:inscriber", "source": "bundled",
             "mod_version_target": "1.21.1", "engine_version": "1.0" },
  "fields": {
    "type":   { "descriptor": "ConstantField", "constantValue": "appeng:inscriber" },
    "top":    { "descriptor": "IngredientField", "optional": true,
                "acceptedTypes": ["ITEM", "ITEM_TAG"], "subfields": {} },
    "middle": { "descriptor": "IngredientField", "optional": false,
                "acceptedTypes": ["ITEM", "ITEM_TAG"], "subfields": {} },
    "bottom": { "descriptor": "IngredientField", "optional": true,
                "acceptedTypes": ["ITEM", "ITEM_TAG"], "subfields": {} },
    "result": {
      "descriptor": "IngredientField", "optional": false,
      "acceptedTypes": ["ITEM"],
      "subfields": {
        "count": { "descriptor": "ScalarField", "scalarType": "INTEGER",
                   "optional": true, "defaultValue": "1", "min": 1, "max": 64 }
      }
    },
    "mode": {
      "descriptor": "ScalarField", "scalarType": "ENUM_STRING",
      "optional": false, "defaultValue": "inscribe",
      "enumValues": ["inscribe", "press"]
    }
  }
}
```

---

## 7. Common mistakes

| Mistake | Fix |
|---|---|
| Omitting `"type"` field from `fields` | Add `{ "descriptor": "ConstantField", "constantValue": "<type id>" }` |
| Using `"FLUID"` in `acceptedTypes` but no `amount` in `subfields` | Add an `amount` ScalarField(INTEGER) as subfield |
| `"optional": false` on a field that the serializer marks optional | Check the codec for `optionalFieldOf` or `Optional<>` |
| Missing enum values (only listing examples seen in data) | Check the enum class source for all constants |
| Numeric `min`/`max` copied from example values instead of serializer limits | Look at validation code in the serializer, not at recipe examples |
| `"source"` not set to `"bundled"` | The registry ignores the file until this is exactly `"bundled"` |

---

## 8. Test your fragments

You can add and correct field definitions using **fragments**. A fragment is a small JSON patch
applied on top of any template (inferred or bundled) at game load time.
Fragments are stored in your game's `config/` directory, so no recompile is ever needed.

### 8.1 Where to put the file

```
<minecraft_instance>/
└── config/
    └── kubejsrecipeeditor/
        └── templates/
            └── fragments/
                ├── _global.json                  ← applies to every recipe type
                ├── create__.json                 ← applies to all  create:*  types
                └── create__mixing.json           ← applies only to create:mixing
```

Create the `fragments/` folder if it does not exist — the mod will not create it automatically.

**Naming convention** (`__` = double underscore):

| File name | Applies to |
|---|---|
| `_global.json` | All recipe types |
| `<namespace>__.json` | All types in that namespace (`<namespace>:*`) |
| `<namespace>__<path>.json` | One specific type (`<namespace>:<path>`) |
| `<namespace>__<path_a>__<path_b>.json` | Type with slash in path (`<namespace>:<path_a>/<path_b>`) |

### 8.2 Fragment structure

```json
{
  "_meta": {
    "description": "Human-readable description of what this fragment does",
    "author":      "your_name (optional)",
    "priority":    10,
    "targets":     ["create:mixing"]
  },

  "add_fields": {
    "new_field_key": {
      "descriptor":   "ScalarField",
      "scalarType":   "ENUM_STRING",
      "optional":     true,
      "defaultValue": "value_a",
      "enumValues":   ["value_a", "value_b", "value_c"],
      "display_label": "My Field"
    }
  },

  "override_fields": {
    "existing_field_key": {
      "optional":     true,
      "defaultValue": "new_default",
      "min":          0,
      "max":          100,
      "acceptedTypes": ["ITEM", "FLUID"],
      "enumValues":   ["extra_value"],
      "display_label": "Label shown in GUI"
    }
  },

  "remove_fields": ["internal_field_a", "internal_field_b"],

  "add_registry_hints": {
    "my_custom_key": {
      "contentType":   "CUSTOM",
      "registry_key":  "mymod:my_registry",
      "display_label": "My Resource"
    }
  }
}
```

| Section | What it does |
|---|---|
| `add_fields` | Adds a new field that does not exist in the current template. Error if the key already exists — use `override_fields` instead. |
| `override_fields` | Partially patches an existing field. Only the listed properties are changed; others are kept. Cannot change the `descriptor` type. |
| `remove_fields` | Hides fields from the GUI. They are still written to the exported JSON using the template's default value (`ConstantField` semantics). |
| `add_registry_hints` | Registers a new JSON key → Minecraft registry mapping so the mod can resolve the correct slot colour for custom resource types. |

`priority` (default `0`): fragments with higher priority are applied last and
win over conflicts. The built-in bundled schemas have implicit priority `0`;
set your fragment's priority to any positive integer to be sure it takes effect.

`targets` accepts three forms:
- `"create:mixing"` — one specific type
- `"create:*"` — all types in the `create` namespace
- `"*"` — every loaded recipe type

### 8.3 `add_fields` — adding a missing field

Use this when the runtime inference did not detect an optional field (because
no loaded recipe uses it) and there is no bundled schema for the type yet.

**Scenario**: Create Mixing has a `heatRequirement` field, but all the recipes
in your pack use the default `"none"`, so the inference engine never sees it and
the GUI shows no heat selector.

`create__mixing.json`:
```json
{
  "_meta": {
    "description": "Expose heat requirement selector for Create mixing recipes",
    "priority": 5,
    "targets": ["create:mixing"]
  },
  "add_fields": {
    "heat_requirement": {
      "descriptor": "ScalarField",
      "scalarType": "ENUM_STRING",
      "optional": true,
      "defaultValue": "none",
      "enumValues": ["none", "heated", "superheated"],
      "display_label": "Heat Requirement"
    }
  }
}
```

### 8.4 `override_fields` — correcting an existing field

Use this when a field is already in the template but has the wrong type,
wrong `acceptedTypes`, or a missing enum value.

**Scenario**: Mekanism Crushing shows the `input` slot as grey (CUSTOM) because
the inference engine did not recognise the chemical key. You want it purple.

`mekanism__crushing.json`:
```json
{
  "_meta": {
    "description": "Fix input slot colour for Mekanism crushing",
    "priority": 5,
    "targets": ["mekanism:crushing"]
  },
  "override_fields": {
    "input": {
      "acceptedTypes": ["ITEM", "ITEM_TAG"]
    }
  }
}
```

**Scenario**: a ScalarField was inferred as `FREE_STRING` but is actually an
enum. Add the known values:

`mymod__process.json`:
```json
{
  "_meta": {
    "description": "Fix mode field for mymod:process",
    "priority": 5,
    "targets": ["mymod:process"]
  },
  "override_fields": {
    "mode": {
      "enumValues": ["fast", "slow", "burst"],
      "defaultValue": "fast"
    }
  }
}
```

### 8.5 `remove_fields` — hiding internal fields

Use this when the GUI shows a field that the user should never edit directly
(e.g. an internal version counter or a computed hash).

```json
{
  "_meta": { "description": "Hide internal fields", "priority": 5, "targets": ["mymod:thing"] },
  "remove_fields": ["_version", "computed_hash"]
}
```

Hidden fields are not shown in the GUI but are still written to the JSON output
using the value from the original template.

### 8.6 Testing workflow

1. **Write the fragment** in `config/kubejsrecipeeditor/templates/fragments/`
   following the naming convention above.

2. **Start or resume the game** (the mod loads fragments at game boot, so if the
   game is already running skip to step 3).

3. **Reload without restarting**: run this command in chat or the server console:
   ```
   /kre regenerate_templates all
   ```
   The mod will re-read every fragment file and rebuild the in-memory templates.
   No restart, no reload screen.

4. **Open the recipe builder** with **J**, navigate to the recipe type you edited.

5. **Verify**:
   - New fields appear as the correct widget (spinner, cycle button, slot, etc.)
   - Slot colours match the `acceptedTypes` you set
   - Fluid slots (`~` indicator) appear for `FLUID` types
   - Chemical slots appear purple for `CHEMICAL_*` types
   - Removed fields are gone from the GUI

6. **Check the export**: build a recipe and click **Export**. Open the output
   `.js` file and confirm the JSON structure matches what the recipe type expects.

7. **Iterate**: edit the fragment file, run `/kre regenerate_templates all` again —
   no game restart required between iterations.
