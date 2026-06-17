#!/usr/bin/env bash
# PreToolUse hook for Claude Code: when Edit/Write targets any
# `resources/packages/**/impls.clj`, inject a system-reminder telling
# the model to load `graphden-packages-quality` before proceeding.
#
# Why: composition logic (Ring wraps, cache orchestration, multi-step
# pipelines) regularly sneaks into `impls.clj` as innocent-looking
# `defn-` helpers — and the skill catches that pattern, but only if
# loaded. The hook makes loading non-optional: every touch of impls
# gets the reminder. Hook never blocks, only informs.
#
# Output shape (PreToolUse contract):
#   {"hookSpecificOutput": {"hookEventName": "PreToolUse",
#                           "additionalContext": "…"}}

set -uo pipefail

payload=$(cat 2>/dev/null || true)
[ -z "$payload" ] && exit 0

file_path=$(printf '%s' "$payload" | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
    print(d.get("tool_input", {}).get("file_path", ""))
except Exception:
    pass
' 2>/dev/null)

# Only fire on resources/packages/**/impls.clj
case "$file_path" in
  */resources/packages/*/impls.clj) ;;
  *) exit 0 ;;
esac

python3 - <<'PY'
import json
print(json.dumps({
  "hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "additionalContext": (
      "Editing a `resources/packages/**/impls.clj` file. Load the "
      "`graphden-packages-quality` skill via the Skill tool BEFORE "
      "proceeding (or confirm it's already in this turn's context). "
      "It catches the most common impls-file pitfall: private `defn-` "
      "helpers that quietly accrete composition (Ring wraps, cache "
      "orchestration, multi-step pipelines) which should be expressed "
      "as fn-defs over small base-fns — pattern reference: "
      "`:branch-routing-wrap` in `web/branch-router/fns.edn`. See "
      "§3.3 in the skill for grep heuristics and the refactor recipe."
    )
  }
}))
PY
