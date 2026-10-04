# Architecture Decision Records

One file per decision: `NNNN-short-slug.md`, created from `0000-template.md`. Never rewrite an accepted ADR —
supersede it with a new one and update the old one's status.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-project-baseline.md) | Project baseline | Accepted |
| [0002](0002-application-id.md) | Application ID and package name | Accepted |
| [0003](0003-module-layout.md) | Module layout and package structure | Accepted |
| [0004](0004-trigger-rule-action-model.md) | Trigger → Rule → Action domain model and extension points | Accepted |
| [0005](0005-dependency-injection.md) | Dependency injection: manual container | Accepted |
| [0006](0006-persistence.md) | Persistence: typed DataStore with kotlinx.serialization JSON | Accepted |
| [0007](0007-always-on-host-accessibility-service.md) | Always-on host: AccessibilityService only (no foreground service) | Accepted |
| [0008](0008-motion-gesture-detection.md) | Motion gesture detection: one segmenter + DTW template matching | Accepted |
| [0009](0009-navigation.md) | Navigation: AndroidX Navigation 3 | Accepted |

## Pending decisions
None. The decisions listed as open in ADR 0001 and AGENTS.md §3 were accepted on 2026-10-01 (spec
[0001](../specs/0001-motion-gestures.md)): module layout → 0003, domain model → 0004, DI → 0005,
persistence → 0006, always-on host → 0007, gesture detection and trace format → 0008, navigation → 0009.
