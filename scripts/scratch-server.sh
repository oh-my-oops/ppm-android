#!/bin/bash
# A throwaway PPM server for the app to test against: its own PPM_HOME under /tmp (never the
# real ~/.ppm), one project called `demo`, a random password and no Claude or Codex login (a chat
# turn there fails instead of spending anything; use a mock session). It listens on 127.0.0.1, which
# the emulator reaches as 10.0.2.2; set PPM_SCRATCH_HOST (a Tailscale or LAN address) for a
# phone. Runs the server of the checkout this script is in.
#
#   android/scripts/scratch-server.sh start [port]   prints "<url> <password>"
#   android/scripts/scratch-server.sh stop
#   android/scripts/scratch-server.sh env            the running one's URL / PASSWORD / PROJECT
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
state=/tmp/ppm-android-scratch.env

running_pid() {
  [[ -f "$state" ]] || return 1
  # shellcheck disable=SC1090
  source "$state"
  # Only a server started by this script: the pid must still be running with our PPM_HOME.
  tr '\0' '\n' < "/proc/$PID/environ" 2>/dev/null | grep -qx "PPM_HOME=$HOME_DIR" || return 1
  echo "$PID"
}

case "${1:-}" in
  start)
    if pid=$(running_pid); then
      echo "already running (pid $pid): $URL" >&2
      exit 1
    fi
    port="${2:-8937}"
    if ss -ltn | grep -q ":$port\b"; then
      echo "port $port is taken" >&2
      exit 1
    fi
    host="${PPM_SCRATCH_HOST:-127.0.0.1}"
    home_dir=$(mktemp -d /tmp/ppm-android-scratch.XXXX)
    mkdir -p "$home_dir/demo"
    password=$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')
    cd "$repo"
    env -u NOTIFY_SOCKET PPM_HOME="$home_dir" bun src/index.ts config set auth.token "$password" >/dev/null
    # No AI login reaches it: text typed into the wrong box must not start a real Claude or Codex
    # turn. The clean environment also keeps the caller's CLAUDE_* and NOTIFY_SOCKET out of it.
    mkdir -m 700 "$home_dir/no-claude" "$home_dir/no-codex"
    env -i HOME="$HOME" PATH="$PATH" PPM_HOME="$home_dir" \
      CLAUDE_CONFIG_DIR="$home_dir/no-claude" CODEX_HOME="$home_dir/no-codex" \
      setsid nohup bun src/index.ts __serve__ "$port" "$host" \
      > "$home_dir/serve.log" 2>&1 < /dev/null &
    pid=$!
    url="http://$host:$port"
    for _ in $(seq 1 60); do
      curl -s -m 1 "$url/api/health" > /dev/null && break
      sleep 0.5
    done
    curl -s -X POST -H "Authorization: Bearer $password" -H 'Content-Type: application/json' \
      -d "{\"path\":\"$home_dir/demo\",\"name\":\"demo\"}" "$url/api/projects" > /dev/null
    umask 077
    printf 'PID=%s\nHOME_DIR=%s\nURL=%s\nPASSWORD=%s\nPROJECT=demo\n' "$pid" "$home_dir" "$url" "$password" > "$state"
    echo "$url $password"
    ;;
  stop)
    if pid=$(running_pid); then
      kill "$pid"
      for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.25; done
    fi
    if [[ -f "$state" ]]; then
      # shellcheck disable=SC1090
      source "$state"
      [[ "$HOME_DIR" == /tmp/ppm-android-scratch.* ]] && rm -rf "$HOME_DIR"
      rm -f "$state"
    fi
    ;;
  env)
    running_pid > /dev/null || { echo "not running" >&2; exit 1; }
    cat "$state"
    ;;
  *)
    sed -n '2,10p' "$0"
    exit 2
    ;;
esac
