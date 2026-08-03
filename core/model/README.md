# `:core:model`

The smallest shared model layer. It contains Android-free value objects that can cross module boundaries without pulling in UI, platform, or data-source dependencies.

## Responsibilities

- Represent a latitude/longitude pair in decimal degrees.
- Enforce finite values and the inclusive geographic ranges at construction time.
- Provide a stable shared type for domain calculations and map feature state.
- Remain a plain Kotlin/JVM module with no Android or Compose dependency.

## Directory and package structure

| Path | Ownership |
| --- | --- |
| `src/main/kotlin/ir/hrka/shahbaz/core/model/GeoCoordinate.kt` | Production model in package `ir.hrka.shahbaz.core.model`. |
| `src/test/kotlin/ir/hrka/shahbaz/core/model/GeoCoordinateTest.kt` | Boundary, range, and non-finite input tests. |

## Public entry points

- `GeoCoordinate(latitude, longitude)` is an immutable validated coordinate. Construction throws `IllegalArgumentException` when either component is non-finite or outside latitude `-90..90` and longitude `-180..180`.

## Dependency direction

```mermaid
graph RL
  model[":core:model"]
  domain[":core:domain"]
  map[":feature:map:impl"]

  domain -->|api| model
  map -->|api| model
```

`:core:model` has no project dependencies. It must not depend on Android, a feature, or `:app`.

## Using this module

Add the type directly when a module needs geographic coordinates:

```kotlin
dependencies {
    implementation(projects.core.model)
}
```

```kotlin
val tehran = GeoCoordinate(latitude = 35.6892, longitude = 51.3890)
```

Consumers of `:core:domain` or `:feature:map:impl` can also see `GeoCoordinate` because those modules expose `:core:model` with `api`.

## Resource ownership

This is a pure JVM module and owns no Android resources, manifest, UI text, or formatting intended for display. Resource-backed presentation belongs to the consuming feature.

## Test and build

```powershell
.\gradlew.bat :core:model:test :core:model:build
```

Tests should cover every invariant added to a shared value object because invalid instances are rejected at construction.
