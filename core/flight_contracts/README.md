# Flight contracts

`:core:flight_contracts` is the Android-free boundary between high-level pilot policy and a flight
controller implementation. It contains immutable intent and feedback values only. Autopilot,
flight-controller, remote-pilot, and hardware implementations must not depend directly on one
another; an app or feature composition layer that can see both sides owns conversion and wiring.

```text
:core:autopilot ---> :core:flight_contracts <--- composition adapter ---> :core:flight_controller
                                                                  |
                                                                  v
                                                   :core:hardware_connection
```

The arrow into hardware represents composition-layer adaptation of neutral controller actuator
actions. `:core:flight_contracts` itself has no hardware API and no dependency on any module shown.

## Public surface

The contract deliberately exposes only what the current point-to-point policy needs:

- finite `Vector3d`, `Quaterniond`, and `GeoPoint` value objects;
- a controller-owned `LocalNavigationReference` and `VehicleStateEstimate` view;
- arming/lifecycle state, health issues, and target-tracking feedback;
- `PositionControlTarget`; and
- sequenced, time-bounded `FlightControlCommand` values.

Constructors reject non-finite coordinates, invalid geographic bounds, negative timestamps and
sequences, inconsistent navigation-reference timestamps, and invalid command deadlines. A command
created with `FlightControlCommand.validFor` also rejects duration overflow.

The module intentionally contains no USB or wire schema, Android lifecycle type, actuator action,
controller gain, estimator implementation, mission state machine, UI resource, PX4/MAVLink type,
or uORB topic. It is an internal Shahbaz interoperability boundary, not a claim of PX4 API
compatibility.

## Use

The autopilot accepts the preceding immutable controller view and returns the next command and
lifecycle request. The composition owner explicitly converts both directions:

```kotlin
val decision = autopilot.step(
    input = autopilotInput.copy(
        flightController = adaptControllerSnapshot(controller.snapshot.value),
    ),
    request = operatorRequest,
)

controller.step(
    input = controllerInput,
    command = decision.flightControlCommand?.let(::adaptControllerCommand),
    lifecycleRequest = adaptLifecycleRequest(decision.lifecycleRequest),
)
```

Adapters must preserve sequences, deadlines, monotonic acquisition timestamps, local NED axes,
quaternion direction, health meaning, and every enum case. They should be exhaustive `when`
expressions with focused tests. Do not add a dependency from autopilot to the controller merely to
avoid writing an adapter.

## Verification

```powershell
.\gradlew.bat :core:flight_contracts:test verifyFlightModuleIndependence --no-daemon
```

These tests establish DTO invariants and source-graph rules only. Controller behavior,
composition-adapter mappings, hardware transport, HIL, and flight behavior require their own test
levels. See the [PX4 compatibility and flight-readiness matrix](../../docs/PX4_COMPATIBILITY_MATRIX.md).
