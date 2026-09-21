# Android 1.5.7 — long-session investigation

Reports describe a complete stop after a long match and frame drops on a Galaxy A15 at
60 FPS **and on more powerful phones**. No device crash report or frame-time capture accompanied
these reports. The changes below address issues visible in the common Android code; they do not
establish the root cause of every reported stop or prove sustained 1080p/60 FPS.

## Changes

- Let Android schedule the main, FIFO and renderer threads. The desktop shared-cache affinity
  heuristic chose a CPU/cache domain once at startup and applied it to all these threads. On a
  heterogeneous mobile CPU this could confine them to one core/cluster and inhibit migration.
  Thread priorities remain unchanged; desktop affinity is preserved.
- The render worker sleeps on queue notification instead of waking every millisecond while idle.
  Its worker identity is thread-local, eliminating unsynchronized reads/writes of `std::thread::id`.
  Queue closure wakes the sleeping consumer, and shutdown still drains queued work.
- Publish the frame counter atomically. The recorder increments it while the renderer and compiler
  read it for resource lifetimes; the previous plain integer was a C++ data race.
- On Android, trim cold pipeline and sampler entries toward soft limits of 1,024 and 256 entries
  every 120 frames. Only entries unused for over 600 frames are eligible, oldest first. Recently used
  entries and queued draws are protected even when above the limit. Releasing a cache reference does
  not destroy resources still owned by GPU commands. Cold pipelines can be recreated through the
  existing Dawn disk cache. This can trade a later cold-cache miss for lower retained memory.
- Disable the speculative texture-conversion worker introduced in 1.5.6 on **all Android models**.
  RAM-based enablement was not evidence of a performance benefit. Texture content reuse and ordinary
  conversion/upload remain in place; this removes the extra source copies, background conversions
  and up to 20 MiB of queued/ready payload (plus in-flight conversion and bookkeeping).
- Add a background session sample every ten seconds while the activity is resumed. It records
  atomic render progress, frame number, age of the last presentation, pipeline/sampler counts,
  compiling/waited pipeline IDs, available system RAM, low-memory state, battery temperature and
  Android thermal status. API 26–28 records thermal status as unavailable. It reads no renderer,
  cache or driver mutex and submits no renderer work, so a render hang cannot block the sampler.
  The file is bounded at 32 KiB, has no per-frame disk writes or fsync, stops on activity destruction,
  and appears in the existing diagnostic report without additional gameplay UI.

Selected resolution, 60/120 FPS limits, graphics preferences, saves, controls, system-driver default
and optional custom-driver compatibility rules are preserved. The update adds no forced restart,
timeout that kills the match, loading notice or silent resolution reduction.

## Validation

New CPU tests exercise a sleeping queue's work/close wakeup, reentrant render calls across 25 worker
lifecycles and 2,500 queued frames, cold-cache trimming across 100 synthetic sessions, protection of
recent and submitted resources, and frame-counter wrap. Android tests cover bounded diagnostic
storage, recent-sample retention, API 26/35 device sampling, and inclusion in the copied report.
Existing texture, FIFO, native save, pacing, driver, touch and graphics tests remain required.

The release must also pass full ARM64 compilation, signature/update-identity and 16 KiB alignment
checks. Automated tests run without game data and are not multi-match gameplay measurements.

For a remaining device failure, copy **Ajustes → Informe de diagnóstico** after reopening the
launcher and before pressing Play again. The report distinguishes a process exit from a live renderer
stall and provides memory/thermal context. Compare first-use objects, Bowser, super strikes,
transitions, one long match and at least three consecutive matches on the system driver. Record
actual resolution and graphics settings; stable 1080p/60 FPS remains a device-validation goal.
