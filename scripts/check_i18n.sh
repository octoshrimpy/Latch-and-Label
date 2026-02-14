#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
LANG_FILE="$ROOT_DIR/src/main/resources/assets/latchlabel/lang/en_us.json"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# Keys referenced by Text.translatable("...") in Java sources.
rg --no-filename -o --replace '$1' 'Text\.translatable\("([^"]+)"' "$ROOT_DIR/src" | sort -u > "$TMP_DIR/referenced_translatable.txt"

# Also include keybinding translation ids that are passed as string constants.
{
  rg --no-filename -o --replace '$1' '"(key\.latchlabel\.[^"]+)"' "$ROOT_DIR/src"
  rg --no-filename -o --replace '$1' '"(key\.categories\.latchlabel)"' "$ROOT_DIR/src"
} | sort -u > "$TMP_DIR/referenced_keybinds.txt"

cat "$TMP_DIR/referenced_translatable.txt" "$TMP_DIR/referenced_keybinds.txt" | sort -u > "$TMP_DIR/referenced_all.txt"

# Only enforce this mod's keys; ignore vanilla/mod-external translation ids.
rg '^(latchlabel\.|screen\.latchlabel\.|key\.latchlabel\.|key\.categories\.latchlabel$|modmenu\.nameTranslation\.latchlabel$)' \
  "$TMP_DIR/referenced_all.txt" > "$TMP_DIR/referenced_filtered.txt" || true

# Extract keys defined in en_us.json.
rg --no-filename -o --replace '$1' '^\s*"([^"]+)"\s*:' "$LANG_FILE" | sort -u > "$TMP_DIR/defined.txt"

comm -23 "$TMP_DIR/referenced_filtered.txt" "$TMP_DIR/defined.txt" > "$TMP_DIR/missing.txt" || true

if [ -s "$TMP_DIR/missing.txt" ]; then
  echo "Missing localization keys:" >&2
  cat "$TMP_DIR/missing.txt" >&2
  exit 1
fi

echo "i18n check passed: all referenced keys exist in en_us.json"
