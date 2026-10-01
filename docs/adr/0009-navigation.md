# ADR 0009 — Navigation: AndroidX Navigation 3

- **Status:** Accepted (maintainer, 2026-10-01; spec 0001 resolved decisions)
- **Date:** 2026-10-01
- **Deciders:** maintainer, architect (agent)

## Context
The app is single-activity Compose (AGENTS.md §3). Spec 0001 introduces five destinations: gesture list,
create-gesture flow (one destination with internal steps), app picker, accessibility explainer,
and notification-policy explainer. Requirements from
docs/engineering/compose-ui.md: predictive back through the navigation back stack; back never trapped in a
permission flow; adaptive layouts. Each destination needs its own **ViewModel scope**: the create-flow ViewModel
must be discarded when the flow is left, not live on at activity scope.

## Options considered
### Option A — Navigation 3 (`androidx.navigation3:navigation3-runtime` / `-ui` 1.2.0)
- Pros: Compose-first. The back stack is a plain state list we own (`NavBackStack<NavKey>`), and the
  docs describe this model ("you navigate between destinations by adding and removing items from a list",
  verified, https://developer.android.com/guide/navigation/navigation-3). Back-stack keys are `@Serializable`
  and saved across process death (verified, https://developer.android.com/guide/navigation/navigation-3/save-state).
  Per-entry ViewModel scoping via `lifecycle-viewmodel-navigation3` +
  `rememberViewModelStoreNavEntryDecorator()` (verified, same page). Predictive back is handled by `NavDisplay`
  (*inferred* from the library depending on `androidx.navigationevent`). Adaptive multi-pane scenes exist for
  later.
- Cons: two new artifacts (+ the lifecycle add-on). Transitively pulls `kotlinx-serialization-core`, which we add
  anyway (ADR 0006). Newer than Navigation 2, so fewer community answers.
### Option B — Navigation Compose 2.x (`androidx.navigation:navigation-compose` 2.10.2)
- Pros: mature; type-safe routes with kotlinx.serialization; per-destination ViewModel scoping built in.
- Cons: the controller owns the back stack (`NavController`), a graph DSL with string- or serializer-based
  routes. Heavier, and Google's Compose-first direction is Navigation 3 (*inferred*).
### Option C — Hand-rolled back stack (`mutableStateListOf` + `BackHandler`)
- Pros: zero dependencies.
- Cons: we would re-implement saved state, ViewModel scoping per entry (a custom `ViewModelStoreOwner`) and
  predictive-back animations. Too much infrastructure to own and test for ~6 screens.

## Decision
We choose **Option A — Navigation 3**:
- `sealed interface AppNavKey : NavKey` with `@Serializable` objects/classes: `GestureList`, `CreateGesture`,
  `AppPicker`, `AccessibilitySetup`, `NotificationPolicySetup`.
- `NavDisplay(entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(),
  rememberViewModelStoreNavEntryDecorator()))`.
- Results (e.g. picked app) flow back through the create-flow ViewModel or a small result holder, not through
  back-stack mutation hacks.

## Consequences
- Positive: we own the back stack (testable as a list); proper ViewModel scoping; predictive back without custom
  code.
- Negative / accepted trade-offs: new dependencies (below); a newer library.
- Follow-ups: Compose UI tests navigate by manipulating the back stack and asserting the visible screen.

## New dependencies
| Artifact | Version | License |
|---|---|---|
| `androidx.navigation3:navigation3-runtime` | 1.2.0 | Apache-2.0 |
| `androidx.navigation3:navigation3-ui` | 1.2.0 | Apache-2.0 |
| `androidx.lifecycle:lifecycle-viewmodel-navigation3` | 2.11.0 (= existing `lifecycleRuntimeKtx` ref) | Apache-2.0 |
| transitive: `androidx.navigationevent`, `androidx.savedstate:savedstate-compose`, `kotlinx-serialization-core` | per POM | Apache-2.0 |

## References
- Navigation 3 overview: https://developer.android.com/guide/navigation/navigation-3
- Save and manage navigation state: https://developer.android.com/guide/navigation/navigation-3/save-state
- docs/engineering/compose-ui.md
