#!/usr/bin/env bash
# Build and open the standalone, authenticated localhost dashboard.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/java-env.sh
if ss -ltn | grep -q ':8080 '; then echo 'Port 8080 is occupied. Stop the existing backend first.'; exit 1; fi
(cd frontend && npm ci && npm run build)
./backend/mvnw -f backend/pom.xml -q package -DskipTests
mkdir -p data
java -Dschedwise.data="$PWD/data" -jar backend/target/schedwise-0.1.0.jar &
backend_pid=$!
cleanup(){ kill "$backend_pid" 2>/dev/null || true; wait "$backend_pid" 2>/dev/null || true; }
trap cleanup EXIT
trap 'exit 130' INT TERM
python3 - "$backend_pid" <<'PY'
import os,sys,time,urllib.request,webbrowser
from pathlib import Path
pid=int(sys.argv[1]);ready=False
for _ in range(100):
 try:
  os.kill(pid,0)
  with urllib.request.urlopen('http://localhost:8080/',timeout=.2) as res:ready=res.status==200
  if ready:break
 except (OSError,urllib.error.URLError):pass
 time.sleep(.1)
if not ready:raise SystemExit('Backend did not become ready')
secret=Path('data/launch-bootstrap').read_text().strip()
webbrowser.open('http://localhost:8080/#bootstrap='+secret)
print('Opened http://localhost:8080/ with a single-use local session. Keep this terminal open.')
PY
wait "$backend_pid"
