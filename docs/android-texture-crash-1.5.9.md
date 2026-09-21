# Android 1.5.9 — texture bundle crash at the cup presentation

## Evidence and reproduction

The supplied SM-A155M report records SIGSEGV/SEGV_ACCERR in process 23332,
thread 23397, after `[stats] games=3`. Its first executable mapping starts at
`0x7acf83d000`, with file offset zero. ELF PT_LOAD confirms load bias, so:

| Register | Relative address | 1.5.8 symbol/source |
| --- | --- | --- |
| PC `0x7ad020ec2c` | `0x9d1c2c` | `glx_MakeTexture`, width read at glxTexture.cpp:302 |
| LR `0x7ad020f620` | `0x9d2620` | `glplatEndLoadTextureBundle`, inlined parser/Add call at lines 520/477/772 |

Exact unstripped artifact: run 35557836262, artifact 10621385017, libstrikers.so
Build ID `06cb9b2ac39a31bdabdb255af1e78595ec3a25c4`, verified against the delivered
1.5.8 library. The fault address is `x0 + 0x0e`, the width field, in a no-access
mapping. There is no driver frame at the faulting PC.

The asynchronous parser read a disc big-endian count, hashes, offsets and sizes
as native-endian integers, and discarded its `size` argument. The streaming path
already converted its dictionary. `ReadTrophyTexture` is the caller that finishes
an asynchronously loaded trophy bundle through this parser. This matches the
reported cup-win context; the report alone does not provide a full native stack.

A synthetic, valid two-texture bundle passed to the **actual 1.5.8 loader**
reproduces an ASan SEGV through `glx_MakeTexture → glplatTextureAdd →
glxParseTextureBundle → glplatEndLoadTextureBundle`. The same fixture passes with
the correction. Host and ARM64 compilers schedule the first header read differently;
the host reproduction faults on numLevels rather than width.

## Changes

- Decode every asynchronous bundle field from big-endian bytes without mutating
  the source. Check dictionary length, offsets, entry lengths and texture/palette
  spans before constructing a texture. Preflight the complete async bundle before
  modifying the inventory. Keep existing callback/remapping behavior.
- Bound mip counts before GCTextureSize, which does not terminate for zero
  levels; reject truncated headers and invalid dimensions/formats before copying.
  Palette replacements use the validated source span and destination capacity.
- Size the streaming loader's reusable scratch allocation to its largest validated
  entry. The old fixed 0x40800-byte buffer could overflow for larger textures.
  Validate file/dictionary spans and close/free resources on rejected input.
- Use SystemClock.uptimeMillis for Java diagnostic timestamps to match native
  CLOCK_MONOTONIC, and retain PID/clock in each performance sample after rotation.
  Previously elapsedRealtime included deep sleep but CLOCK_MONOTONIC did not;
  therefore the different timestamps in the supplied report do **not** establish
  that its logs came from different sessions. See [Android SystemClock](https://developer.android.com/reference/android/os/SystemClock).

No render-quality, FPS-limit, effects, save-location or driver setting changes.
No extra loading UI or deliberate frame/effect skipping.

## Verification and limits

The host regression links production texture loading, size calculation and AVL
inventory code, replacing only filesystem/GPU/arena boundaries. ASan/UBSan check
valid BE bundles, 100 releases/reloads, CMPR mipmaps, CI8 palettes, remapping,
unaligned buffers, malformed/truncated fields, a protected-page boundary and
512x512 RGBA8 entries exceeding the old scratch allocation. Local ASan/UBSan
passes; LeakSanitizer alone requires disabling in the ptraced workspace. CI keeps
the complete sanitizer set and the existing renderer, card, Android and ARM64 gates.

This fixes a reproduced loader defect identified by the crash addresses. It does
not establish that every previously reported stutter/freeze has the same cause,
or benchmark sustained 1080p/60 FPS. No ROM or physical-phone run is available.
Device validation should repeat the cup victory sequence and at least three
consecutive matches, with the user's current settings and system GPU driver.
