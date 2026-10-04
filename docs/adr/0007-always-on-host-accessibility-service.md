# ADR 0007 — Always-on host: AccessibilityService only (no foreground service)

- **Status:** Accepted (maintainer, 2026-10-01; spec 0001 resolved decisions)
- **Date:** 2026-10-01
- **Deciders:** maintainer, architect (agent)

## Context
Gestures must fire while the screen is on, including on the lock screen (per-rule opt-in), while no Mighty
Gestures activity is visible. They must never fire while the screen is off. The host needs to:
1. **receive accelerometer/gyroscope events** with no visible activity;
2. **lock the screen** (action "Lock screen");
3. **launch other apps' activities from the background** (action "Open app");
4. toggle the torch, DND and ringer mode. These need no host privileges; see spec 0001.

The maintainer chose an AccessibilityService as the host, with no screen-content access, and asked us to
verify that (a) it is needed and (b) it is sufficient without an FGS. Evidence labels follow AGENTS.md §8.

### Platform facts
| # | Claim | Status | Source |
|---|---|---|---|
| F1 | Since Android 9, apps "in the background" get no events from continuous sensors (accelerometer, gyroscope). The page names an FGS as the remedy. | verified | https://developer.android.com/about/versions/pie/android-9.0-changes-all |
| F2 | SensorService decides access per UID with `isUidActive(uid)`, fed by an ActivityManager UID observer (`UID_OBSERVER_ACTIVE/IDLE`). | verified in AOSP `main` | `frameworks/native/services/sensorservice/SensorService.cpp` (`hasSensorAccessLocked`, `UidPolicy`) |
| F3 | The system binds accessibility services with `BIND_AUTO_CREATE \| BIND_FOREGROUND_SERVICE_WHILE_AWAKE \| BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS \| BIND_INCLUDE_CAPABILITIES`. | verified in AOSP `android-15.0.0_r1` and `main` (identical); 16/17 release tags *inferred* unchanged | `frameworks/base/services/accessibility/.../AccessibilityServiceConnection.java` |
| F4 | For a binding from a persistent system process: `BIND_FOREGROUND_SERVICE_WHILE_AWAKE` gives the service process `PROCESS_STATE_BOUND_FOREGROUND_SERVICE` while the device is awake, otherwise `PROCESS_STATE_IMPORTANT_FOREGROUND`. Both are below `PROCESS_STATE_TRANSIENT_BACKGROUND`, the `isProcStateBackground` cut-off, so the UID is never idled. | verified in AOSP `android-15.0.0_r1` and `main` | `OomAdjuster.java` (bind-flag block), `ActivityManager.isProcStateBackground` |
| F5 | ⇒ While our accessibility service is bound, our UID stays active and continuous sensors keep delivering events with no visible activity and **no FGS**. That holds **even with the screen off**. | *inferred from F2–F4*; must be device-verified (spec 0001 AC-H2) | — |
| F6 | Background activity starts are allowed when "the app is bound by a service that has been granted permission to start background activities". FGS alone is **not** in the exception list; `SYSTEM_ALERT_WINDOW` is. | verified (doc text); the link to F3's `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS` is *inferred* | https://developer.android.com/guide/components/activities/background-starts |
| F7 | `AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN` (API 28, value 8) — "Action to lock the screen" via `performGlobalAction`. | verified | https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#GLOBAL_ACTION_LOCK_SCREEN |
| F8 | No public SDK API lets a non-system app turn the display off without locking. `PowerManager.goToSleep` is system-only; `DevicePolicyManager.lockNow` needs device admin and also locks. | *inferred* | — |
| F9 | Apps targeting API 37 get task-hijacking rules. Rule 2: only an activity in a foreground task matching the top activity's UID may create a new task or bring another task forward. | verified (doc text); effect on our launch path *inferred* | background-starts page, "Task Hijacking Prevention" |

### Accessibility-specific risks
| Risk | Status | Notes |
|---|---|---|
| **Restricted settings (Android 13+)**: for some sideloaded installs, the accessibility toggle is greyed out until the user opens App info → ⋮ → "Allow restricted settings". | steps verified (https://support.google.com/android/answer/12623953); *which* installs are affected (non-session installers; F-Droid client uses a session installer) is *inferred* from third-party sources | The explainer screen must include this hint and a button to App info. |
| **F-Droid anti-features**: none exists for accessibility or special access. | verified (https://f-droid.org/docs/Anti-Features/) | Reviewers may still ask why; README section required (spec 0001 §README). |
| **Advanced Protection Mode (Android 16+/17)**: press reports say APM revokes or blocks accessibility services not declared `isAccessibilityTool="true"`. | *inferred* (press reports, e.g. Android Authority, Security Affairs; not confirmed on developer.android.com/privacy-and-security/advanced-protection-mode) | We must **not** claim `isAccessibilityTool="true"`: we are not an accessibility tool. Under APM, gestures will not run; the list banner shows "Gestures are paused" because the service is off. |
| **User trust / consent**: the system shows a strong warning ("full control of device"). | verified UX of the platform (*inferred* exact wording per OEM) | Explain before sending the user to Settings; state what the service does NOT do. |

## Options considered
### Option A — AccessibilityService only
- Pros: covers sensors (F5, inferred), lock (F7) and background launches (F6) with **one** special access.
  No persistent notification, no `FOREGROUND_SERVICE*` or `POST_NOTIFICATIONS` permission. The system rebinds
  it after boot, so there's no `RECEIVE_BOOT_COMPLETED`.
- Cons: accessibility is the most sensitive special access. It meets restricted-settings friction for manual
  installs and APM incompatibility (inferred). F5 is inferred from source and must be verified on devices.
### Option B — Foreground service (`specialUse`) only
- Pros: well-documented sensor exemption (F1); familiar model.
- Cons: **cannot lock** without device admin (`lockNow`), which is another special access, a deprecated-style
  policy and forces strong authentication on next unlock (*inferred*). **Cannot launch apps** from the
  background (F6) without `SYSTEM_ALERT_WINDOW`, a third special access with Android 15 FGS/overlay coupling. It
  also needs a permanent notification (`POST_NOTIFICATIONS`) and `FOREGROUND_SERVICE_SPECIAL_USE`.
### Option C — FGS + AccessibilityService
- Pros: sensor access does not rely on inferred F5.
- Cons: two always-on components, extra permissions and a notification, with no capability gain if F5 holds.
  Kept as the **fallback** if device verification falsifies F5.
### Option D — FGS + overlay + device admin
- Cons: three special accesses for what Option A does with one. Rejected.

## Decision
We choose **Option A** and make F5 a gating acceptance criterion. If F5 fails on API 35 or 37, this ADR is
superseded by Option C before release.

Service declaration (exact XML is the developer's; the constraints below are binding):
- `<service android:name=".platform.accessibility.MightyGesturesAccessibilityService"
  android:exported="true" android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">` with the
  `android.accessibilityservice.AccessibilityService` intent filter and `android.accessibilityservice` meta-data.
  Exported because the system must bind it, and protected by the bind permission.
- `accessibility_service_config.xml`:
  - `android:canRetrieveWindowContent="false"`; no `canPerformGestures`; no
    `flagRequestFilterKeyEvents`, `flagRetrieveInteractiveWindows` or `flagRequestTouchExplorationMode`.
  - `android:isAccessibilityTool="false"`.
  - `android:accessibilityEventTypes`: **no event types**, so the service receives nothing.
    `AccessibilityServiceInfo.eventTypes` is a plain bitmask (verified,
    https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#eventTypes);
    that a zero mask binds and delivers no events is *inferred* and checked on device (AC-H1). Never use
    `typeWindowStateChanged` / `typeWindowContentChanged`: they reveal the foreground app or screen content.
  - `android:description` / `android:summary`: plain-language purpose and the "does not read the screen" statement.
- `onAccessibilityEvent` and `onInterrupt` are no-ops. `onServiceConnected` starts the host wiring;
  `onUnbind`/`onDestroy` stop it and unregister everything.
- Runs in the default process (shared `AppContainer` / single DataStore instance, ADR 0005/0006).
- Screen state: dynamically registered receiver for `ACTION_SCREEN_ON`, `ACTION_SCREEN_OFF`,
  `ACTION_USER_PRESENT` with `RECEIVER_NOT_EXPORTED`. System broadcasts still arrive with that flag
  (*inferred*; Context docs say the flag is only *required* for non-system broadcasts; verify in Robolectric and on
  device). Initial state comes from `PowerManager.isInteractive()` and `KeyguardManager.isKeyguardLocked()`.
- **Sensors are unregistered on `ACTION_SCREEN_OFF`.** Because of F5 the platform will *not* stop delivery
  for us when the screen turns off, so this unregistration is the only thing that stops sampling.
- Background launches use a plain `startActivity` from the service, or from the trampoline activity on the lock
  screen (spec 0001). No `PendingIntent`/`IntentSender`, so the API 34/35/37 PendingIntent opt-in rules do not
  apply.

Settings entry: `Settings.ACTION_ACCESSIBILITY_SETTINGS` (verified; no public per-service deep link exists in
`android.provider.Settings` for API 37). Restricted-settings hint opens
`Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for our package.

## Consequences
- Positive: one special access, no notification, no FGS permissions, no boot receiver.
- Negative / accepted trade-offs: users must grant accessibility, and some will not. F-Droid users who sideload
  manually may hit restricted settings. APM users cannot use gestures (inferred). Detection depends on F5.
- Follow-ups: device verification on an API 35 **and** an API 37 image (`mg_api37`; `google_apis` allowed for
  tests only, spec 0001 decision 13) plus one real device (spec 0001 test plan). README section "Why does
  Mighty Gestures need Accessibility?" (spec 0001). Revisit if Android restricts non-tool accessibility
  services beyond APM.

## References
- Background sensor limits (Android 9): https://developer.android.com/about/versions/pie/android-9.0-changes-all
- Background activity starts: https://developer.android.com/guide/components/activities/background-starts
- AccessibilityService: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- AccessibilityServiceInfo: https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo
- Android 17 behavior changes (BAL hardening): https://developer.android.com/about/versions/17/behavior-changes-17
- AOSP sources (read 2026-10-01):
  - https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/accessibility/java/com/android/server/accessibility/AccessibilityServiceConnection.java
  - https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/services/core/java/com/android/server/am/OomAdjuster.java
  - https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/services/sensorservice/SensorService.cpp
- Restricted settings: https://support.google.com/android/answer/12623953
- F-Droid anti-features: https://f-droid.org/docs/Anti-Features/
- Advanced Protection Mode: https://developer.android.com/privacy-and-security/advanced-protection-mode
- docs/engineering/sensors-and-power.md, docs/engineering/security-privacy.md
