# Latch & Label

Client-side Fabric mod for chest tagging and item finding.

## Features
- Tag placed inventory blocks (any block entity inventory, plus ender chests) with categories.
- Quick apply (`Shift+B`) and quick clear (`Ctrl+B`) for looked-at containers.
- Category picker UI with search, numeric hotkeys, and recent category row.
- Container screen tag button for chest-like screens.
- `/find` command with optional block-variant expansion.
- Temporary world highlights for find results.
- Optional clickable find overlay list.
- Shift tooltip category display for mapped items.

## Keybinds
- `B`: Open category picker for targeted container.
- `Shift+B`: Apply last-used category.
- `Ctrl+B`: Clear tag.
- `Alt` hold or sneak: Inspect mode active state.

## Tagging Rules
- Tagging applies to placed container blocks only.
- Current supported targets include any placed inventory block entity, plus ender chests.
- Data is client-local and stored in the client config directory.

## Inspect Mode Visuals
- Inspect mode is active while holding Alt or sneaking.
- Find highlights render as centered half-block boxes.
- Exact matches and variant matches use different visual styles.

## `/find` Usage
- `/find`: uses main hand item.
- `/find <itemid>`
- `/find <itemid> <radius>`

Notes:
- Scans are client-side.
- Results are limited to nearby loaded storage blocks.
- Inventory matching relies on client-visible inventory data.

## Config Files
All config files are under:
- `config/latchlabel/`

Primary files:
- `client_config.json`
- `categories.json`
- `tags.json`
- `item_categories_overrides.json`

Reload config in-game with:
- `/latchlabel reload`

Validate localization key coverage:
- `./scripts/check_i18n.sh`

## Development Notes
- This mod is client-only and uses a `client` entrypoint in `fabric.mod.json`.
