#!/data/data/com.termux/files/usr/bin/bash
# start-streambdixplus.sh - helper for the StreamBDIX+ Launcher Android app.
#
# Usage: start-streambdixplus.sh [start|status|stop]
#   start   (default) run the NEWEST StreamBDIX+ from npm unless an instance is already running
#   status  print machine-readable status lines (used by the app)
#   stop    send SIGTERM to a running instance (only ever on explicit request)
#   __run   internal: resolves the newest version on npm and runs it inside the session. Do not call directly.
#
# Duplicate protection:
#   1. a lock directory serialises concurrent starts (e.g. double taps)
#   2. a PID file written by our own runner process
#   3. a strict /proc scan for a *node* process with an argument that is exactly
#      "streambdixplus" or a path to it (this does NOT match this script or pgrep itself)
# If tmux is installed, StreamBDIX+ runs in the tmux session "streambdixplus" so that a later
# START can re-attach to the very same session instead of starting a second process.

if (( BASH_VERSINFO[0] < 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] < 4) )); then
  echo "ERROR: bash 4.4 or newer is required (found $BASH_VERSION)." >&2
  exit 30
fi

SESSION_NAME="streambdixplus"
# Nothing is pinned any more: every start asks npm for the newest published version
# (the "latest" dist-tag) and runs exactly that one. If npm cannot be reached, the
# "@latest" tag is passed to npx instead, which resolves it itself (or uses its cache).
PKG_NAME="streambdixplus"
SELF_NAME="start-streambdixplus.sh"
STATE_DIR="${HOME:-/data/data/com.termux/files/home}/.streambdixplus"
PID_FILE="$STATE_DIR/runner.pid"
VERSION_FILE="$STATE_DIR/version"
LOCK_DIR="$STATE_DIR/start.lock"
SCRIPT_PATH="$(readlink -f "$0" 2>/dev/null)"
[ -n "$SCRIPT_PATH" ] || SCRIPT_PATH="$0"

mkdir -p "$STATE_DIR" 2>/dev/null

say()   { printf '%s\n' "$*"; }
warn()  { printf '%s\n' "$*" >&2; }
pause() { if [ -t 0 ]; then printf '\n'; read -r -p "Press Enter to close... " _; fi; }
fail()  { warn "ERROR: $2"; pause; exit "$1"; }

# ---------- /proc helpers ----------

# $1 = pid, $2 = exact argument expected somewhere in its argv
cmdline_has_arg() {
  local -a argv=()
  local a
  mapfile -d '' -t argv 2>/dev/null < "/proc/$1/cmdline" || return 1
  for a in "${argv[@]}"; do
    [ "$a" = "$2" ] && return 0
  done
  return 1
}

# $1 = pid, $2 = substring expected in its command line
cmdline_contains() {
  local cl
  cl=$(tr '\0' ' ' 2>/dev/null < "/proc/$1/cmdline") || return 1
  [[ "$cl" == *"$2"* ]]
}

# Prints the PID of our runner (the "__run" process) if the PID file is valid and alive.
runner_pid() {
  local pid=""
  [ -r "$PID_FILE" ] || return 1
  read -r pid 2>/dev/null < "$PID_FILE"
  case "$pid" in ''|*[!0-9]*) return 1 ;; esac
  [ -d "/proc/$pid" ] || return 1
  cmdline_has_arg "$pid" "__run" || return 1
  printf '%s\n' "$pid"
}

# Prints PIDs of node processes that look like StreamBDIX+ (also catches manual runs).
find_node_pids() {
  local d pid a i found
  local -a args
  for d in /proc/[0-9]*; do
    pid=${d#/proc/}
    [ "$pid" = "$$" ] && continue
    args=()
    mapfile -d '' -t args 2>/dev/null < "$d/cmdline" || continue
    [ "${#args[@]}" -ge 2 ] || continue
    case "${args[0]##*/}" in node|nodejs) ;; *) continue ;; esac
    found=0
    for ((i = 1; i < ${#args[@]}; i++)); do
      a=${args[i]}
      case "$a" in
        *start-streambdixplus*) found=0; break ;;
        streambdixplus|streambdixplus@*|*/streambdixplus|*/streambdixplus/*|*/streambdixplus@*) found=1 ;;
      esac
    done
    [ "$found" = 1 ] && printf '%s\n' "$pid"
  done
}

# Prints all descendants of PID $1.
descendants_of() {
  local root="$1" d pid stat cur p qi=0
  local -A parent=()
  local -a queue=("$root")
  for d in /proc/[0-9]*; do
    pid=${d#/proc/}
    read -r stat 2>/dev/null < "$d/stat" || continue
    stat=${stat##*) }
    set -- $stat
    parent[$pid]=$2
  done
  while (( qi < ${#queue[@]} )); do
    cur=${queue[qi]}
    qi=$((qi + 1))
    for p in "${!parent[@]}"; do
      if [ "${parent[$p]}" = "$cur" ]; then
        queue+=("$p")
        printf '%s\n' "$p"
      fi
    done
  done
}

collect_pids() {
  local rp
  rp=$(runner_pid)
  {
    find_node_pids
    [ -n "$rp" ] && descendants_of "$rp"
  } | sort -un
}

is_running() {
  [ -n "$(runner_pid)" ] || [ -n "$(find_node_pids)" ]
}

# ---------- lock ----------

acquire_lock() {
  local owner=""
  if mkdir "$LOCK_DIR" 2>/dev/null; then
    printf '%s\n' "$$" > "$LOCK_DIR/pid"
    return 0
  fi
  sleep 0.2
  read -r owner 2>/dev/null < "$LOCK_DIR/pid"
  if [ -n "$owner" ] && [ "$owner" != "$$" ] && [ -d "/proc/$owner" ] \
     && cmdline_contains "$owner" "$SELF_NAME"; then
    return 1   # another live instance of this script holds the lock
  fi
  rm -rf "$LOCK_DIR"   # stale lock
  if mkdir "$LOCK_DIR" 2>/dev/null; then
    printf '%s\n' "$$" > "$LOCK_DIR/pid"
    return 0
  fi
  return 1
}

release_lock() {
  local owner=""
  read -r owner 2>/dev/null < "$LOCK_DIR/pid"
  [ "$owner" = "$$" ] && rm -rf "$LOCK_DIR"
  return 0
}

# ---------- tmux helpers ----------

have_tmux()           { command -v tmux >/dev/null 2>&1; }
tmux_session_exists() { have_tmux && tmux has-session -t "=$SESSION_NAME" 2>/dev/null; }
attachable()          { [ -n "$(runner_pid)" ] && tmux_session_exists; }

remove_pid_file() {
  local p=""
  read -r p 2>/dev/null < "$PID_FILE"
  [ "$p" = "$$" ] && rm -f "$PID_FILE"
  return 0
}

# ---------- status extras (uptime, screen preview) ----------

# $1 = pid -> prints how many seconds it has been running (assumes CLK_TCK=100, true on Android)
proc_age_secs() {
  local pid="$1" stat up start
  read -r stat 2>/dev/null < "/proc/$pid/stat" || return 1
  stat=${stat##*) }
  # shellcheck disable=SC2086
  set -- $stat
  start=${20}
  read -r up _ 2>/dev/null < /proc/uptime || return 1
  up=${up%%.*}
  case "$start$up" in ''|*[!0-9]*) return 1 ;; esac
  echo $(( up - start / 100 ))
}

fmt_secs() {
  local s="$1" h m
  h=$((s / 3600)); m=$(( (s % 3600) / 60 )); s=$((s % 60))
  if [ "$h" -gt 0 ]; then printf '%dh %dm' "$h" "$m"
  elif [ "$m" -gt 0 ]; then printf '%dm %ds' "$m" "$s"
  else printf '%ds' "$s"; fi
}

# Last visible lines of the tmux session (plain text, no colours). Empty if there is no tmux session.
preview_text() {
  tmux_session_exists || return 0
  { tmux capture-pane -p -J -t "=${SESSION_NAME}:" -S -40 2>/dev/null \
      || tmux capture-pane -p -J -t "${SESSION_NAME}:" -S -40 2>/dev/null; } \
    | sed '/^[[:space:]]*$/d' | tail -n 10 | cut -c1-90
}

# ---------- newest version on npm ----------

# Prints the newest published version of $PKG_NAME (e.g. 1.4.2), or fails.
resolve_latest() {
  local v=""
  if command -v timeout >/dev/null 2>&1; then
    v=$(timeout 30 npm view "$PKG_NAME" version 2>/dev/null)
  else
    v=$(npm view "$PKG_NAME" version 2>/dev/null)
  fi
  v=$(printf '%s\n' "$v" | tail -n 1 | tr -d '[:space:]')
  case "$v" in
    [0-9]*.[0-9]*.[0-9]*) ;;
    *) return 1 ;;
  esac
  case "$v" in *[!0-9A-Za-z.+-]*) return 1 ;; esac
  printf '%s\n' "$v"
}

# ---------- commands ----------

cmd_run() {
  local owner="" code
  printf '%s\n' "$$" > "$PID_FILE"
  read -r owner 2>/dev/null < "$LOCK_DIR/pid"
  [ "$owner" = "$$" ] && rm -rf "$LOCK_DIR"     # direct mode: we inherited the lock via exec
  trap remove_pid_file EXIT
  # Ctrl+C reaches this shell too. Without a handler, bash kills itself as soon as the child dies
  # from SIGINT, so the "[exited]" line and "Press Enter to close..." below were never reached.
  # A trap (not "ignore") keeps default SIGINT behaviour for npx/node, which are separate processes.
  trap ':' INT
  rm -f "$STATE_DIR/last_exit"
  cd "${HOME:-/}" 2>/dev/null
  local ver spec
  say "Checking npm for the newest $PKG_NAME ..."
  if ver=$(resolve_latest); then
    spec="$PKG_NAME@$ver"
    printf '%s\n' "$ver" > "$VERSION_FILE"
    say "Newest version: $ver"
  else
    spec="$PKG_NAME@latest"
    rm -f "$VERSION_FILE"
    say "Could not reach npm to look up the version. Letting npx resolve @latest."
  fi
  say
  say "=== StreamBDIX+: running npx --yes --package=$spec $PKG_NAME ==="
  say "(Ctrl+C stops it. Inside tmux, press Ctrl+b then d to detach and leave it running.)"
  say
  npx --yes --package="$spec" "$PKG_NAME"
  code=$?
  printf '%s\n' "$code" > "$STATE_DIR/last_exit"
  remove_pid_file
  say
  say "[StreamBDIX+ exited with code $code]"
  pause
  exit "$code"
}

cmd_start() {
  if ! acquire_lock; then
    say "Another start is already in progress. Not starting a second instance."
    pause
    exit 0
  fi
  trap release_lock EXIT

  if is_running; then
    local pids
    pids=$(collect_pids | tr '\n' ' ')
    release_lock
    trap - EXIT
    say "StreamBDIX+ is already running (PID: ${pids% }). Not starting another instance."
    if attachable; then
      say "Re-attaching to the existing session..."
      sleep 1
      unset TMUX
      exec tmux attach-session -t "=$SESSION_NAME"
    fi
    say "It runs in another Termux session. Open the Termux session list to see it."
    pause
    exit 0
  fi

  command -v node >/dev/null 2>&1 || fail 20 "Node.js not found. In Termux run: pkg install nodejs"
  command -v npx  >/dev/null 2>&1 || fail 21 "npx not found. It ships with npm. In Termux run: pkg install nodejs"

  if have_tmux; then
    # Nothing is running (checked above), so a leftover session is a dead shell. Remove it.
    tmux_session_exists && tmux kill-session -t "=$SESSION_NAME" 2>/dev/null
    # 41 columns so the box fits the launcher's phone-sized live view (StreamBDIX+ shrinks to the terminal width).
    tmux new-session -d -x 41 -y 40 -s "$SESSION_NAME" -c "${HOME:-/}" \
      "exec $(printf '%q' "$BASH") $(printf '%q' "$SCRIPT_PATH") __run" \
      || fail 31 "Could not create the tmux session."
    local i
    for i in $(seq 1 50); do
      [ -n "$(runner_pid)" ] && break
      sleep 0.1
    done
    if [ -z "$(runner_pid)" ]; then
      tmux kill-session -t "=$SESSION_NAME" 2>/dev/null
      fail 32 "The StreamBDIX+ runner did not start inside tmux."
    fi
    release_lock
    trap - EXIT
    unset TMUX
    exec tmux attach-session -t "=$SESSION_NAME"
  else
    say "Tip: run 'pkg install tmux' so the launcher can re-attach to this session next time."
    trap - EXIT          # exec drops traps anyway; the runner releases the lock
    exec "$BASH" "$SCRIPT_PATH" __run
  fi
}

cmd_status() {
  local pids rp pid age last="" np ver
  pids=$(collect_pids | tr '\n' ' ')
  rp=$(runner_pid)
  if command -v node >/dev/null 2>&1; then echo "NODE=ok"; else echo "NODE=missing"; fi
  if command -v npx  >/dev/null 2>&1; then echo "NPX=ok";  else echo "NPX=missing";  fi
  if have_tmux; then echo "TMUX=installed"; else echo "TMUX=missing"; fi
  if [ -n "$pids" ] || [ -n "$rp" ]; then
    echo "STATE=RUNNING"
    pid=${rp:-${pids%% *}}
    age=$(proc_age_secs "$pid" 2>/dev/null)
    if [ -n "$age" ] && [ "$age" -ge 0 ] 2>/dev/null; then
      echo "UPTIME=$(fmt_secs "$age")"
      echo "STARTED=$(( $(date +%s) - age ))"
    fi
    np=$(find_node_pids | head -n 1)
    echo "NODE_PID=${np:-${pids%% *}}"
    ver=""
    [ -r "$VERSION_FILE" ] && read -r ver 2>/dev/null < "$VERSION_FILE"
    case "$ver" in ''|*[!0-9A-Za-z.+-]*) ;; *) echo "VERSION=$ver" ;; esac
  else
    echo "STATE=STOPPED"
    [ -r "$STATE_DIR/last_exit" ] && read -r last 2>/dev/null < "$STATE_DIR/last_exit"
    case "$last" in ''|*[!0-9]*) ;; *) echo "LAST_EXIT=$last" ;; esac
  fi
  echo "PIDS=${pids% }"
  if attachable; then echo "ATTACHABLE=yes"; else echo "ATTACHABLE=no"; fi
  echo "@@PREVIEW@@"
  preview_text
  exit 0
}

cmd_stop() {
  local rp pids i
  rp=$(runner_pid)
  pids=$(
    {
      find_node_pids
      [ -n "$rp" ] && descendants_of "$rp"
    } | sort -un | tr '\n' ' '
  )
  if [ -z "${pids// /}" ]; then
    say "StreamBDIX+ is not running."
    tmux_session_exists && tmux kill-session -t "=$SESSION_NAME" 2>/dev/null
    echo "STATE=STOPPED"
    exit 0
  fi
  say "Sending SIGTERM to PID: ${pids% }"
  # shellcheck disable=SC2086
  kill -TERM $pids 2>/dev/null
  for i in $(seq 1 10); do
    sleep 1
    is_running || break
  done
  if is_running; then
    say "StreamBDIX+ did not exit within 10 s. To force it: kill -9 ${pids% }"
    echo "STATE=RUNNING"
    exit 1
  fi
  tmux_session_exists && tmux kill-session -t "=$SESSION_NAME" 2>/dev/null
  say "StreamBDIX+ stopped."
  echo "STATE=STOPPED"
  exit 0
}

case "${1:-start}" in
  start)  cmd_start ;;
  status) cmd_status ;;
  stop)   cmd_stop ;;
  __run)  cmd_run ;;
  *)      warn "Usage: $SELF_NAME [start|status|stop]"; exit 64 ;;
esac
