#!/usr/bin/env bash
# Forwards the JSON Claude Code gives a hook on stdin to the CodeLoupe daemon (POST /hook) and prints the daemon's reply.
# The daemon decides everything; this script only finds it. It fails open: no daemon, no curl, a slow or broken answer
# all mean "nothing to add", exit 0 and no output, so a hook can never stop a session or delay it by more than the timeouts below.
#
#   CODELOUPE_HOOKS=off   skip every CodeLoupe hook (also "hooks": {"enabled": false} in the daemon's config.json)
#   CODELOUPE_PORT        the daemon's port; without it the port in daemon.json of the daemon's directory is used
#   CODELOUPE_HOME        the daemon's directory; default is the same place the daemon uses
#
# With daemon.token in that directory the daemon first proves it holds the token (an answer to a nonce), then the token is sent.
# Without the file (a daemon from before the token) the script talks to the port as it always did.

[ "$CODELOUPE_HOOKS" = off ] && exit 0
command -v curl >/dev/null 2>&1 || exit 0

. "${BASH_SOURCE[0]%/*}/daemon-auth.sh"
codeloupe_dir

port="$CODELOUPE_PORT"
if [ -z "$port" ]; then
  # No daemon.json: no daemon is running, and nothing is waited for.
  [ -r "$CL_DIR/daemon.json" ] || exit 0
  info=$(<"$CL_DIR/daemon.json")
  port=${info#*\"port\":}
  port=${port%%[!0-9]*}
fi
[ -n "$port" ] || exit 0

# A daemon that made a token must prove it holds it before a prompt or a command is sent to it: a process that took the port after a
# crash cannot, and gets nothing. The token goes to curl as a header file, never on a command line other users can read.
auth=()
codeloupe_prove "$CL_DIR" "$port"
case $? in 0) auth=(-H "@$CL_TOKEN_FILE") ;; 2) ;; *) exit 0 ;; esac

# A tool call is judged in milliseconds; a session start may wait for the repository map.
IFS= read -r -d '' body
wait=2
# Only the event name counts: a Bash command that mentions SessionStart is still a tool call.
case "$body" in *'"hook_event_name":"SessionStart"'*|*'"hook_event_name": "SessionStart"'*) wait=10 ;; esac

# No proxy: the body holds prompts and commands and goes to this machine only.
reply=$(curl -sf --noproxy '*' --connect-timeout 0.3 -m "$wait" -H 'x-codeloupe: 1' "${auth[@]}" -H 'content-type: application/json' --data-binary @- "http://127.0.0.1:$port/hook" <<<"$body" 2>/dev/null) || exit 0

# True when $1 is the inside of a JSON string: every quote in it is escaped.
plain() { local s=${1//\\?/}; [[ $s != *\"* ]]; }

# Only the three shapes the daemon writes go on to Claude Code. Whatever else listens on the port cannot approve a tool call
# (permissionDecision allow), rewrite its input (updatedInput) or stop the session (continue).
safe() {
  local r=$1 rest event text
  case $r in
    '{"systemMessage":"'*'"}')
      text=${r#'{"systemMessage":"'}; plain "${text%'"}'}" ;;
    '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"'*'"}}')
      text=${r#'{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"'}; plain "${text%'"}}'}" ;;
    '{"hookSpecificOutput":{"hookEventName":"'*'","additionalContext":"'*'"}}')
      rest=${r#'{"hookSpecificOutput":{"hookEventName":"'}; event=${rest%%\"*}
      [[ $event =~ ^[A-Za-z]+$ ]] || return 1
      text=${rest#"$event"'","additionalContext":"'}; plain "${text%'"}}'}" ;;
    *) return 1 ;;
  esac
}

safe "$reply" && printf '%s' "$reply"
exit 0
