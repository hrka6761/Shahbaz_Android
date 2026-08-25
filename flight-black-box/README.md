# Shahbaz Flight Black Box

`flight-black-box` is an independent diagnostics module. Shahbaz modules may depend on it, but it
does not depend on app, feature, USB, navigation, Settings, or business-logic modules.

## Architecture

The process initializes `FlightBlackBox` with an app-private `filesDir`. Initialization immediately
creates:

- `files/flight_black_box/reports/FBB_<date>_<time>_<short-session-id>.txt`
- `files/flight_black_box/metadata/<session-id>.properties`
- `files/flight_black_box/metadata/active_session.properties`

The report header is flushed and forced before initialization returns. Normal app startup can then
emit events without waiting for report creation.

Event producers call the generic `FlightBlackBox.record(...)` or `traceCall(...)` APIs. Each event
gets a gap-free session sequence (`E000001`, `E000002`, ...), a readable category, and optional
causal fields (`cause`, `parent`, `trace`, `span`, `parentSpan`). Records are formatted as
streaming plain text lines ending in `#END`, with large stack traces or payloads moved into detail
sections.

## Writer And Durability

Events flow through a bounded `LinkedBlockingQueue` to one dedicated writer thread named
`ShahbazFlightBlackBoxWriter`. The queue never silently drops events. If the queue is full, the
producer deliberately slows down while the writer drains earlier events, preserving sequence order.

Persistence classes:

- `NORMAL`: queued and appended continuously.
- `IMPORTANT`: appended with a prompt flush.
- `CRITICAL`: synchronously acknowledged after flush and `FileChannel.force(true)`.

Durability modes:

- `STANDARD`: continuous append with periodic force.
- `RELIABLE`: default; important events flush and critical events force immediately.
- `STRICT`: every event waits for durable persistence. Use only for focused diagnostics.

The recorder tracks latest produced, written, and durably persisted sequence numbers in health
counters and sidecar metadata.

## Crash And Recovery

The module installs a global uncaught-exception handler, preserves the previous handler, writes the
crash synchronously, forces it to storage, marks the session `CRASHED`, and delegates to the previous
handler.

On the next process start, a previous `ACTIVE` metadata file is inspected. If the report ends with an
incomplete line, the incomplete tail is truncated. The previous report then receives a `RECOVERY`
event and its metadata is marked `ABNORMAL_TERMINATION`.

## Redaction

Metadata values are redacted by sensitive key fragments such as `password`, `token`,
`authorization`, `secret`, `api_key`, and `credential`. Binary payloads are summarized by size and a
short SHA-256 digest. Feature code should still avoid putting private user text into descriptions.

## Public API

Use:

```kotlin
FlightBlackBox.initialize(context)
FlightBlackBox.record(FbbEventType.USER, "MainScreen.ConnectButton clicked")
FlightBlackBox.recordThrowable(description = "decodeDeviceInfo failed", error = error)
FlightBlackBox.reports(context).getReportDescriptors()
```

Settings or another UI-owning module can list descriptors, access a report file, and delete only
non-active reports through `FlightBlackBoxReports`. Report-management UI remains outside this module.

## Instrumentation

The current integration uses explicit hooks at lifecycle, UI action, state transition, USB, and
error boundaries. `@FbbTrace` and `@FbbRedact` define the stable annotation contract for a future
compiler or bytecode instrumentation pass. No AGP bytecode transformer is installed yet because the
existing project has no build-logic module and a safe transformer should be introduced as its own
reviewed build-system change.

## Example

```text
SHAHBAZ FLIGHT BLACK BOX
Format: SEN/1
Session ID: 5c76273e-3042-47c1-a530-a7f3c92150ba
Started: 2026-08-25 11:21:34.381 +03:30
Status: ACTIVE
Trace Level: DETAILED
Durability Mode: RELIABLE
--------------------------------------------------
TIMELINE
[+00:00.000] E000001 TRIGGER | Shahbaz process started | thread=main #END
[+00:00.041] E000002 CALL | ShahbazApplication.onCreate() | cause=E000001 | thread=main #END
[+00:00.126] E000003 DECISION | locationEnabled=false -> SHOW_ENABLE_LOCATION_DIALOG | cause=E000002 | thread=main #END
```
