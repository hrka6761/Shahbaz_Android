# Flight dashboard implementation

This Android library owns the post-setup flight dashboard UI and its lifecycle-aware state holder.
It depends on `:core:hardware_connection` only through that module's public typed API; USB enumeration,
permission handling, Protocol v2 framing, session protection, and telemetry transport remain wholly
independent inside `:core:hardware_connection`.

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
labelled external USB-board data. Compass heading, angular distance to N/E/S/W, and X/Y/Z phone
orientation are explicitly labelled internal phone data. Low, unknown, or unreliable compass
accuracy is visibly degraded instead of appearing healthy. The map warns that phone GPS is only a
temporary phone-position proxy and must not be interpreted as board/drone telemetry.

Instrument cards are rendered from stable keyed `InstrumentReadout` entries so later readings can
be added without restructuring the cockpit. Postponed speed, acceleration, and obstacle features
are not shipped in this module. The bottom red Start Flight control is deliberately disabled and
contains no click or long-press implementation yet.

Focused JVM tests cover adaptive layout, connection gating/recovery decisions, every external and
phone presentation state, cardinal-angle calculations, and pressure-altitude boundaries.
