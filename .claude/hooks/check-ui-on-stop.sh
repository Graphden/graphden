#!/usr/bin/env bash
# Stop hook for Claude Code: when UI files (.js / .css under
# resources/packages/app/editor/) have been modified in the current
# working tree, run biome + stylelint with error-only severity. If
# anything fails, exit 2 with stderr — Claude is then instructed to
# fix before finishing.
#
# Warnings (e.g., the legacy ~80 biome `let` reassignments) are
# allowed; only true errors block.
#
# To bypass once: `git stash` your edits or commit them. To disable
# permanently, remove the Stop entry from .claude/settings.json.

set -uo pipefail

PROJECT_ROOT="/root/projects/graphden"
cd "$PROJECT_ROOT" || exit 0

# Read JSON payload from stdin (Claude Code's hook contract).
payload=$(cat 2>/dev/null || true)

# Avoid recursive triggers: if we already fired once and Claude is
# re-entering Stop, just bail.
if [ -n "$payload" ]; then
  active=$(printf '%s' "$payload" | python3 -c \
    'import sys,json
try:
    d=json.load(sys.stdin)
    print(d.get("stop_hook_active", False))
except Exception:
    print(False)' 2>/dev/null || echo "False")
  if [ "$active" = "True" ] || [ "$active" = "true" ]; then
    exit 0
  fi
fi

# Working-tree diff vs HEAD plus any untracked files that match the
# editor pattern. Untracked = newly-added .js / .css that biome /
# stylelint would otherwise miss.
ui_re='^resources/packages/app/editor/.*\.(js|css)$'
modified=$( { git diff --name-only HEAD 2>/dev/null; \
              git ls-files --others --exclude-standard 2>/dev/null; } \
            | grep -E "$ui_re" | sort -u || true )

if [ -z "$modified" ]; then
  exit 0
fi

js_files=$(printf '%s\n' "$modified" | grep -E '\.js$' || true)
css_files=$(printf '%s\n' "$modified" | grep -E '\.css$' || true)

biome_out=""
stylelint_out=""
biome_failed=0
stylelint_failed=0

if [ -n "$js_files" ]; then
  # --diagnostic-level=error: warnings (the legacy `let`/optional-chain
  # backlog) don't block the hook. New errors do.
  # shellcheck disable=SC2086
  if ! biome_out=$(npx --yes biome check \
                       --diagnostic-level=error \
                       --max-diagnostics=999 \
                       $js_files 2>&1); then
    biome_failed=1
  fi
fi

if [ -n "$css_files" ]; then
  # shellcheck disable=SC2086
  if ! stylelint_out=$(npx --yes stylelint $css_files 2>&1); then
    stylelint_failed=1
  fi
fi

if [ "$biome_failed" -eq 0 ] && [ "$stylelint_failed" -eq 0 ]; then
  exit 0
fi

{
  echo "═══ UI checks failed (Stop hook) ═══"
  echo ""
  echo "Modified UI files:"
  printf '  - %s\n' $modified
  echo ""
  if [ "$biome_failed" -eq 1 ]; then
    echo "── biome (errors) ──"
    echo "$biome_out"
    echo ""
  fi
  if [ "$stylelint_failed" -eq 1 ]; then
    echo "── stylelint ──"
    echo "$stylelint_out"
    echo ""
  fi
  echo "Fix above before stopping. Reproduce with: bb biome / bb stylelint."
} >&2

exit 2
