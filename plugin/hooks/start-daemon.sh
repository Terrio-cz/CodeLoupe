#!/usr/bin/env bash
# SessionStart: make sure the CodeLoupe daemon runs, so the MCP server answers from the first tool call, then ask it for the
# context a session starts with (the repository map and the state of the worktree; hook.sh prints it).
# Never fails the session: a missing install or a failed start only means the tools are unavailable.
# Silent unless CODELOUPE_HOOK_VERBOSE is set (stdout of a SessionStart hook reaches the model).

bin="${CODELOUPE_BIN:-codeloupe}"
IFS= read -r -d '' input

if ! command -v "$bin" >/dev/null 2>&1; then
  [ -n "$CODELOUPE_HOOK_VERBOSE" ] && echo "CodeLoupe is not installed: '$bin' is not on PATH (set CODELOUPE_BIN to its launcher)." >&2
elif out=$("$bin" start </dev/null 2>&1); then
  [ -n "$CODELOUPE_HOOK_VERBOSE" ] && echo "$out"
else
  [ -n "$CODELOUPE_HOOK_VERBOSE" ] && echo "CodeLoupe daemon did not start: $out" >&2
fi

# A daemon started by the desktop app is as good as one started here.
bash "$(dirname "${BASH_SOURCE[0]}")/hook.sh" <<<"$input"
exit 0
