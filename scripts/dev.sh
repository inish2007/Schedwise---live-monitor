#!/usr/bin/env bash
# SchedWise v1 Development Startup
# Starts backend (Spring Boot on :8080) and frontend (Vite dev server on :5173).
# Generates per-launch session token in data/session-token.
# Startup is passive: does NOT automatically saturate cores or alter priorities.
set -euo pipefail
cd "$(dirname "$0")/.."

source scripts/java-env.sh
export MAVEN_USER_HOME="$PWD/.tools/maven"
mkdir -p data

# Check ports 8080 and 5173
if command -v ss >/dev/null 2>&1; then
  if ss -ltn | grep -q ':8080 '; then
    echo "ERROR: Port 8080 is already in use. Stop the existing process before starting dev.sh."
    exit 1
  fi
  if ss -ltn | grep -q ':5173 '; then
    echo "ERROR: Port 5173 is already in use. Stop the existing process before starting dev.sh."
    exit 1
  fi
fi

if [[ ! -d frontend/node_modules ]]; then
  echo "Installing frontend dependencies..."
  (cd frontend && npm ci)
fi

echo "Building backend..."
./backend/mvnw -f backend/pom.xml -q package -DskipTests

mkdir -p .tools
run_dir=$(mktemp -d "$PWD/.tools/run.XXXXXX")
cp backend/target/schedwise-0.1.0.jar "$run_dir/schedwise.jar"

echo "Starting Spring Boot backend on 127.0.0.1:8080..."
(cd backend && exec java -Dschedwise.data="$PWD/../data" -jar "$run_dir/schedwise.jar") &
backend_pid=$!

frontend_pid=''
cleanup() {
  echo ""
  echo "Shutting down SchedWise development services..."
  kill "$backend_pid" ${frontend_pid:+"$frontend_pid"} 2>/dev/null || true
  wait "$backend_pid" ${frontend_pid:+"$frontend_pid"} 2>/dev/null || true
  rm -f "$run_dir/schedwise.jar"
  rmdir "$run_dir" 2>/dev/null || true
  echo "Services stopped cleanly."
}
trap cleanup EXIT
trap 'exit 130' INT TERM

echo "Starting Vite dev server on 127.0.0.1:5173..."
(cd frontend && exec node node_modules/vite/bin/vite.js --host 127.0.0.1) &
frontend_pid=$!

echo ""
echo "================================================================="
echo " SchedWise v1 Development Environment Running"
echo " Dashboard: http://localhost:5173"
echo " Backend:   http://127.0.0.1:8080"
echo " Authentication: automatic local browser session"
echo " Press Ctrl+C to stop all services cleanly"
echo "================================================================="
echo ""

wait -n "$backend_pid" "$frontend_pid"
