# `:core:map`

Reusable MapLibre Compose presentation for Shahbaz flight routes. The module is an Android UI
library, not a feature: it has no ViewModel, navigation, location provider, network monitor, USB
transport, or dashboard dependency.

## Responsibilities

- Draw a fixed blue origin and red destination joined by a direct two-point route.
- Draw an optional teal current-position marker and rotate its heading glyph when a finite heading
  is available.
- Frame the fixed route and any tracked coordinate available during initial map load.
- Leave later camera movement under user control instead of following every telemetry update.
- Keep MapLibre logo and attribution enabled.
- Present contained loading, offline, timeout, and map-style error states.
- Report the same state to the host through a callback while leaving connectivity ownership to the
  consuming feature.

## Public API

`FlightRouteMapState` contains fixed origin/destination coordinates and an optional current tracked
position/heading. `FlightRouteMap` renders that state and accepts host connectivity plus load-state
and retry callbacks. `FlightMapLoadState` is `LOADING`, `READY`, `OFFLINE`, or `ERROR`.

```kotlin
FlightRouteMap(
    state = FlightRouteMapState(
        origin = plan.origin,
        destination = plan.destination,
        currentPosition = dashboardState.currentPosition,
        currentPositionHeadingDegrees = dashboardState.currentHeadingDegrees,
    ),
    isOnline = dashboardState.isOnline,
    modifier = Modifier.fillMaxSize(),
)
```

The default style is OpenFreeMap Liberty and can be replaced through `styleUri`. The module manifest
owns the `INTERNET` permission required to load a remote style; the host must still determine
whether the active network is validated and supply `isOnline`.

## Dependency direction

```text
:feature:map:impl ---------\
                            -> :core:map -> :core:model
:feature:dashboard:impl ---/
```

The core map module must never depend on a feature or on `:app`. Public route coordinates use
`GeoCoordinate`, so `:core:model` is an API dependency. MapLibre, Compose, and Material 3 remain
implementation dependencies.

## Tests

Pure JVM tests cover route/position bounds, degenerate point framing, marker selection, heading
normalization, and public heading invariants:

```powershell
.\gradlew.bat :core:map:testDebugUnitTest
```

MapLibre style loading, ornament visibility, camera animation, glyph availability, and gestures
still require an Android device or emulator. Compose UI tests should additionally verify the
loading/offline/error surfaces when this module is integrated into the application graph.
