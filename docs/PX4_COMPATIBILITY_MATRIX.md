# PX4 compatibility and flight-readiness matrix

## Result

The current Shahbaz Android application and Shahbaz interface-board firmware are **not an exact
implementation of PX4**, are **not flight-qualified**, and must not be treated as safe to fly a
physical quadcopter. They contain a useful point-to-point mission policy, a custom feedback-control
prototype, a guarded USB/ESP32 boundary, and a four-channel VL53L0X implementation. Those are
analogous subsets of a few PX4 functions, not substitutes for the complete PX4 flight stack.

If "exactly what PX4 does" is a hard requirement, the supported architecture is:

```text
Android Shahbaz (planning, operator intent, monitoring)
                         |
                  MAVLink / MAVSDK
                         |
supported flight-controller hardware running a reviewed, pinned PX4 release
                         |
             sensors, ESCs, motors, safety I/O
```

PX4 documents this flight-controller-plus-companion-computer split and explicitly notes that a
companion may run Android. In that architecture, Android does not close the attitude/rate loop and
does not retain sole actuator authority. See [PX4 system architecture][px4-system-architecture].

The alternative custom Shahbaz path can continue as an experimental research stack, but passing
the tests listed here would still demonstrate only its own requirements. It cannot establish
behavioral identity with all PX4 vehicle, estimator, navigation, safety, driver, parameter, and
logging subsystems.

## Audit basis and meaning of status

This matrix was prepared on 2026-09-02 from these local working copies:

- Shahbaz Android baseline `45ad8fc7ff24e684adf3df0887bbc97cc47663d5`, including the current
  uncommitted flight-policy, contract-boundary, dashboard, and rangefinder changes;
- Shahbaz interface-board baseline `654e6722427cc45e102290a6816513dda994b0d6`, including the current
  uncommitted four-VL53L0X changes; and
- clean PX4 `main` at `8f43cf7268b88ad23abcc63c0fbbfa43eb42b9e3`
  (`v1.18.0-beta1-496-g8f43cf7268`).

The PX4 comparison is pinned because PX4 `main` changes continuously. Primary references are the
[pinned PX4 source tree][px4-source], [PX4 flight-stack architecture][px4-architecture],
[uORB messaging][px4-uorb], [rangefinder guidance][px4-rangefinders],
[conditional range aiding][px4-range-aid], and [land detector configuration][px4-land-detector].

Statuses mean:

- **Implemented (software):** present in the current source with focused automated tests. This is
  not physical validation.
- **Analogous subset:** similar purpose or shape, but materially less capable than PX4.
- **Scaffolded/gated:** an interface or path exists but production inputs, outputs, or evidence are
  deliberately unavailable.
- **Missing:** no equivalent capability was found in the audited working tree.
- **External validation required:** correctness depends on real wiring, timing, propulsion, RF/GNSS,
  airframe, environment, or flight evidence that source inspection cannot provide.

## Architecture and module boundaries

| Area | PX4 reference behavior | Current Shahbaz evidence | Status and gap |
| --- | --- | --- | --- |
| Primary flight authority | PX4 runs estimation, guidance, control, allocation, drivers, and safety on flight-controller hardware with real-time scheduling support. | The mission and control loop run in an Android process; the ESP32 is a USB sensor/actuator peripheral. | **Not equivalent.** Android scheduling, process lifecycle, USB, and the phone are in the primary control path. |
| Runtime model | PX4 modules run as tasks or work-queue items and communicate asynchronously through uORB. | `FlightMissionRuntime` serializes synchronous `Autopilot.step` and `FlightController.step` calls on `Dispatchers.Default.limitedParallelism(1)`. | **Analogous subset.** Deterministic call order is useful, but a JVM coroutine dispatcher is not a hard real-time flight runtime. |
| Internal communication | Typed uORB topics decouple drivers, estimator, commander, navigator, controllers, allocation, logging, and external bridges. | `:core:flight_contracts` provides immutable policy/controller DTOs; the dashboard composition layer converts between autopilot, controller, and hardware types. | **Implemented (software), not uORB parity.** No asynchronous flight-wide bus, topic introspection, multi-instance arbitration, or uORB replacement semantics. |
| Android module independence | PX4 uses replaceable modules with shared message contracts. | Autopilot, flight controller, hardware connection, and remote pilot are separate Gradle modules. Autopilot uses `:core:flight_contracts` rather than importing the controller implementation; dashboard owns adaptation. | **Implemented directionally.** Independence means no sibling-implementation dependency, not zero shared-library dependency or process isolation. |
| External API | PX4 exposes established MAVLink/MAVSDK and ROS 2 integration paths. | Shahbaz exposes local Kotlin APIs and a private USB Protocol v2. | **Analogous subset.** The custom API is simple for in-app use but is not MAVLink-compatible or interoperable with PX4 tooling. |
| Hardware support | PX4 has board configurations and a large driver ecosystem across validated flight-controller targets. | The board firmware targets one ESP32-S3 profile and the Android transport targets its VID/PID and Protocol v2 contract. | **Narrow custom implementation.** No PX4 board target, Pixhawk standard conformance, or equivalent driver coverage. |
| Parameters and calibration | PX4 has persistent parameters, airframe configuration, sensor calibration, estimator/controller tuning, and metadata. | Kotlin config objects and ESP Kconfig values provide compile-time/runtime constants. | **Missing PX4 equivalent.** There is no complete persistent, versioned, field-serviceable airframe/calibration parameter system. |
| Logging and diagnostics | PX4 provides events, uORB diagnostics, perf counters, and ULog flight logging used by its analysis ecosystem. | Flight controller and hardware connection record selected events through `:core:flight_black_box`; board components expose bounded health counters. | **Analogous subset.** No ULog-equivalent full-rate synchronized flight log or PX4 analysis compatibility. |
| Supported vehicle scope | PX4 supports many multirotor, fixed-wing, VTOL, rover, and other configurations. | The custom controller is fixed to one configurable Quad-X model. | **Intentional subset.** This is acceptable only if the Shahbaz product requirement remains one proven Quad-X airframe. |

## Guidance, navigation, estimation, and control

| Area | PX4 reference behavior | Current Shahbaz evidence | Status and gap |
| --- | --- | --- | --- |
| State estimation | PX4 EKF2 performs delayed multi-sensor fusion, covariance propagation, innovation checks, source switching, bias estimation, terrain estimation, and estimator status reporting. | `StateEstimator` derives attitude/rate, barometric altitude/vertical velocity, and GNSS-derived local position/velocity from phone and board samples. | **Analogous subset, major gap.** It is not EKF2 and lacks equivalent uncertainty, bias, delay, redundancy, innovation, reset, and sensor-fault machinery. |
| Attitude and body-rate control | PX4 multicopter attitude and rate modules run reviewed feedback laws with parameterization, constraints, saturation handling, and extensive integration history. | The custom controller has quaternion attitude feedback, body-rate PID, limits, and anti-windup. | **Analogous subset.** Math-level tests do not establish equivalent dynamics, tuning, timing, actuator response, or disturbance rejection. |
| Position, velocity, and altitude control | PX4 generates and tracks constrained trajectories through its multicopter position-control stack. | Shahbaz has position/velocity/altitude cascades and accepts bounded position targets with feed-forward. | **Analogous subset.** No PX4 trajectory-generator parity, estimator coupling, or airframe-validated tuning. |
| Control allocation | PX4 control allocation supports configured geometries, effectiveness matrices, failures, multiple actuator types, and output drivers. | `QuadXControlAllocator` maps thrust/torque to four PWM values using one Quad-X layout and reports saturation. | **Analogous subset.** No equivalent actuator-effectiveness model, motor-failure handling, dynamic allocation, ESC telemetry feedback, or supported output ecosystem. |
| Point-to-point mission | PX4 Navigator, mission modes, flight tasks, commander, and land/RTL behavior cooperate through published state and parameters. | `PointToPointAutopilot` performs preflight, arm, vertical takeoff, direct cruise, landing, touchdown confirmation, disarm, abort-to-origin, and emergency-state transitions. | **Implemented for the stated policy, not PX4 mission parity.** It is one mission state machine without the full Navigator/Commander/flight-mode system. |
| Straight route and smooth motion | PX4 produces constrained setpoints and controllers close the loop on estimated state. | Shahbaz uses acceleration-bounded triangular/trapezoidal profiles, velocity feed-forward, target settling, and maximum trajectory lead. | **Implemented (software), external validation required.** The path is direct in local NED, but smooth/stable physical motion depends on estimation, timing, tuning, propulsion, and wind tests. |
| Takeoff | PX4 takeoff behavior is integrated with commander state, land detector, estimator validity, constraints, and failsafes. | The custom policy commands a vertical position profile over the captured origin and waits for sustained target tracking. | **Analogous subset.** Production arming providers are absent, so the present app cannot execute physical takeoff. |
| Landing | PX4 landing coordinates navigation, position control, estimator/terrain information, land detection, thrust behavior, and failsafes. | Shahbaz has two-rate descent, a 2 m downward-range handover, final-descent hold on unusable range, and sustained independent touchdown requirements. | **Analogous subset.** The range aid does not replace the estimator reference, and the production independent land detector is not connected. |
| Wind response | PX4 rejects bounded disturbances through its estimator and closed feedback loops; safety behavior depends on configuration and available wind/airspeed information. | Shahbaz feedback loops can mathematically correct measured error, while `AutopilotSafetyStatus.windWithinLimits` gates/contains the mission. | **Scaffolded/gated.** No production wind provider, calibrated envelope, or flight evidence exists. Never describe this as protection from arbitrary wind. |
| Geofence, route, and airspace | PX4 has geofence, mission feasibility, traffic/airspace integrations, and configurable failsafe actions. | The autopilot checks a direct-route corridor and requires affirmative route, destination-zone, and geofence booleans from external owners. | **Scaffolded/gated.** Default providers are unavailable; no terrain/obstacle volume or authoritative airspace service is integrated. |
| Collision prevention | PX4 can integrate obstacle-distance data with collision-prevention behavior. | Up, front-left, and front-right VL53L0X values are displayed but are not consumed by the mission policy. | **Missing control behavior.** The three channels are telemetry only; they must not be represented as obstacle avoidance. |
| Return and containment | PX4 has configurable RTL and failsafe action selection based on vehicle/state context. | Abort and some policy failures select return-to-origin; inadequate energy, excessive wind, safety loss during return, and landing conditions select land-in-place or a latched failure. | **Analogous subset.** No safe-point database, rally points, terrain-aware return, link ecosystem, or PX4 commander action matrix. |
| Manual/offboard mode arbitration | PX4 has explicit flight-mode ownership, RC/offboard loss handling, and commander arbitration. | One dashboard runtime owns the custom autopilot/controller loop; `:core:remote_pilot` is reserved. | **Missing.** There is no production manual takeover, RC input, source arbitration, or PX4-compatible offboard mode. |

## Safety, actuator, and transport path

| Area | PX4 reference behavior | Current Shahbaz evidence | Status and gap |
| --- | --- | --- | --- |
| Preflight and arming | PX4 commander evaluates estimator, sensors, calibration, power, safety, vehicle configuration, and configured health checks before arming. | Autopilot and controller reject arming unless fresh navigation, controller reference/health, board state, landing observation, route/LZ/energy/geofence/wind status, and motor availability pass. | **Scaffolded/gated.** The policy is fail-closed, but several production providers do not exist and its check set is not PX4 Commander-equivalent. |
| Board-confirmed lifecycle | PX4 publishes and supervises actuator-armed state inside the flight stack. | Controller waits for newer board status to confirm arm/disarm and treats unexpected disarm or confirmation timeout as faults. | **Implemented (software), HIL required.** USB/session timing and real board behavior still need fault-injected validation. |
| Command freshness | PX4 setpoints and offboard control have freshness/loss semantics, while onboard control avoids dependence on a consumer USB link. | Commands have monotonic sequence numbers and deadlines; Protocol v2 validates framing, sequence/freshness, and ACK/NACK. | **Implemented custom mechanism, not equivalent.** The primary loop still depends on Android and USB continuity. |
| Multi-motor atomicity | PX4 allocation/output drivers apply a coherent actuator-output generation to the relevant hardware backend. | `sendMotorPulses` emits one fixed-size Protocol v2 `MotorFrameCommand` containing channels 0 through 3 exactly once. Android and firmware validate the whole generation, track one ACK/NACK, and a board apply error forces all outputs safe. | **Implemented protocol-level coherence; HIL required.** One USB frame removes independently deliverable per-channel updates. The ESP32 backend stages four duties before update, but simultaneous peripheral-register latching, update skew, ESC behavior, and injected apply failures still require instrumented board HIL. |
| Independent actuator watchdog | PX4 flight-controller outputs and failsafe behavior execute onboard. | The ESP32 safety supervisor has stale-frame handling and can stop outputs independently of an Android heartbeat. Physical actuators are disabled by default behind evidence gates. | **Scaffolded/gated.** Timing bounds, stop behavior, reset/brownout behavior, and all four real ESC channels require bench/HIL proof. |
| Emergency stop | PX4 has vehicle- and configuration-specific kill/disarm/failsafe semantics with explicit hazards. | Android/controller/board expose a latched emergency path that suppresses normal PWM commands. | **Implemented custom path, HIL required.** An emergency motor stop can itself cause injury or uncontrolled descent; it is not proof of PX4 semantics. |
| Link/process failure | PX4 can keep essential control onboard if a GCS or companion link fails. | Android process death, OS scheduling stalls, USB detach, or phone failure removes the custom controller authority; the board can only apply its limited watchdog action. | **Major architectural gap.** This is the strongest reason to place PX4 on a dedicated flight controller. |
| Physical output enablement | PX4-supported boards have defined output mappings and hardware validation processes. | ESP32 actuator output defaults off and requires board/pin evidence before composition can select the physical backend. | **Correctly fail-closed, external validation required.** Do not bypass the evidence gate to demonstrate progress. |

## Four VL53L0X / GY-530 channels

ST specifies that VL53L0X uses I2C and powers up at the same device address. Its multi-device
application note requires devices to be released one at a time using independently controlled
XSHUT lines and assigned unique addresses, or to use an equivalent expander/multiplexer design.
See the [VL53L0X datasheet][st-datasheet], [ST API manual][st-api], and
[ST multi-device application note AN4846][st-an4846].

Connecting only all four SDA wires to GPIO8 and all four SCL wires to GPIO9 is therefore
insufficient. The exact XSHUT wiring (or a selected I2C mux) is required before enabling this
feature.

| Requirement | Current implementation | Status and remaining evidence |
| --- | --- | --- |
| Shared I2C bus | All four devices use the board I2C bus on GPIO8/GPIO9. | **Implemented in configuration.** Verify bus voltage, pull-ups, capacitance, power integrity, and exact GY-530 breakout behavior on the assembled aircraft. |
| Unique device selection | Board code holds all sensors in shutdown, releases them sequentially, verifies identity, and assigns `0x30`, `0x31`, `0x32`, and `0x33`. | **Implemented (software).** Four independent XSHUT controls or a separately implemented mux are mandatory. |
| XSHUT pins | Kconfig proposes GPIO12/13/14/15 for ground/up/front-left/front-right and checks uniqueness/conflicts. Enablement also requires a non-empty physical-review evidence record. | **Scaffolded/gated.** These are proposals, not a wiring fact. Record the actual pins and electrical review; otherwise leave `SHAHBAZ_VL53L0X_ENABLE=n`. |
| Roles and identity | Instance 0 = ground, 1 = up, 2 = front-left, 3 = front-right, consistently represented by board and Android enums. | **Implemented (software).** Permanently label harnesses and verify orientation on the assembled airframe. |
| Driver scheduling | The cooperative driver performs at most one bounded bus/recovery/XSHUT operation per step, uses round-robin work, timeouts, retry backoff, and shared-bus invalidation. | **Implemented (software).** Measure actual bus utilization, sensor rate, worst-case latency, simultaneous optical interference, and recovery on hardware. |
| Measurement validity | Board parsing retains raw range status, accepts the same conservative PX4-driver status codes, and limits control-eligible distance to 30–2000 mm. | **Implemented policy.** Current signal quality is derived as 100/0 from status/range rather than a calibrated continuous confidence measure. Surface reflectance, sunlight, cover glass, dust, angle, and crosstalk remain uncharacterized. |
| Protocol and Android decoding | Protocol SensorId 3 carries four instances; Android validates exact field types, limits, status/quality consistency, freshness, lifecycle, and retains last-good values independently. | **Implemented (software).** Run USB corruption, loss, reorder, restart, and real-sensor fault tests. |
| Dashboard display | Four labelled values are shown independently: ground clearance, obstacle above, front-left obstacle, and front-right obstacle. | **Implemented (software).** Verify labels against the physical harness and accessibility/layout on target devices. |
| Landing handover | Only the ground channel enters autopilot. At or below 2 m, three distinct fresh accepted samples are required; checks cover quality, tilt projection, timestamps, continuity/jumps, and barometer disagreement. Loss near the surface commands altitude hold rather than blind barometric descent. | **Implemented policy, not PX4 EKF range aid.** Validate mounting offset, landing-gear geometry, ground slope, rotor vibration/dust, surfaces, transitions around 2 m, and every rejection/loss case in HIL and flight test. |
| Touchdown | Default policy additionally requires ground range at or below 0.5 m plus fresh independent `ON_GROUND`, low speed, bounded position/altitude, prior airborne evidence, and sustained confirmation. | **Scaffolded/gated.** The production independent land detector is absent. Range alone must never disarm the aircraft. |
| Other three sensors | Their telemetry is preserved for later use. | **No obstacle-control use.** Up/front sensors currently neither alter the route nor stop the vehicle. |
| PX4 message equivalence | PX4 `DistanceSensor` describes min/max range, variance, signal quality, horizontal/vertical field of view, quaternion/orientation, and timestamps. | **Not equivalent.** Shahbaz has fixed role mapping and a private payload; it lacks the complete PX4 topic metadata/fusion path. |

The pinned PX4 VL53L0X driver configures a 2 m maximum and 25-degree field of view and publishes
through its rangefinder abstraction. Reusing part of that register sequence under its included
BSD-3-Clause notice does not make the Shahbaz driver or system PX4-compatible.

## Information required before a mission can be authorized

The routine operator setup should collect only mission intent that cannot be derived safely:

- destination coordinate;
- cruise altitude above the takeoff surface; and
- destination ground elevation relative to takeoff, unless a trusted terrain/elevation source
  supplies it.

The origin should be captured from a fresh, accurate live navigation fix and confirmed against the
controller's immutable local reference. "Starting altitude" is ambiguous: Shahbaz currently treats
the requested positive value as **cruise altitude above takeoff**, not an absolute MSL altitude and
not the aircraft's height while still on the ground.

The following are required mission/vehicle evidence, but should normally come from authoritative
providers and configuration rather than repeated free-form operator entry:

- route legality/airspace, geofence, complete corridor terrain/obstacle clearance, and destination
  landing-zone suitability;
- current and forecast wind against a tested aircraft envelope;
- battery state, propulsion health, payload/mass, reserve energy, and return/land contingency;
- calibrated navigation accuracy, sensor health, aircraft geometry, motor/propeller/ESC mapping,
  and controller parameters; and
- the exact four-sensor power/I2C/XSHUT wiring, mounting orientation, optical clearance, and
  downward-sensor height relative to the landing gear.

The current dashboard runtime deliberately supplies unavailable defaults for the independent land
detector and mission-safety providers. Pressing Start therefore requests preflight but must not
advance to physical arming.

## Verification gap and acceptance gates

No finite test suite can prove that "nothing and no state is left untested," particularly for a
physical aircraft in unbounded environments. A defensible goal is complete traceability of stated
requirements, systematic boundary/fault coverage, measured coverage reports, and progressively
stronger physical evidence. PX4 itself uses multiple levels including unit tests, integration
tests, simulation, fuzzing, bench tests, HITL, and test flights; see [PX4 testing and CI][px4-tests],
[MAVSDK integration testing][px4-mavsdk-tests], [fuzz tests][px4-fuzz],
[simulation][px4-simulation], and [HITL][px4-hitl].

The custom stack must remain physically disabled until all of these gates pass:

1. **Architecture decision:** either adopt pinned PX4 on supported flight-controller hardware, or
   formally accept that the custom stack has independent requirements and cannot claim PX4 parity.
2. **Requirements traceability:** assign stable IDs and acceptance criteria to every mission,
   sensor, estimator, controller, actuator, fault, recovery, operator, and environmental behavior.
3. **Electrical review:** verify the exact ESP32-S3 board, power/backfeed protection, I2C electrical
   budget, four XSHUT routes, sensor orientation, native USB route, actuator pins, and brownout/reset
   behavior; store the referenced evidence records required by firmware gates.
4. **Atomic actuator protocol:** apply and acknowledge one complete four-motor generation or none;
   prove stale, duplicate, corrupt, partial, delayed, reordered, saturated, and backpressured cases.
5. **Propulsion bench tests:** props removed first, then guarded thrust stand; verify motor order,
   direction, PWM bounds, ESC startup/loss behavior, watchdog latency, emergency action, power sag,
   and thermal limits.
6. **Estimator/control verification:** use recorded/replayed data, sensor delay/drop/bias/noise/freeze
   injection, timing-overrun tests, model-in-the-loop/simulation, parameter sweeps, and measured
   stability margins for the exact airframe.
7. **Rangefinder HIL:** test all four real devices together across range boundaries, surfaces,
   sunlight, tilt, vibration, dust, crosstalk, bus faults, XSHUT faults, reconnect/reboot, handover,
   loss grace, ground slope, and false-near/false-far readings.
8. **System HIL/fault injection:** exercise Android process death, phone thermal throttling, USB
   detach/reconnect, board reset, sensor and GNSS loss, stale/future clocks, corrupted frames,
   actuator NACK/timeout, battery/wind/geofence changes, abort, RTL, landing, and emergency paths.
9. **Incremental flight-test program:** only after formal review and HIL success, use a controlled
   legal site and qualified personnel, beginning tethered/low-energy with explicit abort criteria.
   Progress from hover to vertical profiles, short routes, landing surfaces, disturbance cases, and
   envelope expansion while reviewing synchronized logs after every test.

Passing Android JVM tests, board host tests, an ESP-IDF build, or a dashboard demonstration does not
satisfy gates 3–9 and does not authorize flight.

## Decision record

Until the acceptance gates above are completed, documentation and UI must use these descriptions:

- **Allowed:** "PX4-inspired control structure," "analogous subset," "experimental custom flight
  stack," and "software-tested, hardware validation pending."
- **Not allowed:** "PX4-compatible flight controller," "same as PX4," "flight safe," "wind-proof,"
  "obstacle avoidance," or "fully/exhaustively tested."

[px4-source]: https://github.com/PX4/PX4-Autopilot/tree/8f43cf7268b88ad23abcc63c0fbbfa43eb42b9e3
[px4-architecture]: https://docs.px4.io/main/en/concept/architecture
[px4-system-architecture]: https://docs.px4.io/main/en/concept/px4_systems_architecture
[px4-uorb]: https://docs.px4.io/main/en/middleware/uorb
[px4-rangefinders]: https://docs.px4.io/main/en/sensor/rangefinders
[px4-range-aid]: https://docs.px4.io/main/en/advanced_config/tuning_the_ecl_ekf#conditional-range-aiding
[px4-land-detector]: https://docs.px4.io/main/en/advanced_config/land_detector
[px4-tests]: https://docs.px4.io/main/en/test_and_ci/
[px4-mavsdk-tests]: https://docs.px4.io/main/en/test_and_ci/integration_testing_mavsdk
[px4-fuzz]: https://docs.px4.io/main/en/test_and_ci/fuzz_tests
[px4-simulation]: https://docs.px4.io/main/en/simulation/
[px4-hitl]: https://docs.px4.io/main/en/simulation/hardware
[st-datasheet]: https://www.st.com/resource/en/datasheet/vl53l0x.pdf
[st-an4846]: https://www.st.com/resource/en/application_note/an4846-using-multiple-vl53l0x-in-a-single-design-stmicroelectronics.pdf
[st-api]: https://www.st.com/resource/en/user_manual/um2039-world-smallest-timeofflight-ranging-and-gesture-detection-sensor-application-programming-interface-stmicroelectronics.pdf
