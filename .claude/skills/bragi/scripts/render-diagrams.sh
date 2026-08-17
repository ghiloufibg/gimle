#!/usr/bin/env bash
# Renders every .d2 source under gimle-docs/diagrams/ into gimle-docs/static/diagrams/ with the
# site's house style. Multi-board sources (steps:/scenarios:/layers:) render twice: an animated
# SVG (<name>.svg) and a static final-board SVG (<name>-static.svg) for pages where readers need
# to study the end state rather than watch it build up.
#
# Usage: render-diagrams.sh [file.d2 ...]   (no args = all sources)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../../.." && pwd)"
SRC_DIR="${REPO_ROOT}/gimle-docs/diagrams"
OUT_DIR="${REPO_ROOT}/gimle-docs/static/diagrams"

# --sketch: hand-drawn house style; --theme=0 + --dark-theme=200: one SVG adapts to the site's
# light/dark color mode via prefers-color-scheme.
HOUSE_FLAGS=(--sketch --theme=0 --dark-theme=200 --pad=20)
ANIMATE_INTERVAL="${ANIMATE_INTERVAL:-1200}"

if ! command -v d2 >/dev/null 2>&1; then
  "${SCRIPT_DIR}/install-d2.sh"
  export PATH="${D2_INSTALL_DIR:-$HOME/.local/bin}:$PATH"
fi

if [ "$#" -gt 0 ]; then
  sources=("$@")
else
  [ -d "$SRC_DIR" ] || { echo "no diagram sources at ${SRC_DIR}" >&2; exit 1; }
  mapfile -t sources < <(find "$SRC_DIR" -name '*.d2' | sort)
fi
[ "${#sources[@]}" -gt 0 ] || { echo "no .d2 sources found" >&2; exit 1; }

mkdir -p "$OUT_DIR"
for src in "${sources[@]}"; do
  name="$(basename "$src" .d2)"
  if grep -qE '^\s*(steps|scenarios|layers)\s*:' "$src"; then
    d2 "${HOUSE_FLAGS[@]}" --animate-interval="$ANIMATE_INTERVAL" "$src" "${OUT_DIR}/${name}.svg"
    # Also render just the final board: steps compose cumulatively, so the last declared step is
    # the complete picture — the right thing to embed where readers study the end state.
    kind="$(grep -oEm1 '^(steps|scenarios)' "$src" || true)"
    final_board="$(awk '/^steps:|^scenarios:/{inblock=1; next} inblock && /^  [^ ]+ *: *\{/{k=$1; gsub(/[: {].*/,"",k); last=k} END{print last}' "$src")"
    if [ -n "$kind" ] && [ -n "$final_board" ]; then
      d2 "${HOUSE_FLAGS[@]}" --target="${kind}.${final_board}" "$src" "${OUT_DIR}/${name}-static.svg"
      echo "rendered ${name}.svg (animated) + ${name}-static.svg"
    else
      echo "rendered ${name}.svg (animated; skipped static final board — top-level ${kind:-board} keys not found)"
    fi
  else
    d2 "${HOUSE_FLAGS[@]}" "$src" "${OUT_DIR}/${name}.svg"
    echo "rendered ${name}.svg"
  fi
done
