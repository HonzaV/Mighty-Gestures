# ADR 0008 — Motion gesture detection: one segmenter + DTW template matching on raw ACC/GYRO

- **Status:** Accepted (maintainer, 2026-10-01; spec 0001 resolved decisions)
- **Date:** 2026-10-01
- **Deciders:** maintainer, architect (agent)

## Context
Users record their own movement and confirm it once by repeating it. Afterwards the movement must be recognized
in everyday use: "similar enough, not exact", with very few false positives. The detection core must be pure
Kotlin and testable against trace fixtures (AGENTS.md §4, docs/engineering/testing.md). Battery rules in
docs/engineering/sensors-and-power.md apply. Maintainer constraints:
- recording starts on *significant acceleration* and ends when the movement has been quiet for **500 ms**;
- the same movement must be confirmed once;
- detection only while the screen is on (ADR 0007).

Raw `TYPE_ACCELEROMETER` always contains ~9.81 m/s² of gravity, so a gravity-removed signal is required. The
platform offers `TYPE_LINEAR_ACCELERATION`, "acceleration along each device axis, excluding gravity", usable
"to perform gesture detection". The docs show the same thing done with a low-pass/high-pass filter on the
accelerometer (verified,
https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion).

## Options considered
### Detection approach
**A — Template matching: DTW on resampled, normalized multi-axis signals (chosen).**
Works from 1–2 user examples, needs no training corpus, is explainable and has deterministic cost. DTW absorbs
local tempo differences. The cost is O(N·band) per comparison and runs only at segment end, never per sample.
**B — Hand-written per-gesture detectors (thresholds + state machines).** Great for fixed gestures (flip, shake),
but cannot represent arbitrary user-recorded movements. Kept as a future *trigger type*, not for this feature.
**C — On-device ML classifier.** Needs a training corpus and a runtime dependency. Cannot learn a new class from
two examples without few-shot machinery. Rejected for v1.
**D — Summary features + distance (means, variances, peaks).** Cheap, but discards temporal order (left→right vs
right→left look identical), so false positives rise. Rejected as the matcher; peak and energy features are used
only as gates.
**E — Orientation-invariant recognizers (magnitude-only or world frame via rotation vector).** More tolerant of
how the phone is held, but much less discriminative: a world-frame "chop" resembles walking or putting the phone
down. World frame also needs the rotation-vector sensor (magnetometer, more power). Rejected; see trade-offs.

### Gravity removal
**G1 — Raw `ACC` + `GYRO`, gravity removed in Kotlin (low-pass estimate subtracted) (chosen).** Traces store what
the hardware reports, matching the existing CSV format in testing.md. The filter is deterministic and unit-tested,
and identical on every device. Raw storage (ADR 0006) lets a better filter be applied to old templates later.
**G2 — `TYPE_LINEAR_ACCELERATION` (+ `TYPE_GYROSCOPE`).** Better rotation handling via sensor fusion, but the
implementation is vendor-specific: AOSP's virtual sensor or a vendor sensor, possibly absent without a gyroscope
(*inferred* from the "secondary sensors" note on the same page). Fixtures would encode one vendor's fusion
output, and a synthetic generator would have to imitate an unknown fusion algorithm. Rejected for v1;
revisit if real-motion validation (spec 0001 AC-R1) shows rotation leakage hurts.

## Decision
### Sensors and registration
| | Value | Why |
|---|---|---|
| Sensors | `TYPE_ACCELEROMETER` (required), `TYPE_GYROSCOPE` (optional), default non-wake-up instances | wake-up is pointless: we only sense with the screen on |
| Sampling period | **20 000 µs (50 Hz)**, requested explicitly via `registerListener(listener, sensor, samplingPeriodUs, maxReportLatencyUs, handler)` (verified overload, https://developer.android.com/reference/android/hardware/SensorManager) | sensors-and-power.md rule 1; well below the 200 Hz cap; no `HIGH_SAMPLING_RATE_SENSORS` |
| Max report latency | **capture (record/confirm): 0**; **live: 100 000 µs** (initial; performance-reviewer may raise it) | capture gives immediate UI feedback; live batching cuts wakeups. Segmentation uses sensor timestamps, so batching never changes results, only adds ≤ 100 ms latency |
| Thread | one `HandlerThread` ("mg-sensors"); segmenter and matcher run on it | off main thread; no per-event coroutine/Flow emission |
| When registered | live: only while ≥ 1 armed motion rule (ADR 0004 engine contract); unregistered on screen off, keyguard with no lock-screen rules, capture in progress, service unbind | sensors-and-power.md rule 4 |
| No gyroscope | template records its channel set; matching uses ACC only | still works on gyro-less devices with reduced discrimination |

The device may deliver faster than requested. Every component is driven by **sensor timestamps** and must give
the same verdicts at 50/100/200 Hz (robustness tests).

### Pipeline (all in `domain/trigger/motion`, pure Kotlin)
```
onSample(kind, tNanos, x, y, z)           // primitive call from the adapter, zero allocation
  → FrameBuilder: on each ACC sample: gravity g ← g + α(a − g), α = dt/(τg + dt), τg = 0.25 s;
                  lin = a − g; gyro = latest GYRO sample (sample-and-hold); frame = (t, lin, gyro)
  → Segmenter (state machine, below)       // the ONE segmenter for record, confirm and live
  → on segment: Preprocessor → Matcher (live / confirm) or TemplateValidator (record)
```

**Segmenter** (`MotionConfig` holds all constants; values are *initial*, calibrated in milestone 3 of spec 0001
on the **synthetic** corpus only, and **unverified on real human motion** until spec 0001 AC-R1 is done):

| Parameter | Initial value |
|---|---|
| onset: frame "active" if `|lin| ≥ A_on` **or** `|ω| ≥ G_on` | `A_on = 3.0 m/s²`, `G_on = 2.0 rad/s` |
| onset confirmation | 2 consecutive active frames |
| quiet (hysteresis): frame "quiet" if `|lin| < A_off` **and** `|ω| < G_off` | `A_off = 1.5 m/s²`, `G_off = 1.0 rad/s` |
| end debounce (maintainer) | quiet continuously for **500 ms** of sensor time |
| pre-roll kept before onset | 100 ms (ring buffer sized for 200 Hz) |
| segment = | `[onset − preRoll, lastActiveFrame]`: the quiet tail is **trimmed** |
| min active duration | 150 ms (shorter → discarded as a bump; capture keeps waiting, see below) |
| max active duration | 3 000 ms (longer → discarded, back to SETTLING; capture reports "too long") |
| timestamp gap > 200 ms or non-monotonic | reset to SETTLING / drop sample |

Discards carry a reason (`TOO_SHORT`, `TOO_LONG`). Live detection ignores both. Capture (AC-C4) ends the attempt
with "too long" on `TOO_LONG`. It treats `TOO_SHORT` as a bump and keeps waiting. The validator's 250 ms floor then
reports "too short" for segments between 150 and 250 ms.

States: `SETTLING` (needs 500 ms quiet) → `ARMED` → `ACTIVE` → (500 ms quiet) emit segment → `ARMED`.
`ACTIVE` > max → `SETTLING`. Registration starts in `SETTLING`. This absorbs gravity-filter warm-up and the jolt
of tapping "Record". It also makes continuous motion like walking or running self-cancel: there is never 500 ms
of quiet, so segments hit the max and are discarded.

**Capture session** (record and confirm only) wraps the same segmenter: a 10 s timeout from start without an
emitted segment → `NoMovement` (UI says "hold still, then move"). The first segment ends the session.

**Template validator** (record only), the "distinctiveness gate":
- active duration ≥ 250 ms;
- peak `|lin|` ≥ 8 m/s² **or** peak `|ω|` ≥ 5 rad/s;
- mean energy `mean(|lin|²/A_on² + |ω|²/G_on²)` over active frames ≥ 4.

These reject gentle movements that resemble ordinary handling. Initial values, calibrated on the synthetic corpus (provisional, AC-R1).

**Preprocessor:** resample the segment to **N = 64** frames, uniform in time, by linear interpolation. Scale
each sensor group (lin, gyro) by its RMS vector magnitude over the segment, floored at `A_off` / `G_off` so
near-silent channels are not amplified into noise. Keep duration and the two RMS values for gates.

**Matcher:**
1. Gates (cheap): duration ratio live/template ∈ [0.5, 2.0]; RMS ratio per group ∈ [0.5, 2.0].
2. Dependent multivariate DTW over the 6-D normalized frames (3-D if ACC-only), squared Euclidean local cost,
   Sakoe–Chiba band ±8 frames (12.5 %). Distance = accumulated cost / warping-path length.
3. A template holds **two exemplars** (recording + confirmation). Live distance = min over exemplars.
4. **Match iff distance ≤ τ.** `τ` is one global constant in `MotionConfig`. Its value is calibrated in
   milestone 3 on the synthetic corpus (tuning seeds, then verified on held-out seeds). Until then a placeholder
   is used. Provisional until real-motion validation (AC-R1).
5. If several armed rules match one segment, only the **lowest-distance** rule fires (one movement → at most one
   action).

**Confirmation** uses the same segmenter, preprocessor, matcher and **the same τ** as live detection. A passing
confirmation therefore means "this repeat would have been detected live". Confirmation proves *repeatability*,
not *distinctiveness*. Distinctiveness comes from the validator, the collision check, the negative corpus and
the defaults below.

**Collision check** (at confirmation): distance between the new exemplars and every existing rule's exemplars,
enabled or not. If ≤ `1.2 τ`, the new gesture is rejected as "too similar to ‹name›" (spec 0001 open
question 6).

**Cooldown:** per rule, 1 500 ms after firing (engine, ADR 0004). The segmenter already gives one segment per
movement; the cooldown guards against a user immediately repeating the gesture by accident.

### Invariance trade-offs (accepted)
| Dimension | Choice | Consequence |
|---|---|---|
| Orientation | **device frame** (gravity removed, no world-frame rotation) | Same movement with the phone held differently (portrait vs landscape, screen facing away) may not match. This is the main source of misses; the UI tells users to hold the phone the way they will use the gesture. Strongly fewer false positives. |
| Speed | resampling to N + DTW band + duration gate [0.5×, 2×] | Moderately slower or faster repeats match; very different tempos do not. |
| Amplitude | per-group RMS normalization + RMS gate [0.5×, 2×] | Gentler/stronger repeats match within 2×; a light twitch cannot match a vigorous template. |
| Direction | sign-preserving per-axis signals | Left-chop and right-chop are different gestures. |

### Trace format (synthetic fixtures, any future real recordings and stored exemplars share the fields)
CSV per docs/engineering/testing.md: header `timestamp_ns,sensor,x,y,z`, sensors `ACC` (raw
`TYPE_ACCELEROMETER`, **including gravity**, m/s²) and `GYRO` (`TYPE_GYROSCOPE`, rad/s). Timestamps are
`SensorEvent.timestamp` rebased so the first row is 0. Additions:
- Lines starting with `#` are metadata. **`source=` is mandatory** (`synthetic` or `device`), so synthetic data
  can never pass as human recordings. Synthetic example: `# source=synthetic; generator=SyntheticTraceGenerator@1;
  seed=42; model=walking; rate_hz=50`. A future device example: `# source=device; device=<model>; android=36;
  situation=walking`. No identifiers beyond device model.
- **v1 is synthetic only** (spec 0001 decision 5). Corpus traces are generated in memory by a seeded,
  deterministic generator in the `test` source set. It models a sensor (noise, bias, jitter, drops, rates
  50/100/200 Hz), rigid-body kinematics (gravity rotated consistently with the gyro), parametric gestures with
  performer variability, and negative situations (spec 0001 "Synthetic trace corpus and calibration").
- Only small golden fixtures are committed: `app/src/test/resources/traces/motion/{positive/<gesture>,negative}/
  <case>.csv`, each with a `source=synthetic` header.

### Performance budget (checked by a JVM benchmark test, not guessed)
- Per sample: O(1), **zero allocations** in `onSample` → segmenter (primitive ring buffers, preallocated).
- Per segment end: ≤ 2 exemplars × armed rules DTW comparisons of 64 × 17 cells. Target < 2 ms for 50 rules on
  a JVM dev machine (*inferred* to be ~5× slower on a phone, still negligible at one segment per movement).

## Consequences
- Positive: user-defined gestures from two examples; deterministic and trace-testable; cheap; one pipeline
  for record, confirm and live.
- Negative / accepted trade-offs: device-frame matching misses gestures performed in a different grip. All
  thresholds are fitted to a synthetic motion model of our own making (circular). They are **unverified on real
  human motion**. The low-pass gravity estimate leaks gravity during fast
  rotations, so rotation-heavy gestures end ~0.5 s later. Template and live share the pipeline, so leakage is
  consistent and does not break matching.
- Follow-ups: synthetic calibration in spec 0001 milestone 3. **Before the first public release**, a follow-up
  spec must validate or re-calibrate `A_on`, `G_on`, the validator gates and `τ` on real human motion
  (spec 0001 AC-R1).
  Bump `ALGORITHM_VERSION` on any pipeline change and re-derive stored templates from their raw samples. Track
  a false-positive budget (spec 0001).

## References
- Motion sensors guide (linear acceleration, high-pass example): https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion
- SensorManager (`registerListener` with `maxReportLatencyUs` + `Handler`): https://developer.android.com/reference/android/hardware/SensorManager
- docs/engineering/sensors-and-power.md, docs/engineering/testing.md
- Sakoe & Chiba, "Dynamic programming algorithm optimization for spoken word recognition", IEEE TASSP 1978 (DTW band)
