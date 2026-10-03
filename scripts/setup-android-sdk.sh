#!/usr/bin/env bash
# Installs the Android SDK (command-line only, no Android Studio) into ~/Android/Sdk
# without sudo. Idempotent: re-running only installs what is missing.
#
# Usage:
#   scripts/setup-android-sdk.sh                     # SDK, platform-tools, build-tools, platforms
#   scripts/setup-android-sdk.sh --with-emulator      # + emulator and an AOSP (no Google APIs) API 35 AVD
#                                                     # (mg_api35). System image download: 782,404,023 bytes
#                                                     # (verified, x86_64 'default' image).
#   scripts/setup-android-sdk.sh --with-emulator --api37
#                                                     # + a second API 37 AVD (mg_api37) for targetSdk-37
#                                                     # behavior checks. Prefers an AOSP ("default") x86_64
#                                                     # image; if none is published yet, falls back to the
#                                                     # google_apis x86_64 image as a test-only exception for
#                                                     # *emulator testing only* - the app itself stays
#                                                     # Google-free. Never google_apis_playstore, *_ps16k, or
#                                                     # the wear/desktop/automotive images. Separate, large
#                                                     # (system image download > 1 GB, unverified) and opt-in:
#                                                     # on a metered link, run without --api37. If neither an
#                                                     # AOSP nor a google_apis image exists for API 37, this
#                                                     # dies before installing any SDK packages.
#
# Environment overrides:
#   ANDROID_HOME     install location (default: ~/Android/Sdk)
#   MIN_API          minimum platform to install (default: 35)
#   AVD_NAME         emulator AVD name for MIN_API (default: mg_api35)
#   API37_AVD_NAME   emulator AVD name for --api37 (default: mg_api37)
#
# NOTE: this script accepts the Android SDK licenses non-interactively (`yes | sdkmanager --licenses`).
# Run `sdkmanager --licenses` yourself first if you want to read them.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
MIN_API="${MIN_API:-35}"
AVD_NAME="${AVD_NAME:-mg_api35}"
API37_AVD_NAME="${API37_AVD_NAME:-mg_api37}"
WITH_EMULATOR=0
WITH_API37=0
for arg in "$@"; do
  case "$arg" in
    --with-emulator) WITH_EMULATOR=1 ;;
    --api37) WITH_API37=1 ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "usage: $0 [--with-emulator] [--api37]" >&2; exit 2 ;;
  esac
done
if (( WITH_API37 )) && (( ! WITH_EMULATOR )); then
  echo "usage: $0 --with-emulator --api37 (--api37 requires --with-emulator)" >&2
  exit 2
fi

log() { printf '\033[1;34m[setup-android-sdk]\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[setup-android-sdk]\033[0m %s\n' "$*" >&2; exit 1; }

for cmd in java curl unzip; do command -v "$cmd" >/dev/null || die "missing required tool: $cmd"; done
JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' '/version/ {split($2,v,"."); print v[1]}')"
(( JAVA_MAJOR >= 21 )) || die "JDK 21+ required (found $JAVA_MAJOR). Install Temurin 21."

REPO_XML="https://dl.google.com/android/repository/repository2-3.xml"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"

# 1. Command-line tools ------------------------------------------------------
if [[ ! -x "$SDKMANAGER" ]]; then
  log "Resolving latest cmdline-tools build..."
  TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
  curl -fsSL -o "$TMP/repo.xml" "$REPO_XML"
  ZIP="$(grep -o 'commandlinetools-linux-[0-9]*_latest.zip' "$TMP/repo.xml" | sort -t- -k3 -n | tail -1)"
  [[ -n "$ZIP" ]] || die "could not resolve cmdline-tools download"

  # Expected size + checksum from the <complete> block whose <url> is $ZIP. Google currently publishes sha1
  # only; prefer sha256 if it ever appears. CMDLINE_TOOLS_SHA256 pins a known-good hash and takes precedence.
  read -r EXP_SIZE EXP_TYPE EXP_SUM < <(awk -v zip="$ZIP" '
    /<complete>/ { size=""; type=""; sum="" }
    match($0, /<size>[0-9]+<\/size>/) { size=substr($0, RSTART+6, RLENGTH-13) }
    match($0, /<checksum type="[a-z0-9]+">[0-9a-f]+<\/checksum>/) {
      s=substr($0, RSTART, RLENGTH); t=s; sub(/^<checksum type="/, "", t); sub(/".*/, "", t)
      v=s; sub(/^[^>]*>/, "", v); sub(/<.*/, "", v)
      if (type != "sha256") { type=t; sum=v }
    }
    index($0, "<url>" zip "</url>") { print size, type, sum; exit }' "$TMP/repo.xml")
  if [[ -n "${CMDLINE_TOOLS_SHA256:-}" ]]; then EXP_TYPE=sha256; EXP_SUM="$CMDLINE_TOOLS_SHA256"; fi
  [[ -n "${EXP_SUM:-}" && "${EXP_TYPE:-}" =~ ^sha(1|256)$ ]] || die "no checksum published for $ZIP - refusing to install"

  log "Downloading $ZIP..."
  curl -fsSL -o "$TMP/tools.zip" "https://dl.google.com/android/repository/$ZIP"
  if [[ -n "${EXP_SIZE:-}" && -z "${CMDLINE_TOOLS_SHA256:-}" ]]; then
    [[ "$(stat -c %s "$TMP/tools.zip")" == "$EXP_SIZE" ]] || die "size mismatch for $ZIP"
  fi
  echo "$EXP_SUM  $TMP/tools.zip" | "${EXP_TYPE}sum" -c --status || die "$EXP_TYPE checksum mismatch for $ZIP - aborting"
  log "Verified $EXP_TYPE checksum of $ZIP."
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
# Latest stable *major* platform. Since API 36.1 packages may be named "android-NN.M"; the major release is
# "android-NN" or "android-NN.0". Previews (-beta, letters), extension (-extNN) and minor (.1+) packages are skipped.
LATEST_PLATFORM="$(grep -oE "^\s*platforms${S}android-[0-9]+(\.0)?\s" <<<"$LIST" | grep -oE 'android-[0-9.]+' \
  | sort -t- -k2 -V | tail -1 || true)"
LATEST_API="${LATEST_PLATFORM#android-}"; LATEST_API="${LATEST_API%.0}"
LATEST_BT="$(grep -oE "^\s*build-tools${S}[0-9]+\.[0-9]+\.[0-9]+\s" <<<"$LIST" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | sort -V | tail -1 || true)"
[[ -n "$LATEST_API" && -n "$LATEST_BT" ]] || die "could not resolve platform/build-tools versions from 'sdkmanager --list'"

PKGS=("platform-tools" "build-tools${S}$LATEST_BT" "platforms${S}android-$MIN_API")
[[ "$LATEST_API" != "$MIN_API" ]] && PKGS+=("platforms${S}$LATEST_PLATFORM")

if (( WITH_EMULATOR )); then
  [[ -e /dev/kvm ]] || die "/dev/kvm not available - the emulator needs KVM acceleration"
  # AOSP 'default' image: no Google APIs / Play Services, matching the app's Google-free target.
  SYSIMG="system-images${S}android-$MIN_API${S}default${S}x86_64"
  PKGS+=("emulator" "$SYSIMG")
fi

if (( WITH_API37 )); then
  # Resolved at runtime (never hard-coded): a freshly released API level sometimes has only a
  # google_apis* system image for a while before an AOSP 'default' one follows. Prefer the AOSP
  # image; accept google_apis as a test-only exception for *emulator testing only* (the app itself
  # never depends on Google APIs - AGENTS.md §2). Never accept google_apis_playstore, any
  # *_ps16k variant, or the wear/desktop/automotive images: those pull in Play Store / Play
  # Services, exactly what this project refuses to depend on.
  # Major release only (".0" or bare), matching the platform resolution above: this intentionally
  # excludes minor releases (android-37.1, .2) and previews (android-37.2-beta1).
  API37_DEFAULT="$(grep -oE "^\s*system-images${S}android-37(\.0)?${S}default${S}x86_64\s" <<<"$LIST" \
    | grep -oE "system-images${S}android-37(\.0)?${S}default${S}x86_64" | head -1 || true)"
  API37_GOOGLE="$(grep -oE "^\s*system-images${S}android-37(\.0)?${S}google_apis${S}x86_64\s" <<<"$LIST" \
    | grep -oE "system-images${S}android-37(\.0)?${S}google_apis${S}x86_64" | head -1 || true)"
  if [[ -n "$API37_DEFAULT" ]]; then
    API37_SYSIMG="$API37_DEFAULT"
    log "API 37: using the AOSP ('default') image - $API37_SYSIMG"
  elif [[ -n "$API37_GOOGLE" ]]; then
    API37_SYSIMG="$API37_GOOGLE"
    log "API 37: no AOSP image published yet; using google_apis for emulator testing only (test-only exception, maintainer decision) - $API37_SYSIMG. The app itself never depends on Google APIs."
  else
    # The grep can legitimately match nothing (e.g. an arm64-only or empty android-37 listing); add
    # `|| true` so that case doesn't trip `set -e` before the die below runs.
    API37_TAGS="$(grep -oE "android-37(\.[0-9]+)?(-[a-z0-9]+)?${S}[a-zA-Z0-9_-]+${S}x86_64" <<<"$LIST" \
      | sort -u | paste -sd',' - | sed 's/,/, /g' || true)"
    die "no AOSP or google_apis API 37 x86_64 image in 'sdkmanager --list' (found: ${API37_TAGS:-none})"
  fi
  PKGS+=("$API37_SYSIMG") # --api37 requires --with-emulator above, which already queues the "emulator" package
fi

log "Installing: ${PKGS[*]}"
"$SDKMANAGER" --install "${PKGS[@]}" >/dev/null
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

if (( WITH_EMULATOR )); then
  # Capture the listing once into a variable instead of piping straight into `grep -q`: with
  # pipefail, `grep -q` can exit as soon as it finds its match, sending SIGPIPE to avdmanager and
  # making the pipeline's exit status racily non-zero, which `set -e` would then treat as failure.
  EXISTING_AVDS="$("$AVDMANAGER" list avd -c 2>/dev/null)" || true
  if ! grep -qx -- "$AVD_NAME" <<<"$EXISTING_AVDS"; then
    log "Creating AVD $AVD_NAME..."
    # avdmanager always takes the ';' form of the package path.
    echo no | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "${SYSIMG//\//;}" -d pixel_7 >/dev/null
  else
    log "AVD $AVD_NAME already exists."
  fi
fi

if (( WITH_API37 )); then
  # --api37 requires --with-emulator (enforced above), so $EXISTING_AVDS is already populated.
  if grep -qx -- "$API37_AVD_NAME" <<<"$EXISTING_AVDS"; then
    # An AVD can outlive the system image it was created from, or be created manually from an
    # unrelated image (e.g. API 35, or arm64-v8a). Re-check config.ini rather than trusting the AVD
    # name: tag.id must be 'default' or 'google_apis' (the two tags this script itself ever
    # creates), and image.sysdir.1 - the actual installed image path - must independently confirm
    # API 37 (or 37.0, major only, matching the resolution above), the x86_64 ABI, and a tag segment
    # that agrees with tag.id. Die on any mismatch or missing key, naming the offending value, so a
    # wrong or corrupt AVD is never silently reused.
    AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
    API37_CONFIG="$AVD_HOME/$API37_AVD_NAME.avd/config.ini"
    API37_TAG="$(grep -m1 -oE '^tag\.id=.*' "$API37_CONFIG" 2>/dev/null | cut -d= -f2- || true)"
    [[ -n "$API37_TAG" ]] \
      || die "AVD $API37_AVD_NAME already exists but $API37_CONFIG has no 'tag.id' key - cannot verify its system image. Remove it or set API37_AVD_NAME to a new name and re-run."
    case "$API37_TAG" in
      default|google_apis) ;;
      *) die "AVD $API37_AVD_NAME already exists with an unexpected system-image tag '$API37_TAG' in $API37_CONFIG (expected 'default' or 'google_apis') - remove it or set API37_AVD_NAME to a new name and re-run." ;;
    esac
    # The google_apis exception only applies while no AOSP image exists (AGENTS.md §2, test-only
    # exception). If one has since appeared, silently continuing to use it is not allowed - the AVD
    # must be recreated from the AOSP image, but deleting it automatically would be destructive, so
    # die and tell the caller how to do it themselves.
    if [[ "$API37_TAG" == "google_apis" && -n "$API37_DEFAULT" ]]; then
      die "AVD $API37_AVD_NAME already exists using the google_apis test-only exception, but an AOSP ('default') API 37 image is now available ($API37_DEFAULT) - the exception no longer applies. Remove it with: \"$AVDMANAGER\" delete avd -n $API37_AVD_NAME ; then re-run this command."
    fi
    API37_SYSDIR="$(grep -m1 -oE '^image\.sysdir\.1=.*' "$API37_CONFIG" 2>/dev/null | cut -d= -f2- || true)"
    [[ -n "$API37_SYSDIR" ]] \
      || die "AVD $API37_AVD_NAME already exists but $API37_CONFIG has no 'image.sysdir.1' key - cannot verify its API level/ABI. Remove it or set API37_AVD_NAME to a new name and re-run."
    # image.sysdir.1 looks like "system-images/android-37.0/default/x86_64/".
    IFS='/' read -r _ SYSDIR_API SYSDIR_TAG SYSDIR_ABI <<<"${API37_SYSDIR%/}"
    [[ "$SYSDIR_API" =~ ^android-37(\.0)?$ ]] \
      || die "AVD $API37_AVD_NAME already exists but its image.sysdir.1 ($API37_SYSDIR) in $API37_CONFIG is for '$SYSDIR_API', not API 37 - remove it or set API37_AVD_NAME to a new name and re-run."
    [[ "$SYSDIR_ABI" == "x86_64" ]] \
      || die "AVD $API37_AVD_NAME already exists but its image.sysdir.1 ($API37_SYSDIR) in $API37_CONFIG is for ABI '$SYSDIR_ABI', not x86_64 - remove it or set API37_AVD_NAME to a new name and re-run."
    [[ "$SYSDIR_TAG" == "$API37_TAG" ]] \
      || die "AVD $API37_AVD_NAME already exists but its image.sysdir.1 tag ('$SYSDIR_TAG') in $API37_CONFIG does not match tag.id ('$API37_TAG') - remove it or set API37_AVD_NAME to a new name and re-run."
    log "AVD $API37_AVD_NAME already exists (tag.id=$API37_TAG, image.sysdir.1=$API37_SYSDIR)."
  else
    log "Creating AVD $API37_AVD_NAME..."
    echo no | "$AVDMANAGER" create avd -n "$API37_AVD_NAME" -k "${API37_SYSIMG//\//;}" -d pixel_7 >/dev/null
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
(( WITH_API37 )) && echo "Start the API 37 emulator: emulator -avd $API37_AVD_NAME -no-snapshot-save &"
exit 0
