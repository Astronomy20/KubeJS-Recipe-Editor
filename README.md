# KubeJS Recipe Editor

Recipe builder with visual editing, tagging, and instant KubeJS export.

## Requirements
- KubeJS
- JEI (bundled - installed automatically if not already present)

## Usage

### Building a custom recipe
1. Press **J** (configurable in Controls → KubeJS Recipe Editor) to open the recipe builder
2. Select a mod namespace to expand its categories, then click a category
3. Drag items from the JEI panel into the ingredient slots
4. **Right-click** a filled slot to select a tag or adjust item count
5. **Drag** items between slots to reorder
6. Configure extra parameters (heat requirement, processing time, XP, etc.) with the **-/+** buttons
7. Click **Export** to append the recipe to the output file

### Fluid slots (modded recipes)
- Drag a **fluid** from JEI onto a slot that accepts fluids (e.g. Create Mixing water input)
- A blue `~` indicator appears to confirm fluid mode
- **Right-click** the fluid slot to adjust the amount in millibuckets (100 mB steps, Shift = 1000 mB)

### Tags as ingredients
- Right-click a filled slot and select a tag from the list
- A `#` indicator appears on the slot
- Output slots always use the specific item (tags not allowed on output)

### Export All
From the main screen, click **Export All** to export every loaded recipe to KubeJS format.
A status message shows the result: `Exported N types, M recipes`.

## Output Paths

| Recipe type                      | Output file                                   |
|----------------------------------|-----------------------------------------------|
| Vanilla crafting, smelting, etc. | `kubejs/server_scripts/vanilla/<type>.js`     |
| Modded recipes                   | `kubejs/server_scripts/<namespace>/<type>.js` |
| Fuel (burn time)                 | `kubejs/startup_scripts/fuel.js`              |

## Tag Editor

Click **Tags** in the main screen to open the tag editor.
- Browse and edit item, block, and fluid tags
- Create new tags or add/remove entries
- Output: `kubejs/data/<pack>/tags/<type>/<tag_name>.json`

## Recipe Browser

Click **Browse** in the main screen to browse loaded KubeJS script files.
Quickly locate and remove previously exported recipes.

## Cache

Template data (slot layout, extra parameters) is cached in `config/kubejsrecipeeditor_cache.json`.
The cache is automatically invalidated when:
- The mod version changes
- The installed mod list changes (any mod added, removed, or updated)

To force manual regeneration: run the command `/kre regenerate_cache`.

---

## Adding support for new recipe types

To add or improve GUI support for a recipe type (slot colours, optional fields,
enum values, fluid/chemical inputs), write a **fragment schema** JSON file.

See **[RECIPE_SCHEMA_GUIDE.md](RECIPE_SCHEMA_GUIDE.md)** for the full format
reference and worked examples covering Create, Mekanism, AE2, Farmers Delight, and more.