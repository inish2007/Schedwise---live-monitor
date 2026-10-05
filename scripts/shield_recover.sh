#!/usr/bin/env bash
# SchedWise Game Shield Manual Recovery
# Verifies stored process identities before sending SIGCONT.
# Refuses to compete with a live guardian process.
set -euo pipefail
cd "$(dirname "$0")/.."

python3 tools/shield_recover.py --data "${1:-data}"
