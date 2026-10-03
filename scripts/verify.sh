#!/usr/bin/env bash
# Canonical local verification. Agents and humans run this before declaring work done.
#
#   scripts/verify.sh           Google-free check + spotlessCheck detekt lintDebug testDebugUnitTest
#                               jacocoCoverageVerification (75 % line coverage gate, prints the %)
#                               + Google-free check of the resolved release classpath
#   scripts/verify.sh --fast    Google-free check + spotlessCheck testDebugUnitTest (inner loop; no coverage
#                               gate — it's a done criterion, not a mid-iteration one, and --fast never marks
#                               the snapshot verified anyway)
#   scripts/verify.sh --device  full + connectedDebugAndroidTest (needs an attached emulator/device)
#
# jacocoCoverageVerification depends on jacocoTestReport which depends on testDebugUnitTest, and Gradle
# de-duplicates tasks within one invocation, so unit tests still run exactly once.
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
  -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
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
  full)   TASKS=(spotlessCheck detekt lintDebug testDebugUnitTest jacocoCoverageVerification) ;;
  device) TASKS=(spotlessCheck detekt lintDebug testDebugUnitTest jacocoCoverageVerification connectedDebugAndroidTest)
          ADB="$(command -v adb || echo "${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb")"
          "$ADB" get-state >/dev/null 2>&1 || { echo "No device/emulator attached (see device-verify skill)." >&2; exit 1; } ;;
esac

echo "==> ./gradlew ${TASKS[*]}"
./gradlew --console=plain "${TASKS[@]}"

if [[ "$MODE" != fast ]]; then
  echo "==> Google-free check of resolved (transitive) dependencies"
  scripts/check-no-gms.sh --classpath

  # Cheap: parse the JaCoCo XML's overall (last, i.e. report-level, not per-class/package) LINE counter
  # instead of re-running anything. Never fails the run: a missing/unparseable report only prints a notice.
  JACOCO_XML=app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml
  counter=""
  [[ -f "$JACOCO_XML" ]] && counter="$(grep -o '<counter type="LINE"[^>]*/>' "$JACOCO_XML" | tail -n1 || true)"
  if [[ -n "$counter" ]]; then
    missed="$(sed -E 's/.*missed="([0-9]+)".*/\1/' <<<"$counter")"
    covered="$(sed -E 's/.*covered="([0-9]+)".*/\1/' <<<"$counter")"
    total=$((missed + covered))
    if (( total > 0 )); then
      # LC_ALL=C: some locales (e.g. cs_CZ) make awk print a decimal comma instead of a dot.
      pct="$(LC_ALL=C awk -v c="$covered" -v t="$total" 'BEGIN { printf "%.1f", (c / t) * 100 }')"
      echo "==> app line coverage: ${pct}% (${covered}/${total} lines)"
    else
      echo "==> app line coverage: report unparseable (0 lines in $JACOCO_XML)"
    fi
  else
    echo "==> app line coverage: report not found ($JACOCO_XML)"
  fi

  mv "$STARTED" "$STATE_DIR/last-verify"
fi
echo "==> verify ($MODE): OK"
