# Shahbaz Flight Controller

`:core:flight_controller` is the synchronous, UI-free control boundary between a pilot and the
aircraft actuators. It consumes independently timestamped sensor/board state plus one externally
selected control primitive, estimates the vehicle state, enforces low-level safety, runs cascaded
feedback control, allocates Quad-X motor output, and returns hardware-neutral actuator actions.

The module is inspired by PX4's separation of position, attitude, rate, and allocation loops. It is
not a Kotlin port of all PX4 subsystems and must not be represented as flight-qualified software.

## Flight Controller Responsibility

Flight Controller has no pilot or mission-planning responsibility. It converts a pilot-supplied
control primitive and sensor-derived vehicle state into actuator commands for the current loop
iteration.

It owns:

- per-sample freshness and numerical validation;
- the current local estimation origin and lightweight state estimate;
- attitude, body-rate, altitude, velocity, and local-position feedback loops;
- controller limits, integrators, anti-windup, and target tracking status;
- Quad-X control allocation and PWM generation;
- arming rejection, board-confirmed arming, disarm, failsafe, and emergency stop;
- sparse Flight Black Box records for state, health, command, and saturation changes.

It does not choose a destination, waypoint, route, takeoff/landing sequence, return-to-home action,
or next primitive. Reaching a primitive is reported as `ControlTargetStatus.REACHED`; the supplied
target remains the target until the pilot replaces it or it expires.

## Flight Controller vs Autopilot

```text
Autopilot / Remote Pilot
          |
          | FlightControlCommand (desired state or motion)
          v
    Flight Controller
          |
          | FlightControllerActuatorAction
          v
   hardware_connection
          |
          | Protocol v2 / Android USB host
          v
 Shahbaz Interface Board
          |
          v
   Motors / future actuators
```

The future `:core:autopilot` decides what the aircraft should do and decomposes missions into
control primitives. The future `:core:remote_pilot` converts human/network/joystick intent into the
same primitives. Neither module may depend on `:core:hardware_connection` or send motor commands.

`:core:hardware_connection` alone owns Android USB discovery, permission, Protocol v2, board
sessions, telemetry, command serialization, and physical-board transmission. Flight-control math
does not import USB or protocol classes.

## Workflow

One complete control session works as follows:

1. The composition layer creates `AndroidPhoneSensorSource`, `HardwareConnection`, and one
   `FlightController`.
2. `AndroidPhoneSensorSource` converts Android device axes into aircraft body FRD using the
   configured physical mounting and publishes independently timestamped phone samples.
3. The composition layer converts fresh board telemetry and board status into
   `ExternalSensorSnapshot` and `FlightControllerBoardState` without dropping their acquisition
   times.
4. Autopilot or Remote Pilot selects exactly one primitive and wraps it in a bounded
   `FlightControlCommand` with a monotonically increasing sequence and validity deadline.
5. At the control-loop time, the caller builds `FlightControllerInput` using the same monotonic
   elapsed-realtime clock as every sample and calls `FlightController.step(...)`.
6. The estimator accepts only fresh samples, updates attitude/local references, and differentiates
   altitude/location by sample time. Repeated samples are not integrated or differentiated twice.
7. Health checks validate loop timing, command freshness/order, required sensor observability,
   board readiness, actuator availability, and motor channel count.
8. An `ARM` request moves to `ARMING` only if health passes. The controller emits `Arm` but no PWM.
   It enters `ARMED` only after a fresh board device-status sample confirms `actuatorArmed=true`.
9. While armed, the selected cascade computes error, attitude/body-rate/throttle targets, normalized
   torque, and four motor outputs. Saturation feedback freezes integrators that would wind up.
10. The allocator emits one coherent `ApplyMotorPwm` action carrying the original controller
    timestamp. The integration layer maps it to `HardwareConnection.sendMotorPulses(...)`.
11. `hardware_connection` preserves that generation timestamp in every Protocol v2 motor frame.
    The board rejects stale frames and independently stops outputs if fresh actuator commands stop,
    even while USB heartbeat remains healthy.
12. The caller observes `health`, `tracking`, and `armingState`. On `REACHED`, only the pilot decides
    whether to refresh the target, replace it, or issue a different primitive.
13. The caller explicitly disarms before stopping/resetting the controller and closes phone/USB
    sources during host lifecycle shutdown.

No blocking I/O occurs in `step`. The caller should run it on one dedicated high-priority loop and
execute returned hardware actions outside the control calculation.

## Public API

### Engine

```kotlin
val controller = FlightController.create(FlightControllerConfig())

val output = controller.step(
    input = input,
    command = currentPilotCommand,
    lifecycleRequest = FlightControllerLifecycleRequest.RUN,
)
```

`step` is synchronous and serialized. `snapshot: StateFlow<FlightControllerSnapshot>` exposes the
latest result to monitoring code. `reset()` clears estimator references, command sequence, and
integrators, but is rejected while `ARMING` or `ARMED`; disarm first.

### Pilot command

```kotlin
val command = FlightControlCommand.validFor(
    sequence = 42,
    issuedAtNanos = nowNanos,
    validForNanos = 250_000_000L,
    setpoint = AttitudeControlTarget.fromEuler(
        rollRadians = 0.0,
        pitchRadians = 0.0,
        yawRadians = 10.0 * Math.PI / 180.0,
        thrust = CollectiveThrust(0.5),
    ),
)
```

Command semantics are strict:

- a command is valid only from `issuedAtNanos` through `validUntilNanos`;
- the maximum accepted validity interval is configured, defaulting to 1 second;
- the same sequence may be reused only with exactly equal content;
- a greater sequence immediately replaces the previous target;
- an older or conflicting sequence is rejected;
- a missing/expired/rejected command blocks arming and causes armed failsafe;
- continuing a long-lived target requires a new sequence and fresh deadline;
- source arbitration occurs above this module, never in the control math.

### Lifecycle requests

| Request | Meaning |
|---|---|
| `HOLD_DISARMED` | Remain logically disarmed; emit a disarm action only when leaving a non-disarmed state. |
| `ARM` | Request arming. Safety may reject it; successful request first enters `ARMING`. |
| `RUN` | Track the supplied command without changing a disarmed controller into armed. |
| `DISARM` | Immediately stop control output and emit a hardware disarm action. |
| `EMERGENCY_STOP` | Latch `EMERGENCY_STOPPED` and emit the board safety override. |

`FAILSAFE` remains latched until an explicit `DISARM`. `EMERGENCY_STOPPED` ignores `DISARM` and
remains latched until `reset()`; the board may additionally require its own recovery/restart policy.

## Supported Primitives

| Type | External intent | Internal cascade | Required estimate |
|---|---|---|---|
| `RateControlTarget` | Body FRD roll/pitch/yaw rate plus collective thrust | Rate PID -> torque -> allocation | Attitude and body rate |
| `AttitudeControlTarget` | Body-to-NED attitude plus collective thrust | Quaternion attitude P -> rate PID -> allocation | Attitude and body rate |
| `AltitudeControlTarget` | Altitude above local origin, climb feed-forward, optional NED yaw | Altitude P -> vertical velocity PID -> attitude/rate -> allocation | Altitude and vertical velocity |
| `VelocityControlTarget` | Local NED velocity and optional NED yaw | Velocity PID -> attitude/rate -> allocation | Complete local NED velocity |
| `PositionControlTarget` | Local NED position, velocity feed-forward, optional NED yaw | Position P -> velocity PID -> attitude/rate -> allocation | Complete local NED position and velocity |

There is currently no takeoff, land, waypoint, relative-displacement, bounded-distance, route, or
mission command. A future pilot can implement climb `+50 m` by reading the reported local altitude,
issuing an absolute `AltitudeControlTarget(current + 50)`, observing `REACHED`, and then choosing the
next command. It can implement bounded travel by issuing position/velocity primitives and tracking
progress above this boundary.

## Tracking and Health

`ControlTracking` reports:

- command sequence and `INACTIVE`, `TRACKING`, `REACHED`, `UNAVAILABLE`, or `REJECTED`;
- position, velocity, altitude, quaternion small-angle, and body-rate errors when applicable;
- motor allocation saturation.

Completion uses configurable tolerances. It is status only and never triggers another primitive.

`FlightControllerHealth.issues` contains stable `FlightControllerHealthIssueCode` values plus human
messages. It distinguishes timestamp regression/gap, missing/future/expired/out-of-order commands,
unobservable state, stale board state, unavailable actuators/channels, arming-confirmation timeout,
and an unexpected board disarm. Ordinary high-rate health conditions are returned as data instead
of exceptions.

Constructors reject impossible numerical/domain values such as NaN vectors, a zero attitude
quaternion target, invalid latitude/humidity, invalid PWM limits, or negative gains.

## Frames and Units

All public names carry units. The fixed conventions are:

| Quantity | Convention |
|---|---|
| Body vectors | FRD: X forward, Y right, Z down |
| Local vectors | NED: X north, Y east, Z down |
| Attitude quaternion | Rotates body FRD vectors into local NED |
| Yaw | Radians; zero is local magnetic north, positive clockwise toward east |
| Angular rate | Radians/second in body FRD |
| Position / altitude | Meters |
| Velocity / acceleration | Meters/second and meters/second squared |
| Pressure | Board pressure in pascals; Android pressure in hectopascals |
| Time | Monotonic nanoseconds; never wall-clock/UTC |
| Motor command | Normalized `0..1` plus PWM pulse width in microseconds |

Android's rotation vector is ENU/device-frame data. `AndroidPhoneSensorSource` converts it to NED
and converts accelerometer/gyroscope/magnetometer vectors into body FRD. The default mounting is
screen up with the phone top toward the aircraft nose. Any other physical mounting must supply an
explicit `AndroidPhoneSensorMounting`; a wrong transform produces wrong control direction.

## State Estimation

The current estimator intentionally makes limited claims:

- Android fused attitude is preferred. Fresh body acceleration/magnetic field provide a
  tilt-compensated magnetic fallback. Each new gyro sample may propagate an existing attitude.
- The first fresh MSL-altitude source captures the local altitude origin. Sources are considered in
  this order: board altitude, board pressure/QNH, phone pressure/QNH, location altitude.
- The first location captures the local horizontal origin. A short-distance equirectangular
  conversion produces north/east meters.
- Vertical and horizontal velocity are finite differences of new altitude/location samples with a
  simple low-pass blend. The 10 ms control-loop period is not used as GPS/barometer sample time.
- Position/velocity targets remain unavailable until enough fresh measurements exist.

This is not an EKF and does not estimate IMU bias, wind, terrain, optical flow, motor dynamics,
battery sag, or GPS uncertainty. It does not integrate accelerometer data into inertial position.
Phone location velocity is too noisy/slow for precision position flight, magnetic heading can be
disturbed by the airframe/motors, and changing altitude sources may introduce offsets. A production
aircraft requires calibrated sensors, a validated mounting, a substantially stronger estimator,
airframe-specific tuning, and hardware-in-the-loop/flight testing.

## Arming and Failsafe

Arming requires all of the following for the requested primitive:

- strictly advancing control-loop time with an acceptable gap;
- a fresh accepted pilot command;
- fresh body-to-NED attitude and body angular rate;
- any altitude/position/velocity state required by the target;
- fresh `Ready` board state;
- physical actuator availability and all four configured motor channels;
- no contradictory `actuatorArmed=true` report while logically disarmed.

After `Arm` is transmitted, motor output remains absent until fresh board status confirms arming.
Loss of any required condition, stale command, stale critical sensor, board disarm, invalid time, or
arming timeout forces `FAILSAFE` and a `Disarm` action. Emergency stop takes priority even when
command/sensors are invalid.

The interface-board firmware adds an independent actuator-command watchdog (production default
250 ms). USB heartbeat alone cannot keep stale PWM active. Physical actuators remain disabled in
the production board configuration until the reviewed GPIO/evidence gates are deliberately enabled.

## Control Loops

The controller uses cascaded loops:

```text
position error
    -> bounded velocity target
    -> velocity PID (derivative on measured velocity)
    -> bounded roll/pitch/collective
    -> quaternion attitude error
    -> bounded body-rate target
    -> rate PID (derivative on measured angular acceleration)
    -> normalized body torque
    -> Quad-X allocation/desaturation
    -> motor PWM frame
```

Velocity and rate integrals have explicit limits. Integration that pushes a saturated output
farther outward is blocked, and controller allocation saturation freezes integration on the next
loop. Integrators and derivative history reset on disarm, failsafe, reset, and target-cascade type
changes. Control `dt` is clamped to `0.125..20 ms` by default.

Default gains are starting values only, not validated flight tuning.

## Quad-X Layout

The default `QuadXMotorLayout.PX4_QUAD_X` is configurable:

| Channel | Position | Roll scale | Pitch scale | Yaw scale |
|---:|---|---:|---:|---:|
| 0 | Front right | -1 | +1 | +1 |
| 1 | Rear left | +1 | -1 | +1 |
| 2 | Front left | +1 | +1 | -1 |
| 3 | Rear right | -1 | -1 | -1 |

Channel wiring, propeller direction, ESC response, and sign must be verified without propellers
before use. `QuadXMotorLayout` allows channel/sign changes without modifying controller math. The
current allocator intentionally emits four motors, while `FlightControllerActuatorAction` is a
separate hardware-neutral boundary where future actuator action types can be added without
rewriting the estimator or feedback loops.

While armed, normalized motor values are constrained to the configured flying range (default
`0.08..0.95`) and mapped to PWM (default `1000..2000 us`). While disarmed, `motors` contains safe
diagnostic values but no `ApplyMotorPwm` action is emitted.

## Complete Example

This example shows the composition boundary. It intentionally does not implement pilot logic.

```kotlin
import android.content.Context
import android.os.SystemClock
import ir.hrka.shahbaz.flightcontroller.*
import ir.hrka.shahbaz.hardwareconnection.*

class FlightRuntime(context: Context) : AutoCloseable {
    private val phone = AndroidPhoneSensorSource(
        context = context,
        mounting = AndroidPhoneSensorMounting.SCREEN_UP_TOP_FORWARD,
    )
    private val hardware = HardwareConnection(
        context,
        HardwareConnectionConfig(allowActuatorCommands = true),
    )
    private val controller = FlightController.create()
    private var commandSequence = 0L

    fun start() {
        phone.start()
        hardware.start()
    }

    // A future Autopilot/Remote Pilot owns when and why this target is selected.
    fun newLevelAttitudeCommand(nowNanos: Long): FlightControlCommand =
        FlightControlCommand.validFor(
            sequence = ++commandSequence,
            issuedAtNanos = nowNanos,
            validForNanos = 250_000_000L,
            setpoint = AttitudeControlTarget.fromEuler(
                rollRadians = 0.0,
                pitchRadians = 0.0,
                yawRadians = 0.0,
                thrust = CollectiveThrust(0.5),
            ),
        )

    fun controlStep(
        pilotCommand: FlightControlCommand?,
        lifecycle: FlightControllerLifecycleRequest,
    ): FlightControllerOutput {
        val now = SystemClock.elapsedRealtimeNanos()
        val telemetry = hardware.telemetry.value
        val connection = hardware.connectionState.value
        val ms5611 =
            (telemetry.ms5611 as? SensorState.Available<Ms5611Telemetry>)?.sample
        val status = telemetry.deviceStatus
        val ready = connection as? BoardConnectionState.Ready

        val input = FlightControllerInput(
            timestampNanos = now,
            phone = phone.frame.value.snapshot,
            external = ExternalSensorSnapshot(
                pressurePascal = ms5611?.let {
                    TimedSensorValue(
                        it.value.pressurePascal,
                        it.receivedAtElapsedRealtimeMillis * 1_000_000L,
                    )
                },
                altitudeAboveMeanSeaLevelMeters = ms5611?.let {
                    TimedSensorValue(
                        it.value.altitudeAboveMeanSeaLevelMeters,
                        it.receivedAtElapsedRealtimeMillis * 1_000_000L,
                    )
                },
            ),
            board = FlightControllerBoardState(
                ready = ready != null,
                actuatorAvailable = ready?.deviceInfo?.actuatorAvailable == true,
                activeMotorChannels = ready?.deviceInfo?.activeMotorChannels ?: 0,
                actuatorArmed = status?.actuatorArmed == true,
                observedAtNanos = now,
                actuatorStateObservedAtNanos =
                    status?.receivedAtElapsedRealtimeMillis?.times(1_000_000L),
            ),
        )

        val output = controller.step(input, pilotCommand, lifecycle)
        output.actuatorActions.forEach { action ->
            when (action) {
                FlightControllerActuatorAction.Arm -> hardware.armActuators()
                FlightControllerActuatorAction.Disarm -> hardware.disarmActuators()
                FlightControllerActuatorAction.EmergencyStop ->
                    hardware.emergencyStopActuators()
                is FlightControllerActuatorAction.ApplyMotorPwm ->
                    hardware.sendMotorPulses(
                        pulses = action.motors.map {
                            BoardMotorPulse(it.channel, it.pulseMicros)
                        },
                        generatedAtElapsedRealtimeNanos = action.generatedAtNanos,
                    )
            }
        }
        return output
    }

    override fun close() {
        phone.close()
        hardware.disarmActuators()
        hardware.close()
    }
}
```

The application must inspect `BoardActuatorCommandResult`; a rejected arm/output submission should
be reflected into the next board state and treated as a failed flight start or immediate disarm.
The example omits scheduling, lifecycle cancellation, and pilot-source arbitration because those
belong to the composition/pilot layers.

## Future Autopilot Sequence

A future Autopilot can perform this mission without moving mission logic into Flight Controller:

```text
Autopilot reads estimate.altitudeAboveOriginMeters
  -> sends AltitudeControlTarget(current + 50 m)
  -> waits for REACHED
  -> sends attitude/heading target yaw = 10 degrees magnetic NED
  -> waits for REACHED
  -> sends velocity/position primitives representing 50 km/h over 2 km
  -> observes progress and REACHED
  -> chooses the next primitive
```

Every arrow is a new externally selected command sequence. Flight Controller only tracks the
currently supplied primitive.

## Verification

Run Android checks with:

```powershell
.\gradlew.bat :core:flight_controller:testDebugUnitTest `
  :core:flight_controller:lintDebug `
  :core:flight_controller:assembleRelease --no-daemon
```

Tests cover disarmed output, explicit/confirmed/rejected arming, confirmation timeout, unexpected
board disarm, attitude/rate correction direction, altitude and heading tracking, NED
position/velocity direction, primitive completion, command replacement/order/expiry, stale sensors,
monotonic time, saturation, anti-windup, Quad-X signs, invalid numbers, reset, emergency stop,
sample-time velocity estimation, and phone mounting transforms. Tests require no USB device.

Before physical flight, also run interface-board host tests and firmware safety checks, then perform
propeller-free integration and hardware-in-the-loop validation. Passing unit tests is not evidence
that the aircraft, mounting, motor order, gains, or estimator are flight safe.
