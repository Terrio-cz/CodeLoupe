#!/usr/bin/env bash
# Forwards the JSON Claude Code gives a hook on stdin to the CodeLoupe daemon (POST /hook) and prints the daemon's reply.
# The daemon decides everything; this script only finds it. It fails open: no daemon, no curl, a slow or broken answer
# all mean "nothing to add", exit 0 and no output, so a hook can never stop a session or delay it by more than the timeouts below.
#
#   CODELOUPE_HOOKS=off   skip every CodeLoupe hook (also "hooks": {"enabled": false} in the daemon's config.json)
#   CODELOUPE_PORT        the daemon's port; without it the port in daemon.json of the daemon's directory is used
#   CODELOUPE_HOME        the daemon's directory; default is the same place the daemon uses

[ "$CODELOUPE_HOOKS" = off ] && exit 0
command -v curl >/dev/null 2>&1 || exit 0

port="$CODELOUPE_PORT"
if [ -z "$port" ]; then
  dir="$CODELOUPE_HOME"
  if [ -z "$dir" ]; then
    case "$OSTYPE" in
      msys*|cygwin*|win*) dir="${LOCALAPPDATA:-$USERPROFILE/AppData/Local}/codeloupe" ;;
      darwin*) dir="$HOME/Library/Caches/codeloupe" ;;
      *) dir="${XDG_CACHE_HOME:-$HOME/.cache}/codeloupe" ;;
    esac
  fi
  # No daemon.json: no daemon is running, and nothing is waited for.
  [ -r "$dir/daemon.json" ] || exit 0
  info=$(<"$dir/daemon.json")
  port=${info#*\"port\":}
  port=${port%%[!0-9]*}
fi
[ -n "$port" ] || exit 0

# A tool call is judged in milliseconds; a session start may wait for the repository map.
IFS= read -r -d '' body
wait=2
case "$body" in *SessionStart*) wait=10 ;; esac

curl -sf --connect-timeout 0.3 -m "$wait" -H 'x-codeloupe: 1' -H 'content-type: application/json' --data-binary @- "http://127.0.0.1:$port/hook" <<<"$body" 2>/dev/null
exit 0
