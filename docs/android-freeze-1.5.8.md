# Android 1.5.8 — preserve the evidence behind a 0 FPS stop

## Device evidence

The supplied 1.5.7 report is from Samsung SM-A155M, Android 16/API 36. It advances
at approximately 60 frames/second for several minutes, then reaches frame 25126,
phase 3, renderer progress 0 and presentation age 5127 ms. Pipelines/samplers remain
341/19, compiling/waiting are zero, available memory is 590 MiB, low_memory=false,
thermal_status=0 and battery temperature is 35.9 C in the last sample.

This is evidence of stopped presentation, not proof of its cause. Phase 3 still
covers simulation, task transitions and recording. Stable cache counts below the
1.5.7 trim thresholds do not implicate cache eviction in this run. Battery temperature
is not CPU/GPU temperature; this report cannot establish every thermal condition.

USER_REQUESTED with `[REMOVE TASK]` records the eventual removal from Recents. It
does not explain the earlier freeze. Android documents that distinction in
[ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo#REASON_USER_REQUESTED).

The supplied last-run tail contains an incomplete native maps dump. In the app,
that dump is produced by the native signal handler, but the old report retained only
the last 48 KiB and discarded the preceding signal, PC/LR and error context. Without
that header or a native stack, naming the crashing function would be speculation.

## Changes and limits

- Recover the newest native fault and its preceding context from existing logs,
  including 1.5.7 dumps. Stream/filter away unrelated maps, retaining mappings for
  PC, LR, fault address and the app's native libraries. Preserve the fault even if
  later memory samples continue. Bound lines/output and scan the last 16 MiB with
  an explicit notice if the scan limit is reached. Read on a background executor.
- Record the report build separately from the session build, so an upgraded app
  does not silently attribute an old incident to the new executable. Legacy logs
  without a session version still require the user's original version information.
- Native capture now restores and invokes the previous signal handler with the
  original siginfo/ucontext. SA_RESETHAND resets to the default disposition, not
  the previous Android debuggerd handler; the old comment claiming otherwise was
  incorrect. Android installs its own handler as shown in
  [AOSP debuggerd](https://android.googlesource.com/platform/system/core/+/refs/heads/main/debuggerd/handler/debuggerd_handler.cpp).
  Retain PID/TID, monotonic time, registers and only relevant mappings. Bound the
  map scan/output and remove fsync from the faulting thread. Reuse an existing
  alternate signal stack. This repairs diagnostic behavior; it does not make an
  invalid memory access recoverable or establish that the handler caused the freeze.
- Fix a concrete FIFO data race: the consumer's bounds assertion read the producer's
  changing, non-atomic buffer size. Validate the release-published prefix against
  allocation capacity, updating capacity under the same mutex as reallocation.
  No scheduling timeout, command skipping or callback reordering is introduced.
  The missing fault header prevents attributing this user's incident to that race.
- Add scalar atomic game stage/task/state markers, FIFO published/processed/drain
  counters and worker stage. Split end-frame progress into FIFO drain, cleanup,
  finish, overlay and enqueue. Existing ten-second background samples can now
  distinguish a stalled task from a blocked FIFO/renderer without acquiring their
  locks. No per-frame file writes or additional gameplay UI are added.

Resolution, frame limit, shader/effect behavior, saves and system/custom driver
selection remain unchanged. There is no measured 1080p/60 FPS or freeze-fix claim.

## Validation and next device step

Native subprocess tests check chaining the original signal context, repeat install,
returning handlers, default raise/abort/real protection-fault termination and bounded
output. A concurrent task test samples a deliberately blocked transition. FIFO tests
query progress during a blocked callback and grow the command buffer while it is
being consumed. JVM/Android tests cover recovery from a large incomplete legacy map,
later memory samples, repeated faults, unsigned addresses, malformed/oversized lines,
scan limits and asynchronous copying on API 26/35. Existing CI gates remain required.

No ROM gameplay or physical-phone run is available in this environment. After
installing over 1.5.7, open **Ajustes → Informe de diagnóstico before pressing Jugar**.
If the old log is still present, this exports the previously omitted header without
requiring another match. Keep 1.5.7 symbols to resolve those addresses. If the old
record was overwritten, the new task/FIFO markers and native header support the next
incident. The root cause is still unconfirmed until that evidence can be resolved.
