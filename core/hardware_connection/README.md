# `:core:hardware_connection`

A standalone, UI-free Android USB-host library for the production
`shahbaz_interface_board`. It discovers only the board's native TinyUSB CDC identity
(`VID 0x303A`, `PID 0x4001`), owns USB permission and attach/detach handling, performs Shahbaz
Protocol v2 setup, and publishes typed link and sensor state. It has no dependency on the Shahbaz
app, feature modules, AndroidX, Compose, activity, fragment, or view API. It depends on
`:core:flight_black_box` for audit events and exposes Kotlin coroutines `StateFlow` in its public
contract.

The library remains telemetry-only by default. Actuator commands are available only when
`HardwareConnectionConfig.allowActuatorCommands=true`, the Protocol v2 session is Ready, and the
board reports an actuator-capable runtime profile.

## Public contract

```kotlin
import ir.hrka.shahbaz.hardwareconnection.HardwareConnection

val board = HardwareConnection(applicationContext)

val connection: StateFlow<BoardConnectionState> = board.connectionState
val telemetry: StateFlow<BoardTelemetrySnapshot> = board.telemetry

board.start()
board.requestPermission() // module owns the Android permission request and result receiver
board.refresh() // idempotently reconcile attachment/permission after host resume
board.retry()
board.setQnh(1013.25)
board.stop()
board.close()
```

Bench/HIL builds that intentionally need PWM output can opt in:

```kotlin
val board = HardwareConnection(
    applicationContext,
    HardwareConnectionConfig(
        allowActuatorCommands = true,
        motorPulseBounds = BoardPulseBounds(900, 2100),
    ),
)

board.armActuators()
board.sendMotorPulses(
    listOf(
        BoardMotorPulse(channel = 0, pulseMicros = 1500),
        BoardMotorPulse(channel = 1, pulseMicros = 1500),
        BoardMotorPulse(channel = 2, pulseMicros = 1500),
        BoardMotorPulse(channel = 3, pulseMicros = 1500),
    ),
    generatedAtElapsedRealtimeNanos = controllerOutput.generatedAtNanos,
)
board.disarmActuators()
board.emergencyStopActuators()
```

Every actuator method returns `BoardActuatorCommandResult.Queued` or
`BoardActuatorCommandResult.Rejected`. Rejections cover closed links, disabled actuator mode,
non-Ready sessions, unavailable board actuators, invalid channels, empty/oversized batches, and
out-of-range pulse widths. A motor submission must contain every active channel exactly once. Its
monotonic source timestamp is preserved on the wire, and future or over-age output is rejected
instead of being made to look fresh after queue delay. Command ACK/NACK frames are matched to
pending command sequences and recorded in the flight black box.

`BoardConnectionState.Ready` is deliberately strict. It is emitted only after all of the following:

1. exact native-USB discovery and Android permission;
2. CDC bulk IN/OUT open;
3. Protocol v2 TimeSync with a non-zero, echoed session token (using bounded initial retries);
4. DeviceInfo validation for Protocol v2, ESP32-S3, accepted advisory evidence, and no fatal or
   unknown validation bits;
5. a current HeartbeatAck; and
6. StartTelemetry acknowledgement.

Physical detach clears the parser, token, command sequences, telemetry, and raw sensor state. A
reconnect always starts with a new TimeSync. CRC/COBS/length errors are bounded and observable in
`BoardLinkDiagnostics`; CRC-valid frames with malformed payloads are rejected per-frame and cannot
cancel the connection scope. Replay tracking mirrors the firmware's 12-entry USB priority queue:
it accepts a late modulo-u32 frame only inside that bounded window and only when a validated,
higher-priority frame could have overtaken it. Duplicate, too-old, and same-priority reordered
frames cannot refresh health or become telemetry. Heartbeat, handshake, USB, session, permission,
and device-validation failures are typed in `BoardLinkErrorCode`.

SHT30 and MS5611 each have an independent `SensorState`: awaiting, available, stale, failed, or
unavailable. If either sensor produces no first sample within
`HardwareConnectionConfig.firstSensorSampleTimeoutMillis`, only that sensor becomes
`Failed(NO_RESPONSE)`; the other remains usable, and any later valid sample recovers the failed
sensor. A reading is exposed only when firmware validity, freshness, health, field type, and
physical-range checks pass. Pressure remains raw Pa; altitude is recalculated on Android from the
app-owned QNH. A sensor sample is accepted only after the current attachment reaches `Ready`, and
its device timestamp must map into the configured freshness/future-skew window established by the
current TimeSync. The first TimeSync device receive time remains an immutable attachment floor;
periodic mappings support bounded backward conversion when HIGH traffic legitimately overtakes
earlier NORMAL telemetry. Unknown sensor IDs and additional instances are retained as `RawSensorSample`
keyed by `SensorKey`, allowing new board sensors without changing the USB layer; retention is
strictly capped by `HardwareConnectionConfig.maximumUnknownSensors`.

## Safety boundary

Arming and motor/servo PWM commands are deliberately opt-in and protocol-specific. The facade does
not expose raw byte writes. It validates configuration, current connection state, runtime board
actuator availability, complete motor frames, active channel counts, duplicate channels, batch
sizes, source freshness, and PWM ranges before queueing commands on the serial USB dispatcher. Safe
shutdown may send `StopTelemetry` followed by the tokenless `Disarm` safety override before closing
USB.

If actuator commands are not enabled in `HardwareConnectionConfig`, the library treats a board
reporting `actuatorArmed=true` as a critical protocol violation and closes the link. When actuator
commands are enabled, armed status is accepted as part of the explicit control session and remains
observable through `BoardTelemetrySnapshot.deviceStatus`.

The library dynamically registers scoped receivers for permission and USB attach/detach while
started, scans for a board that was connected before the app or dashboard, and can idempotently
reconcile current attachment and authoritative `UsbManager.hasPermission` state after host resume.
A confirmed detach first enters a bounded two-second searching grace so a reset/re-enumerated board
can replace its old Android attachment without flashing a false terminal-disconnect dialog.
A consumer never creates a `PendingIntent`, receiver, `UsbManager`, device connection, or CDC
endpoint. Each open drives CDC DTR to `0`, requires the exact seven-byte line-coding transfer, then
drives DTR to `1` while leaving RTS deasserted. Close drives DTR back to `0`,
so firmware observes a fresh logical session even without a physical unplug. Its manifest declares
USB host as optional so unsupported phones remain installable and report `USB_HOST_UNAVAILABLE`
rather than disappearing from device compatibility.
Receiver registration is transactional and reports `RECEIVER_REGISTRATION_FAILED` after rolling
back a partial registration. Each client instance uses an unpredictable permission action. Calling
`close()` on Android's main thread queues the complete `StopTelemetry`/`Disarm`/USB cleanup on the
module's serial I/O dispatcher so UI teardown is not blocked.
Heartbeat recovery begins after DeviceInfo validation. The initial DeviceStatus request and
StartTelemetry are sent only after the first valid HeartbeatAck, and `Ready` is published only
after the exact StartTelemetry acknowledgement.

The outbound API is intentionally protocol-specific rather than a raw-byte escape hatch. This
keeps TimeSync/session, CRC, freshness, heartbeat, and fail-closed actuator rules inside the module.

## Verification

```powershell
.\gradlew.bat :core:hardware_connection:testDebugUnitTest :core:hardware_connection:lintDebug :core:hardware_connection:assembleRelease --no-daemon
```

Unit tests cover CRC-32C, COBS, the shared golden frame, bounded resynchronization, session reset,
TimeSync rejection and bounded retry, advisory/fatal/unknown DeviceInfo masks,
guarded actuator encoding and public pulse-bound models, typed sensor validation, sequence regression,
inbound priority/replay/reorder/wrap behavior, strict heartbeat/command acknowledgements, independent
first-sample timeout and recovery, staleness/offline behavior, QNH recalculation, atomic receiver
rollback, mutable permission-result policy, staged handshake decisions, CDC logical-session
control-line policy, and bounded unknown-sensor extensibility. Android USB permission, physical
detach/reconnect, endpoint compatibility, and live telemetry still require an OTG-capable physical
phone and the native USB connector.
