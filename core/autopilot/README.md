# Shahbaz Autopilot

`:core:autopilot` is a synchronous, UI-free policy engine for Shahbaz's point-to-point mission:

1. bind the confirmed plan to the live aircraft and controller reference;
2. complete fail-closed preflight checks;
3. arm and climb vertically above the takeoff point;
4. fly the direct local-NED route at the configured cruise altitude;
5. descend at the destination using a two-rate landing profile;
6. require sustained, independent touchdown evidence; and
7. disarm and report completion.

The module decides **what target comes next**. It consumes and emits immutable
`:core:flight_contracts` values and never imports `:core:flight_controller`, USB, PX4/MAVLink, PWM,
Android UI, or motor implementations. `:core:flight_controller` remains responsible for
estimation, feedback control, arming health, motor allocation, and low-level failsafe behavior. A
composition layer that depends on both modules owns the explicit contract conversions.

This is not flight-qualified software. The dashboard can now dispatch mission intent and present
autopilot state, but Start only requests preflight: static policy may keep the plan in `STANDBY`,
and the button never arms motors directly. Physical board actuators remain disabled by default,
the four-rangefinder profile remains disabled until its XSHUT wiring evidence is supplied, and
no production landing detector or safety-status providers are integrated. Those missing
prerequisites keep arming blocked. Do not bypass them to make a physical aircraft fly.

## Mission information

The guided setup records:

- a precise live takeoff origin (captured automatically, not typed by the user);
- the destination coordinate;
- cruise altitude above the takeoff surface; and
- destination ground elevation relative to the takeoff surface (`0` means level endpoints).

The last value is necessary because a 2D destination cannot tell the autopilot what barometric
altitude represents its landing surface. A trusted terrain service or downward range sensor should
eventually supply it automatically; manual entry is only a fallback and is not touchdown evidence.

Speed limits, climb/descent rates, target tolerances, command lifetime, timeouts, route corridor,
and return policy are aircraft policy in `AutopilotConfig`, not routine user questions. The caller
must additionally provide fresh `AutopilotSafetyStatus` from route/airspace, landing-zone, battery,
geofence, and wind-envelope owners plus an independent `AutopilotLandingObservation`.

## State machine

```text
STANDBY -> PREFLIGHT -> ARMING -> TAKEOFF -> CRUISE -> LANDING
                                                        |
                                                        v
                                  COMPLETED <- DISARMING

active phase -- abort/policy loss/timeout --> RETURN_CLIMB -> RETURNING -> LANDING(origin)
controller failsafe -----------------------> FAILED
emergency request -------------------------> EMERGENCY_STOPPED
```

Target completion must remain `REACHED` for `targetSettleMillis`; one noisy sample never advances
the mission. Takeoff, cruise, return-climb, and return legs use rest-to-rest triangular or
trapezoidal profiles with configured acceleration and speed limits, velocity feed-forward, and
smooth acceleration/deceleration. A trajectory setpoint may lead the measured aircraft by no more
than `maximumTrajectoryLeadMeters`; if feedback shows the aircraft falling behind, feed-forward is
suppressed until it catches up. Landing uses a similarly lead-limited, acceleration-bounded,
two-rate vertical schedule: it descends up to `landingDescentRateMetersPerSecond`, settles into the
flare segment, then descends up to `finalDescentRateMetersPerSecond` before stopping at ground.

These targets are followed by the flight controller's closed position, velocity, attitude, and
rate loops, so bounded wind disturbance is corrected from measured state instead of by open-loop
motor commands. That is not protection from arbitrary wind: fresh external policy must keep
`windWithinLimits` true. Wind outside the configured aircraft envelope blocks preflight or commits
an airborne mission to a controlled landing at its current horizontal position; it never commands
a climb while already landing. Controller failure remains a terminal failure.

Disarming requires a fresh `ON_GROUND` observation that occurred after an observed airborne state,
bounded position/altitude/velocity, and sustained confirmation time. Mission completion then waits
for a newer, fresh board status confirming `actuatorArmed=false`; emitting a host-side Disarm action
is not completion. Barometric altitude reaching the planned ground elevation is deliberately
insufficient.

### Downward-range landing aid

Only the VL53L0X channel explicitly identified as `GROUND` is eligible for landing control. At or
below the default 2 m handover height, the policy requires three distinct advancing samples and
checks freshness, reported quality, vehicle tilt, timestamp order, continuity, physically plausible
vertical rate, and agreement with the barometric estimate. An accepted slant range is projected
onto the local vertical before use. The resulting AGL error is translated into the controller's
existing altitude reference; it does not silently redefine that reference.

Once final range-guided descent is active, a missing or rejected observation near the surface
commands an altitude hold rather than a blind barometric fallback. Range at or below the default
0.5 m threshold is still only proximity evidence: sustained fresh `ON_GROUND` evidence, low
horizontal/vertical speed, bounded position and altitude, and prior airborne observation remain
necessary before disarm. The up, front-left, and front-right channels are dashboard telemetry only
and currently provide no obstacle-avoidance behavior.

An ordinary airborne abort returns to the known origin: climb in place to cruise altitude, fly home,
land, then disarm. A preflight abort cancels immediately, while an unarmed arming abort performs the
confirmed disarm path. Energy exhaustion, excessive wind, or loss of the aggregate safety feed
selects land-in-place instead of a return climb. Airborne mission/phase timeouts are one-shot
containment events: outbound phases return, return phases land in place, and a landing timeout keeps
descending until independently confirmed touchdown. `EMERGENCY_STOP` is a separate explicit,
latched path. Low-level controller failsafe is never disguised as a successful abort.

## Use

```kotlin
val autopilot = Autopilot.create(confirmedFlightPlan)

// Run on the same serialized, monotonic control loop as the flight controller.
val decision = autopilot.step(
    input = AutopilotInput(
        timestampNanos = nowNanos,
        flightController = adaptControllerSnapshot(controller.snapshot.value),
        navigationFix = navigationFix,
        landingObservation = landingDetectorObservation,
        groundRange = downwardRangeObservation,
        safetyStatus = safetyStatus,
    ),
    request = if (startPressed) AutopilotRequest.START else AutopilotRequest.NONE,
)

val controllerOutput = controller.step(
    input = controllerInput,
    command = decision.flightControlCommand?.let(::adaptControllerCommand),
    lifecycleRequest = adaptLifecycleRequest(decision.lifecycleRequest),
)
```

The `adapt...` functions belong to the composition layer; they are intentionally not part of the
autopilot module. Shahbaz's dashboard implementation provides one concrete set in
`FlightMissionRuntime`.

Call order is intentional: the autopilot consumes the preceding controller snapshot and issues the
next bounded command. The controller then evaluates current sensor input and that decision. Execute
the controller's neutral actuator actions outside both calculations.

The app's runtime adapter owns this serialization and turns UI intent into one-shot
`AutopilotRequest` values. It also translates controller actions at the hardware boundary and
publishes `AutopilotSnapshot` for the dashboard. A consumer can use the same small API without the
dashboard: create one `Autopilot`, call `step` with fresh typed observations and the prior
controller snapshot, then pass its command and lifecycle request to one controller step.

Create/reset the flight controller for the mission before preflight and feed the same accepted
absolute location stream to the controller and autopilot. The controller now publishes its exact
captured `LocalNavigationReference`; destination NED conversion uses that reference, while
preflight rejects a reference that does not match the saved origin.

## Preflight and containment

After static mission policy accepts Start, the mission remains in `PREFLIGHT` until all of these are
current and valid:

- the plan passes distance, altitude, endpoint-clearance, and destination-elevation policy;
- a fresh accurate navigation fix is near the saved takeoff origin;
- the controller's immutable local reference is available and agrees with that origin;
- the independent landing detector says the aircraft is on the ground;
- route/airspace, destination landing zone, energy reserve, and geofence checks pass;
- measured or forecast wind remains inside the configured flight envelope; and
- the flight controller reports the current position target can arm.

Every live gate is checked again while arming, including on the exact input that carries the
board-confirmed `ARMED` state. If any provider regresses in that interval, the mission transitions
to failure and requests disarm instead of entering takeoff.

During flight the module refreshes every command with a strictly increasing sequence and bounded
deadline, supervises the direct-route corridor and safety status, enforces phase/mission timeouts,
and contains policy failures without requesting airborne disarm. Route/LZ/geofence failures select
return-to-origin when viable; inadequate energy, excessive wind, stale/unavailable aggregate safety,
or a fault during return selects land-in-place. With no terrain model, land-in-place uses the higher
known endpoint elevation as a conservative floor and therefore cannot replace an AGL/terrain source.
Controller failsafe, unexpected in-flight disarm, or timestamp regression remains an explicit
terminal failure.

## Runtime boundary and remaining blockers

The Android integration provides the Start/Abort/E-STOP intent path, serialized mission/controller
execution boundary, and dashboard snapshot presentation. A production aircraft integration must
still provide all of the following before preflight can advance to arming:

- an onboard PX4/MAVLink flight path, or another reviewed single actuator authority;
- HIL validation of the implemented atomic multi-motor transport/application frame, including
  ACK/NACK, backpressure, channel-update skew, and all-or-safe failure behavior;
- reviewed, timestamp-preserving navigation and inertial sensor adapters on the serialized loop;
- battery, geofence, wind, route/airspace, and landing-zone readiness sources;
- physically verified downward ranging plus an independent/contact-based land detector;
- obstacle/terrain clearance for the full route, not only its endpoints;
- tested abort/link-loss behavior and hardware-in-the-loop/flight validation.

The Shahbaz interface-board firmware implements environmental telemetry and four typed
VL53L0X streams. Rangefinder enablement is safely off until the exact four XSHUT routes are reviewed
and recorded, and physical actuators remain off by default. Pressure may assist monitoring, but it
is not by itself a terrain or landing sensor. Until actuator authority, real downward ranging, and
the independent providers above exist and are validated, the runtime reports blockers and no
physical mission can start. The project-wide comparison and acceptance gates are recorded in
[`docs/PX4_COMPATIBILITY_MATRIX.md`](../../docs/PX4_COMPATIBILITY_MATRIX.md).

## Verification

```powershell
.\gradlew.bat :core:autopilot:testDebugUnitTest :core:autopilot:lintDebug `
  :core:autopilot:assembleRelease --no-daemon
```

Tests cover mission validation, preflight interlocks, phase settling, sequencing, touchdown,
abort/return, failsafe/emergency behavior, timestamp rejection, route geometry, and antimeridian
conversion. `GroundRangeLandingAidTest` additionally covers acquisition, handover, loss handling,
and rejection boundaries. Physical integration still requires HIL and flight testing.
