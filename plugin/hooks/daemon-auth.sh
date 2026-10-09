# Sourced by hook.sh and mcp-headers.sh: finds the daemon's directory and checks who answers on its port before the token goes there.
#
# codeloupe_dir               sets CL_DIR: CODELOUPE_HOME, else the place the daemon uses
# codeloupe_prove DIR PORT    0: the daemon on PORT proved it holds DIR/daemon.token; CL_TOKEN holds the value and CL_TOKEN_FILE is a path
#                             curl can read as a header file (-H @file), so the secret never appears on a command line
#                             2: DIR has no daemon.token (a daemon from before the token existed): nothing to prove, nothing to send
#                             1: the token file is unusable, or whatever answers on PORT cannot prove it holds the token

codeloupe_dir() {
  CL_DIR="$CODELOUPE_HOME"
  [ -n "$CL_DIR" ] && return
  # The daemon takes this home as its own (it follows the variable too); without one (cron, a minimal environment) both use the passwd entry, which ~ expands to.
  local home
  if [ -n "$HOME" ]; then home=$HOME; else home=~; fi
  case "$OSTYPE" in
    msys*|cygwin*|win*) CL_DIR="${LOCALAPPDATA:-$USERPROFILE/AppData/Local}/codeloupe" ;;
    darwin*) CL_DIR="$home/Library/Caches/codeloupe" ;;
    *) CL_DIR="${XDG_CACHE_HOME:-$home/.cache}/codeloupe" ;;
  esac
}

# Hash of stdin, first field; whichever tool the machine has.
codeloupe_sha256() {
  local out
  if command -v sha256sum >/dev/null 2>&1; then out=$(sha256sum)
  elif command -v shasum >/dev/null 2>&1; then out=$(shasum -a 256)
  elif command -v openssl >/dev/null 2>&1; then out=$(openssl dgst -sha256 -r)
  else return 1; fi
  printf '%s' "${out%% *}"
}

# The value of the x-codeloupe-proof header in the header block on stdin.
codeloupe_proof_of() {
  local line
  shopt -s nocasematch
  while IFS= read -r line; do
    line=${line%$'\r'}
    case $line in x-codeloupe-proof:*) line=${line#*:}; printf '%s' "${line// /}"; return ;; esac
  done
}

codeloupe_prove() {
  local dir=$1 port=$2 token nonce want got
  CL_TOKEN=; CL_TOKEN_FILE=
  [ -r "$dir/daemon.token" ] || return 2
  token=$(<"$dir/daemon.token")
  token=${token%$'\r'}
  token=${token##* }
  [[ $token =~ ^[A-Za-z0-9_-]{32,128}$ ]] || return 1
  nonce="$$${RANDOM}${RANDOM}${RANDOM}"
  want=$(printf '%s' "codeloupe-proof:$token:$nonce" | codeloupe_sha256) || return 1
  got=$(curl -s --noproxy '*' --connect-timeout 0.3 -m 1 -D - -o /dev/null -H "x-codeloupe-nonce: $nonce" "http://127.0.0.1:$port/status" 2>/dev/null | codeloupe_proof_of)
  [ -n "$want" ] && [ "$got" = "$want" ] || return 1
  CL_TOKEN=$token
  CL_TOKEN_FILE="$dir/daemon.token"
  command -v cygpath >/dev/null 2>&1 && CL_TOKEN_FILE=$(cygpath -m "$CL_TOKEN_FILE")
  return 0
}
