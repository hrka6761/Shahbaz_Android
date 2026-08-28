# Flight Black Box Integration Map

This document records the current Shahbaz Android Flight Black Box integration after the
module was moved under `core:flight_black_box`.

## Ownership

- `core:flight_black_box` owns recorder startup, event ordering, durable report writing, crash
  capture, previous-session recovery, safe report file access, report cleanup primitives, and
  persisted recorder configuration. Trace level is forced to `DEEP` and durability is forced to
  `STRICT` for every recorder session.
- `feature:settings:impl` owns the lightweight Settings entry surface and report summary.
- `feature:reports:impl` owns the report list, long-press multi-select, Select all, and selected
  delete actions.
- `feature:report_details:impl` owns report details, chunked report viewer, search, share/export
  via `FileProvider`, and individual delete.
- `app` owns process initialization and app-shell navigation into Settings and Reports.

## Module Coverage

| Area | Files | Coverage |
| --- | --- | --- |
| Process startup | `ShahbazApplication.kt` | Initializes Flight Black Box with fixed deep/strict diagnostics; records process/app lifecycle and foreground/background transitions. |
| App shell | `MainActivity.kt` | Records activity lifecycle, permission requests/results, Map/Dashboard navigation, Settings/Reports open/close, app-settings intents, and location-settings intents. |
| Map feature | `MapViewModel.kt` | Records compass startup/failure, location permission decisions, location-service decisions, destination changes, flight setup decisions, location updates, stale data, and location failures without logging exact coordinates. |
| Dashboard feature | `DashboardViewModel.kt` | Records host lifecycle decisions, flight-plan changes, board connection states, USB permission retries/results, telemetry start/stop, and dashboard clear/retry paths. |
| Hardware connection | `HardwareConnection.kt` | Records USB attach/detach broadcasts, permission flow, discovery/open outcomes, validation, protocol frame acceptance/rejection, command TX/RX, sensor errors, handshake progress, and transport failures without logging raw packet bytes. |
| Settings feature | `SettingsScreen.kt`, `SettingsViewModel.kt` | Shows Flight Black Box report count, active count, storage size, warning/error totals, and opens Reports. |
| Reports feature | `ReportsScreen.kt`, `ReportsViewModel.kt` | Lists reports in elevated cards, enters checkbox selection on long press, supports Select all, and deletes selected non-active reports. |
| Report details feature | `ReportDetailsScreen.kt`, `ReportDetailsViewModel.kt` | Shows report details, loads large reports in chunks, searches reports, shares/exports via `FileProvider`, and deletes one non-active report. |
| Crash/recovery | `FbbEngine.kt`, `FbbStorage.kt` | Synchronously records uncaught crashes, marks crash metadata, detects previous active sessions on next startup, repairs incomplete tails, and appends recovery records. |

## Report Management API

Settings, Reports, and Report Details use only public core APIs:

- `FlightBlackBox.reports(context).getAllReportDetails()`
- `getReportDetails(sessionId)`
- `readReportChunk(sessionId, offsetBytes, maxBytes)`
- `searchReport(sessionId, query, maxMatches)`
- `deleteReport(sessionId)` and `deleteReports(sessionIds)`
- `storageStats()`

Active reports are protected from deletion. Bulk cleanup APIs remain in core for tooling, but they
are not exposed by the current Reports UI.

## Verification

Automated checks run in this phase:

- `.\gradlew.bat :core:flight_black_box:testDebugUnitTest`
- `.\gradlew.bat :core:flight_black_box:testDebugUnitTest :app:testDebugUnitTest`
- `.\gradlew.bat :feature:settings:impl:testDebugUnitTest :feature:reports:impl:testDebugUnitTest :feature:report_details:impl:testDebugUnitTest :app:testDebugUnitTest`

The final Settings/app verification was run with `GRADLE_USER_HOME`, `TEMP`, and `TMP` pointed
inside `D:\Shahbaz_Android\.gradle\` because `C:` had about 160 MB free and Gradle failed while
writing `C:\Users\HAMIDREZA\.gradle\.tmp`.

Existing Flight Black Box unit coverage includes immediate report creation, gap-free event
sequencing, sensitive metadata redaction, concurrent producers, critical durability, previous
active-session recovery, active-report delete protection, and Settings-shaped report management
operations.

## Limitations

- `@FbbTrace` is currently a marker annotation plus explicit `FlightBlackBox.record`,
  `traceCall`, and `recordThrowable` hooks. No compiler plugin, bytecode transform, or KSP/Kotlin
  symbol processor has been added, so annotation-only automatic function tracing is not claimed.
- Device-only scenarios such as real USB permission dialogs, actual board telemetry, Android
  process kill, and end-to-end crash UI review were not executed in this environment. The code
  paths are instrumented and the crash/recovery storage behavior is covered by JVM tests.
- Settings, Reports, and Report Details have build verification through their Gradle test tasks,
  but no dedicated Compose UI test source exists yet.
