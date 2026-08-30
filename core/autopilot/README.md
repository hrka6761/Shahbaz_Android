# `:core:autopilot`

Reserved core module for the future Shahbaz autopilot.

Implementation is intentionally postponed. This module currently contains only a package marker so
the project has a stable Gradle module and namespace for future route planning, mission execution,
takeoff/landing policy, return-to-launch policy, geofence supervision, and conversion of high-level
flight goals into `:core:flight_controller` targets.

Its Gradle dependency on `:core:flight_controller` reserves the allowed control path. It has no
dependency on `:core:hardware_connection`; implementation remains postponed.

Expected future boundary:

- Own high-level autonomous decisions: what target should be flown next.
- Read flight-controller health and tracking status.
- Emit explicit flight-controller setpoints rather than sending actuator commands directly.
- Avoid owning USB, motor PWM transmission, or low-level stabilization.

Verification placeholder:

```powershell
.\gradlew.bat :core:autopilot:assembleDebug --no-daemon
```
