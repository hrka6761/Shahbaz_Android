# Flight dashboard implementation

This Android library owns the post-setup flight dashboard UI and its lifecycle-aware state holder.
It consumes the independent `:core:autopilot` and `:core:hardware_connection` contracts; USB
enumeration, permission handling, Protocol v2 framing, session protection, and telemetry transport
remain wholly independent inside `:core:hardware_connection`.

`DashboardScreen` is fail-closed: instruments are not shown until `BoardConnectionState.Ready`,
which means TimeSync, DeviceInfo validation, heartbeat recovery, and the StartTelemetry
acknowledgement have all succeeded. The screen then uses
an adaptive, exact 70% instruments / 30% route-map split. Landscape uses side-by-side panes and
portrait uses stacked panes. Map load/offline errors stay inside the map pane and do not hide
working instruments.

Takeoff-relative altitude is anchored only by a new validated MS5611 sample received after the
current USB session reaches `Ready`; telemetry already present at the Ready transition is rejected
as a baseline. Once established, that baseline remains fixed for the flight plan across an ordinary
USB reconnect. Selecting a different flight plan clears and re-arms it.

Every value exposes its source and a distinct live, loading, stale, no-response, unavailable,
invalid, or error status. SHT30 temperature/humidity and MS5611 pressure/altitudes are explicitly
labelled external USB-board data. Internal phone orientation is presented as equal compass and
attitude-indicator cards. Their status-colored borders and accessibility descriptions distinguish
live, degraded, stale, and unavailable readings. A compact row beneath the cards reports compass
heading, pitch, roll, and yaw from the same retained orientation sample. The compass card uses the
reusable Canvas presentation from `:core:designsystem`.
The matching attitude card uses the shared aircraft-style Canvas indicator: its fixed roll scale and
aircraft symbol sit above a clipped sky, ground, horizon, and pitch ladder animated from the same
display-corrected phone orientation reading.

Instrument cards are rendered from stable keyed `InstrumentReadout` entries so later readings can
be added without restructuring the cockpit. Four separate cards expose the ground, up, front-left,
and front-right rangefinder roles. Board-reported disabled/absent, initializing, live, and degraded
lifecycle states are never collapsed across roles: they render as not present, loading, the current
sample state, and degraded respectively. Automatic obstacle avoidance remains outside this UI
module.

The mission-control area is a pure intent/status surface over `AutopilotSnapshot`. In `STANDBY` it
offers **Start**; active phases offer **Abort** and a distinct **E-STOP**; terminal phases are
read-only. It also presents the first fail-closed issue and the count of remaining issues. These
callbacks submit requests to the runtime integration and never arm, disarm, or write motor values
from Compose. Start enters autopilot `PREFLIGHT` only after static mission policy passes; otherwise
the mission remains in `STANDBY` with its blocker. The core policy and flight controller retain
authority over every arming gate.

`FlightMissionRuntime` is the dashboard-owned composition layer. It borrows the one
`HardwareConnection` whose lifecycle remains owned by `DashboardViewModel`, owns its phone-sensor
source, and runs the autopilot and flight controller on one limited-parallelism dispatcher with a
monotonic clock. Each iteration samples one coherent input frame, lets the autopilot consume the
previous controller snapshot, runs one controller step, and only then adapts neutral actuator
actions at the hardware boundary. Synchronous actuator rejection latches a runtime failure and
requests emergency stop; an asynchronous board NACK, missing ACK, or acknowledgement backlog fails
the board link, which the next controller iteration treats as unsafe. Closing the runtime applies a
disarm or emergency action appropriate to controller state, but never closes the borrowed board
connection.

A control-loop exception is terminal for that runtime instance: it clears the running/prepared
state, immediately requests the hardware emergency-stop path, latches the failure for presentation,
and cannot be silently restarted.

`FlightMissionRuntime.prepare()` starts acquisition and the serialized control loop without
starting a mission. `startMission()`, `abortMission()`, and `emergencyStop()` enqueue operator
intent with emergency precedence, while its `StateFlow` feeds the resulting mission snapshot back
into `DashboardUiState`. This preserves the autopilot's reusable, UI-free API and keeps both core
engines independent of Compose and Protocol v2.

The present interface-board firmware is sensor-only and advertises no usable motor channels.
Production landing detection plus route/airspace, landing-zone, energy, geofence, and wind-safety
providers are also absent. `FlightMissionInputs` defaults to explicitly unavailable observations;
it never invents safety approval or infers ground contact from pressure. Those facts are exposed as
preflight blockers, so the current dashboard cannot advance to physical arming even though its
Start intent is wired. No physical-flight claim is made.

The production/default build also passes `allowActuatorCommands=false` to the hardware connection.
Developers can compile an explicitly experimental artifact with
`-Pshahbaz.experimentalPhysicalActuators=true`, but that switch only opens the Android-side command
gate. It does not enable firmware PWM, satisfy board evidence gates, supply missing safety/landing
providers, validate wiring, or authorize propellers/flight. The property accepts only the exact
strings `true` and `false` so misspellings fail the build instead of weakening the default.

Focused JVM tests cover adaptive layout, connection gating/recovery decisions, mission-control
presentation and shutdown policy, runtime request precedence, actuator adaptation/escalation,
timestamp-preserving sensor conversion, every external and phone presentation state,
cardinal-angle calculations, and pressure-altitude boundaries.
