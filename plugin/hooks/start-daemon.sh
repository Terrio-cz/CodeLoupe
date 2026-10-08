#!/usr/bin/env bash
# SessionStart: make sure the CodeLoupe daemon runs, so the MCP server answers from the first tool call.
# Never fails the session: a missing install or a failed start only means the tools are unavailable.
# Silent unless CODELOUPE_HOOK_VERBOSE is set (stdout of a SessionStart hook reaches the model).

bin="${CODELOUPE_BIN:-codeloupe}"

if ! command -v "$bin" >/dev/null 2>&1; then
  [ -n "$CODELOUPE_HOOK_VERBOSE" ] && echo "CodeLoupe is not installed: '$bin' is not on PATH (set CODELOUPE_BIN to its launcher)." >&2
  exit 0
fi

out=$("$bin" start 2>&1) || {
  [ -n "$CODELOUPE_HOOK_VERBOSE" ] && echo "CodeLoupe daemon did not start: $out" >&2
  exit 0
}
[ -n "$CODELOUPE_HOOK_VERBOSE" ] && echo "$out"
exit 0
