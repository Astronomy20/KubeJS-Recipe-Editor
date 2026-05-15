# Fragment Schema Guide

This document explains how to write a **fragment** to customize and extend recipe templates
for KubeJS Recipe Editor. Fragments allow you to add missing fields, correct field types,
hide internal fields, and register custom resource types — all without modifying source code
or restarting the game.

---

## 1. Priority levels

| Priority | Source | Location |
|---|---|---|
| 1 (lowest) | Runtime inference | Generated at game load from actual recipe JSON corpus |
| 2 | Bundled schema | Applied by the mod for known recipe types |
| 3 (highest) | **User fragment** | `config/kubejsrecipeeditor/templates/fragments/` |

User fragments override both inferred and bundled templates, giving you complete control
over how each recipe type appears in the GUI.

---

## 2. File location and naming

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

---

## 3. Fragment structure

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
| `remove_fields` | Hides fields from the GUI. They are still written to the exported JSON using the template's default value. |
| `add_registry_hints` | Registers a new JSON key → Minecraft registry mapping so the mod can resolve the correct slot colour for custom resource types. |

### 3.1 `_meta` fields

| Key | Required | Description |
|---|---|---|
| `description` | no | Human-readable text explaining what the fragment does. Shown in debug logs. |
| `author` | no | Your name or identifier. |
| `priority` | no | Default: `0`. Fragments with higher priority are applied last and win over conflicts. Set to any positive integer to ensure your fragment takes effect. |
| `targets` | yes | Array of recipe type IDs or patterns this fragment applies to. Supports `"namespace:type"`, `"namespace:*"`, or `"*"`. |

---

## 4. FieldDescriptor reference

### 4.1 `ConstantField`

A value that is always the same — the user never edits it.
The GUI hides it; the JSON output always copies it from the template.

```json
{ "descriptor": "ConstantField", "constantValue": "create:mixing" }
```

**Always use this for the `"type"` field in bundled schemas.**
Fragments typically do not add or override constant fields.

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

## 5. Fragment operations

### 5.1 `add_fields` — adding a missing field

Use this when the runtime inference did not detect an optional field (because
no loaded recipe uses it) and there is no bundled schema for the type yet.

**Scenario**: Create Mixing has a `heatRequirement` field, but all the recipes
in your pack use the default `"none"`, so the inference engine never sees it and
the GUI shows no heat selector.

**File**: `config/kubejsrecipeeditor/templates/fragments/create__mixing.json`

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

---

### 5.2 `override_fields` — correcting an existing field

Use this when a field is already in the template but has the wrong type,
wrong `acceptedTypes`, or a missing enum value.

**Scenario**: Mekanism Crushing shows the `input` slot as grey (CUSTOM) because
the inference engine did not recognise the item key. You want it white.

**File**: `config/kubejsrecipeeditor/templates/fragments/mekanism__crushing.json`

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

**File**: `config/kubejsrecipeeditor/templates/fragments/mymod__process.json`

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

**Scenario**: expand numeric limits on a field.

```json
{
  "_meta": {
    "description": "Allow larger stack sizes",
    "priority": 5,
    "targets": ["mymod:*"]
  },
  "override_fields": {
    "stack_size": {
      "min": 1,
      "max": 999
    }
  }
}
```

---

### 5.3 `remove_fields` — hiding internal fields

Use this when the GUI shows a field that the user should never edit directly
(e.g. an internal version counter or a computed hash).

```json
{
  "_meta": {
    "description": "Hide internal fields",
    "priority": 5,
    "targets": ["mymod:thing"]
  },
  "remove_fields": ["_version", "computed_hash"]
}
```

Hidden fields are not shown in the GUI but are still written to the JSON output
using the value from the original template.

---

### 5.4 `add_registry_hints` — registering custom resource types

Use this when a recipe field contains a custom resource type that the mod does not
recognise natively (not `ITEM`, `FLUID`, `CHEMICAL_GAS`, etc.). This allows the GUI
to render the slot in the correct colour and connect it to the right registry.

```json
{
  "_meta": {
    "description": "Register custom resources for mymod",
    "priority": 5,
    "targets": ["mymod:*"]
  },
  "add_registry_hints": {
    "catalyst": {
      "contentType": "CUSTOM",
      "registry_key": "mymod:catalyst",
      "display_label": "Catalyst"
    },
    "modifier": {
      "contentType": "CUSTOM",
      "registry_key": "mymod:modifier",
      "display_label": "Modifier"
    }
  }
}
```

The `registry_key` should match a Minecraft registry that the mod registers.
The `display_label` is shown in tooltips and debug output.

---

## 6. Testing workflow

1. **Write the fragment** in `config/kubejsrecipeeditor/templates/fragments/`
   following the naming convention in §2.

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
   - Enum values appear in cycle buttons or dropdowns

6. **Check the export**: build a recipe and click **Export**. Open the output
   `.js` file and confirm the JSON structure matches what the recipe type expects.

7. **Iterate**: edit the fragment file, run `/kre regenerate_templates all` again —
   no game restart required between iterations.

---

## 7. Common patterns

### Pattern A — Expose a hidden optional field (no bundled schema)

Use `add_fields` when a recipe type has no bundled schema and an optional field
is never seen in the loaded recipes.

```json
{
  "_meta": {
    "description": "Expose optional fields for recipe type",
    "priority": 5,
    "targets": ["modname:recipetype"]
  },
  "add_fields": {
    "optional_field_1": {
      "descriptor": "ScalarField",
      "scalarType": "ENUM_STRING",
      "optional": true,
      "defaultValue": "default_value",
      "enumValues": ["default_value", "alt_1", "alt_2"]
    },
    "optional_field_2": {
      "descriptor": "ScalarField",
      "scalarType": "INTEGER",
      "optional": true,
      "defaultValue": "100",
      "min": 1,
      "max": 1000
    }
  }
}
```

---

### Pattern B — Fix inference for a common mod

Use `override_fields` to correct how the inference engine classified fields.

```json
{
  "_meta": {
    "description": "Fix field types for modname recipes",
    "priority": 10,
    "targets": ["modname:*"]
  },
  "override_fields": {
    "input_item": {
      "acceptedTypes": ["ITEM", "ITEM_TAG"]
    },
    "input_fluid": {
      "acceptedTypes": ["FLUID"]
    },
    "processing_mode": {
      "scalarType": "ENUM_STRING",
      "enumValues": ["fast", "normal", "slow"]
    }
  }
}
```

---

### Pattern C — Hide debug/internal fields

Use `remove_fields` to declutter the GUI.

```json
{
  "_meta": {
    "description": "Hide internal/debug fields",
    "priority": 5,
    "targets": ["modname:*"]
  },
  "remove_fields": ["_version", "_internal_hash", "debug_flag"]
}
```

---

### Pattern D — Register mod-specific registries

Use `add_registry_hints` for custom resource types.

```json
{
  "_meta": {
    "description": "Register custom registries for modname",
    "priority": 5,
    "targets": ["modname:*"]
  },
  "add_registry_hints": {
    "component": {
      "contentType": "CUSTOM",
      "registry_key": "modname:component",
      "display_label": "Component"
    },
    "enchantment": {
      "contentType": "CUSTOM",
      "registry_key": "minecraft:enchantment",
      "display_label": "Enchantment"
    }
  }
}
```

---

## 8. Common mistakes

| Mistake | Fix |
|---|---|
| Typo in namespace or type name in `targets` | Double-check against `/kre debug_recipes` output or JEI |
| Using `add_fields` on a key that already exists | Use `override_fields` instead, or check if the field is already in the template |
| Forgetting to set `priority` higher than bundled schema (0) | Add `"priority": 5` (or higher) to your fragment |
| Changing `descriptor` type in `override_fields` | Not allowed. Create a new field with `add_fields` and remove the old one with `remove_fields` if needed. |
| Not running `/kre regenerate_templates all` after editing | The command is required; changes are not applied automatically until the next game restart or you run it. |
| Malformed JSON (missing commas, quotes) | Use a JSON validator or your editor's JSON extension |
| `acceptedTypes` includes `FLUID` but no `amount` subfield | Fluids need an `amount` field; add it to `subfields` |

---

## 9. Debugging

### Check what templates are loaded

Run this command in chat or console:
```
/kre debug_recipes <namespace>
```

Example output:
```
create:mixing [bundled (priority 0)]
  fields: ingredients, results, processing_time, heat_requirement
  
mekanism:crushing [inferred (priority 1)]
  fields: input, output, secondary_output
  
mymod:thing [fragment (priority 5)]
  fields: id, data, count
```

If your fragment is not listed, check:
- File is in `config/kubejsrecipeeditor/templates/fragments/`
- Filename follows the naming convention
- JSON is valid (use a JSON linter)
- `targets` in `_meta` matches the recipe type ID

### View template details

```
/kre debug_templates create:mixing
```

Shows the full field definitions (FieldDescriptor structures) for the template.

### Reload fragments live

```
/kre regenerate_templates all
```

Reads all fragment files and rebuilds templates without restarting the game.

---

## 10. Fragment file examples

### Example 1 — Add missing enum field

**File**: `config/kubejsrecipeeditor/templates/fragments/create__mixing.json`

```json
{
  "_meta": {
    "description": "Expose heat requirement selector",
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

---

### Example 2 — Fix inferred slot colours

**File**: `config/kubejsrecipeeditor/templates/fragments/mekanism__reaction.json`

```json
{
  "_meta": {
    "description": "Fix slot colours for Mekanism reaction",
    "priority": 10,
    "targets": ["mekanism:reaction"]
  },
  "override_fields": {
    "chemical_input": {
      "acceptedTypes": ["CHEMICAL_GAS"]
    },
    "chemical_output": {
      "acceptedTypes": ["CHEMICAL_GAS"]
    },
    "item_input": {
      "acceptedTypes": ["ITEM", "ITEM_TAG"]
    },
    "item_output": {
      "acceptedTypes": ["ITEM"]
    }
  }
}
```

---

### Example 3 — Fix enum inference + hide internal fields

**File**: `config/kubejsrecipeeditor/templates/fragments/farmersdelight__.json`

```json
{
  "_meta": {
    "description": "Fix Farmers Delight recipe fields",
    "priority": 8,
    "targets": ["farmersdelight:*"]
  },
  "override_fields": {
    "sound": {
      "scalarType": "ENUM_STRING",
      "enumValues": [
        "minecraft:block.wood.break",
        "minecraft:block.stone.break",
        "minecraft:item.crop.plant",
        "minecraft:item.crop.harvest"
      ],
      "defaultValue": "minecraft:block.wood.break"
    }
  },
  "remove_fields": ["_version", "_author"]
}
```

---

### Example 4 — Register custom registries

**File**: `config/kubejsrecipeeditor/templates/fragments/appeng__.json`

```json
{
  "_meta": {
    "description": "Register AE2 custom types",
    "priority": 5,
    "targets": ["appeng:*"]
  },
  "add_registry_hints": {
    "crystal": {
      "contentType": "CUSTOM",
      "registry_key": "ae2:crystal",
      "display_label": "AE2 Crystal"
    },
    "energy": {
      "contentType": "CUSTOM",
      "registry_key": "ae2:energy_cell",
      "display_label": "Energy Cell"
    }
  }
}
```

---

### Example 5 — Global fixes for all recipes

**File**: `config/kubejsrecipeeditor/templates/fragments/_global.json`

```json
{
  "_meta": {
    "description": "Global overrides for all recipe types",
    "priority": 1,
    "targets": ["*"]
  },
  "remove_fields": ["debug_id", "internal_nbt"],
  "override_fields": {
    "count": {
      "min": 1,
      "max": 999
    }
  }
}
```

---

## 11. When to use fragments vs. reporting an issue

- **Use a fragment** if you want to patch a specific recipe type in your game instance.
- **Report an issue** if you find a bundled schema is incomplete or incorrect.
- **Report an issue** if the inference engine consistently misidentifies a field type.

Fragments are for customization; bundled schemas are for mod support at the source.
