# Security & privacy checklist

An automation app holds powerful capabilities (always-on sensors, possibly accessibility, overlays, DND,
launching apps). Users must be able to trust that it does exactly what they configured and nothing else.

## Threat model (short)

| Asset | Threat |
|---|---|
| Motion sensor stream | Side-channel inference (typing, location, activity) if leaked or persisted |
| Accessibility / overlay grants | Abuse by our bugs or by other apps driving our exported components |
| User rules & action config | Tampering by other apps → unwanted actions (calls, app launches, DND changes) |
| Release signing | Supply-chain compromise, malicious dependency |

## Checklist (apply to every change touching these areas)

**Manifest & components**
- [ ] Every `<activity>`, `<service>`, `<receiver>`, `<provider>` declares `android:exported` explicitly; only the
      launcher activity and components that the system must reach are exported.
- [ ] Exported components are protected by a system permission (e.g. `BIND_ACCESSIBILITY_SERVICE`,
      `BIND_QUICK_SETTINGS_TILE`) or validate their callers.
- [ ] No `INTERNET`, no `QUERY_ALL_PACKAGES` (use `<queries>` / launcher intents), no unused permissions.
- [ ] New permission or special access → justified in spec/ADR, rationale UI exists, feature degrades without it.
- [ ] `android:allowBackup` / data-extraction rules reviewed: rule config may be backed up; no sensor data.

**Intents & IPC**
- [ ] `PendingIntent`s use `FLAG_IMMUTABLE` unless mutability is required and justified; explicit intents only.
- [ ] Incoming intents/extras are validated; no actions executed from unvalidated external input.
- [ ] Dynamically registered receivers use `RECEIVER_NOT_EXPORTED` unless they must receive system broadcasts.

**Accessibility service (if adopted)**
- [ ] Minimal `accessibilityEventTypes`, `canRetrieveWindowContent` only if strictly needed, no reading or storing
      of screen content; the purpose is disclosed in the service description and in-app.

**Data**
- [ ] Sensor data processed in memory only; debug recordings are opt-in, stored in app-private storage, and
      deletable from the UI.
- [ ] No logging of sensor values, app usage, or identifiers in release builds.
- [ ] No WebView. If ever needed: no JavaScript bridge, no file access.

**Build & supply chain**
- [ ] `scripts/check-no-gms.sh` passes; new dependencies are FOSS, pinned, from Maven Central/Google Maven only,
      and justified. Consider Gradle dependency verification (`gradle/verification-metadata.xml`).
- [ ] Release build is minified (R8) without keep-rules that expose more than necessary.
- [ ] No secrets in the repo; signing config read from environment/local files that are gitignored.

## Reporting format

Findings use severity **Critical / High / Medium / Low / Info**, each with file:line, the concrete attack or
failure scenario, and a fix. Speculative issues are labeled as such.
