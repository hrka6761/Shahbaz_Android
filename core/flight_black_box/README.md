# Shahbaz Flight Black Box

`:core:flight_black_box` is an independent diagnostics module. Shahbaz modules may depend on it, but it
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
- `RELIABLE`: important events flush and critical events force immediately.
- `STRICT`: default and required; every event waits for durable persistence.

Trace level is fixed to `DEEP` for Shahbaz builds so generated reports carry the richest available
diagnostic timeline.

The recorder tracks latest produced, written, and durably persisted sequence numbers in health
counters and sidecar metadata.

## Crash And Recovery

The module installs a global uncaught-exception handler, preserves the previous handler, writes the
crash synchronously, forces it to storage, marks the session `CRASHED`, and delegates to the previous
handler.

On the next process start, a previous `ACTIVE` metadata file is inspected. If the report ends with an
incomplete line, the incomplete tail is truncated. The previous report then receives a `RECOVERY`
event, a `REPORT END` footer, and metadata marked `ABNORMAL_TERMINATION`.

## Redaction

Metadata values are redacted by sensitive key fragments such as `password`, `token`,
`authorization`, `secret`, `api_key`, and `credential`. Binary payloads are summarized by size and a
short SHA-256 digest. Feature code should still avoid putting private user text into descriptions.

## Reading Reports With AI

Flight Black Box reports are UTF-8, append-only plain text files intended to be readable by both
people and diagnostic agents. An AI should read a report as a structured execution timeline, not as
free prose.

Parsing rules:

- Read the header first. Lines before `TIMELINE` are session facts such as format version, session
  id, start wall-clock time, app/device info, trace level, durability mode, queue capacity, storage
  path, and current report status.
- Treat each line ending with `#END` as a complete persisted record. If the active report ends with
  a partial line that has no `#END`, ignore that tail; recovery will truncate it on the next startup.
- Timeline events have the shape
  `[+HH:MM:SS.mmm] E000001 TYPE | description | key=value | key=value #END`.
- Closed or recovered reports end with `REPORT END | status=... | ended=... | latestEvent=... #END`.
  Prefer this tail status over the header's initial `Status: ACTIVE` line when both are present.
- Use the relative timestamp to understand order and timing within the session. Use the `E000001`
  event id as the stable anchor for references.
- Interpret `TYPE` as the event category. Important categories include `TRIGGER`, `CALL`, `RETURN`,
  `STATE`, `DECISION`, `USER`, `UI`, `NAV`, `USB_TX`, `USB_RX`, `WARNING`, `ERROR`, `EXCEPTION`,
  `CRASH`, and `RECOVERY`.
- Split metadata on ` | ` after the description. Metadata keys are sorted when written. Causal
  fields are especially important: `cause=E000123` points to the event that directly triggered the
  current event, `parent=E000123` identifies a broader owner event, and `trace`, `span`, and
  `parentSpan` link async work.
- If a timeline event contains `detail=D...`, find the matching detail block below it:
  `[DETAIL D...] ... [/DETAIL D...]`. Detail blocks usually contain stack traces or long payloads.
  Attach the block to the referencing event instead of treating it as a separate timeline action.
- Values may be redacted or truncated. `<REDACTED>` means the value was intentionally hidden; binary
  payload summaries and hashes prove size/identity without exposing raw bytes. Do not infer hidden
  secrets from surrounding context.
- Session status matters. `ACTIVE` can still be growing, `COMPLETED` ended cleanly, `CRASHED`
  contains a synchronous crash record, and `ABNORMAL_TERMINATION` means the previous process stopped
  without writing a clean completion or crash marker.

Recommended AI reading workflow:

1. Summarize the header: session id, status, app/build, device, start time, trace level, and
   durability mode.
2. Build a chronological event table from timeline records: event id, relative time, type,
   description, metadata, and attached detail id/text.
3. Follow `cause`, `parent`, `trace`, and `span` links to group related work such as one user action,
   one navigation flow, one USB exchange, or one async operation.
4. Prioritize `CRASH`, `EXCEPTION`, `ERROR`, `WARNING`, and `RECOVERY` events, then walk backward
   through their causal links to find the first suspicious decision, state change, timeout, or
   transport event.
5. Report findings with event ids and timestamps, and distinguish observed facts from hypotheses.
   Example: "Observed: `E000042 ERROR` followed `E000039 USB_RX`. Hypothesis: frame decoding failed
   after the USB response."

## Public API

Use:

```kotlin
FlightBlackBox.initialize(context)
FlightBlackBox.record(FbbEventType.USER, "MainScreen.ConnectButton clicked")
FlightBlackBox.recordThrowable(description = "decodeDeviceInfo failed", error = error)
FlightBlackBox.reports(context).getReportDescriptors()
```

Reports or another UI-owning module can list descriptors, access a report file, and delete only
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
Trace Level: DEEP
Durability Mode: STRICT
--------------------------------------------------
TIMELINE
[+00:00.000] E000001 TRIGGER | Shahbaz process started | thread=main #END
[+00:00.041] E000002 CALL | ShahbazApplication.onCreate() | cause=E000001 | thread=main #END
[+00:00.126] E000003 DECISION | locationEnabled=false -> SHOW_ENABLE_LOCATION_DIALOG | cause=E000002 | thread=main #END
```
