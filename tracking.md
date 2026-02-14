# Project: Latch & Label - Chest tagging + find (client-side Fabric 1.21.10+ mod)

## Epic 0 — Repo & Build

### Issue 001 — Initialize client-only Fabric mod skeleton

**Goal:** A clean Fabric project that loads on client and never loads server-only classes.

**DoD**

* Game launches with mod enabled in singleplayer.
* Joining a multiplayer server with only the client mod does not crash.
* `fabric.mod.json` uses a **client** entrypoint only.

**Tasks**

* [x] Generate Fabric template (Gradle, Loom) for target MC + Fabric API
* [x] Set `fabric.mod.json` entrypoint under `"client"`
* [x] Create package layout: `client/` vs `common/` (avoid server classloading)
* [x] Add basic logging + mod id constants
* [x] Add placeholder client initializer

---

### Issue 002 — Add Fabric API modules and optional config dependencies

**Goal:** Add the APIs needed for commands, tooltips, screens, rendering.

**DoD**

* Build succeeds.
* Fabric API modules compile.
* Optional ModMenu/config compiles (or is stubbed behind compat checks).

**Tasks**

* [x] Add Fabric API dependency (matching MC version)
* [x] Confirm access to:

  * [x] Client commands v2
  * [x] Screen events
  * [x] Item tooltip callback
  * [x] World render events
* [x] (Optional) Add ModMenu
* [x] (Optional) Add Cloth Config / AutoConfig (or plan JSON-only config)

---

## Epic 1 — Data & Persistence

### Issue 010 — Define category + chest tag data model

**Goal:** Stable data structures for categories and chest tags.

**DoD**

* Category class supports id, name, color, icon item id, order, visibility.
* ChestKey supports dimension + BlockPos and stable string key.

**Tasks**

* [x] Create `Category` model
* [x] Create `ChestKey` model:

  * [x] `Identifier dimensionId`
  * [x] `BlockPos pos`
  * [x] `toStringKey()` / `fromStringKey()`
* [x] Create `TagStore` (map ChestKey -> CategoryId)
* [x] Store `lastUsedCategoryId`

---

### Issue 011 — Implement JSON persistence for categories and tags

**Goal:** Load/save on client with debounced writes.

**DoD**

* On startup, categories/tags load from `config/<modid>/...`
* Tag changes save within ~1s (debounced)
* Clean shutdown saves pending changes

**Tasks**

* [x] Choose file paths:

  * [x] `config/<modid>/categories.json`
  * [x] `config/<modid>/tags.json`
* [x] Implement JSON read/write with version field
* [x] Implement debounced save scheduler
* [x] Add “create defaults if missing”
* [x] Add minimal migration stub (version check + fallback)

---

### Issue 012 — Default categories & icons

**Goal:** Ship a sensible default taxonomy and visuals.

**DoD**

* Fresh install creates default categories list.
* Each category has:

  * [x] name
  * [x] color
  * [x] icon item id fallback (e.g. `minecraft:stone`)

**Tasks**

* [x] Define default categories (~12–18)
* [x] Pick icon items per category
* [x] Pick colors per category
* [x] Ensure categories are ordered and visible

---

## Epic 2 — Inputs & Targeting

### Issue 020 — Register keybinds + input handling

**Goal:** Keys work reliably without spamming.

**Keybinds**

* `B` open picker (targeted container)
* `Shift+B` quick apply last-used
* `Ctrl+B` clear
* `Alt` inspect-mode hold

**DoD**

* Pressing keys does the right thing only once per press (no repeat spam).
* Keys do nothing when not targeting a taggable container (except toast if desired).

**Tasks**

* [x] Register keybindings
* [x] Implement edge-triggered press detection
* [x] Implement “inspect mode active” state (alt or crouch)
* [x] Optional: configurable keybinds via config

---

### Issue 021 — Raycast targeting + taggable container whitelist

**Goal:** A single utility that resolves “what container block am I looking at?”

**DoD**

* `getTargetedContainer()` returns `(dimension, pos)` when crosshair targets:

  * chest, trapped chest, barrel, shulker box (placed), etc.
* Returns empty for non-container blocks/entities.

**Tasks**

* [x] Implement raycast from camera
* [x] Validate block is in whitelist
* [x] Ensure it works for double chests (pick the clicked half’s pos)

---

## Epic 3 — Tagging UI

### Issue 030 — Implement category picker overlay (search + grid)

**Goal:** Fast HUD picker usable from world and container UI button.

**DoD**

* Picker opens with a target `ChestKey`.
* Search field is focused immediately.
* Click category or press `1–9` selects.
* Enter confirms, Esc cancels.
* Shows recently used row.

**Tasks**

* [x] Create overlay UI component (HUD-drawn or minimal Screen)
* [x] Render list/grid of categories with icon + color
* [x] Implement search filter
* [x] Implement hotkeys `1–9`
* [x] Implement “recently used”
* [x] Provide callbacks: `onSelect(categoryId)`, `onClear()`, `onCancel()`

---

### Issue 031 — Apply tag / clear tag from picker + toast feedback

**Goal:** Tag changes persist and provide non-chat feedback.

**DoD**

* Selecting a category sets chest tag + saves.
* Clearing removes tag + saves.
* Toast appears: “Tagged: <Category>” / “Tag cleared”.

**Tasks**

* [x] Connect picker selection to TagStore update
* [x] Update lastUsedCategoryId
* [x] Trigger debounced save
* [x] Show client toast UI (or overlay message)

---

### Issue 032 — Add container-screen “tag button” (top-left)

**Goal:** Tagging works “within it” via a floating button in the chest UI.

**DoD**

* In chest/barrel/shulker screens, top-left button appears.
* Hover shows current category.
* Click opens picker bound to that container’s position.
* If position cannot be resolved, button disables with tooltip.

**Tasks**

* [x] Hook ScreenEvents AFTER_INIT for handled screens
* [x] Add button widget at top-left (non-overlapping)
* [x] Style button: category icon + color border
* [x] Implement click -> open picker
* [x] Implement right-click -> clear (optional)

---

### Issue 033 — Resolve container BlockPos while inside GUI

**Goal:** Given an open container screen, resolve the world BlockPos reliably.

**DoD**

* Chest/barrel/shulker placed block screens resolve BlockPos in typical cases.
* Fallback behavior is safe (disabled button) when unresolved.

**Tasks**

* [x] Track last interacted blockpos when opening container (hook interaction)
* [x] Attempt to resolve from screen handler when possible
* [x] Implement fallback logic + debug logging toggle

---

## Epic 4 — Inspect-mode Rendering (Category visuals)

### Issue 040 — Implement inspect-mode renderer (nearby tagged containers)

**Goal:** When crouching or holding Alt, show category color edge overlay.

**DoD**

* When inspect-mode active (crouch OR Alt):

  * containers within range show colored edge overlay.
* When inspect-mode inactive:

  * nothing renders.
* Performance is reasonable in storage rooms (bounded rendering).

**Tasks**

* [x] Add config: `inspectRange` default 8
* [x] Gather nearby tagged containers in loaded chunks within range
* [x] Render colored outline/edge overlay per container
* [x] Ensure it doesn’t render through the whole world unbounded
* [x] Cap max renders per frame (optional)

---

### Issue 041 — Focused icon billboard (inspect-mode + crosshair target)

**Goal:** Only when targeting a tagged container, show a floating icon in front.

**DoD**

* With inspect-mode active AND crosshair targets a tagged container:

  * floating icon appears in front face
  * icon border matches category color
* Category name text appears only when Alt is held (not crouch alone)

**Tasks**

* [x] Determine chest face closest to camera or use camera-facing offset
* [x] Render icon (use item icon or custom sprite)
* [x] Render colored border
* [x] Render optional label text (Alt-only)

---

## Epic 5 — `/find` (client-side find + highlights)

### Issue 050 — Register `/find` client command + argument parsing

**Goal:** Command exists and resolves query + radius.

**DoD**

* `/find` works:

  * no args -> uses mainhand item
  * `/find <itemid>` works
  * `/find <itemid> <radius>` works
* Error shown if no args and mainhand empty.

**Tasks**

* [x] Register client command
* [x] Parse args:

  * [x] item id (`minecraft:...`)
  * [x] optional radius int
* [x] Implement mainhand fallback
* [x] Implement user feedback for errors (toast preferred)

---

### Issue 051 — Variant expansion rules engine

**Goal:** Convert a “searched item” into a set of matching items (exact + variants).

**DoD**

* Given a target item, returns:

  * `Set<Item>` matchSet
  * `boolean usedVariants` (or per-item match reason)
* Default: for block items, include stairs/slabs/wall/fence/gate/button/pressure plate
* Config toggle to disable variant expansion.

**Tasks**

* [x] Create `VariantMatcher` service
* [x] Implement default variant families
* [x] Add config toggles / future hooks
* [x] Provide per-result flag: exact vs variant

---

### Issue 052 — Scan nearby tagged containers for matches (loaded chunks only)

**Goal:** Find which tagged containers contain any items from matchSet.

**DoD**

* `/find` scans within radius but only considers:

  * loaded chunks
  * containers that have accessible client-side inventory data
* Produces list of results with:

  * `BlockPos`
  * `exactOrVariant` flag
  * distance

**Tasks**

* [x] Iterate tagged containers within radius
* [x] Skip if chunk not loaded
* [x] Read inventory:

  * [x] from block entity inventory when available
  * [x] from open screen handler when relevant (optional)
* [x] Compare stacks to matchSet
* [x] Return results list

---

### Issue 053 — Render `/find` highlights (half-block centered box)

**Goal:** Show temporary highlights without chat spam.

**DoD**

* After `/find`, matching containers render highlight for N seconds (default 10).
* Highlight is a half-block centered box.
* Exact matches: solid
* Variant matches: dashed or pulsing distinct style
* Expire automatically.

**Tasks**

* [x] Create `FindResultState` with expiry timestamps
* [x] Add renderer in world render event
* [x] Implement half-block AABB box rendering
* [x] Implement style differences (solid vs dashed/pulse)
* [x] Add config: highlight duration

---

### Issue 054 — Optional clickable results list overlay (config-gated)

**Goal:** Optional on-screen list; default off.

**DoD**

* Config toggle enables overlay list after `/find`.
* List is distance-sorted.
* Clicking an entry focuses/pings that container (brief vertical line or extra pulse).

**Tasks**

* [x] Add config: `enableFindOverlayList` default false
* [x] Implement overlay UI listing results
* [x] Click handler triggers “focus ping”
* [x] Ensure overlay auto-dismisses on expiry or Esc

---

## Epic 6 — Item tooltip category

### Issue 060 — Add Shift tooltip line: item category

**Goal:** When holding Shift on an item tooltip, show its category.

**DoD**

* Shift held: tooltip includes `Category: <name>`
* Not held: tooltip unchanged
* Mapping uses:

  * default item->category mapping shipped
  * user overrides file

**Tasks**

* [x] Register ItemTooltipCallback
* [x] Implement “is shift held” check
* [x] Implement category lookup for item
* [x] Append styled tooltip line

---

### Issue 061 — Item->category mapping + overrides

**Goal:** Provide a configurable mapping that users can edit.

**DoD**

* Default mapping exists (creative-ish).
* Overrides file merges on load.
* Invalid entries are ignored safely.

**Tasks**

* [x] Define mapping file format (JSON)
* [x] Load mapping on init
* [x] Implement override merge strategy
* [x] Add basic validation + logging

---

## Epic 7 — Config & UX polish

### Issue 070 — Config screen (optional ModMenu) or JSON-only docs

**Goal:** Users can edit ranges, toggles, and categories.

**DoD (choose one)**

* **Option A:** ModMenu config screen exists and works
* **Option B:** Documented JSON config with reload command

**Tasks**

* [x] Add config options:

  * [x] inspect range
  * [x] default find radius
  * [x] highlight duration
  * [x] enable clickable list
  * [x] variant matching toggle(s)
* [x] Implement config reload hook

---

### Issue 071 — Localization + UI text cleanup

**Goal:** All strings are translatable; no hardcoded text.

**DoD**

* `en_us.json` contains all UI strings, tooltips, toasts, command feedback.
* No raw English strings in code (except debug logs).

**Tasks**

* [x] Add lang file
* [x] Replace literals with translation keys
* [x] Verify strings display correctly

---

## Epic 8 — QA & Release

### Issue 080 — Singleplayer smoke tests

**DoD**

* World key tagging works
* GUI tag button works
* Inspect-mode overlay works (crouch/Alt)
* Focus icon works
* `/find` highlights correct containers
* Variant matches show distinct style
* Tooltip category shows on Shift

**Tasks**

* [x] Write a manual test checklist doc
* [ ] Run through checklist on fresh world + existing world

---

### Issue 081 — Multiplayer client-only safety tests

**DoD**

* Can join a server with the mod only on client.
* No server-side crashes / classloading errors.
* `/find` behaves sensibly with loaded-chunks constraint.

**Tasks**

* [ ] Test on a vanilla server
* [ ] Test on a modded server (if you use one)
* [ ] Fix any client/server classloading issues

---

### Issue 082 — Performance pass + bounds

**DoD**

* Inspect-mode overlay does not tank FPS in a storage room.
* `/find` scan is limited to loaded chunks and doesn’t freeze.

**Tasks**

* [x] Add render cap (max containers per frame) if needed
* [x] Add scan cap / early exit if needed
* [x] Add debug toggle to log timings

---

### Issue 090 — Packaging + README

**DoD**

* Build produces a jar.
* README includes:

  * keybinds
  * tagging rules (placed containers only)
  * inspect-mode visuals
  * `/find` usage + loaded-chunks limitation
  * config locations

**Tasks**

* [x] Add mod icon + metadata
* [x] Write README
* [ ] Build release jar

---

## Suggested milestone order for Codex

1. 001 → 002 → 010 → 011 → 012
2. 020 → 021
3. 030 → 031
4. 033 → 032 (pos resolution first, then button)
5. 040 → 041
6. 050 → 051 → 052 → 053 → 054
7. 060 → 061
8. 070 → 071
9. 080 → 081 → 082 → 090
