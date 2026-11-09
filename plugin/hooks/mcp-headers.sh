#!/usr/bin/env bash
# headersHelper of the plugin's MCP entry: prints the headers Claude Code sends to the CodeLoupe daemon, as JSON. The daemon token is
# among them once the daemon has proved it holds it; a daemon from before the token existed gets the plain header it still accepts.
# Never fails: the worst case is the plain header, with which the read-only tools work and the others say what they need.
#
# This runs once, when the client connects: it cannot put right a connection that goes stale later. A window that
# connected while an older daemon held the port keeps the headers printed here, so after that daemon is replaced every
# call is refused, and no run of this helper changes that (CL-260). The daemon answers such a call on /mcp with a
# JSON-RPC error that names reconnecting (/mcp, Reconnect) or a new window as the fix; what this helper can do is get
# the token right the first time, which is why it starts the daemon below rather than printing the plain header.

here=${BASH_SOURCE[0]//\\//} # mcp-headers.cmd passes a Windows path, whose backslashes `%/*` would not see
. "${here%/*}/daemon-auth.sh"
codeloupe_dir
port="${CODELOUPE_PORT:-47391}"

# The token is made by the daemon: start it when the MCP server connects before the session's own start has run.
if [ ! -r "$CL_DIR/daemon.token" ] && command -v "${CODELOUPE_BIN:-codeloupe}" >/dev/null 2>&1; then
  "${CODELOUPE_BIN:-codeloupe}" start </dev/null >/dev/null 2>&1
fi

if command -v curl >/dev/null 2>&1 && codeloupe_prove "$CL_DIR" "$port"; then
  printf '{"x-codeloupe":"1","x-codeloupe-token":"%s"}\n' "$CL_TOKEN"
else
  printf '{"x-codeloupe":"1"}\n'
fi
exit 0
