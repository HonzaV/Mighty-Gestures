# Compose UI guidelines

Jetpack Compose + Material 3, single activity, no XML layouts. The app is configuration-heavy (rules, gestures,
actions, permissions), so clarity and accessibility beat visual novelty.

## Structure

- One screen = `XxxRoute` (wires ViewModel, collects state with `collectAsStateWithLifecycle()`, handles
  navigation) + `XxxScreen(state, onEvent)` (stateless, previewable).
- Composables take `modifier: Modifier = Modifier` as the first optional parameter and apply it to the root.
- State hoisting: leaf composables receive values and lambdas; no ViewModel references below the route.
- UI state is an immutable data class or sealed interface, `@Immutable`/`@Stable` where Compose can't infer it.
  Use `kotlinx.collections.immutable` for lists in state.
- Side effects only in `LaunchedEffect` / `DisposableEffect` / `rememberCoroutineScope` with correct keys.
- Every screen and non-trivial component has `@Preview`s: light, dark, large font (`fontScale = 1.5f`),
  and at least one with long localized strings.

## Theming

- All colors, typography and shapes come from `MaterialTheme`. No hard-coded `Color(...)` or `sp/dp` magic
  numbers outside the theme/dimension tokens.
- Support dynamic color (Material You) with a branded fallback scheme; both light and dark.

## Platform behavior for current target SDKs

- **Edge-to-edge is mandatory** (enforced when targeting API 35, no opt-out when targeting API 36). Handle
  insets explicitly: `Scaffold` content padding, `WindowInsets.safeDrawing` / `systemBars` / `ime` paddings.
  Verify nothing is hidden behind status bar, gesture navigation bar, display cutout or keyboard.
- **Predictive back.** Use `BackHandler` / `PredictiveBackHandler` / navigation's back stack; never rely on
  `onBackPressed()`. Back must always be possible and never trap the user in a permission flow.
- **Adaptive layouts.** When targeting API 36, orientation and resizability restrictions are ignored on large
  screens. Layouts must work on phones, foldables and tablets, in portrait and landscape (use window size classes).

## Accessibility (required, not optional)

- Touch targets ≥ 48×48 dp. Text contrast ≥ 4.5:1 (3:1 for large text).
- Every interactive element has a meaningful label (`contentDescription`, or visible text merged via
  `Modifier.semantics(mergeDescendants = true)`). Decorative icons use `contentDescription = null`.
- Switch/checkbox rows: make the whole row toggleable (`Modifier.toggleable`) with the right `Role`.
- Don't convey meaning by color alone. Respect font scaling up to 200 % without clipping.
- Gestures are the product, so **every gesture-triggered action must also be reachable without motion**
  (test button in the rule editor) for users who cannot perform the gesture.
- Verify with TalkBack and with the Accessibility Scanner or Compose UI tests asserting semantics.

## Permission & special-access UX

- Explain *before* asking: a rationale screen saying what the permission enables and what happens without it.
- Deep-link to the exact settings page for special access (accessibility, overlay, DND, battery optimization)
  and re-check the state on resume.
- Degrade gracefully: a rule whose permission is missing is shown as "needs attention", never silently broken.

## Strings & localization

- All text in `res/values/strings.xml`; plurals via `pluralStringResource`. No string concatenation for
  sentences. Test with pseudo-locales (`en-XA`, `ar-XB`) for length and RTL.
