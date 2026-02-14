# Manual Test Checklist

## Environment
- Minecraft: 1.21.10
- Fabric Loader: compatible with project settings
- Mod: `latchlabel`

## Singleplayer Smoke
- Start a fresh singleplayer world with the mod enabled.
- Confirm client startup has no crashes.
- Place chest, barrel, and shulker box.

## Tagging Flow
- Look at a placed container and press `B`.
- Verify picker opens with focused search.
- Select with mouse and with `1-9`.
- Press Enter to confirm selection.
- Press Backspace/Delete to clear.
- Press Esc to cancel.
- Verify `Shift+B` applies the last used category.
- Verify `Ctrl+B` clears the tag.

## Container Screen Button
- Open a chest/barrel/shulker screen.
- Verify left-side tag button appears.
- If resolvable, click button and verify picker opens for that container.
- If not resolvable, verify button is disabled and has tooltip.

## Inspect Mode
- Hold Alt and verify inspect-mode can be activated.
- Sneak and verify inspect-mode can be activated.
- Ensure no inspect visuals when neither Alt nor sneak is active.

## Find Command
- Run `/find` with non-empty main hand item.
- Run `/find minecraft:stone`.
- Run `/find minecraft:stone 32`.
- Verify invalid item id reports an error.
- Verify empty main hand `/find` reports an error.
- Verify only loaded chunks are considered.
- Verify results include exact/variant distinctions in output.
- Verify highlights render and expire automatically.

## Find Overlay List (Config-Gated)
- Set `enableFindOverlayList` to true in client config.
- Reload config using `/latchlabel reload`.
- Run `/find` and verify distance-sorted list appears.
- Click a list entry and verify focus ping effect.
- Press Esc and verify overlay dismisses.

## Tooltip Category
- Hover mapped items while holding Shift.
- Verify `Category: <name>` line appears.
- Verify no category line without Shift.

## Config + Reload
- Edit `config/latchlabel/client_config.json` values.
- Run `/latchlabel reload`.
- Verify updated behavior for find radius/highlight duration/variant toggle.

## Multiplayer Client-Only Safety
- Join a server with mod only on client.
- Verify no classloading or connection crashes.

