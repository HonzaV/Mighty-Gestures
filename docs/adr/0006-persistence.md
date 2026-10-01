# ADR 0006 — Persistence: typed DataStore with kotlinx.serialization JSON

- **Status:** Accepted (maintainer, 2026-10-01; spec 0001 resolved decisions)
- **Date:** 2026-10-01
- **Deciders:** maintainer, architect (agent)

## Context
v1 persists a list of rules ("gestures") as defined in ADR 0004. Each rule has a name, enabled flag, conditions,
one action, and a motion template. The template is two short exemplars of motion samples (ADR 0008):
≤ ~3.1 s × 50 Hz × 2 sensors × 3 axes ≈ 1 900 floats per exemplar. The count is unlimited by product decision,
but a realistic user has 1–20 gestures. Access patterns:
- the UI observes the list (Flow), toggles `enabled`, adds and deletes;
- the accessibility service loads all enabled rules' templates into memory for matching;
- no queries, joins, partial updates or search.

The service and the UI run in the **same process** (ADR 0007). Privacy (AGENTS.md §2): templates are sensor
data. Storing them was approved as spec 0001 decision 1, with the AGENTS.md §2 amendment applied by the maintainer.
They must not leave the device through backup.

## Options considered
### Option A — Typed DataStore (`androidx.datastore:datastore`) + kotlinx.serialization JSON, one file
- Pros: AndroidX, Apache-2.0. Atomic whole-file writes, a coroutine/Flow API, and a corruption handler. A
  single `@Serializable` root DTO with sealed `@SerialName`-discriminated trigger/action DTOs maps the model
  directly. Schema versioning is a field plus migration code. Unit-testable on the JVM by serializing DTOs
  without Android.
- Cons: every change rewrites the whole file. At ~10–12 KB per rule (templates base64-encoded), 100 rules
  ≈ 1.2 MB per toggle, which is acceptable for a user action but not for hot paths. The docs recommend Room
  for "large or complex datasets, partial updates, or referential integrity" (verified,
  https://developer.android.com/topic/libraries/architecture/datastore). We need none of those. Adds
  datastore (+ datastore-core, datastore-core-okio, okio) and kotlinx-serialization-json, plus the Kotlin
  serialization compiler plugin.
### Option B — Room
- Pros: partial updates, scales to thousands of rows, migrations tooling.
- Cons: needs KSP (or kapt) + room-runtime/compiler + sqlite artifacts. Polymorphic trigger/action configs
  would still be JSON or blob columns, so kotlinx.serialization is needed anyway. Generated DAO code affects
  coverage exclusions. Relational features unused.
### Option C — Plain files: one JSON file per rule, written with `AtomicFile`, no DataStore
- Pros: per-rule writes; one fewer dependency (DataStore).
- Cons: we hand-write the Flow observation, write serialization (mutex), corruption handling and the
  directory-scan consistency logic that DataStore provides.
### Option D — Preferences DataStore
- Cons: key–value only; nested polymorphic data would be JSON strings in prefs. Worse than Option A.

## Decision
We choose **Option A**: one typed `DataStore<GestureStoreDto>` at `files/datastore/gestures.json`, created
exactly once in the `AppContainer` (ADR 0005). The docs say "Never create more than one instance of DataStore
for a given file in the same process" (verified, same URL).

Format details:
- DTOs live in `data/` and are separate from domain models. Domain stays free of serialization annotations,
  and persistence format changes do not ripple into domain code. Explicit `@SerialName` discriminators:
  `"motion"`, `"launch_app"`, `"toggle_torch"`, `"lock_screen"`, `"toggle_dnd"`, `"set_ringer_mode"`.
  `Json { ignoreUnknownKeys = true; encodeDefaults = true }`. A root `schemaVersion: Int = 1`.
- **Templates store the raw segment samples, not only derived features.** Each exemplar stores raw `ACC`
  (gravity included) and `GYRO` samples of the segment window (pre-roll included), relative timestamps, the
  gravity estimate at segment start, and `algorithmVersion`. These are the same fields as a trace CSV row. Arrays
  are base64 of little-endian `Int64`/`Float32` for compactness.
  *Why raw:* any later matcher or filter improvement can re-derive features. Storing only processed features
  would force every user to re-record every gesture after an algorithm change.
  *Privacy trade-off:* a ≤ 3 s window of a deliberate, user-performed movement. No data before or after the
  movement is kept. The derived-only alternative minimizes data slightly more; the maintainer chose raw
  storage (spec 0001 decision 1).
- Corruption: `ReplaceFileCorruptionHandler` resets to an empty store, and the UI shows a one-time "Saved gestures
  could not be read" notice. Losing gestures beats crashing the accessibility service on every bind.
- **Backup:** add `android:dataExtractionRules="@xml/data_extraction_rules"` excluding `datastore/` from **both** `<cloud-backup>` and `<device-transfer>`
  (https://developer.android.com/identity/data/autobackup). For apps targeting Android 12+,
  `allowBackup="false"` alone does not stop device-to-device transfer on some devices (verified,
  https://developer.android.com/about/versions/12/behavior-changes-12). Templates are device-specific (sensor
  hardware differs), so restoring them elsewhere has little value anyway.

## Consequences
- Positive: small dependency surface (all Apache-2.0); one atomic file; Flow out of the box; JVM-testable
  serialization.
- Negative / accepted trade-offs: whole-file rewrites; all templates in memory while the service runs
  (≈ 15 KB/rule in memory, inferred). If users routinely exceed ~200 gestures, revisit with Option C or Room.
- Follow-ups: migration tests per schema version; a fixture JSON committed for version 1 so later versions prove
  they still read it.

## New dependencies (details and licenses also in spec 0001)
| Artifact | Version | License | Note |
|---|---|---|---|
| `androidx.datastore:datastore` | 1.2.1 | Apache-2.0 | pulls `datastore-core`, `datastore-core-okio` |
| `com.squareup.okio:okio` (transitive) | 3.9.1 | Apache-2.0 | via datastore |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.11.0 | Apache-2.0 | |
| Gradle plugin `org.jetbrains.kotlin.plugin.serialization` | = Kotlin 2.4.20 | Apache-2.0 | resolves from Maven Central like the existing compose plugin (*inferred*) |

## References
- DataStore guide: https://developer.android.com/topic/libraries/architecture/datastore
- Auto Backup / data extraction rules: https://developer.android.com/identity/data/autobackup
- Android 12 backup behavior change: https://developer.android.com/about/versions/12/behavior-changes-12
- docs/engineering/security-privacy.md ("rule config may be backed up; no sensor data")
