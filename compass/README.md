# `:compass`

`:compass` is a reusable, UI-free Android library for observing the device's display-corrected
orientation. It owns Android sensor discovery and registration, magnetic-heading calculation,
optional true-north correction, circular smoothing, update throttling, accuracy normalization, and
typed failure reporting.

The library has no production dependencies on Shahbaz, AndroidX, a UI toolkit, a lifecycle
framework, networking, or a location provider. Its published coordinates are
`ir.hrka:compass:0.1.0`; it supports Android API 21 and later and targets Java 17 bytecode.

## Responsibilities and non-goals

The module provides:

- Magnetic azimuth, optional true azimuth, magnetic declination, pitch, and roll in one immutable
  `CompassReading`.
- Display-aware orientation: azimuth follows the top edge of the display associated with the
  context passed to `Compass.create`.
- Eight-point cardinal/intercardinal direction lookup and shortest signed/absolute deviations from
  a direction or arbitrary bearing.
- Sensor availability, the selected sensor source, accuracy, estimated heading error when the
  sensor supplies it, and calibration guidance.
- A small, restartable `start`/`stop` API whose events are serialized on Android's main thread.

The module deliberately does not provide:

- Compose, Views, strings, icons, compass-rose rendering, or any other UI.
- Activity, Fragment, or application lifecycle integration. The host decides when observation is
  active and must call `start` and `stop` accordingly.
- GPS, location acquisition, location permission handling, or position persistence. The host may
  supply an already obtained `GeomagneticPosition` if it needs true north.
- Navigation, route bearings, maps, networking, telemetry, or user-facing error messages.

## Structure

```text
compass/
|-- build.gradle.kts
|-- README.md
|-- .gitignore
`-- src/
    |-- main/
    |   |-- AndroidManifest.xml
    |   `-- kotlin/ir/hrka/compass/
    |       |-- Compass.kt
    |       |-- CompassAccuracy.kt
    |       |-- CompassAvailability.kt
    |       |-- CompassConfig.kt
    |       |-- CompassReading.kt
    |       |-- GeomagneticPosition.kt
    |       `-- internal/
    |           |-- AndroidCompass.kt
    |           `-- CompassMath.kt
    `-- test/kotlin/ir/hrka/compass/
        |-- CompassModelsTest.kt
        |-- CompassReadingTest.kt
        `-- internal/CompassMathTest.kt
```

The `ir.hrka.compass` package is the supported public API. The `internal` package contains the
Android sensor implementation and pure calculation rules and is not a consumer API.

## Add the dependency

### In this Gradle build

The module is included as `:compass` in `settings.gradle.kts`. A Kotlin DSL consumer can depend on
it through the type-safe project accessor:

```kotlin
dependencies {
    implementation(projects.compass)
}
```

Without type-safe project accessors, use:

```kotlin
dependencies {
    implementation(project(":compass"))
}
```

When copying the module source into another build, include `":compass"` in that build's settings.
The supplied build script uses the version-catalog aliases `libs.plugins.android.library` and
`libs.junit`; either define equivalent aliases in the destination catalog or replace them with the
destination build's Android library plugin and JUnit dependency declarations.

### From Maven Local

Publish the release AAR, POM, and sources artifact under `ir.hrka:compass:0.1.0`:

```powershell
.\gradlew.bat :compass:publishReleasePublicationToMavenLocal
```

Add Maven Local to the consumer build's dependency repositories:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}
```

Then add the published dependency:

```kotlin
dependencies {
    implementation("ir.hrka:compass:0.1.0")
}
```

### As an AAR

Build the release artifact:

```powershell
.\gradlew.bat :compass:assembleRelease
```

Copy `compass/build/outputs/aar/compass-release.aar` into the consumer's `libs` directory and add:

```kotlin
dependencies {
    implementation(files("libs/compass-release.aar"))
}
```

The AAR has no transitive production library dependencies.

## Manifest, hardware features, and permissions

The library manifest declares these capabilities as optional:

```xml
<uses-feature
    android:name="android.hardware.sensor.compass"
    android:required="false" />
<uses-feature
    android:name="android.hardware.sensor.accelerometer"
    android:required="false" />
```

They are merged into the host manifest but do not prevent installation on devices that lack the
hardware. Check `Compass.availability` or the result of `Compass.start` at runtime instead of
assuming that a sensor exists.

The module declares no permissions. Its sampling presets stay below Android's permission-gated
high-frequency sensor rate, so it does not need a high-sampling-rate sensor permission. It also
does not request location permission: location acquisition and any permission it requires belong
to the host application.

## Public API

### `Compass`

Create an initially stopped instance with `Compass.create(context, config)`. The implementation
retains only the application context and captures the display identity represented by the supplied
context.

| Member | Purpose |
| --- | --- |
| `availability` | Reports `Available(source)` or `Unavailable(reason)` without registering sensors. |
| `isRunning` | Reports whether the instance currently owns registered sensor listeners. |
| `start(listener)` | Registers the selected sensor strategy and returns a typed synchronous result. |
| `stop()` | Unregisters sensors and clears session samples, timestamps, accuracy, and smoothing state. |
| `setGeomagneticPosition(position)` | Supplies or clears the caller-owned input used to calculate true north. |

`CompassStartResult` is one of:

- `Started`: every required sensor registration succeeded.
- `AlreadyRunning`: the compass was already active; the existing listener remains installed.
- `Failed(failure)`: observation could not start.

An active `CompassListener` receives:

- `CompassEvent.Reading(value)` for a valid `CompassReading`.
- `CompassEvent.Failure(failure)` when an individual sample cannot be converted into a finite
  orientation. A continuous malformed-sample sequence is coalesced instead of flooding the
  listener.

`CompassFailureCode` distinguishes `SENSOR_UNAVAILABLE`, `REGISTRATION_FAILED`, and
`INVALID_SENSOR_DATA`. A `CompassFailure` may also retain the platform exception as `cause` for
diagnostics.

### Configuration

`CompassConfig` is immutable and is applied when the instance is created:

```kotlin
val config = CompassConfig(
    updateRate = CompassUpdateRate.NORMAL,
    smoothing = CompassSmoothing.BALANCED,
)
```

Current update-rate policies are:

| `CompassUpdateRate` | Requested sample period | Minimum reading interval | Intended use |
| --- | ---: | ---: | --- |
| `LOW_POWER` | 200 ms | 250 ms | Slowly changing, non-interactive consumers. |
| `NORMAL` | 50 ms | 100 ms | Ordinary compass and navigation state. |
| `RESPONSIVE` | 20 ms | 33 ms | More responsive orientation changes. |

Android treats a requested sample period as a hint; physical delivery can be slower. The module
also enforces the listed minimum interval before delivering readings.

`CompassSmoothing` applies a circular exponential filter that correctly crosses the 0/360-degree
boundary. `NONE`, `LIGHT`, `BALANCED`, and `STRONG` apply newest-sample weights of `1.0`, `0.5`,
`0.2`, and `0.1`, respectively. A new observation session starts with fresh filter state.

### Readings, directions, and deviations

A `CompassReading` contains:

| Property | Meaning |
| --- | --- |
| `magneticAzimuthDegrees` | Clockwise heading relative to magnetic north, normalized to `0 <= value < 360`. |
| `trueAzimuthDegrees` | Clockwise heading relative to true north, or `null` until a geomagnetic position is supplied. |
| `declinationDegrees` | Signed correction added to magnetic azimuth to obtain true azimuth, or `null`. |
| `pitchDegrees` / `rollDegrees` | Finite, display-corrected Android orientation angles. |
| `accuracy` | Normalized accuracy level, optional error estimate, and calibration guidance. |
| `sensorSource` | The sensor strategy that produced the reading. |
| `timestampNanos` | Monotonic Android sensor timestamp in nanoseconds since boot. |

The azimuth is based on the top edge of the associated display and increases clockwise. Use the
helpers rather than duplicating angle normalization:

- `azimuth(NorthReference)` selects magnetic or true azimuth.
- `nearestDirection(NorthReference)` maps to `NORTH`, `NORTH_EAST`, `EAST`, `SOUTH_EAST`,
  `SOUTH`, `SOUTH_WEST`, `WEST`, or `NORTH_WEST`. Exact half-sector boundaries belong to the
  clockwise sector.
- `deviationFrom(direction, reference)` and `deviationFrom(bearingDegrees, reference)` return a
  `DirectionDeviation`. `signedDegrees` is in `[-180, 180)`: positive means clockwise and negative
  means counter-clockwise. `absoluteDegrees` is in `[0, 180]`.

Magnetic north is the default reference. Helpers return `null` if `NorthReference.TRUE` is selected
before true-north input is available.

`CompassAccuracy` exposes `UNKNOWN`, `UNRELIABLE`, `LOW`, `MEDIUM`, or `HIGH` through its `level`.
Calibration guidance is derived as follows:

| Accuracy level | Calibration status |
| --- | --- |
| `UNKNOWN` | `UNKNOWN` |
| `UNRELIABLE` | `REQUIRED` |
| `LOW` | `RECOMMENDED` |
| `MEDIUM` or `HIGH` | `NOT_REQUIRED` |

`estimatedErrorDegrees` is present only when a rotation-vector sample supplies a valid estimate
greater than zero and no greater than 360 degrees.

## Magnetic north and true north

Every reading contains magnetic azimuth. True azimuth is opt-in because calculating declination
requires geographic position, altitude, and time:

```kotlin
compass.setGeomagneticPosition(
    GeomagneticPosition(
        latitudeDegrees = 35.6892,
        longitudeDegrees = 51.3890,
        altitudeMeters = 1_200.0,
        timestampEpochMillis = System.currentTimeMillis(),
    )
)
```

`altitudeMeters` is WGS-84 ellipsoidal altitude, not height above the local ground. The timestamp is
a UTC epoch-millisecond instant for Android's geomagnetic model. The host is responsible for
validating the source and freshness of all four inputs.

The model rejects non-finite values, latitudes outside `-90..90`, longitudes outside `-180..180`,
altitudes that cannot be represented as an Android `Float`, and negative timestamps.

For subsequent readings, the module calculates:

```text
true azimuth = normalize(magnetic azimuth + magnetic declination)
```

Clear position-derived correction when the position is no longer valid:

```kotlin
compass.setGeomagneticPosition(null)
```

This immediately disables true-north fields while magnetic readings continue. Supplying a
position does not request or imply location permission.

## Lifecycle and threading

- `availability`, `isRunning`, `start`, `stop`, and `setGeomagneticPosition` may be called from any
  thread.
- All listener events are serialized on Android's main thread.
- Call `start` when the owning host enters its foreground/active state and `stop` when it leaves.
- `stop` is idempotent, unregisters all selected sensors, invalidates queued events from that
  session, and allows the same instance to be restarted.
- If another thread calls `stop` while a listener callback is running, `stop` waits for that
  callback to finish. Calling `stop` or restarting from inside the callback is supported.
- A second `start` while running returns `AlreadyRunning` and does not replace the original
  listener.

Choose the creation context deliberately on multi-display devices: display remapping follows the
display captured from that context. An Activity or display context is therefore preferable when
the orientation must follow a non-default display; the implementation still retains only its
application context.

## Kotlin example

This controller keeps lifecycle ownership in the host and exposes no UI assumptions:

```kotlin
import android.content.Context
import ir.hrka.compass.Compass
import ir.hrka.compass.CompassConfig
import ir.hrka.compass.CompassDirection
import ir.hrka.compass.CompassEvent
import ir.hrka.compass.CompassListener
import ir.hrka.compass.CompassSmoothing
import ir.hrka.compass.CompassStartResult
import ir.hrka.compass.CompassUpdateRate
import ir.hrka.compass.GeomagneticPosition
import ir.hrka.compass.NorthReference

class OrientationController(context: Context) {
    private val compass = Compass.create(
        context = context,
        config = CompassConfig(
            updateRate = CompassUpdateRate.NORMAL,
            smoothing = CompassSmoothing.BALANCED,
        ),
    )

    private val listener = CompassListener { event ->
        when (event) {
            is CompassEvent.Reading -> {
                val reading = event.value
                val reference = if (reading.trueAzimuthDegrees != null) {
                    NorthReference.TRUE
                } else {
                    NorthReference.MAGNETIC
                }
                val headingDegrees = requireNotNull(reading.azimuth(reference))
                val direction = requireNotNull(reading.nearestDirection(reference))
                val northDeviation = requireNotNull(
                    reading.deviationFrom(CompassDirection.NORTH, reference)
                )

                onOrientation(
                    headingDegrees = headingDegrees,
                    direction = direction,
                    clockwiseFromNorth = northDeviation.signedDegrees,
                )
            }

            is CompassEvent.Failure -> onCompassFailure(event.failure.code.name)
        }
    }

    fun onForeground(): CompassStartResult = compass.start(listener)

    fun onBackground() {
        compass.stop()
    }

    fun updateTrueNorth(
        latitudeDegrees: Double,
        longitudeDegrees: Double,
        altitudeMeters: Double,
        timestampEpochMillis: Long,
    ) {
        compass.setGeomagneticPosition(
            GeomagneticPosition(
                latitudeDegrees = latitudeDegrees,
                longitudeDegrees = longitudeDegrees,
                altitudeMeters = altitudeMeters,
                timestampEpochMillis = timestampEpochMillis,
            )
        )
    }

    fun clearTrueNorth() {
        compass.setGeomagneticPosition(null)
    }

    private fun onOrientation(
        headingDegrees: Float,
        direction: CompassDirection,
        clockwiseFromNorth: Float,
    ) {
        // Store or forward logical orientation state to the host application.
    }

    private fun onCompassFailure(code: String) {
        // Map the typed failure to host logging, recovery, or user-facing state.
    }
}
```

Before starting, a host can inspect `compass.availability` to disable unsupported behavior early.
It must still handle `CompassStartResult.Failed`, because Android may reject registration even when
the required sensor was discovered.

## Sensor selection and fallback

At construction, the implementation selects the first complete strategy available in this order:

1. `TYPE_ROTATION_VECTOR` -> `CompassSensorSource.ROTATION_VECTOR`
2. `TYPE_GEOMAGNETIC_ROTATION_VECTOR` ->
   `CompassSensorSource.GEOMAGNETIC_ROTATION_VECTOR`
3. Both `TYPE_ACCELEROMETER` and `TYPE_MAGNETIC_FIELD` ->
   `CompassSensorSource.ACCELEROMETER_AND_MAGNETOMETER`

The manual fallback fuses a finite accelerometer/magnetometer pair whose timestamps are no more
than 500 ms apart. Game rotation vectors are not used because they do not maintain a magnetic-north
reference. Android's heading sensor is not used because it does not provide the pitch and roll
included in this module's coherent reading model.

`CompassAvailability.Available(source)` and each reading expose the chosen strategy. If no strategy
is complete, availability reports `NO_SUPPORTED_SENSOR`; absence of Android's sensor service reports
`SENSOR_SERVICE_UNAVAILABLE`.

## Build, test, lint, and publish

Run these commands from the repository root:

```powershell
# Compile the public and internal implementation.
.\gradlew.bat :compass:compileDebugKotlin

# Run model, direction, deviation, filtering, sampling, accuracy, and display-axis unit tests.
.\gradlew.bat :compass:testDebugUnitTest

# Check the library manifest and Android-specific static analysis rules.
.\gradlew.bat :compass:lintDebug

# Produce compass/build/outputs/aar/compass-release.aar.
.\gradlew.bat :compass:assembleRelease

# Generate the release publication POM without publishing it.
.\gradlew.bat :compass:generatePomFileForReleasePublication

# Publish ir.hrka:compass:0.1.0 to the current user's Maven Local repository.
.\gradlew.bat :compass:publishReleasePublicationToMavenLocal
```

The tests are local JVM tests for deterministic public-model and calculation behavior. Sensor
registration and physical orientation must also be exercised on real Android hardware.

## Device caveats

- Many emulators and some physical devices do not expose a usable north-bearing sensor. Treat
  `availability` and start failures as ordinary runtime states.
- Magnetic headings are affected by cases, speakers, vehicles, reinforced structures, electrical
  equipment, and other nearby magnetic fields. Surface `CalibrationStatus.RECOMMENDED` or
  `REQUIRED` through the host's own UX when appropriate.
- Android sensor delivery rates are best-effort; power modes and device firmware can reduce them.
- Pitch and roll use Android's display-corrected orientation conventions. Validate the desired
  device posture on the target hardware rather than treating them as aircraft attitude values.
- True north is only as accurate as the caller-supplied coordinates, WGS-84 ellipsoidal altitude,
  and timestamp. Clear stale input instead of silently presenting old declination as current.
- Verify rotation changes, the intended Activity/display context, background stop behavior,
  calibration changes, magnetic interference, and every supported sensor strategy on physical
  devices whenever possible.
