# 0001 — Motion gestures: record a movement, confirm it, run an action

- **Status:** Approved (maintainer, 2026-10-01; decisions in "Resolved decisions")
- **Author:** architect (agent)
- **Related ADRs:** [0003 module layout](../adr/0003-module-layout.md) ·
  [0004 trigger → rule → action model](../adr/0004-trigger-rule-action-model.md) ·
  [0005 dependency injection](../adr/0005-dependency-injection.md) ·
  [0006 persistence](../adr/0006-persistence.md) ·
  [0007 always-on host (AccessibilityService)](../adr/0007-always-on-host-accessibility-service.md) ·
  [0008 motion detection](../adr/0008-motion-gesture-detection.md) ·
  [0009 navigation](../adr/0009-navigation.md) — all **Accepted**

Evidence labels follow AGENTS.md §8: **verified** = read in official docs or AOSP source (URL in the ADRs);
*inferred* = reasoning not yet confirmed. Every *inferred* platform behavior has a device-verification AC.

## Problem
Mighty Gestures does nothing yet. This is the first real feature and the core of the product. The user teaches
the phone a movement of their own and attaches an action to it: open the camera, toggle the flashlight, lock
the screen, switch Do Not Disturb or the ringer. After that, performing the movement while the screen is on
runs the action, with no app open. The user can create as many gestures as they like.

## Scope
- **In scope**
  - Gesture list: name, action summary, enabled switch, delete, "Run now". No editing; to change a gesture,
    delete it and create it again.
  - Create flow: Trigger (Motion only) → record Movement → Confirm movement → pick Action → name + "Allow on
    lock screen".
  - Motion trigger: recording, confirmation and live detection through **one** segmenter and matcher (ADR 0008).
  - Actions: **Open app** (any launcher app), **Flashlight** (toggle), **Lock screen** (turns the display off),
    **Do Not Disturb** (toggle), **Sound mode** (set Ring / Vibrate / Silent).
  - Always-on host: AccessibilityService that reads no screen content (ADR 0007); detection only while the screen
    is on; per-gesture lock-screen opt-in.
  - Permission / special-access UX: accessibility explainer, notification-policy explainer (only when a DND or
    sound-mode action is picked).
  - Threshold calibration on a **synthetic**, physically plausible trace corpus (decision 5). Thresholds remain
    **unverified on real human motion** until the release-gating follow-up (see "Release gate").
  - README section explaining the accessibility use.
- **Non-goals**
  - **Wi-Fi, Bluetooth, NFC toggles.** Not possible for a normal app targeting our SDK:
    `WifiManager.setWifiEnabled` "will always fail and return false" for apps targeting API 29+, and
    `BluetoothAdapter.enable()/disable()` "will always fail and return false" for apps targeting API 33+.
    Only device owner, profile owner and system apps are exempt (both verified,
    https://developer.android.com/reference/android/net/wifi/WifiManager#setWifiEnabled(boolean),
    https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#enable()). NFC has no public
    toggle API (*inferred*). Doing it through accessibility UI automation would need
    `canRetrieveWindowContent`, which we refuse. A later "open the Wi-Fi/Internet panel" action
    (`Settings.Panel`) is possible but out of scope.
  - Media / volume actions. Multiple actions per gesture: the model allows it (ADR 0004) but the UI creates one.
  - Editing a gesture, re-recording a movement in place, import/export, backup of gestures.
  - Gestures while the screen is off (maintainer decision). Touch gestures (v2), edge handle (v3).
  - A user-facing sensitivity setting (decision 4).
  - Recording real sensor traces: no trace recorder, no real fixtures in this spec (decision 5).
  - A dedicated "secure camera" action (`MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE`) that opens the camera
    without unlocking. Possible later.

## Wording (user-facing ↔ domain)
The maintainer used "Gesture", "Trigger", "Rule" (= the movement) and "Action". "Rule" means something else in
the domain (AGENTS.md §4), so the UI never says "rule". UI wording is **Gesture → Movement → Action**:

| UI term | Meaning | Domain (ADR 0004) |
|---|---|---|
| **Gesture** | what the user creates, lists, enables, deletes | `Rule` |
| **Trigger: Motion** | how the gesture is started (only option in v1; v2 adds "Touch", v3 "Edge handle") | `TriggerSpec.Motion` |
| **Movement** | the recorded and confirmed motion | `MotionTemplate` (config of the motion trigger) |
| **Action** | what happens | `ActionSpec` |
| **Allow on lock screen** | gesture may run while the lock screen is showing | `RuleConditions.allowOnLockScreen` |

## User-visible behavior

### Gesture list (start destination)
- Top bar "Gestures"; FAB "New gesture".
- Empty state: short explanation ("Teach your phone a movement and choose what it does") + "New gesture".
- Row: name; action summary ("Opens Camera", "Toggles flashlight", "Locks the screen", "Toggles Do Not
  Disturb", "Sets sound to Vibrate"); "Works on lock screen" label if enabled; enabled switch (whole row
  toggleable, `Role.Switch`); overflow menu with **Run now** and **Delete** (confirmation dialog).
- **Needs attention** (text plus icon, never color alone), with a fix button:
  - DND / sound-mode gesture without notification-policy access → "Needs Do Not Disturb access" → explainer.
  - Open-app gesture whose app is no longer installed → "App not installed" (fix = delete and recreate).
  - Last background run failed, e.g. flashlight in use by the camera → "Last run failed: ‹reason›". Kept in
    memory only; cleared on the next success or app restart.
- **Banner** while the accessibility service is not enabled: "Gestures are paused. Mighty Gestures needs
  Accessibility access to react to movements while you use other apps." → **Set up** → accessibility explainer.
  The state is re-checked on every resume.

### Create gesture (one destination with steps; back goes to the previous step)
1. **Trigger.** "What starts this gesture?" One option, **Motion**: "Move your phone in a particular way."
   Preselected; **Next** (decision 7).
2. **Movement.** Instructions: "Hold your phone the way you will when using this gesture. Tap Record, hold
   still for a moment, then make your movement. Recording stops when you hold still again."
   - Tap **Record** → "Hold still…" (until the segmenter is ARMED: 500 ms quiet) → "Move now" → at movement
     onset "Recording…" → after 500 ms of stillness "Movement recorded" → automatically to step 3.
   - Failures, each with **Try again**: no movement within 10 s ("No movement detected"); too short or too gentle
     ("Make a bigger, more distinct movement"); longer than 3 s ("Keep the movement under 3 seconds").
3. **Confirm.** "Do the same movement once more." Same capture UI.
   - Match → "Movement confirmed" → step 4.
   - No match → "That didn't match your first movement. Try again." Unlimited retries. After 3 failures also
     offer **Record a new movement** (back to step 2, first recording discarded).
   - Too similar to an existing gesture → "This movement is too similar to “‹name›”. Record a different
     movement." → back to step 2 (decision 6).
4. **Action.** "What should happen?" List: Open app · Flashlight · Lock screen (turns the display off) · Do Not
   Disturb · Sound mode.
   - **Open app** → app picker (searchable list of launcher apps with icon and label; Mighty Gestures itself
     excluded).
   - **Do Not Disturb** / **Sound mode** (then choose Ring / Vibrate / Silent): if notification-policy access is
     missing → explainer ("To switch Do Not Disturb or sound mode, Android requires you to allow Mighty
     Gestures to change Do Not Disturb. Nothing else is accessed.") → **Open settings**
     (`Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS`) → re-check on return. Still missing: stay on the
     action list with an inline note. The user may pick another action.
   - **Try it** runs the selected action now. Lock screen needs the accessibility service; if it's off, "Try it"
     explains that and offers setup.
5. **Finish.** Name (prefilled from the action, e.g. "Open Camera"; non-blank; ≤ 40 characters; duplicates
   allowed). Switch **Allow on lock screen**, default **off**, with the explanation "When on, this gesture also
   works while your phone is locked and the screen is on. Off reduces accidental triggers, e.g. in a pocket."
   **Save** → back to the list. If the accessibility service is off, the list shows the banner.
- Leaving the flow (close or back from step 1) after a movement was recorded asks "Discard this gesture?".
- **Live detection is paused** while step 2 or 3 is on screen, so recording a movement never fires existing
  gestures.

### Accessibility explainer (from banner or "Try it" on Lock screen)
"Why Accessibility?" Uses the same text as the README section (appendix A): what it enables (reacting while other
apps are open, locking the screen, opening apps from the background) and what it does **not** do (read the
screen, see what you type, collect or send data). Buttons: **Open Accessibility settings**
(`Settings.ACTION_ACCESSIBILITY_SETTINGS`). Hint: "If the switch is greyed out ("Restricted setting"): open App
info → ⋮ → Allow restricted settings, then try again." → **Open App info**
(`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`). Back always returns to the list.

### Firing (live)
- Gestures fire only while the screen is on. With the lock screen showing, only gestures with "Allow on lock
  screen" fire.
- One movement fires at most one gesture: the best match. The same gesture cannot fire again within 1.5 s.
- No sound, vibration or notification on firing (decision 10). The action itself is the feedback.

### Per-action behavior on the lock screen (only for gestures with "Allow on lock screen")
| Action | Unlocked | Lock screen showing |
|---|---|---|
| Open app | opens the app | shows the unlock prompt (bouncer); after unlock the app opens; if the user cancels, nothing opens. Non-secure keyguard (swipe/none) is dismissed immediately (verified behavior of `requestDismissKeyguard`) |
| Flashlight | toggles | toggles (*inferred*; device AC-A4) |
| Lock screen | locks, display off | display off (already locked) (*inferred*) |
| Do Not Disturb | toggles our DND mode | same (*inferred*) |
| Sound mode | sets mode | same (*inferred*) |

## Acceptance criteria
IDs are referenced by tests and reviews. "Device" = verified on emulator AVD `mg_api35` **and** the API 37 AVD
`mg_api37` (decision 13), plus one real device for sensor-dependent ones.

**List (L)**
1. **AC-L1** With no gestures, the list shows the empty state and a "New gesture" action.
2. **AC-L2** Each row shows name, action summary, lock-screen label when applicable, and an enabled switch; the
   whole row toggles with `Role.Switch` semantics.
3. **AC-L3** Toggling the switch persists immediately and survives process death. A disabled gesture never fires
   (engine test).
4. **AC-L4** Delete asks for confirmation. On confirm, the rule and its template are removed from the store
   (repository test reads the file back).
5. **AC-L5** No upper limit: a list of 200 gestures renders and scrolls (lazy list; Compose test with fakes),
   and the store round-trips 200 rules.
6. **AC-L6** The "Gestures are paused" banner is shown iff the accessibility service is not enabled and is
   re-evaluated on resume.
7. **AC-L7** A gesture needing attention (missing policy access, uninstalled app, last run failed) shows a text
   label + fix action (Compose semantics test).
8. **AC-L8** "Run now" executes the action through the same executor as live firing. For Lock screen without
   the service, it shows the explanation instead of failing silently.

**Create flow (C)**
9. **AC-C1** Steps run Trigger → Movement → Confirm → Action → Finish. Back returns to the previous step. Leaving
   after a recording asks to discard. The trigger step offers exactly one type, Motion, preselected.
10. **AC-C2** Recording uses the shared segmenter: "Hold still" until ARMED, onset starts recording, 500 ms of
    quiet ends it. The stored exemplar ends at the last active frame (± 1 frame) and contains the 100 ms
    pre-roll (trace test).
11. **AC-C3** No onset within 10 s of tapping Record → "No movement detected" + retry; nothing stored.
12. **AC-C4** A recording failing the validator (too short < 250 ms, too gentle, > 3 s) shows the specific
    message; nothing stored (trace tests per case).
13. **AC-C5** Confirmation with a matching repeat (positive fixture pair) succeeds; with a different movement
    (negative pair) shows "didn't match". After 3 failures "Record a new movement" is offered.
14. **AC-C6** A movement within `1.2 τ` of any existing gesture's exemplars is rejected naming that gesture
    (decision 6).
15. **AC-C7** The action step offers exactly: Open app, Flashlight, Lock screen, Do Not Disturb, Sound mode
    (Ring/Vibrate/Silent). "Try it" executes the selected action.
16. **AC-C8** Notification-policy access is requested **only** after the user picks Do Not Disturb or Sound mode,
    always after the explainer. A test creates Flashlight / Open app / Lock gestures and asserts no policy-access
    intent is fired.
17. **AC-C9** The app picker shows launchable apps only (MAIN/LAUNCHER), excluding Mighty Gestures, sorted by
    label, filterable by text. The merged manifest has a `<queries>` MAIN/LAUNCHER intent and **no**
    `QUERY_ALL_PACKAGES`.
18. **AC-C10** Finish requires a non-blank name ≤ 40 characters. "Allow on lock screen" defaults to off. Save
    stores the rule with both exemplars, `enabled = true`.
19. **AC-C11** While the Movement or Confirm step is visible, no rule is armed (`captureInProgress`); live sensor
    listeners are unregistered (engine test + device).
20. **AC-C12** Process death in the middle of the flow does not crash. The flow restarts at step 1 (recorded
    exemplars are not persisted before Save).
20a. **AC-C13** Leaving the app (Home, recents, screen off) during the Movement or Confirm step cancels the
    capture: capture listeners are unregistered and live detection is re-armed within 1 s (`dumpsys
    sensorservice` on device; Robolectric lifecycle test).

**Motion core (M)**, pure Kotlin, trace-tested
21. **AC-M1** `domain/**` contains no `android.*`/`androidx.*` imports (detekt ForbiddenImport, ADR 0003).
22. **AC-M2** Capture (record/confirm) and live detection use the same `Segmenter`, `Preprocessor`, `Matcher` and
    τ. The same trace fed through the capture session and the live pipeline yields identical segment boundaries
    and distances.
23. **AC-M3** (synthetic corpus, milestone 3) For each of ≥ 6 synthetic reference gestures (e.g. shake,
    double chop, twist, flip, circle, tap-tap) and each of its ≥ 10 seeded "performer" variants: record +
    confirm passes and ≥ 4 of 5 further repeats match. *This proves the pipeline against the synthetic model only;
    it says nothing about recall on real human motion (see AC-R1).*
24. **AC-M4** **False-positive budget on the synthetic corpus: zero** matches across the whole synthetic negative
    corpus (≥ 30 min of simulated time) against every synthetic reference template. Situations modeled: walking,
    stairs, running, phone in hand while reading/scrolling, typing taps, picking up from a table, putting down,
    pocket in/out, bag, car ride, handing the phone over. *Same caveat: unverified on real motion.*
25. **AC-M5** Robustness: identical verdicts (match/no-match, ± 1 frame boundaries) at 50/100/200 Hz, ± 2 ms
    timestamp jitter, 5 % dropped samples, ± 0.2 m/s² bias, any static gravity orientation.
26. **AC-M6** Timing: a movement starting during SETTLING is not segmented. Two movements separated by ≥ 600 ms
    of quiet give two segments; by < 500 ms, one. A rule matched twice within 1.5 s fires once.
27. **AC-M7** Continuous motion longer than 3 s (walking trace) never yields a segment.
28. **AC-M8** When a segment matches several armed rules, only the lowest-distance rule fires.
29. **AC-M9** The per-sample path allocates nothing. A benchmark test feeding a 10-minute 50 Hz trace asserts
    zero allocated bytes in `onSample` after warm-up (thread allocation counter) and reports per-segment matching
    time for 50 rules (budget < 2 ms on the dev JVM).
30. **AC-M10** ACC-only (no gyroscope) templates record, confirm and match (synthetic traces).
30a. **AC-M11** Synthetic traces are physically plausible and labeled. Each generated or committed trace
    starts with `# source=synthetic; generator=<class>@<version>; seed=<n>; model=<situation/gesture>`. The
    generator's own tests assert:
    - the static `|ACC|` is 9.81 ± 0.05 m/s²;
    - the device-frame gravity direction rotates consistently with the integrated gyro (≤ 3° error per second of
      motion);
    - motion components are band-limited to human movement (< 15 Hz, except modeled impacts and vibration);
    - noise and bias levels are within the declared sensor model.
    No trace is presented as a human recording.

**Actions (A)**
31. **AC-A1** Open app from the background (home screen visible, app not in foreground) opens the target (device).
32. **AC-A2** (decision 12) Open app with the lock screen showing (secure keyguard): bouncer
    appears; the trampoline survives while the bouncer is shown; after unlock the target opens; cancel →
    nothing opens and the trampoline finishes (device, API 35 **and** 37).
33. **AC-A3** Open app for an uninstalled package → `Failed(AppNotFound)`, no crash; the row shows "App not
    installed".
34. **AC-A4** Flashlight toggles on/off with **no CAMERA permission** in the merged manifest. `CAMERA_IN_USE` or
    other `CameraAccessException` → `Failed(TorchUnavailable)`, no crash (Robolectric). Works on the lock screen
    (device).
35. **AC-A5** Lock screen calls `performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)` on the bound service; with the
    service unbound → `Failed(MissingAccess(ACCESSIBILITY_SERVICE))`.
36. **AC-A6** (decision 8) Do Not Disturb toggles an app-owned "Mighty Gestures" mode on/off
    (explicit `AutomaticZenRule`).
    Without access → `Failed(MissingAccess(NOTIFICATION_POLICY))` and the row needs attention. Deleting the last
    DND gesture removes the app's mode.
37. **AC-A7** Sound mode sets the chosen ringer mode. Without access → `Failed(MissingAccess)`. If
    `isVolumeFixed()` → `Failed(Unsupported)`.
38. **AC-A8** `platform/action` and `domain/action` never import from `…domain.trigger` (code review; a grep
    in the PR description is enough evidence).

**Host (H)**
39. **AC-H1** The service is declared per ADR 0007: exported only with `BIND_ACCESSIBILITY_SERVICE`,
    `canRetrieveWindowContent=false`, `isAccessibilityTool=false`, no event types. On device it binds and
    `onAccessibilityEvent` is never called during a 5-minute session of normal use (debug counter).
40. **AC-H2 (gating)** With the service enabled and **no Mighty Gestures activity visible**, accelerometer and
    gyroscope events are delivered and a gesture fires. Check: `adb shell dumpsys sensorservice` lists our
    package at ~20 ms; `adb shell dumpsys activity processes` shows our proc state as
    bound-foreground-service. Device: API 35 and 37 + real device. **If this fails, stop and supersede ADR 0007
    with the FGS + accessibility fallback.**
41. **AC-H3** Screen off → our sensor registrations are gone from `dumpsys sensorservice` within 1 s. Screen on
    (with armed rules) → registered again.
42. **AC-H4** Lock screen showing: with no lock-screen gesture, no sensor registration; with one, registered,
    and only lock-screen gestures can fire. After unlock (`USER_PRESENT`) all enabled gestures are armed.
43. **AC-H5** No enabled gesture → no sensor registration.
44. **AC-H6** No action fires while the screen is off (engine test over DeviceState sequences + device check).
45. **AC-H7** Disabling the service (or unbind) unregisters all listeners and receivers and cancels its scope
    (Robolectric).
46. **AC-H8** The service class contains no business logic: it only wires the container's engine, sensor source,
    screen-state monitor and host handle (code review).

**Privacy & permissions (P)**
47. **AC-P1** Merged release manifest = **baseline + exactly these additions**. The baseline (verified in
    today's `processReleaseMainManifest` output) is the AndroidX-merged signature permission
    `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` and its `<uses-permission>`. The only added `<uses-permission>`
    is `ACCESS_NOTIFICATION_POLICY`. Absent: `INTERNET`, `QUERY_ALL_PACKAGES`, `CAMERA`, `FOREGROUND_SERVICE*`,
    `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `SYSTEM_ALERT_WINDOW`, `VIBRATE`. Checked by diffing the
    merged manifest against the baseline in the PR, or by a manifest test.
48. **AC-P2** Every **app-declared** component declares `android:exported`. Exported app components:
    `MainActivity` (launcher, plus the `ACTION_AUTOMATIC_ZEN_RULE` filter, decision 8) and the
    accessibility service. The trampoline is `exported=false`. Library-merged components stay as in the
    baseline (`androidx.profileinstaller.ProfileInstallReceiver`, exported but `DUMP`-protected;
    `androidx.startup.InitializationProvider`, not exported). Any new library-merged component is called out
    in the PR.
49. **AC-P3** `dataExtractionRules` excludes `datastore/` from `cloud-backup` and
    `device-transfer`.
50. **AC-P4** No sensor values, gesture names or package names are logged in release builds (logger guarded by
    `BuildConfig.DEBUG`; review).
51. **AC-P5** The accessibility explainer is shown before opening settings and contains the "does not" list and the
    restricted-settings hint.
52. **AC-P6** (decision 5) No build contains a sensor-trace recorder or any sensor-data export. The synthetic
    trace generator lives only in the `test` source set.

**Battery (B)**
53. **AC-B1** The host PR reports, per docs/engineering/sensors-and-power.md: registered sensors and actual
    rates; `batterystats` showing **no app wakelocks**; a 60-minute screen-on comparison with 3 enabled gestures
    vs. service disabled on a real device (target ≤ 1 percentage point per hour extra drain, *inferred* target to
    be confirmed by the maintainer); Doze (`deviceidle force-idle`) does not break behavior after the screen
    turns back on.

**Release gate (R)**
53a. **AC-R1** **Thresholds are unverified on real human motion.** Before the first public release (first
    `vX.Y.Z` tag / F-Droid submission), a follow-up spec must validate or re-calibrate `A_on`, `G_on`, the
    validator gates and `τ` on real-device human motion. It must re-run AC-M3/M4-equivalent checks on real data.
    Until then, `CHANGELOG.md` and any pre-release notes say detection thresholds are provisional. Not closed by
    this spec.

**Docs (D)**
54. **AC-D1** README contains the section "Why does Mighty Gestures need Accessibility?" (appendix A text,
    adjustable in review).
55. **AC-D2** `CHANGELOG.md` updated, including the note that detection thresholds are provisional (AC-R1).
    ADRs 0003–0009 were accepted on 2026-10-01; any change in implementation supersedes them with a new ADR.

## Design

### Modules, packages, key files (ADR 0003)
Single `:app` module. New packages: `domain/{model,engine,trigger,trigger/motion,action,repository,time}`,
`data/`, `platform/{sensor,accessibility,action,apps,access}`, `ui/{navigation,gestures/list,gestures/create,
apppicker,access}`, `di/`, plus `MightyGesturesApp`. Test-only: `app/src/test/.../motion/synthetic/`
(trace generator, sensor and situation models).
detekt: `ForbiddenImport` with `forbiddenPatterns: '^(android|androidx)\..*'` and
`includes: ['**/domain/**']` (ADR 0003). The "actions never reference triggers" rule (AC-A8) is enforced by
code review, because detekt has a single `ForbiddenImport` configuration per project.

### Interfaces (ADR 0004)
`Rule`, `TriggerSpec`, `ActionSpec`, `RuleConditions`, `DeviceState`, `TriggerSource`, `TriggerEvent`,
`ActionExecutor`, `ActionResult`, `RuleEngine`, `RuleRepository` exactly as in ADR 0004. Motion-specific:

```kotlin
// domain/trigger/motion
enum class SensorKind { ACC, GYRO }
interface MotionSampleSink { fun onSample(kind: SensorKind, timestampNanos: Long, x: Float, y: Float, z: Float) }
class MotionPipeline(config: MotionConfig) : MotionSampleSink        // FrameBuilder + Segmenter; emits segments
class MotionMatcher(config: MotionConfig)                            // preprocess, gates, DTW
class TemplateValidator(config: MotionConfig)
class CaptureSession(pipeline, validator, matcher, monotonicClock)   // record / confirm, 10 s timeout
class MotionTriggerSource(sampleSourceFactory, pipeline, matcher, …) : TriggerSource
data class MotionTemplate(val exemplars: List<MotionExemplar>, val channels: Set<SensorKind>, val algorithmVersion: Int)
data class MotionExemplar(/* raw ACC/GYRO samples of the segment, primitive arrays, gravity at start */)

// platform/sensor
interface MotionSampleSource { fun start(sink: MotionSampleSink, mode: CaptureMode /* CAPTURE | LIVE */); fun stop() }
```
`AndroidMotionSampleSource` registers ACC (+ GYRO if present) at 20 000 µs with latency 0 (CAPTURE) or
100 000 µs (LIVE) on the `mg-sensors` HandlerThread. It forwards each `SensorEvent` as a primitive `onSample`
call and holds no references to events.

### Rule engine and device state
`DeviceStateHolder` (container singleton) combines `interactive` and `keyguardLocked` from the service's
`ScreenStateMonitor` with `captureInProgress`.
**Capture is bound to the capture screen's lifecycle, not to the ViewModel.** The Movement/Confirm step
starts the capture sensor registration and sets `captureInProgress` on `ON_START`.
It cancels the capture, unregisters the listeners and clears the flag on `ON_STOP`. Clearing also happens on
step change and in `onCleared` as a safety net. This matters because the accessibility binding keeps our UID
active (ADR 0007 F5): a listener registered from the UI keeps sampling after Home is pressed unless we stop it,
and a stale flag would pause every gesture system-wide. Returning to the step shows it in its initial state
("Record"); a half-done capture is not resumed. The engine computes armed rules (ADR 0004) and passes the
motion subset to `MotionTriggerSource`, which starts or stops the sample source. On a segment, the source
matches against armed templates and emits the best `TriggerEvent`. The engine re-checks state, applies the
1.5 s cooldown, and calls `ActionExecutor`. Failures are kept in an in-memory `LastRunStatus` map for the list.

### Data model & persistence (ADR 0006)
`files/datastore/gestures.json`: `GestureStoreDto(schemaVersion = 1, rules: List<RuleDto>)`, with polymorphic
trigger/action DTOs and templates as base64 primitive arrays. A schema fixture is committed for migration tests.
`res/xml/data_extraction_rules.xml` excludes `datastore/` from cloud backup and device transfer.

### Actions (`platform/action`, one class per action behind a dispatching `AndroidActionExecutor`)
- **Open app.** Catalog: `queryIntentActivities(Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER))`, visible
  through `<queries><intent>MAIN/LAUNCHER</intent></queries>`. Declaring intent-filter signatures in `<queries>`
  is the documented mechanism (verified, https://developer.android.com/training/package-visibility/declaring).
  Execute: `getLaunchIntentForPackage(pkg)` + `FLAG_ACTIVITY_NEW_TASK`; `null` → `AppNotFound`.
  - Unlocked: `startActivity` from the service context (BAL exemption via the accessibility binding, ADR 0007
    F6).
  - Keyguard showing (decision 12): start `LaunchOverKeyguardActivity` (`exported=false`,
    `showWhenLocked=true`, `excludeFromRecents`, translucent no-UI theme, own task). **No `noHistory`:** the
    docs say the dismiss callback "will not be invoked if the activity was destroyed before the callback was
    received", and `noHistory` could finish it while the bouncer covers it. The activity calls `finish()`
    itself in every callback path, plus a 60 s safety timeout. It calls
    `KeyguardManager.requestDismissKeyguard(this, callback)`. That method needs an activity visible over the
    keyguard and brings up credential UI for a secure keyguard (verified,
    https://developer.android.com/reference/android/app/KeyguardManager#requestDismissKeyguard(android.app.Activity,%20android.app.KeyguardManager.KeyguardDismissCallback)).
    `onDismissSucceeded` → `startActivity(launchIntent)`, `finish()`; cancelled or error → `finish()`. The only
    extra is the package name; it is re-resolved via the catalog, never used as a raw component. No
    `PendingIntent` anywhere. The API 37 task-hijacking rules (ADR 0007 F9) are expected not to block this,
    because the trampoline is the top activity of a foreground task with our UID (*inferred*, AC-A2 on API 37).
- **Flashlight.** `CameraManager`: pick the first camera with `FLASH_INFO_AVAILABLE` (prefer back-facing). Track
  state through `registerTorchCallback` while the host runs. Toggle with `setTorchMode(id, !on)`.
  `setTorchMode` documents no permission requirement (verified,
  https://developer.android.com/reference/android/hardware/camera2/CameraManager#setTorchMode(java.lang.String,%20boolean)),
  so **no CAMERA permission** is needed. The same page states the torch turns off if the app that turned it on
  exits. Our process is kept alive by the accessibility binding (*inferred*); "Run now" with the service off may
  have the torch go off when the process dies. `CameraAccessException` → `TorchUnavailable`.
- **Lock screen.** `AccessibilityHostHandle.service?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)` (API 28,
  verified). One action for "turn off display" and "lock": no public API turns the display off without
  locking (ADR 0007 F8, *inferred*). The UI label is "Lock screen (turns the display off)".
- **Do Not Disturb.** For apps targeting API 35+, `setInterruptionFilter` no longer changes global DND; it
  toggles an implicit app-owned `AutomaticZenRule` (verified,
  https://developer.android.com/about/versions/15/behavior-changes-15 and
  https://developer.android.com/reference/android/app/NotificationManager#setInterruptionFilter(int)).
  We therefore use an **explicit** `AutomaticZenRule` named "Mighty Gestures" (interruption filter
  PRIORITY, the user's default DND policy; *inferred* to appear in Settings → Modes). The rule needs an owner or
  a configuration activity: "at least one of getOwner() or getConfigurationActivity() must be provided"
  (verified, https://developer.android.com/reference/android/app/AutomaticZenRule). We use `MainActivity` with
  an added intent filter for `NotificationManager.ACTION_AUTOMATIC_ZEN_RULE`; it just opens the app and
  ignores extras. This filter makes `MainActivity` reachable by one more intent action (decision 8).
  The rule is found again with `getAutomaticZenRules()`, which "Returns AutomaticZenRules owned by the caller"
  (verified, same NotificationManager page), by its condition URI. No ID is persisted. Toggle: `getAutomaticZenRuleState(id)` (API 35, verified) → `setAutomaticZenRuleState(id,
  Condition(…, STATE_TRUE/FALSE, SOURCE_USER_ACTION))`.
  **Limitation (platform):** the gesture can only switch *its own* mode. It cannot turn off DND the user turned on
  manually or through another mode. The `setAutomaticZenRuleState` docs warn that "the condition change may be
  ignored if the user has activated or deactivated the rule manually" (verified). We therefore report
  `SOURCE_USER_ACTION`: with the `modes_ui` flag, AOSP replaces the user's manual override with a user-action
  condition from the owning app (verified, `ZenModeHelper.applyConditionAndReconsiderOverride`, AOSP main).
  Without it (`android15-release`), a manual "turn off" snoozes the rule, and a gesture reporting `STATE_TRUE`
  does not clear the snooze (verified, same file, `updateSnoozing`); the toggle may then need one extra gesture
  (*inferred*; device check in PR #6). Which releases enable
  `modes_ui` is *inferred* (Android 16+). `addAutomaticZenRule` throws `SecurityException` without policy
  access (verified).
- **Sound mode.** `AudioManager.setRingerMode(NORMAL|VIBRATE|SILENT)`. "Ringer mode adjustments that would toggle
  Do Not Disturb are not allowed unless the app has been granted Notification Policy Access" (verified,
  https://developer.android.com/reference/android/media/AudioManager#setRingerMode(int)). Which transitions
  touch DND varies by device (*inferred*), so we require policy access for **all** sound-mode gestures. That
  keeps behavior predictable instead of failing on some phones. `isVolumeFixed()` → `Unsupported`.
- `ACCESS_NOTIFICATION_POLICY` is a normal-protection "marker permission" that must be declared so the app can
  appear in the policy-access list (verified,
  https://developer.android.com/reference/android/Manifest.permission#ACCESS_NOTIFICATION_POLICY). The grant
  itself is the special access the user gives in Settings.

### Accessibility host (ADR 0007)
`MightyGesturesAccessibilityService`:
- `onServiceConnected`: create a `CoroutineScope(SupervisorJob() + dispatchers.default)`; start
  `ScreenStateMonitor` (receiver for SCREEN_ON/OFF/USER_PRESENT with `RECEIVER_NOT_EXPORTED`, initial state from
  `PowerManager.isInteractive` / `KeyguardManager.isKeyguardLocked`); publish itself to
  `AccessibilityHostHandle`; start `RuleEngine` with the motion source.
- `onUnbind` / `onDestroy`: reverse all of it.
- `onAccessibilityEvent` / `onInterrupt`: empty.

### Threading / lifecycle
- Sensor callbacks, segmentation and matching: `mg-sensors` HandlerThread (created per registration session,
  quit on stop). Trigger events cross into the engine through a `Channel` (`trySend`, buffered) consumed on the
  service scope.
- Actions: `startActivity`, `performGlobalAction`, `CameraManager`, `NotificationManager` and `AudioManager` are
  invoked on `dispatchers.main` (*inferred* safe on any thread; main keeps it simple).
- Dispatchers and clocks are injected (`AppDispatchers`, `MonotonicClock` = `SystemClock.elapsedRealtimeNanos`
  adapter, `WallClock` for `createdAt`).

### Synthetic trace corpus and calibration (decision 5: synthetic only)
The maintainer will not record real traces. **No trace recorder is built.** Reasons:
- nobody would use it in this spec;
- debug-source-set code counts toward the JaCoCo gate and would need its own tests;
- it adds a screen, a storage directory and a security-review surface;
- the AGENTS.md §2 amendment (decision 1) only needs to cover gesture templates.
A future maintainer-driven calibration can still add a recorder cheaply in its own spec. The CSV trace format
(ADR 0008) and the raw-window template storage (ADR 0006) are kept for exactly that purpose.

**Generator** (`app/src/test/.../motion/synthetic/`, test source set only, pure Kotlin, seeded and
deterministic):
- **Sensor model:** 50 Hz nominal output with configurable rate (50/100/200 Hz). Timestamp jitter, dropped
  samples, white noise, per-axis bias and scale error, and quantization. Noise and bias levels are typical
  consumer-IMU orders of magnitude, declared as constants with a one-line rationale each (*inferred*, not
  measured).
- **Kinematics:** device pose as a quaternion driven by an angular-velocity profile, and position driven by a
  minimum-jerk or sinusoidal acceleration profile. ACC = R(t)ᵀ·(a_world + g); GYRO = ω in the device frame. Every
  rotation therefore moves gravity across the axes consistently with the gyro (AC-M11).
- **Reference gestures** (≥ 6): shake, double chop, twist, flip face-down, circle, tap-tap. Each is a
  parametric primitive sequence.
- **Performer variability** (per seed): tempo ±30 %, amplitude ±30 %, grip tilt ±15°, physiological tremor
  (8–12 Hz, small amplitude), and a 0–300 ms lead-in or tail of handling.
- **Negative situations:** walking (1.6–2.2 Hz steps), stairs, running, reading/scrolling hand sway, typing
  taps, pick-up, put-down, pocket in/out, bag sway, car (road vibration + turns + braking), handing over.
  Parameter ranges are taken from published human-motion literature where available. Each range is cited in
  KDoc; uncited ranges are labeled *assumed*.
- **Output:** in-memory sample streams for tests. Also the ADR 0008 CSV with a
  `# source=synthetic; generator=…; seed=…; model=…` header, used only for a few small committed golden fixtures
  (parser and regression tests). Nothing is labeled or described as a human recording.

**Calibration (milestone 3):**
- A calibration test sweeps `A_on`, `G_on`, the validator gates and `τ` on **tuning seeds**.
- It picks the values that satisfy AC-M4 (zero false positives) with the largest margin, then maximizes AC-M3
  recall.
- It then verifies AC-M3/M4 on **held-out seeds** that were never used for tuning.
- The chosen values go into `MotionConfig`, whose KDoc says: "calibrated on synthetic corpus v1; unverified on
  real human motion (spec 0001 AC-R1)". The sweep table goes in the PR description.
- This tuning is circular: thresholds are fitted to our own motion model. It catches gross errors only. It is
  not evidence of real-world accuracy, which is why AC-R1 gates the first public release.

### Manifest changes (summary)
- `<uses-permission android:name="android.permission.ACCESS_NOTIFICATION_POLICY"/>`
- `<queries><intent><action MAIN/><category LAUNCHER/></intent></queries>`
- `<application android:name=".MightyGesturesApp" android:dataExtractionRules="@xml/data_extraction_rules">`
- accessibility `<service>` + `res/xml/accessibility_service_config.xml` (ADR 0007)
- `LaunchOverKeyguardActivity` (`exported=false`, `showWhenLocked=true`) — decision 12
- `MainActivity` gains an intent filter for `android.app.action.AUTOMATIC_ZEN_RULE` — decision 8

## Permissions, privacy & security impact
| Item | Type | Why | When requested | Without it |
|---|---|---|---|---|
| Accessibility service | special access | background sensing, lock, background app launch (ADR 0007) | from the banner / "Try it", after the explainer | gestures don't fire; the list shows "paused"; creating and "Run now" (except Lock) still work |
| Notification policy access + `ACCESS_NOTIFICATION_POLICY` (normal) | special access | DND mode and ringer changes | only when the user picks DND or Sound mode | those actions can't be chosen; existing ones "need attention" |
| `<queries>` MAIN/LAUNCHER | package visibility | app picker | n/a | — |

- **Exported components:** accessibility service (system-bound, permission-protected); `MainActivity` (launcher +
  zen-rule configuration intent, no extras trusted). Trampoline not exported.
- **Data stored:** gesture definitions including **movement templates = short raw sensor windows**. Approved
  as decision 1; this needs the AGENTS.md §2 amendment, which the maintainer applies (see decision 1). No other
  sensor data is stored in any build (no trace recorder, decision 5). Excluded from backup and transfer.
- **Logged:** nothing sensor-related, no gesture names or package names in release.
- **Not used:** INTERNET, CAMERA, overlay, FGS, notifications, boot receiver, device admin, `QUERY_ALL_PACKAGES`.
- security-reviewer must review milestones 2 (template persistence, backup rules), 4 (actions, trampoline) and 6
  (manifest, service, receivers).

## Battery & performance impact
- Sensors: ACC (+ GYRO) at 50 Hz, non-wake-up, latency 100 ms live / 0 capture. Registered only while the screen is
  on, ≥ 1 rule is armed, and no capture is running. Unregistered on screen off (the platform will not do it for
  us, ADR 0007 F5).
- No wakelocks, no alarms, no jobs, no FGS. The service process stays alive while enabled; its memory is
  templates in memory (≈ 15 KB/rule, *inferred*).
- CPU: O(1) zero-allocation per sample; DTW only at segment end.
- Measurement (AC-B1, AC-M9): `dumpsys sensorservice`, `dumpsys activity processes`, `dumpsys batterystats
  --charged cz.mightybities.mightygestures` after `--reset`, a 60-minute screen-on A/B on a real device, the JVM
  benchmark. The gyroscope is the main cost (*inferred*). If the A/B shows a problem, the performance reviewer
  may propose an ACC-only gate that enables the gyro only after ACC activity, a follow-up ADR.

## Test plan
- **JVM (domain, most tests):** segmenter state machine (every transition, timeouts, gaps); gravity filter;
  preprocessor; DTW (known small matrices); gates; validator; matcher selection (best-of-N); capture session;
  rule engine armed-set computation over `DeviceState` sequences, cooldown and re-check at fire time (Turbine +
  `runTest`, virtual time); repository serialization round-trip, schema fixture, corruption fallback;
  `requiredAccess()`.
- **Trace tests — synthetic only (decision 5):** positive, negative, robustness and timing cases are produced by
  the seeded generator ("Synthetic trace corpus and calibration"). A few small golden CSVs under
  `app/src/test/resources/traces/motion/` carry the `# source=synthetic…` header. Tuning seeds and held-out seeds
  are kept separate. Never tune on one trace without rerunning the corpus (testing.md). **No real human traces
  exist.** Passing AC-M3/M4 shows the pipeline is consistent with our motion model, not that it works on people
  (AC-R1).
- **Benchmark:** AC-M9 (allocation counter via `com.sun.management.ThreadMXBean`; JVM-only test).
- **Robolectric** (verify each shadow against Robolectric 4.17 before relying on it; otherwise wrap the
  framework call in a thin interface and fake it):
  - `AndroidMotionSampleSource`: `ShadowSensorManager` (registration, periods; inject events).
  - `ScreenStateMonitor`: send SCREEN_ON/OFF/USER_PRESENT; `ShadowPowerManager`, `ShadowKeyguardManager`.
  - Executors: `ShadowCameraManager` (torch), `ShadowNotificationManager` (policy access, zen rules),
    `ShadowAudioManager` (ringer), `ShadowPackageManager` (launcher catalog, launch intents).
  - Service: `Robolectric.setupService`, assert wiring/unwiring and `performGlobalAction` recorded.
  - Trampoline: `ShadowKeyguardManager` dismiss callbacks (success/cancel/error).
- **Compose UI tests:** list states (empty, rows, needs attention, banner, 200 rows), create flow steps with fake
  ViewModel state, discard dialog, action picker gating, app picker filter, explainer content; semantics
  (switch role, labels, no color-only meaning); large font.
- **ViewModels:** JVM tests with fakes (`CaptureSession` fake emitting results, fake repository, fake access
  checker).
- **Coverage:** keep `:app` ≥ 75 % lines on every PR. Domain ≥ 80 %. The synthetic generator is test code and
  does not count toward coverage.
- **Device / emulator** (tester, `device-verify` skill): AC-A1/A2/A4, AC-H1–H4, AC-B1 on `mg_api35` and the API 37
  AVD `mg_api37` (decision 13) plus one real device. Emulator `adb emu sensor set` is a step input and only
  verifies wiring (registration, firing on a scripted sequence), not thresholds. On the real device the
  maintainer performs a gesture by hand as a manual smoke check (AC-H2). This is not trace recording; nothing is
  saved beyond the gesture's own template.

## Risks & mitigations
| Risk | Mitigation |
|---|---|
| F5 (sensor delivery via accessibility binding) is wrong on some API level/OEM | AC-H2 gating on API 35 + 37 + real device; documented FGS + accessibility fallback (ADR 0007 Option C) |
| False positives in everyday handling | device-frame matching, distinctiveness gate, collision check, zero-FP negative corpus, cooldown, lock-screen opt-in default off, screen-off never |
| Thresholds wrong: calibrated only on synthetic motion (decision 5), so tuning is circular | held-out seeds; literature-based parameter ranges; `MotionConfig` KDoc and CHANGELOG say "provisional"; **AC-R1 release gate**: real-motion validation before the first public release; raw template storage lets templates be re-derived after re-calibration |
| Synthetic model is unrealistic (e.g. walking too regular), so the negative corpus is too easy | performer and situation variability per seed; reviewer (tester) checks model plausibility (AC-M11); AC-R1 |
| Users hold the phone differently later → misses | instruction text; future: allow adding a third exemplar (out of scope) |
| Accessibility friction (restricted settings, warnings, APM on Android 17 *inferred*) | explainer + hint + README; never claim `isAccessibilityTool` |
| DND semantics confuse users (own mode only) | explicit named mode; limitation in explainer and README FAQ |
| Torch off when process dies (service off + "Run now") | documented; acceptable |
| Recording fires existing gestures | `captureInProgress` pauses live detection (AC-C11) |
| A capture listener or the pause flag outlives the capture screen (the platform won't stop our sensors, ADR 0007 F5) | capture is bound to `ON_START`/`ON_STOP` of the screen, not the ViewModel (AC-C13) |
| Whole-file DataStore rewrites with many gestures | acceptable to ~200 gestures; revisit per ADR 0006 |
| Coverage gate with Android-heavy code | thin platform wrappers + Robolectric; each PR keeps ≥ 75 % |

## Resolved decisions
The maintainer approved all items on 2026-10-01 (relayed by the orchestrator). Twelve items match the architect's
recommendation; #5 differs. The original questions and recommendations are summarized for traceability.

1. **Storing movement templates vs AGENTS.md §2.** — **Approved.** Templates are stored as the raw movement window
   (re-processable), app-private, excluded from backup and device transfer, and deleted with the gesture
   (ADR 0006). Approved amendment to AGENTS.md §2, point 2, second sentence:
   > Sensor data never leaves the device and is never persisted except (a) gesture templates the user explicitly
   > records (only the movement window, app-private, excluded from backup and device transfer, deleted with the
   > gesture) and (b) explicit, user-initiated debug recordings.

   *Applying it:* AGENTS.md is the instruction file every agent loads (`CLAUDE.md` imports it), so the
   architect did not edit it on a relayed approval. The maintainer applies this text directly. Milestone 2
   (template persistence) must not merge before that.
2. **Accessibility as the only host** (ADR 0007). — **Approved.** Fall back to FGS + accessibility only if AC-H2
   fails.
3. **"Allow on lock screen" default.** — **Off.**
4. **User-adjustable sensitivity.** — **None in v1.** One global τ; a global Strict/Normal/Relaxed setting may
   follow in a later spec.
5. **How real traces are obtained.** — **Synthetic only.** The maintainer will not record real traces. (The
   architect had recommended a debug-only recorder plus maintainer recordings.) Consequences:
   - no recorder is built;
   - calibration uses the synthetic, physically plausible corpus;
   - thresholds are **unverified on real human motion**;
   - release gate AC-R1 requires real-motion validation in a follow-up spec before the first public release.
6. **Collision with an existing gesture.** — **Block** at distance ≤ 1.2 τ.
7. **Trigger step with a single option.** — **Show it**, Motion preselected.
8. **DND semantics.** — **Toggle an app-owned "Mighty Gestures" mode** (explicit `AutomaticZenRule`;
   `ACTION_AUTOMATIC_ZEN_RULE` intent filter on `MainActivity`). The limitation is documented in the explainer and
   README.
9. **Sound mode.** — **Set a specific mode** (Ring / Vibrate / Silent) per gesture. Notification-policy access is
   required for all three.
10. **Haptic confirmation on fire.** — **Not in v1** (no `VIBRATE`).
11. **New dependencies** (appendix B). — **Approved.**
12. **Open app on the lock screen** via the bouncer and a non-exported trampoline. — **Approved.**
13. **Device verification on API 37.** — **Approved.** A separate chore PR adds an API 37 AVD (`mg_api37`).
    It prefers an AOSP image; while none is published for API 37, Google's `google_apis` image is allowed as a
    **test-only** exception (maintainer decision 2026-10-02; never a build or runtime dependency). The maintainer
    runs AC-H2/AC-A2 on one real phone as manual smoke checks.

## Implementation order
One spec, delivered **PR by PR, one branch per milestone**, each branched from up-to-date `main` after the
previous PR merged, unless noted otherwise. Every PR keeps `scripts/verify.sh` green and `:app` line coverage
≥ 75 %. PR titles are Conventional Commits (≤ 72 characters) and become the squash commit on `main`.
Branch prefixes follow the commit type (AGENTS.md §7): `feat/` for feature PRs, plus `chore/` and `docs/` for
the two non-feature PRs.

| # | Branch | PR title | Content | ACs | Owners | Reviewers beyond code-reviewer |
|---|---|---|---|---|---|---|
| 0 | `chore/0001-0-api37-emulator` | `chore(harness): add api 37 emulator for targetsdk checks` | `mg_api37` in `scripts/setup-android-sdk.sh`, `device-verify` skill, AGENTS.md §6 command table (decision 13). Independent; must merge before #6 | — | developer | — |
| 1 | `feat/0001-1-motion-core` | `feat(detector): add motion segmenter and template matcher` | catalog: coroutines-test, turbine; detekt ForbiddenImport; gravity filter, segmenter, preprocessor, DTW matcher, validator, capture session, trace CSV parser; **synthetic generator core** (sensor model, kinematics, a few gesture primitives) for unit, robustness and timing tests; benchmark. Thresholds = ADR 0008 initial values | M1, M2, M5–M8, M9, M10 | developer; tester (test design) | performance-reviewer (hot path) |
| 2 | `feat/0001-2-rule-engine-store` | `feat(rule): add rule model, engine and gesture store` | ADR 0004 model, `RuleEngine`, `DeviceStateHolder`, DataStore + DTOs + schema fixture + corruption handler, `data_extraction_rules.xml`, `AppContainer`, `MightyGesturesApp`. **Merge only after the AGENTS.md §2 amendment is applied** (decision 1) | L3–L5 (store), H5, H6 (engine), P3 | developer; tester | security-reviewer (template persistence, backup) |
| 3 | `feat/0001-3-sensor-synthetic-calibration` | `feat(sensor): add sensor adapter and synthetic calibration` | `AndroidMotionSampleSource` (HandlerThread, rates, latency); **full synthetic corpus** (≥ 6 reference gestures × ≥ 10 performer seeds; all negative situations, ≥ 30 min simulated); plausibility tests; calibration sweep on tuning seeds, verification on held-out seeds; `MotionConfig` values + "provisional" KDoc; sweep table in the PR description. **No recorder, no real traces** | M3, M4, M11 (synthetic), P6 | developer (adapter, generator); tester (corpus, calibration, plausibility review) | performance-reviewer (registration, batching) |
| 4 | `feat/0001-4-actions` | `feat(action): add app launch, torch, lock, dnd and ringer actions` | executors, launcher catalog, `<queries>`, trampoline, zen rule + `MainActivity` filter, `ACCESS_NOTIFICATION_POLICY`, special-access checker | A1–A8 (non-device parts) | developer; tester (Robolectric) | security-reviewer |
| 5 | `feat/0001-5-gesture-ui` | `feat(ui): add gesture list and create-gesture flow` | Navigation 3, list, create flow, app picker, explainers, ViewModels, capture lifecycle binding | L1–L8, C1–C13, P5 | ui-expert (screens, semantics, previews); developer (ViewModels); tester (Compose UI + ViewModel tests) | — |
| 6 | `feat/0001-6-accessibility-host` | `feat(service): add accessibility host for motion gestures` | service + config XML, `ScreenStateMonitor`, `AccessibilityHostHandle`, wiring, manifest | H1–H8, P1, P2, P4, B1; device parts of A1, A2, A4, C11, C13 | developer; tester (API 35 + 37 emulators, real-device smoke by maintainer) | security-reviewer, performance-reviewer |
| 7 | `docs/0001-7-accessibility-readme` | `docs: explain accessibility use and update changelog` | README section (appendix A), CHANGELOG with the "thresholds provisional" note, fastlane description if present | D1, D2 | developer; tester (README claims match the manifest) | — |

**Not closed by this spec:** AC-R1, i.e. real-motion validation of thresholds before the first public
release. Track it as a follow-up spec (e.g. `0002-real-motion-calibration`). That spec decides how real motion
is obtained (a recorder, or maintainer smoke sessions with in-app distance read-outs). It reuses the ADR 0008 CSV
format and the raw template storage.

---

## Appendix A — README section draft (milestone 7)

> ### Why does Mighty Gestures need Accessibility?
>
> Android only lets an app react to motion, lock the screen and open other apps while you are using a
> *different* app if it runs as an Accessibility service. Mighty Gestures uses that access for exactly three
> things:
>
> 1. **Reading the motion sensors** (accelerometer, gyroscope) while the screen is on, so your gestures work
>    from anywhere. Sensors are switched off whenever the screen turns off.
> 2. **Locking the screen**, if you pick the "Lock screen" action.
> 3. **Opening an app**, if you pick the "Open app" action.
>
> Mighty Gestures does **not**:
> - read, store or send anything that is on your screen (the service is configured so that Android does not give
>   it screen content);
> - see what you type, or which apps you use;
> - perform taps or gestures on your behalf;
> - have Internet access. The app has no `INTERNET` permission, so nothing can leave your phone.
>
> Your recorded movements are stored only on your phone, are excluded from backups, and are deleted when you
> delete the gesture.
>
> **"Restricted setting"?** On Android 13 and newer, if you installed the APK manually, Android may grey out the
> switch. Open *Settings → Apps → Mighty Gestures → ⋮ → Allow restricted settings*, then enable the service.
>
> **Advanced Protection Mode:** if you use Android's Advanced Protection, Android may not allow apps that
> are not assistive-technology tools to use Accessibility, and gestures will not run. Mighty Gestures does not
> pretend to be an assistive tool.
>
> **Do Not Disturb:** since Android 15, apps can only switch *their own* Do Not Disturb mode. The gesture turns the
> "Mighty Gestures" mode on and off; it cannot turn off Do Not Disturb that you enabled yourself.

## Appendix B — New dependencies

| Artifact | Version (latest stable, checked 2026-10-01) | License | Scope | Why platform/AndroidX alone is not enough |
|---|---|---|---|---|
| `androidx.datastore:datastore` | 1.2.1 | Apache-2.0 | impl | atomic typed store with Flow (ADR 0006) |
| ↳ `androidx.datastore:datastore-core`, `datastore-core-okio` | 1.2.1 | Apache-2.0 | transitive | — |
| ↳ `com.squareup.okio:okio(-jvm)` | 3.9.1 | Apache-2.0 | transitive | — |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.11.0 | Apache-2.0 | impl | polymorphic DTO serialization; Nav3 keys |
| plugin `org.jetbrains.kotlin.plugin.serialization` | 2.4.20 (= Kotlin) | Apache-2.0 | build | needed by kotlinx.serialization |
| `androidx.navigation3:navigation3-runtime`, `navigation3-ui` | 1.2.0 | Apache-2.0 | impl | back stack, predictive back, saved state (ADR 0009) |
| ↳ `kotlinx-serialization-core`, `androidx.navigationevent`, `androidx.savedstate:savedstate-compose` | per POM | Apache-2.0 | transitive | — |
| `androidx.lifecycle:lifecycle-viewmodel-navigation3` | 2.11.0 | Apache-2.0 | impl | per-entry ViewModel scope |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.11.0 | Apache-2.0 | impl | `viewModel()` + factories (ADR 0005) |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.11.0 | Apache-2.0 | impl | `collectAsStateWithLifecycle()` (compose-ui.md) |
| `org.jetbrains.kotlinx:kotlinx-collections-immutable` | 0.5.2 | Apache-2.0 | impl | immutable lists in UI state (compose-ui.md) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.11.0 | Apache-2.0 | test | testing.md |
| `app.cash.turbine:turbine` | 1.2.1 | Apache-2.0 | test | testing.md |

Icons: Material Symbols as vector drawables in `res/drawable` (Apache-2.0), with no icon library dependency.
Each PR adding a dependency runs `scripts/check-no-gms.sh --classpath` and reports the release APK size delta
(f-droid.md). Versions are re-checked when the PR is written; this table is not a pin.
