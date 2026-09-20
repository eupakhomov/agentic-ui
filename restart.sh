#!/usr/bin/env bash
#
# Stop any running agentic-ui backend, build it, and start it again.
# Linux and macOS only. See CLAUDE.md "Run the project" / "Stop / kill" for background.
#
# Usage: ./restart.sh [--full] [--skip-build]
#   --full        Also rebuild the frontend (slow on first run / after frontend changes).
#                 Default reuses frontend/dist for a fast incremental backend build.
#   --skip-build  Skip the Maven build entirely and just (re)start the existing jar.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

PID_FILE=/tmp/agentic-ui.pid
TOKEN_FILE=/tmp/agentic-ui.token
LOG_FILE=/tmp/agentic-ui.log
PORT=8080

FULL_BUILD=0
SKIP_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --full) FULL_BUILD=1 ;;
    --skip-build) SKIP_BUILD=1 ;;
    -h|--help)
      grep '^#' "$0" | sed -n '2,10p' | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "Unknown option: $arg" >&2
      exit 1
      ;;
  esac
done

log() { printf '%s\n' "$*"; }

stop_running() {
  if [ -f "$PID_FILE" ]; then
    local pid
    pid="$(cat "$PID_FILE")"
    if kill -0 "$pid" 2>/dev/null; then
      log "Stopping running backend (pid $pid)..."
      kill "$pid"
      for _ in $(seq 1 30); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
      done
      if kill -0 "$pid" 2>/dev/null; then
        log "Backend did not stop gracefully within 30s, sending SIGKILL..."
        kill -9 "$pid" 2>/dev/null || true
      fi
    fi
    rm -f "$PID_FILE"
  fi

  # Fallback: anything still listening on $PORT (stale/missing pid file, or
  # a backend started outside this script).
  local port_pid=""
  if command -v lsof >/dev/null 2>&1; then
    port_pid="$(lsof -ti tcp:"$PORT" 2>/dev/null || true)"
  elif command -v ss >/dev/null 2>&1; then
    port_pid="$(ss -tlnp 2>/dev/null | grep ":$PORT " | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2 || true)"
  fi
  if [ -n "$port_pid" ]; then
    log "Killing process still listening on :$PORT (pid $port_pid)..."
    kill "$port_pid" 2>/dev/null || true
    sleep 2
    kill -9 "$port_pid" 2>/dev/null || true
  fi

  # Orphaned sidecar processes, if any survive the backend shutdown.
  # (bracket in the pattern keeps this pkill from matching its own command line)
  pkill -f "dist/index[.]js --cwd" 2>/dev/null || true
}

ensure_db() {
  if command -v docker >/dev/null 2>&1; then
    log "Ensuring Postgres is up (docker compose)..."
    docker compose up -d || log "Warning: 'docker compose up -d' failed — make sure Postgres is running manually (see CLAUDE.md 'Database')."
  else
    log "docker not found on PATH — make sure Postgres is running manually (see CLAUDE.md 'Database')."
  fi
}

build_jar() {
  if [ "$SKIP_BUILD" = 1 ]; then
    log "Skipping build (--skip-build)."
    return
  fi

  local mvn_cmd="./mvnw"
  if [ "$(uname)" = "Darwin" ] && command -v mvn >/dev/null 2>&1; then
    # mvnw loses its executable bit across some git checkouts on macOS; use the system mvn.
    mvn_cmd="mvn"
  fi

  local build_args=(package -DskipTests)
  if [ "$FULL_BUILD" = 1 ]; then
    log "Building backend + frontend (--full)..."
  else
    build_args+=(-Dskip.installnodenpm -Dskip.npm)
    log "Building backend (fast: reusing frontend/dist)..."
  fi

  "$mvn_cmd" "${build_args[@]}"
}

start_app() {
  local jar
  jar="$(ls target/agentic.ui-*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)"
  if [ -z "$jar" ]; then
    echo "No jar found in target/ — build failed, or was skipped (--skip-build) before any build ever ran." >&2
    exit 1
  fi

  local token
  if [ -n "${AGENTIC_UI_TOKEN:-}" ]; then
    token="$AGENTIC_UI_TOKEN"
    log "Token: $token (from AGENTIC_UI_TOKEN env — reused, not regenerated)"
  else
    token="$(head -c 24 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 20)"
    log "Token: $token"
  fi
  echo "$token" > "$TOKEN_FILE"

  log "Starting backend from $jar..."
  AGENTIC_UI_TOKEN="$token" nohup java -jar "$jar" --server.address=0.0.0.0 > "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"

  log "Waiting for backend to become healthy..."
  for _ in $(seq 1 120); do
    if curl -sf "http://localhost:$PORT/actuator/health" >/dev/null 2>&1; then
      log ""
      log "UI:    http://localhost:$PORT"
      log "Token: $token"
      return
    fi
    sleep 1
  done

  echo "Backend did not become healthy within 120s — check $LOG_FILE" >&2
  exit 1
}

stop_running
ensure_db
build_jar
start_app
