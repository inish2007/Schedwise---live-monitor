#!/usr/bin/env bash
# SchedWise Native Linux Desktop Application Runner
# Launches SchedWise in a dedicated borderless App Mode window with automatic backend lifecycle management.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

# 1. Detect Browser with App Mode Support
BROWSER=""
for candidate in \
  /usr/bin/google-chrome \
  /usr/bin/google-chrome-stable \
  /usr/bin/microsoft-edge-stable \
  /usr/bin/chromium \
  /usr/bin/chromium-browser \
  "$(which brave-browser 2>/dev/null || true)"; do
  if [[ -n "$candidate" && -x "$candidate" ]]; then
    BROWSER="$candidate"
    break
  fi
done

if [[ -z "$BROWSER" ]]; then
  echo "Error: No compatible Chromium-based browser found (google-chrome, chromium, edge, brave)." >&2
  echo "Please install google-chrome or chromium to run SchedWise in App Mode." >&2
  exit 1
fi

# 2. Check or Start Spring Boot Backend
BACKEND_ALREADY_RUNNING=false
if curl -s -m 2 -o /dev/null -w "%{http_code}" "http://127.0.0.1:8080/api/capabilities" | grep -qE "200|401|403"; then
  BACKEND_ALREADY_RUNNING=true
else
  mkdir -p "$PROJECT_DIR/data"
  # Source Java 21 environment
  if [[ -f "$PROJECT_DIR/scripts/java-env.sh" ]]; then
    # shellcheck disable=SC1091
    source "$PROJECT_DIR/scripts/java-env.sh"
  fi

  JAR_FILE="$PROJECT_DIR/backend/target/schedwise-0.1.0.jar"
  if [[ ! -f "$JAR_FILE" ]]; then
    echo "Building SchedWise packaged binary..."
    "$PROJECT_DIR/backend/mvnw" -f "$PROJECT_DIR/backend/pom.xml" -B package -DskipTests
  fi

  echo "Starting SchedWise background daemon..."
  nohup java -Dschedwise.data="$PROJECT_DIR/data" -jar "$JAR_FILE" > "$PROJECT_DIR/data/backend-desktop.log" 2>&1 &
  BACKEND_PID=$!
  echo "$BACKEND_PID" > "$PROJECT_DIR/data/schedwise-desktop.pid"

  # Wait for backend readiness (up to 15s)
  READY=false
  for _ in {1..30}; do
    if curl -s -m 2 -o /dev/null -w "%{http_code}" "http://127.0.0.1:8080/api/capabilities" | grep -qE "200|401|403"; then
      READY=true
      break
    fi
    sleep 0.5
  done

  if [[ "$READY" != "true" ]]; then
    echo "Error: Backend failed to start within 15 seconds. Check data/backend-desktop.log." >&2
    exit 1
  fi
fi

# 3. Read Launch Bootstrap Credential
TARGET_URL="http://127.0.0.1:8080/"
BOOTSTRAP_FILE="$PROJECT_DIR/data/launch-bootstrap"
if [[ -f "$BOOTSTRAP_FILE" ]]; then
  TOKEN="$(cat "$BOOTSTRAP_FILE" | tr -d '\r\n')"
  if [[ -n "$TOKEN" ]]; then
    TARGET_URL="http://127.0.0.1:8080/#bootstrap=${TOKEN}"
  fi
fi

# 4. Launch Desktop Window in App Mode
PROFILE_DIR="$HOME/.config/schedwise/desktop-profile"
mkdir -p "$PROFILE_DIR"

echo "Launching SchedWise Desktop App (${BROWSER})..."

"$BROWSER" \
  --app="$TARGET_URL" \
  --user-data-dir="$PROFILE_DIR" \
  --class="schedwise" \
  --name="SchedWise" \
  --window-size=1440,920 \
  --no-first-run \
  --no-default-browser-check

# 5. Cleanup on Window Exit
if [[ "$BACKEND_ALREADY_RUNNING" == "false" && -f "$PROJECT_DIR/data/schedwise-desktop.pid" ]]; then
  SPAWNED_PID="$(cat "$PROJECT_DIR/data/schedwise-desktop.pid" 2>/dev/null || true)"
  if [[ -n "$SPAWNED_PID" ]] && kill -0 "$SPAWNED_PID" 2>/dev/null; then
    echo "Closing SchedWise background daemon (PID ${SPAWNED_PID})..."
    kill "$SPAWNED_PID" 2>/dev/null || true
  fi
  rm -f "$PROJECT_DIR/data/schedwise-desktop.pid"
fi
