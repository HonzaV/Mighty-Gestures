#!/usr/bin/env bash
# Canonical local verification. Agents and humans run this before declaring work done.
#
#   scripts/verify.sh           Google-free check + spotlessCheck detekt lintDebug testDebugUnitTest
#                               + Google-free check of the resolved release classpath
#   scripts/verify.sh --fast    Google-free check + spotlessCheck testDebugUnitTest (inner loop)
#   scripts/verify.sh --device  full + connectedDebugAndroidTest (needs an attached emulator/device)
#
# A successful full/device run stores a snapshot of all Kotlin sources in .claude/state/last-verify; the Claude
# Code Stop hook compares against it to know whether the current Kotlin changes were verified.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

MODE=full
case "${1:-}" in
  "") ;;
  --fast) MODE=fast ;;
  --device) MODE=device ;;
  -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
  *) echo "unknown option: $1" >&2; exit 2 ;;
esac

STATE_DIR=.claude/state
mkdir -p "$STATE_DIR"
STARTED="$STATE_DIR/verify-started.$$"
# Snapshot Kotlin sources before running: files edited, added or deleted during the run count as unverified.
scripts/kotlin-snapshot.sh > "$STARTED"
trap 'rm -f "$STARTED"' EXIT

echo "==> Google-free dependency check"
scripts/check-no-gms.sh

if [[ ! -x ./gradlew ]]; then
  echo "==> No Gradle project yet (./gradlew missing) — only the Google-free check ran."
  exit 0
fi

case "$MODE" in
  fast)   TASKS=(spotlessCheck testDebugUnitTest) ;;
  full)   TASKS=(spotlessCheck detekt lintDebug testDebugUnitTest) ;;
  device) TASKS=(spotlessCheck detekt lintDebug testDebugUnitTest connectedDebugAndroidTest)
          ADB="$(command -v adb || echo "${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb")"
          "$ADB" get-state >/dev/null 2>&1 || { echo "No device/emulator attached (see device-verify skill)." >&2; exit 1; } ;;
esac

echo "==> ./gradlew ${TASKS[*]}"
./gradlew --console=plain "${TASKS[@]}"

if [[ "$MODE" != fast ]]; then
  echo "==> Google-free check of resolved (transitive) dependencies"
  scripts/check-no-gms.sh --classpath
fi

if [[ "$MODE" != fast ]]; then
  mv "$STARTED" "$STATE_DIR/last-verify"
fi
echo "==> verify ($MODE): OK"
