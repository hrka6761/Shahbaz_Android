# Temporary Shahbaz Flight-Stack Checklist

Last updated: 2026-09-03 (Asia/Tehran)

This is the resumable source of truth for the current multi-repository task. Read it before doing
more work so completed analysis, implementation, and verification are not repeated. Keep this file
until the external hardware/architecture decisions have been transferred to permanent project
records.

Status legend: `[x]` completed with software evidence, `[~]` active, `[ ]` remaining software work,
`[!]` requires a user decision or physical evidence and cannot be completed from source alone.

## Repository baselines and preservation

- `[x]` Android repository: `D:\Projects\Personal\Shahbaz`, branch `master`, baseline
  `45ad8fc7ff24e684adf3df0887bbc97cc47663d5`. The working tree intentionally contains this task's
  uncommitted implementation plus pre-existing mission work; do not reset it. `.vscode/` is
  unrelated user content and must be preserved.
- `[x]` Interface-board repository: `D:\Projects\Personal\Shahbaz_Interface_Board`, branch
  `master`, baseline `654e6722427cc45e102290a6816513dda994b0d6`.
- `[x]` PX4 reference: `D:\Projects\Personal\PX4-Autopilot`, clean `main` at
  `8f43cf7268b88ad23abcc63c0fbbfa43eb42b9e3`; treat it as read-only.
- `[x]` Preserve all unrelated changes. Use `apply_patch` for source/document edits.

## Safety result that must survive every resume

- `[x]` `docs/PX4_COMPATIBILITY_MATRIX.md` records the source-backed comparison. Android plus an
  ESP32 peripheral is not, and cannot honestly be described as, the complete PX4 flight stack.
- `[x]` If exact PX4 behavior is mandatory, the production architecture is a supported flight
  controller running a pinned/reviewed PX4 release; Android owns planning/operator UI and uses a
  supported companion interface such as MAVLink/MAVSDK. The ESP32 may remain ancillary telemetry.
- `[x]` The custom Android/ESP32 flight path is explicitly experimental, non-PX4-equivalent, and
  non-flight-qualified. Android scheduling and USB are not hard real time.
- `[x]` Physical actuators remain compiled/configured off by default on both sides. Android's
  deliberate bench-only property is `-Pshahbaz.experimentalPhysicalActuators=true`; firmware has
  separate physical-review/evidence gates. Never bypass either gate merely to make a demo run.
- `[x]` Production land detection, route/airspace, landing-zone, energy, geofence, and wind
  providers are not connected. Start therefore enters fail-closed preflight and cannot arm.
- `[x]` Software test success is not permission to install propellers or fly.

## Operator information and mission behavior

- `[x]` Setup captures a fresh origin, destination coordinate, positive cruise altitude above the
  takeoff surface, and signed destination-ground elevation relative to takeoff.
- `[x]` Changing the destination retains the cruise-altitude draft but clears the destination
  ground-elevation value so it must be reconfirmed.
- `[x]` The requested "starting altitude" is defined as cruise altitude above takeoff, not an MSL
  altitude. Origin altitude on the ground is derived from the live reference.
- `[x]` Permanent vehicle/safety facts should come from validated providers/configuration rather
  than repeated free-form operator entry: route legality, terrain/corridor, landing-zone status,
  wind envelope, battery/reserve energy, calibration, mass/payload, motor mapping, and sensor
  geometry.
- `[x]` Dashboard Start submits `START`; it never directly arms motors.
- `[x]` `PointToPointAutopilot` implements fail-closed preflight, arm request, vertical profiled
  takeoff, acceleration-bounded straight local-NED cruise, destination landing, sustained
  touchdown confirmation, disarm, abort/return containment, and latched emergency stop.
- `[x]` Flight-control feedback includes bounded cascaded position/velocity/attitude/rate control,
  Quad-X allocation, limits/anti-windup, command freshness, board-confirmed arming state, and fault
  containment. This is a tested custom subset, not EKF2/Commander/Navigator/control-allocation
  parity and not evidence of arbitrary-wind protection.

## Independent Android modules and simple APIs

- `[x]` Added Android-free pure Kotlin `:core:flight_contracts` for immutable cross-boundary DTOs.
- `[x]` `:core:autopilot`, `:core:flight_controller`, `:core:hardware_connection`, and
  `:core:remote_pilot` have no direct Gradle project dependency on one another.
- `[x]` Autopilot consumes neutral controller snapshots and emits high-level lifecycle/setpoint
  intent; it owns no controller, USB, or actuator implementation.
- `[x]` Flight controller consumes neutral observations/commands and emits a neutral four-motor
  frame; it owns no autopilot or USB implementation.
- `[x]` Hardware connection owns USB/protocol/board telemetry and actuator transport only.
- `[x]` `FlightMissionRuntime` in the dashboard composition boundary owns all adapters and serial
  step ordering.
- `[x]` Public READMEs provide minimal construction/start/step/stop examples.
- `[x]` Root Gradle task `verifyFlightModuleIndependence` mechanically rejects sibling flight
  implementation dependencies.

## Four VL53L0X / GY-530 sensors

- `[x]` Primary references are recorded in permanent docs: ST datasheet, UM2039 API manual,
  AN4846 multi-device note, and pinned PX4 VL53L0X/rangefinder source.
- `[x]` Roles are stable end to end: instance 0 Ground/down, 1 Up, 2 Front-left, 3 Front-right.
- `[x]` All sensors share ESP32-S3 SDA GPIO8/SCL GPIO9 at the configured bus rate.
- `[x]` The board implementation provides four independent active-low XSHUT controls, sequential
  boot/identity validation, and volatile address assignment `0x30` through `0x33`.
- `[x]` Proposed XSHUT GPIOs are 12/13/14/15, with compile-time/runtime uniqueness and conflict
  checks plus mandatory physical-review evidence. The array defaults disabled.
- `[!]` The user must confirm and document four real XSHUT wires (or request a separately designed
  I2C mux path). Connecting only SDA/SCL is insufficient because every sensor powers up at `0x29`.
- `[!]` Verify actual GY-530 breakout voltage behavior, aggregate pull-ups/capacitance, power
  integrity, pin eligibility, harness labels, orientation, optical windows, mounting offsets, and
  crosstalk on the assembled aircraft.

## Interface-board implementation

- `[x]` Added standalone allocation-free `sensor_vl53l0x` domain/driver with bounded cooperative
  initialization, measurement, timeout, retry, rejoin, per-device isolation, and shared-bus
  recovery/readdress behavior.
- `[x]` Range status/limits are validated before publication; invalid distance is never converted
  to ground contact. Published fields are distance mm, raw range status, and conservative quality.
- `[x]` Shared scheduling is fair across SHT30, MS5611, and four rangefinder roles without blocking
  sleeps/spins in steady state.
- `[x]` Sensor ID 3 and instances 0 through 3 extend Protocol v2 compatibly; field IDs 5/6/7 carry
  the three unsigned 32-bit range values.
- `[x]` Extended DeviceStatus is exactly 10 bytes and appends Ground/Up/Front-left/Front-right
  lifecycle bytes: 0 disabled/absent, 1 initializing, 2 live, 3 degraded. The Android/reference
  decoder still accepts exact legacy length 6; all other sizes or values fail closed.
- `[x]` C++ codec/dispatcher/engine, Kotlin reference, Android decoder/state store, Python HIL tool,
  English/Persian docs, and validation scripts use the same IDs and meanings.
- `[x]` Sensor-only/actuator-disabled firmware remains the safe default.

## Android telemetry, dashboard, and landing

- `[x]` Hardware connection exposes typed, timestamped per-role range telemetry and retains each
  last-good value independently from current control eligibility.
- `[x]` Strict decoding rejects malformed size/type/instance/field sets, duplicates, impossible
  distance/status/quality combinations, stale/future/regressing samples, and invalid lifecycle
  values.
- `[x]` Dashboard has four independent labelled cards for ground clearance, obstacle above,
  front-left, and front-right; invalid readings are never displayed as zero.
- `[x]` DeviceStatus rangefinder lifecycle is wired into explicit
  disabled/initializing/live/degraded dashboard states with focused decoder, store, and
  presentation tests. An explicit non-live lifecycle is authoritative over a late valid sample.
- `[x]` Only Ground telemetry is adapted into the autopilot. Up/front values are telemetry for
  future work and are not represented as obstacle avoidance.
- `[x]` The landing aid engages at/below 2 m only after consecutive fresh, good-quality samples;
  checks timestamp identity, age, tilt projection, continuity/jumps, vertical-rate plausibility,
  hysteresis, and barometer disagreement.
- `[x]` Loss or disagreement during final descent commands a bounded hold rather than blind
  barometric descent. A recovered sensor rebases the descent target to avoid a catch-up drop.
- `[x]` Range alone never causes disarm: touchdown also requires fresh independent `ON_GROUND`,
  prior airborne evidence, bounded position/altitude/speed, and sustained confirmation.

## Atomic actuator-generation path

- `[x]` Android public `sendMotorPulses` requires exactly channels 0,1,2,3 once each with valid
  values, snapshots mutable caller input, canonicalizes order, and sends one critical
  `MotorFrameCommand` (`0x8014`) with one sequence and one expected ACK action (`18`).
- `[x]` Bounded submission/ACK tracking, source-generation freshness, NACK/timeout failure, and
  reserved/coalesced Disarm/E-stop submissions prevent unbounded backpressure.
- `[x]` Firmware validates exact length/count/order/ranges before any actuator call and invokes one
  complete `writeMotorFrame` operation.
- `[x]` ESP-IDF stages all four LEDC duties before updating channels. Any staging/update error
  forces all outputs safe and latches the safety fault.
- `[x]` One coherent USB request removes independently deliverable four-command generations from
  the production Android API.
- `[x]` Legacy board-side `MotorCommand` and generic motor `ActuatorCommand` fail closed by
  default; compatibility exists only behind the explicit default-off
  `CONFIG_SHAHBAZ_ALLOW_LEGACY_INDIVIDUAL_MOTOR_COMMANDS` flag, with both policies tested.
- `[!]` ESP32 LEDC has no cross-channel simultaneous-latch primitive in this backend. Measure real
  update skew and all-or-safe behavior with instrumented HIL before considering physical use.

## Verification evidence

No finite suite can prove every physical environment, timing interleaving, silicon failure, or
aircraft state. "Nothing untested" is implemented as strict modeled-state/boundary/fault tests plus
explicitly listed evidence gaps; it must not be reworded as exhaustive flight proof.

Completed during this task:

- `[x]` Android pre-atomic integration gate:
  `gradlew testDebugUnitTest lintDebug assembleDebug verifyFlightModuleIndependence --continue` —
  BUILD SUCCESSFUL, 658 tasks, 13m30s.
- `[x]` Android focused atomic-frame gate:
  `:core:hardware_connection:testDebugUnitTest :feature:dashboard:impl:testDebugUnitTest
  :feature:dashboard:impl:lintDebug verifyFlightModuleIndependence` — BUILD SUCCESSFUL, 245 tasks,
  2m55s.
- `[x]` Focused ground-range landing tests, including duplicate timestamp/cache and recovery
  rebase cases — passed.
- `[x]` Board post-atomic/lifecycle strict clean host build compiled 72/72 steps and CTest passed
  17/17, including VL53 array, dispatcher, fake-LEDC motor-frame, and device-link tests.
- `[x]` Board firmware contract validator and Windows protocol/HIL self-test passed after the
  extended DeviceStatus work.
- `[x]` Board `git diff --check` passed after the post-atomic/lifecycle host build (line-ending
  notices only).
- `[x]` Board pre-final safe ESP-IDF build passed; generated binary was 0x46750 bytes.
- `[x]` Standalone Kotlin protocol-reference self-test with warnings as errors passed after
  extended DeviceStatus work.
- `[x]` Final Android integration gate:
  `gradlew testDebugUnitTest lintDebug assembleDebug verifyFlightModuleIndependence --continue
  --stacktrace` — BUILD SUCCESSFUL in 3m17s, 660 actionable tasks (69 executed, 591 up-to-date).
- `[x]` Final pure-Kotlin gate: `:core:domain:test :core:model:test :core:flight_contracts:test` —
  BUILD SUCCESSFUL.
- `[x]` Final Android JUnit XML audit: 36 XML suites, 310 tests, 0 failures, 0 errors, 0 skipped.
- `[x]` Final Android lint audit: 14 reports, 0 errors and 7 advisory issues (tool/dependency
  version notices, `OldTargetApi`, and one `ObsoleteSdkInt`). Debug assembly and module-dependency
  verification passed in the same gate.
- `[x]` Arming time-of-check/time-of-use regression closed: every preflight prerequisite is
  revalidated while the controller is DISARMED/ARMING and on the exact board-confirmed ARMED input;
  any regression or unexpected DISARMING state fails and commands disarm. Focused autopilot suite
  passes 42 tests.
- `[x]` Delayed board samples retain separate USB-receipt and device-observation timestamps;
  telemetry staleness and controller/autopilot freshness use mapped observation time. Malformed or
  replayed frames cannot reset first-sample deadlines, and a valid late frame cannot override an
  explicit non-LIVE DeviceStatus lifecycle.
- `[x]` An abnormal mission-runtime loop exit now clears prepared/running state, publishes terminal
  failure, and cannot silently restart without explicit teardown/recomposition.
- `[x]` Final clean board host build compiled 72/72 steps and CTest passed 17/17 after the VL53 and
  legacy-motor audit. Added regressions cover per-device register `0x91`, exact Q9.7 rate-limit
  bytes, initial/WaitForBoot shared recovery, full four-address restart, recovered-sample quality,
  and publisher recovery.
- `[x]` Final ESP-IDF 5.4.4 safe/null-actuator production build passed. Image size is `0x46a30`
  (289,328 bytes), 2.30% of the 12,582,912-byte factory partition; production artifact verifier
  confirms `actuator_null` is linked and the physical actuator backend is absent.
- `[x]` Final board protocol/HIL self-test, firmware semantic-contract validator, 61-source
  structural safety checker, boot/reset/watchdog transcript self-test, production-verifier
  contamination self-tests, and Kotlin Protocol v2 reference self-test passed.
- `[x]` Final graphics-input validation passed with four expected missing-photo warnings; those
  warnings deliberately keep physical overlay/evidence blocked.
- `[x]` Final bilingual documentation validation passed: 82 Markdown files, 41 Persian/English
  pairs, no broken local links, direction/terminology policy valid. The Persian Protocol v2 guide
  now mirrors the lifecycle, four-rangefinder, and atomic motor-frame additions.
- `[x]` `git diff --check` passed in both repositories (only Git line-ending notices).
- `[!]` `adb devices -l` reports no attached device. Connected Android instrumentation, USB
  permission/lifecycle, and physical dashboard checks therefore remain unavailable; JVM
  presentation tests are not represented as device/UI tests.
- `[x]` Task-created `build-host-vl53/` and `build-vl53-idf/` remain untracked as rebuild caches to
  make a later source-change verification cheaper. They are reproducible artifacts, not source,
  and may be removed only by their exact verified paths when no longer useful.

Additional verification not yet implemented and not to be hidden:

- `[ ]` Reviewed line/branch coverage report and mutation testing for safety-critical pure logic.
- `[ ]` Long-running parser fuzzing/corpus regression and sanitizer builds on a supported toolchain.
- `[ ]` Cross-process Android-to-board host simulation and PX4/custom-stack SITL scenarios with
  injected wind, sensor, clock, USB, process, and actuator faults.
- `[!]` On-device Android USB permission/detach/re-enumeration/accessibility/orientation tests need
  an API 31+ OTG phone and board.
- `[!]` Four-sensor bench HIL needs the physical board, actual harness, targets/surfaces, sunlight,
  tilt/vibration/dust tests, recorded bus traces, and fault injection.
- `[!]` Actuator HIL needs props removed, oscilloscope/logic-analyzer evidence, ESC loads,
  watchdog/brownout/reset tests, and reviewed emergency procedures.
- `[!]` Any tethered or free-flight testing requires the architecture decision, completed gates,
  legal site, and qualified controls/safety review.

## Board-connected Windows tasks

These items are intentionally blocked until the Shahbaz interface board is physically connected to
Windows. They are the exact next actions to take after connection; keep all actuators disabled
unless a separate reviewed actuator-HIL authority is provided.

- `[!]` Identify the two board USB paths on Windows and record the actual port names:
  `COM_FLASH` for the programming/diagnostic USB-UART path and `COM_USB` for the native ESP32-S3
  USB CDC Protocol v2 path. Do not assume the COM numbers; read them from Windows after plugging in
  the board.
- `[!]` From `D:\Projects\Personal\Shahbaz_Interface_Board`, build the current safe firmware with
  the null actuator backend, then flash only that freshly built image to `COM_FLASH`:
  `powershell -ExecutionPolicy Bypass -File tools\build_esp32.ps1 -ActuatorBackend null`, followed
  by the project flashing command for the discovered `COM_FLASH`.
- `[!]` Capture and validate a boot/reset/watchdog transcript from `COM_FLASH` with
  `py -3 tools\capture_boot_log.py --port COM_FLASH --duration 12 --validate-production
  --artifact artifacts\boot_COM_FLASH_YYYYMMDD_HHMMSS.log`. Required evidence: ESP-IDF 5.4.4 boot,
  16 MB flash, 8 MB PSRAM, null actuator message, I2C ready on GPIO8/GPIO9, native USB CDC ready,
  task watchdog subscribed, and SHT30/MS5611 online with no panic/reset markers.
- `[!]` Run the non-actuating Windows board HIL against the native USB CDC port:
  `py -3 tools\windows_hil_test.py --port COM_USB --qnh-hpa 1013.25 --artifact
  artifacts\windows_hil_COM_USB_YYYYMMDD_HHMMSS.log`. Required evidence: TimeSync, nonzero session
  token, heartbeat, DeviceInfo, DeviceStatus, telemetry start/stop, SHT30 samples, MS5611 samples,
  ping/pong, CRC rejection/recovery, and actuators disabled.
- `[!]` If the four VL53L0X XSHUT wires and evidence are present and the rangefinder profile has
  been deliberately enabled, rerun the HIL with `--require-rangefinders`. Required evidence:
  extended 10-byte DeviceStatus reports Ground/Up/Front-left/Front-right as `LIVE`, and repeated
  valid samples arrive for sensor ID `3`, instances `0`, `1`, `2`, and `3`.
- `[!]` Run the reconnect/session-rollover HIL with `--reconnect-test`; physically detach and
  reconnect the native USB path when prompted. Required evidence: the old session token is rejected,
  the new session token is distinct and nonzero, and stale buffered traffic is not accepted after
  reconnect.
- `[!]` Preserve all transcripts/artifacts and copy the PASS/FAIL result, real COM names, firmware
  commit hash, board serial/identity, and any wiring findings back into this checklist before
  continuing to Android-on-device USB testing.

## Next resume sequence

1. Read this file and both `git status --short` outputs; do not reset either repository.
2. Do not repeat the completed software gates unless source or build inputs have changed.
3. Continue with the explicit coverage/fuzz/simulation items only if their tooling is selected, or
   stop at the `[!]` gates until the user supplies the required wiring, hardware, architecture
   decision, and test authority. Never infer them.

## Session log

### 2026-09-02 — implementation milestone

- Completed the PX4 responsibility matrix and selected the only honest exact-PX4 architecture.
- Refactored Android flight implementations around neutral contracts and added dependency policing.
- Integrated dashboard mission runtime and conservative range-assisted landing policy.
- Added the four-device VL53L0X board stack, protocol, Android telemetry/dashboard, docs, and tests.
- Replaced Android's four independent motor requests with one coherent board frame and added
  validate-before-apply/all-safe semantics plus fake-LEDC failure tests.
- Added extended per-role lifecycle status while preserving strict legacy status decoding.
- At this milestone, production Android lifecycle presentation/default legacy-motor closure and
  the final software gates were still pending.

### 2026-09-03 — software closeout and independent safety audit

- Closed the rangefinder lifecycle/dashboard integration and default-off legacy individual-motor
  policy, then reran the complete Android and interface-board software gates listed above.
- A final Android audit found and fixed arming-gate TOCTOU, device-time/USB-receipt conflation,
  lifecycle/sample contradiction, first-sample deadline reset, and abnormal runtime-loop restart
  behavior; regression tests cover each case.
- A final board audit found and fixed four VL53 array defects that older happy-path tests missed:
  register `0x91` state is now per device, the 0.25 MCPS Q9.7 value is `0x0020`, every shared-bus
  recovery reasserts all XSHUT lines before readdressing (including WaitForBoot interruption), and
  the first later sample carries `RecoveredAfterError`. Successful publication also clears
  transient publisher-backpressure status.
- The closeout review also made a failed VL53 electrical-recovery attempt publish the same shared
  settle barrier as a successful attempt, so SHT30/MS5611 state is invalidated and no I2C client
  resumes before the mandatory full-array XSHUT restart. A focused regression and the full clean
  17/17 host gate pass after this change.
- Synchronized the Persian/English Protocol v2 documentation with extended DeviceStatus,
  rangefinder samples, and coherent `MotorFrameCommand` semantics.
- Software implementation and local verification are complete for the modeled scope. External
  architecture, wiring, on-device, HIL, propulsion, and flight-evidence gates remain deliberately
  blocked and are listed above rather than being misrepresented as tested.
