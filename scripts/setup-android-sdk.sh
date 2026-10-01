#!/usr/bin/env bash
# Installs the Android SDK (command-line only, no Android Studio) into ~/Android/Sdk
# without sudo. Idempotent: re-running only installs what is missing.
#
# Usage:
#   scripts/setup-android-sdk.sh                 # SDK, platform-tools, build-tools, platforms
#   scripts/setup-android-sdk.sh --with-emulator # + emulator and an AOSP (no Google APIs) API 35 AVD
#
# Environment overrides:
#   ANDROID_HOME   install location (default: ~/Android/Sdk)
#   MIN_API        minimum platform to install (default: 35)
#   AVD_NAME       emulator AVD name (default: mg_api35)
#
# NOTE: this script accepts the Android SDK licenses non-interactively (`yes | sdkmanager --licenses`).
# Run `sdkmanager --licenses` yourself first if you want to read them.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
MIN_API="${MIN_API:-35}"
AVD_NAME="${AVD_NAME:-mg_api35}"
WITH_EMULATOR=0
[[ "${1:-}" == "--with-emulator" ]] && WITH_EMULATOR=1

log() { printf '\033[1;34m[setup-android-sdk]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[setup-android-sdk]\033[0m %s\n' "$*" >&2; exit 1; }

for cmd in java curl unzip; do command -v "$cmd" >/dev/null || die "missing required tool: $cmd"; done
JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' '/version/ {split($2,v,"."); print v[1]}')"
(( JAVA_MAJOR >= 17 )) || die "JDK 17+ required (found $JAVA_MAJOR). Install Temurin 17 or 21."

REPO_XML="https://dl.google.com/android/repository/repository2-3.xml"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"

# 1. Command-line tools ------------------------------------------------------
if [[ ! -x "$SDKMANAGER" ]]; then
  log "Resolving latest cmdline-tools build..."
  ZIP="$(curl -fsSL "$REPO_XML" | grep -o 'commandlinetools-linux-[0-9]*_latest.zip' | sort -t- -k3 -n | tail -1)"
  [[ -n "$ZIP" ]] || die "could not resolve cmdline-tools download"
  TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
  log "Downloading $ZIP..."
  curl -fsSL -o "$TMP/tools.zip" "https://dl.google.com/android/repository/$ZIP"
  unzip -q "$TMP/tools.zip" -d "$TMP"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$TMP/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
else
  log "cmdline-tools already present."
fi

export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

log "Accepting SDK licenses..."
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

# 2. Resolve latest stable platform + build-tools -----------------------------
log "Querying available packages..."
LIST="$("$SDKMANAGER" --list 2>/dev/null)"
# cmdline-tools <= 19 print package paths as "platforms;android-35", newer ones as "platforms/android-35".
if grep -qE '^\s*platforms/android-' <<<"$LIST"; then S='/'; else S=';'; fi
LATEST_API="$(grep -oE "^\s*platforms${S}android-[0-9]+\s" <<<"$LIST" | grep -oE '[0-9]+' | sort -n | tail -1 || true)"
LATEST_BT="$(grep -oE "^\s*build-tools${S}[0-9]+\.[0-9]+\.[0-9]+\s" <<<"$LIST" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | sort -V | tail -1 || true)"
[[ -n "$LATEST_API" && -n "$LATEST_BT" ]] || die "could not resolve platform/build-tools versions from 'sdkmanager --list'"

PKGS=("platform-tools" "build-tools${S}$LATEST_BT" "platforms${S}android-$MIN_API")
[[ "$LATEST_API" != "$MIN_API" ]] && PKGS+=("platforms${S}android-$LATEST_API")

if (( WITH_EMULATOR )); then
  [[ -e /dev/kvm ]] || die "/dev/kvm not available - the emulator needs KVM acceleration"
  # AOSP 'default' image: no Google APIs / Play Services, matching the app's Google-free target.
  SYSIMG="system-images${S}android-$MIN_API${S}default${S}x86_64"
  PKGS+=("emulator" "$SYSIMG")
fi

log "Installing: ${PKGS[*]}"
"$SDKMANAGER" --install "${PKGS[@]}" >/dev/null
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

if (( WITH_EMULATOR )); then
  if ! "$AVDMANAGER" list avd -c 2>/dev/null | grep -qx "$AVD_NAME"; then
    log "Creating AVD $AVD_NAME..."
    # avdmanager always takes the ';' form of the package path.
    echo no | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "${SYSIMG//\//;}" -d pixel_7 >/dev/null
  else
    log "AVD $AVD_NAME already exists."
  fi
fi

log "Done. Platforms: $(ls "$ANDROID_HOME/platforms" | paste -sd' '); build-tools: $(ls "$ANDROID_HOME/build-tools" | paste -sd' ')"
cat <<EOF

Add these lines to your shell profile (~/.bashrc or ~/.zshrc):

  export ANDROID_HOME="$ANDROID_HOME"
  export PATH="\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/emulator:\$PATH"

Gradle also picks the SDK up from local.properties (sdk.dir=$ANDROID_HOME), which is gitignored.
Resolved versions: compileSdk candidate = $LATEST_API, build-tools = $LATEST_BT, minSdk = $MIN_API
EOF
(( WITH_EMULATOR )) && echo "Start the emulator: emulator -avd $AVD_NAME -no-snapshot-save &"
exit 0
