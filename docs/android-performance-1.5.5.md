# Android 1.5.5

The 1.5.4 document bridge opened GCI files correctly, but CARDProbe/CARDProbeEx
still checked its virtual path with std::filesystem. The game checks the card
before opening a save, so it reported no card despite a valid selected folder.

## Changes

- Probe document-backed cards through the selected provider. Check the current
  document metadata even when its URI is cached; deleted/revoked cards fail.
- Treat failed I/O on a known save as IOERROR, not NOFILE. A permission problem
  must not be presented to the game as a reason to create a replacement save.
- Share GX shader modules between blend/depth variants, using a 128-entry LRU
  with full configuration equality checks and cleanup after the compiler stops.
- Collapse destination-alpha-only pipeline variants. Alpha values remain in
  each draw's blend constant, so rendering semantics are preserved. Normalize
  cached configurations too, preserving the existing on-disk cache format.
- Service GPU events between condition-variable waits for first-use pipelines.
  Release the pipeline mutex during progress, keep waiting for the complete
  draw, and log waits over 250 ms without an on-screen loading notice.
- Keep resolution/FPS and ROM/Play on the launcher; move language, controller
  options, save-folder selection and diagnostics into expandable Settings.
  Remove explanatory paragraphs and verbose selector labels. Preserve 1080p
  and 120 FPS selections and pass them explicitly to the game process.

## Validation

- A host fixture compiles the actual Android CARD/GCI/FileIO sources with real
  SDL I/O and a fake document-provider boundary. For USA/EUR/JAP and internal/
  selected storage it probes, mounts, creates a five-block save, writes, sets
  status, closes, reopens and reads, including a freshly loaded card instance.
  Selected storage also exercises permission failure, recovery and card removal.
- The production pipeline wait helper is tested with a compiler completion
  requiring callback progress and with shutdown waking a waiter.
- Android 8/API 26 and Android 15/API 35 tests cover current provider metadata
  after deletion/revocation, the Settings folder picker, and launch with saved
  1080p/120 FPS/language/folder selections, alongside existing regressions.
- The Android workflow builds the release ARM64 APK and scans the native game.

These tests do not measure gameplay FPS, temperature or driver stability.
Physical validation still needs the reported attack cinematic, first match
with cold caches, five consecutive matches at 1080p, and save/relaunch on the
affected phone. No constant-FPS or instant-first-load claim is made.
