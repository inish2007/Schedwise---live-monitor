#!/usr/bin/env bash
# SchedWise v1 Comprehensive Verification Suite
# Verifies:
# 1. Java 21 backend compilation, packaging, and JUnit tests (32 tests).
# 2. Python demo harness unit tests (7 tests).
# 3. Frontend Node stream tests, TypeScript strict typecheck, and Vite production bundle build.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "=== [1/3] Frontend checks and production bundle ==="
(cd frontend && npm ci && npm test && npm run typecheck && npm run build)
source scripts/java-env.sh
export MAVEN_USER_HOME="$PWD/.tools/maven"
echo "=== [2/3] Backend tests and packaged dashboard ==="
./backend/mvnw -f backend/pom.xml -B verify
echo "=== [3/3] Python workload and guardian tests ==="
python3 tools/tests/test_demo.py
python3 tools/tests/test_guardian.py
echo "=== Verification passed ==="
