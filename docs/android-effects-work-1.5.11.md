# Android 1.5.11 — avoid invisible draws and empty attachment passes

## Report and scope

The user reports no further crashes so far, but FPS drops continue with large
items, super strikes and explosions. There is still no 1.5.10 performance trace
from the phone. These changes remove two demonstrated sources of redundant
renderer work; they do not establish the cause of every reported slowdown or
promise a measured FPS gain on the SM-A155M.

## Changes

- The planar-shadow reference geometry in `DrawableModel.cpp` uses
  `GX_CULL_ALL` when its debug visualization is disabled. Previously Aurora
  uploaded vertices/indices/arrays, resolved textures and prepared a pipeline
  for these polygons, then disabled every output write. The FIFO processor now
  consumes their complete payload without sending a draw to the renderer. This
  also applies to optimized indexed display lists. Subsequent register changes
  and dirty state are preserved. Lines and points remain visible because GX
  face culling does not apply to them.

- Consecutive EFB copies, including the ten possible shadow texture captures,
  create continuation passes that load/store the unchanged scene without any
  intervening draw. The encoder now omits that empty attachment pass while
  retaining its texture copies, format conversions, palette conversions and
  snapshots in order. Draws, clears, discard operations, explicit resolve loads
  and MSAA passes retain the original encoding path. This avoids redundant
  attachment work without removing a shadow or reducing internal resolution.

- The existing sparse performance report adds `culled_draws` and `empty_passes`.
  They count omitted operations since the previous sample, not FPS or GPU time.
  The 1.5.10 transient frame-gap/wait/draw/upload peaks remain available.

The texture-lifetime correction from 1.5.9 and cinematic pacing correction from
1.5.10 remain. There is no change to particle counts, visual quality settings,
frame cap, save paths, touch controls or driver selection.

## Verification

- Actual GX FIFO encode/decode regressions cover all four polygon forms, byte
  alignment after hidden payloads, optimized indexed lists, subsequent visible
  draws, lines/points and absence of hidden vertex/index/storage uploads.
- The actual frame recorder retains all ten distinct shadow copy targets and
  their source rectangles in order. The fixture requires one initial attachment
  pass instead of eleven; this is a command-work comparison, not a phone timing.
- Regression cases preserve depth/color clears, draws, MSAA, stencil operations,
  discard stores and explicit load semantics.
- Existing renderer, texture ownership, Android unit tests and the full ARM64
  build are release gates. No physical-phone or ROM gameplay benchmark is
  available in this environment.

Device validation: install as an update and repeat the same effects at the same
settings. After a drop, play for another 15 seconds, then copy the diagnostic
report before launching another session. Assess the frame-gap/wait peaks and
the new counters without treating them as GPU execution measurements.
