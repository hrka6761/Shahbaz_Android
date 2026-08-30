# `:core:remote_pilot`

Reserved core module for the future Shahbaz remote-pilot layer.

Implementation is intentionally postponed. This module currently contains only a package marker so
the project has a stable Gradle module and namespace for future manual-pilot input handling,
joystick/stick normalization, pilot intent validation, arming intent, and conversion of operator
commands into `:core:flight_controller` targets.

Its Gradle dependency on `:core:flight_controller` reserves the allowed control path. It has no
dependency on `:core:hardware_connection`; implementation remains postponed.

Expected future boundary:

- Own pilot input interpretation: what target the operator is requesting now.
- Apply operator input dead zones, shaping, rate limits, and command validation.
- Emit explicit flight-controller setpoints and lifecycle requests.
- Avoid owning USB, motor PWM transmission, low-level stabilization, or autonomous missions.

Verification placeholder:

```powershell
.\gradlew.bat :core:remote_pilot:assembleDebug --no-daemon
```
