# Android 1.5.3: sustained play and first-load feedback

Candidate following 1.5.2. Reports: heat, uneven frames, exits after three or
more matches, and a black pause before the first match on slower devices.
Faster devices can already play well at 120 FPS, so that option remains.

## Changes and evidence

- The previous limiter followed display Hz plus 5% with vsync, or 120 Hz when
  unknown. The launcher now offers 60 FPS (default for sustained play) and
  120 FPS. The selected limit travels through both activity intents, is saved,
  and is exported before native initialization. The surface requests the same
  refresh rate; Android may choose a different supported rate. Resolution
  preferences are preserved. No fixed timestep or physics changes.
- On Android a missed frame deadline now rebases rather than emitting several
  unslept catch-up frames. A slow frame is not charged an extra full wait.
- Android staging buffers change from five to three: 275 to 165 MiB of buffer
  capacity, a reduction of 110 MiB. The two frame packets and the size of each
  frame's buffers are unchanged. Existing slot ownership and MapAsync completion
  prevent reuse until the GPU finishes. This is reserved buffer capacity,
  not a measured reduction in process RSS. Actual throughput needs device testing.
- Memory snapshots run every five seconds instead of every 250 ms. Crash signal
  recording remains immediate. Routine NIS diagnostics are no longer enabled.
- Match load/cleanup markers record the allocator's high-water reservation so
  repeated matches can be correlated with resident-memory samples. No speculative
  allocator resets or forced cache deletion between matches were added.
- The existing Android first-use graphics pipeline path blocks intentionally to
  avoid drawing an incomplete stadium. That can contribute to cold-load pauses.
  Loading and graphics wait signals now drive a non-interactive Android text
  indicator, independent of the blocked game renderer. Fast loads do not flash
  it; polling stops on pause/detach/destruction. Long graphics waits (100 ms or
  more) are logged for diagnosis. Existing persistent pipeline/Dawn caches remain.
  The indicator does not make compilation faster and is not a complete diagnosis
  of every black-screen report.
- A launcher button can copy the last session report even without a recorded
  system crash. It includes app version, device, Android version and the native
  log tail. Android 8/9/10 can use the native log; Android 11+ also uses exit info.

## Verification

- Deterministic host test links the actual Android VI implementation: 60/120 FPS
  choices against unknown, 59.94, 60, 90, 120 and 144 Hz displays, normal pacing,
  a 50 ms hitch, a 30 s suspension and retrace callbacks. Undefined-behavior
  sanitizer enabled. Both targets passed locally.
- CI also runs the touch-state native test, Android 26/35 Robolectric tests,
  the full release APK build, and the ARM64 source scan.
- New UI tests exercise fast/slow loads, graphics wait transitions, pause,
  resume, detach and copying a report with/without a log or system exit record.

## Device verification still needed

Test at least five consecutive matches on the affected phone, including returning
to the menu, changing stadium/captains and replaying cinematics. Compare first
load and a repeat load; check 60 and 120 FPS independently. Use 448p/60 FPS to
separate GPU load from other issues. Copy the diagnostic report before starting
another run, since starting a new run resets the log. No real-device thermal,
frame-time or long-session measurements are claimed here. The reported exit
after three matches is not yet proven fixed.

The APK should use the established 1.5/1.5.2 release certificate. The older
1.5.1 APK used a different debug certificate and cannot be updated in place
with that release key; preserve saved data.

Refresh-hint behavior follows the Android [frame-rate documentation](https://developer.android.com/media/optimize/performance/frame-rate).
