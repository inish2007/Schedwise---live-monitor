#!/usr/bin/env bash
# SchedWise Environment Doctor (Read-Only)
# Inspects Linux kernel, CPU topology, runtime tools, and optional kernel metrics.
# Does NOT mutate system state, launch workloads, or write to disk.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "================================================================="
echo "               SchedWise v1 Environment Doctor                   "
echo "================================================================="

# 1. Operating System & Kernel
echo ""
echo "[1/6] Operating System & Kernel:"
kernel=$(uname -s)
release=$(uname -r)
machine=$(uname -m)
echo "  OS:           $kernel"
echo "  Kernel:       $release"
echo "  Architecture: $machine"

if [[ "$kernel" != "Linux" ]]; then
  echo "  STATUS:       UNSUPPORTED. SchedWise requires Linux (or WSL2/Linux VM)."
  exit 1
fi

if grep -qi microsoft /proc/version 2>/dev/null; then
  echo "  Scope:        WSL2 Linux Environment (Windows host applications outside scope)"
else
  echo "  Scope:        Native Linux Environment"
fi

# 2. Runtime Languages & Tools
echo ""
echo "[2/6] Runtimes & Fixed Executables:"
source scripts/java-env.sh 2>/dev/null || true
if command -v java >/dev/null 2>&1; then
  java_ver=$(java -version 2>&1 | head -n 1)
  echo "  Java:         $java_ver (OK)"
else
  echo "  Java:         MISSING. Java 21 is required."
fi

if command -v node >/dev/null 2>&1; then
  node_ver=$(node --version)
  echo "  Node.js:      $node_ver (OK)"
else
  echo "  Node.js:      MISSING. Node.js 20+ required for frontend."
fi

if command -v npm >/dev/null 2>&1; then
  npm_ver=$(npm --version)
  echo "  npm:          $npm_ver (OK)"
else
  echo "  npm:          MISSING."
fi

if command -v python3 >/dev/null 2>&1; then
  py_ver=$(python3 --version 2>&1)
  echo "  Python:       $py_ver (OK)"
  if python3 -c 'import os, signal; fd=os.pidfd_open(os.getpid()); signal.pidfd_send_signal(fd, 0); os.close(fd)' 2>/dev/null; then
    echo "  pidfd APIs:   Available (Python os.pidfd_open / signal.pidfd_send_signal OK)"
  else
    echo "  pidfd APIs:   Unavailable. (Game Shield process pause controls will be disabled; inspect-only mode active)."
  fi
else
  echo "  Python:       MISSING. Python 3 standard library is required for demo workloads."
fi

for tool in getconf taskset renice top; do
  if command -v "$tool" >/dev/null 2>&1; then
    echo "  $tool:        $(command -v "$tool") (OK)"
  else
    echo "  $tool:        MISSING. Required Linux utility."
  fi
done

# 3. Clock Ticks & Platform Timing
echo ""
echo "[3/6] Platform Timing:"
if command -v getconf >/dev/null 2>&1; then
  ticks=$(getconf CLK_TCK 2>/dev/null || echo "Unknown")
  echo "  SC_CLK_TCK:   $ticks ticks/second"
  if [[ "$ticks" != "100" ]]; then
    echo "  Note:         CLK_TCK is $ticks (parsed dynamically, not hardcoded to 100)."
  fi
else
  echo "  SC_CLK_TCK:   getconf unavailable"
fi

# 4. Identity & CPU Topology
echo ""
echo "[4/6] Identity & CPU Mask:"
if [[ -r /proc/self/status ]]; then
  uid_line=$(grep -E '^Uid:' /proc/self/status | awk '{print $2}')
  cpus_line=$(grep -E '^Cpus_allowed_list:' /proc/self/status | awk '{print $2}')
  echo "  Real UID:     $uid_line"
  if [[ "$uid_line" == "0" ]]; then
    echo "  WARNING:      Running as root is prohibited by AGENTS.md. Run as a regular user."
  else
    echo "  Privilege:    Unprivileged regular user (compliant with zero-root policy)."
  fi
  echo "  Allowed CPUs: $cpus_line"
else
  echo "  /proc/self/status: Unavailable."
fi

if [[ -r /proc/self/cgroup ]]; then
  cgroup_line=$(head -n 1 /proc/self/cgroup)
  echo "  Cgroup:       $cgroup_line"
fi

if [[ -r /proc/self/autogroup ]]; then
  autogroup_line=$(cat /proc/self/autogroup)
  echo "  Autogroup:    $autogroup_line"
else
  echo "  Autogroup:    Kernel autogroup accounting unavailable or disabled."
fi

# 5. Optional Kernel Telemetry Sources
echo ""
echo "[5/6] Optional Telemetry Sources:"
if [[ -r /proc/pressure/cpu ]]; then
  psi_some=$(grep -E '^some' /proc/pressure/cpu || true)
  echo "  PSI CPU:      Available (/proc/pressure/cpu)"
  echo "                $psi_some"
  echo "  Note:         PSI 'some' indicates contention; system 'full' is omitted (meaningless for CPU)."
else
  echo "  PSI CPU:      Unavailable. (Optional kernel feature: CONFIG_PSI not enabled)."
fi

if [[ -r /proc/sys/kernel/sched_schedstats ]]; then
  schedstat_enabled=$(cat /proc/sys/kernel/sched_schedstats)
  echo "  Schedstat:    /proc/sys/kernel/sched_schedstats = $schedstat_enabled"
  if [[ "$schedstat_enabled" == "0" ]]; then
    echo "  Note:         Kernel scheduler statistics accounting is inactive. Zero values reflect disabled accounting, not zero waiting."
  fi
else
  echo "  Schedstat:    /proc/sys/kernel/sched_schedstats unavailable."
fi

# 6. Storage & Workspace Health
echo ""
echo "[6/6] Storage & Workspace Health:"
df_out=$(df -h . | tail -n 1)
echo "  Filesystem:   $df_out"
mkdir -p data
if [[ -w data ]]; then
  echo "  data/:        Writable local directory available (OK)."
else
  echo "  data/:        Directory is not writable!"
fi

echo ""
echo "================================================================="
echo "Doctor inspection complete. All checks read-only."
echo "================================================================="
