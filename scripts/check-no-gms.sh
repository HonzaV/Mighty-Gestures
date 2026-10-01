#!/usr/bin/env bash
# Fails if Google proprietary libraries (Play Services, Firebase, ML Kit, Play Core, Ads, Billing) or common
# tracking/analytics SDKs are referenced in build files, manifests or sources. Used by scripts/verify.sh, the
# Claude Code hooks and (later) CI.
#
# Usage:
#   scripts/check-no-gms.sh                 # scan the repository (tracked + untracked, non-ignored files)
#   scripts/check-no-gms.sh --stdin <path>  # scan text from stdin as if it were the content of <path>
#   scripts/check-no-gms.sh --classpath     # scan resolved (transitive) dependencies of every configuration
#                                           # of every Gradle module; needs the Gradle project
#
# Allowed on purpose (FOSS under a com.google namespace): com.google.android.material, com.google.devtools.ksp,
# com.google.dagger, com.google.truth, com.google.protobuf, com.google.code.gson, com.google.guava.
set -euo pipefail

FORBIDDEN='com\.google\.android\.gms|com\.google\.gms|com\.google\.firebase|com\.google\.mlkit|com\.google\.android\.play[.:]|com\.google\.android\.ump|com\.google\.android\.libraries\.(places|maps|ads)|com\.google\.android\.datatransport|com\.google\.android\.recaptcha|com\.google\.ads|com\.google\.ar[.:]|com\.android\.billingclient|com\.crashlytics|io\.fabric|io\.sentry|com\.bugsnag|com\.appsflyer|com\.amplitude|com\.mixpanel|com\.onesignal|com\.flurry|com\.facebook\.android'
# Lines that are only comments are ignored (// # * <!--).
COMMENT='[[:space:]]*(//|#|\*|/\*|<!--)'

is_scanned_path() {
  local p="${1#./}"
  case "$p" in
    docs/*|.claude/*|scripts/check-no-gms.sh|*/build/*|build/*|.gradle/*) return 1 ;;
  esac
  case "$p" in
    *.kt|*.kts|*.gradle|*.toml|*.xml|*.java|*.pro|*.properties) return 0 ;;
  esac
  return 1
}

scan_stream() { # $1 = label; reads stdin; prints label:line: text for violations
  grep -nE "$FORBIDDEN" | grep -vE "^[0-9]+:$COMMENT" | sed "s|^|$1:|" || true
}

if [[ "${1:-}" == "--stdin" ]]; then
  path="${2:?--stdin requires a path}"
  is_scanned_path "$path" || { cat >/dev/null; exit 0; }
  hits="$(scan_stream "$path")"
  if [[ -n "$hits" ]]; then
    printf 'Google-free violation (see AGENTS.md §2):\n%s\n' "$hits"
    exit 1
  fi
  exit 0
fi

root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$root"

if [[ "${1:-}" == "--classpath" ]]; then
  [[ -x ./gradlew ]] || { echo "check-no-gms --classpath: no Gradle project yet, skipped"; exit 0; }
  # Every module, every configuration (all variants/flavors, runtime, compile and test classpaths).
  mapfile -t modules < <(./gradlew --quiet --console=plain projects | sed -nE "s/.*Project '(:[^']+)'.*/\1/p")
  ((${#modules[@]})) || modules=("")   # single-project build: the root project only
  hits=""
  for m in "${modules[@]}"; do
    out="$(./gradlew --quiet --console=plain "$m:dependencies")"
    # Track the current configuration header ("debugRuntimeClasspath - ...") and report "<module> <config>: <dep>".
    h="$(FORBIDDEN_RE="$FORBIDDEN" awk -v mod="${m:-:}" 'BEGIN { re = ENVIRON["FORBIDDEN_RE"] }
      /^[A-Za-z][A-Za-z0-9_]*( - .*)?$/ { cfg=$1; next }
      $0 ~ re { dep=$0; sub(/^[ |+\\-]*/, "", dep); print mod " " cfg ": " dep }' <<<"$out" | sort -u)"
    [[ -n "$h" ]] && hits+="$h"$'\n'
  done
  if [[ -n "$hits" ]]; then
    printf 'Google-free violation in resolved dependencies:\n%s' "$hits"
    echo "Find the culprit with: ./gradlew <module>:dependencyInsight --configuration <config> --dependency <group>"
    exit 1
  fi
  echo "check-no-gms --classpath: OK (${#modules[@]} module(s), all configurations)"
  exit 0
fi

hits=""
while IFS= read -r -d '' f; do
  [[ -f "$f" ]] || continue
  is_scanned_path "$f" || continue
  h="$(scan_stream "$f" <"$f")"
  [[ -n "$h" ]] && hits+="$h"$'\n'
done < <(git ls-files -z --cached --others --exclude-standard 2>/dev/null)

if [[ -n "$hits" ]]; then
  printf 'Google-free violation (see AGENTS.md §2) — remove these references:\n%s' "$hits"
  exit 1
fi
echo "check-no-gms: OK (no Google proprietary or tracking dependencies found)"
