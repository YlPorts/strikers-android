# Android 1.5.10 — cinematic pacing and discarded EFB copies

## Report and evidence

SM-A155M at a selected 60 FPS: super strikes and explosions sometimes fall to
about 30 FPS. There is no new frame-time trace specific to those effects yet.
The changes address two concrete code paths; they do not establish that every
reported slowdown has the same cause.

The follow-up crash report still identifies 1.5.8, process 7723, after three games.
Its library base `0x7ad043f000`, PC `0x7ad0e10c2c` and LR `0x7ad0e11620` give the
same relative addresses `0x9d1c2c` / `0x9d2620` as the previous 1.5.8 crash.
This release retains the validated big-endian/bounds texture-loader fix from
[1.5.9](android-texture-crash-1.5.9.md). The report does not show that correction
running; historical exit records may also outlive an app update.

## Changes

1. `glplatSendFrame -> glxSwapPre -> hitz_Pre` is live on Android. When
   `SetupRenderInfo` selects the cinematic `glxswap/vwait=2` (locked presentation
   state 0x100), the old implementation calls `VIWaitForRetrace` twice. Each call
   waits a full selected frame period in the host VI shim: 60 becomes 30 and 120
   becomes 60. Android now waits once for this path. Other platforms retain the
   original behavior. Task animation deltas still use elapsed time; there is no
   change to time dilation, super-shot rules, playback speed or the user's cap.
   This applies to presentations that request that lock, not necessarily every
   super strike or explosion.

2. `glx_ClearZBuffer` and the final clear in `glx_ShadowTextureGrab` copied the
   full scene into the private `clearz_mem` I8 destination, which is never sampled.
   At 1080p this is also scaled with internal resolution. The Android/Aurora path
   now sends an ordered `AuroraGXClearEFB` FIFO command using the existing clear
   masks/values and pass continuation. With no resolve target the encoder skips
   texture allocation, screen copy/conversion and conversion uniforms. Actual
   shadow textures, depth-of-field captures and effects retain their copy paths.
   Color-only/alpha-only clears still use the existing masked clear logic;
   depth-only clears preserve color. The scene stays at the selected resolution.

3. Sparse diagnostics now retain per-interval peaks for presented-frame gaps,
   CPU frame duration, frame-slot/staging waits, draw counts and texture uploads.
   A short effect hitch is therefore retained even if performance recovers before
   the next ten-second sample. The counters use relaxed atomics, bounded values
   and no extra frame-thread disk I/O. `cpu_frame_max_us` includes game, limiter
   and FIFO work; it is **not** GPU execution time. Peaks need not be from the
   same frame and cannot alone prove a particular bottleneck. Long suspensions
   can appear in presentation gaps.

## Verification

- The actual Hitz swap and VI limiter run against a deterministic clock: regular
  frames, entry/exit of the locked cinematic, 60/120 caps, callbacks and a 50 ms
  effect hitch. The old swap fails the one-retrace assertion; the fix passes.
- FIFO round trips test all eight color/alpha/depth write masks, clear values,
  no-op clears and absence of copy-cache allocations for clear-only commands.
- The real frame recorder checks retained attachments and depth/color clears,
  with no copy target, uploads or conversion uniforms.
- Interval peak retention/reset, overflow saturation and concurrent writers.
- Existing texture bundle ASan/UBSan, renderer/FIFO, memory card, touch, lifecycle,
  save-folder and Android ARM64 build gates remain required.

There is no ROM/physical-phone benchmark in this environment. Do not describe
this release as guaranteeing sustained 1080p/60 on all devices. Device validation:
install as an update, confirm report version 1.5.10, repeat super strikes and
bomb explosions, then finish a cup/at least three matches. If a hitch remains,
export the report after that session so its interval peaks can narrow the cause.
