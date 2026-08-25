# Flight Black Box Integration Map

This document records the current Shahbaz Android Flight Black Box integration after the
module was moved under `core:flight-black-box`.

## Ownership

- `core:flight-black-box` owns recorder startup, event ordering, durable report writing, crash
  capture, previous-session recovery, safe report file access, report cleanup primitives, and
  persisted recorder configuration.
- `feature:settings:impl` owns every user-facing report-management surface: report list,
  details, chunked report viewer, search, share, export, delete, cleanup, and configuration
  controls.
- `app` owns process initialization and app-shell navigation into Settings.

## Module Coverage

| Area | Files | Coverage |
| --- | --- | --- |
| Process startup | `ShahbazApplication.kt` | Initializes Flight Black Box with persisted config; records process/app lifecycle and foreground/background transitions. |
| App shell | `MainActivity.kt` | Records activity lifecycle, permission requests/results, Map/Dashboard navigation, Settings open/close, app-settings intents, and location-settings intents. |
| Map feature | `MapViewModel.kt` | Records compass startup/failure, location permission decisions, location-service decisions, destination changes, flight setup decisions, location updates, stale data, and location failures without logging exact coordinates. |
| Dashboard feature | `DashboardViewModel.kt` | Records host lifecycle decisions, flight-plan changes, board connection states, USB permission retries/results, telemetry start/stop, and dashboard clear/retry paths. |
| Hardware connection | `HardwareConnection.kt` | Records USB attach/detach broadcasts, permission flow, discovery/open outcomes, validation, protocol frame acceptance/rejection, command TX/RX, sensor errors, handshake progress, and transport failures without logging raw packet bytes. |
| Settings feature | `SettingsScreen.kt`, `SettingsViewModel.kt` | Lists reports, opens details, loads large reports in chunks, searches reports, shares/exports via `FileProvider`, deletes selected/all deletable reports, performs cleanup, and persists trace/durability configuration for the next process start. |
| Crash/recovery | `FbbEngine.kt`, `FbbStorage.kt` | Synchronously records uncaught crashes, marks crash metadata, detects previous active sessions on next startup, repairs incomplete tails, and appends recovery records. |

## Report Management API

Settings uses only public core APIs:

- `FlightBlackBox.reports(context).getAllReportDetails()`
- `getReportDetails(sessionId)`
- `readReportChunk(sessionId, offsetBytes, maxBytes)`
- `searchReport(sessionId, query, maxMatches)`
- `deleteReport(sessionId)` and `deleteReports(sessionIds)`
- `deleteAllReports()`
- `deleteReportsOlderThan(olderThanMillis)`
- `cleanupToMaxStorageBytes(maxBytes)`
- `storageStats()`
- `FlightBlackBox.configuration(context).read/save/update/reset()`

Active reports are protected from deletion. Cleanup preserves crash/error/abnormal reports unless
the caller explicitly opts into deleting them.

## Verification

Automated checks run in this phase:

- `.\gradlew.bat :core:flight-black-box:testDebugUnitTest`
- `.\gradlew.bat :core:flight-black-box:testDebugUnitTest :app:testDebugUnitTest`
- `.\gradlew.bat :feature:settings:impl:testDebugUnitTest :app:testDebugUnitTest`

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
- Settings has build verification through `:feature:settings:impl:testDebugUnitTest`, but no
  dedicated Compose UI test source exists yet.
