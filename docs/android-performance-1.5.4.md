# Android 1.5.4

## Loading

- Remove the loading/shader notices, native status hooks and 250 ms UI polling.
- Remove bootstrap loading messages. A translucent bootstrap retains the launcher
  underneath while libraries and the image are checked; errors still have a return button.
- Queue Android GX and clear pipelines on the existing compiler thread while the
  command processor records the rest of the frame. Wait at the first renderer
  binding and prioritize that pipeline. This overlaps work without dropping draws
  or showing an incomplete stadium. Backends without a compiler thread retain
  synchronous compilation. Shutdown wakes pipeline waiters.
- Retain pipeline/Dawn caches, bounded frame slots, staging limits and 60/120 FPS
  choices from 1.5.3. No additional compiler threads or frame queues.

Cold driver compilation and disc reads still take time. No claim of instant
loading, zero black transitions, measured FPS/temperature gains or multi-match
device stability is made without testing a real device and game image.

## Save folder

- System directory picker with persistent read/write permission, passed explicitly
  from the launcher into the separate game process.
- Existing saves in a selected directory take precedence. An empty directory
  receives a copy from the active directory (or original internal saves). Originals
  are retained and failed copies are rolled back. Only region/card `.gci` files
  are copied; ROMs, caches and unrelated files stay outside this operation.
- Aurora's GCI backend uses SAF document descriptors directly for the selected
  folder. Native offset reads/writes close every descriptor; successful writes
  are fsynced. Saves do not depend on an exit callback or delayed background export.
- Probe read/write/seek/flush access before use. Inaccessible folders fail visibly
  at startup. A native enumeration failure cannot silently format an empty card.
  Header commit errors propagate to the existing game card completion callbacks.
- Internal saves remain the default. The launcher can switch back to its retained
  internal saves. Graphics caches keep their existing internal location.

## Validation

- Native descriptor test exercises partial updates at GCI offsets, persistence
  across descriptors, EOF, rejected offsets and denied access without truncation.
- Robolectric tests on Android 8/API 26 and Android 15/API 35 cover internal copy,
  existing-save precedence, switching folders, copy rollback/retry, scoped deletion,
  path validation, fresh-session listing, permission revocation and read-only access.
- CI also runs existing touch/frame-pacing tests, release unit tests, full ARM64
  APK compilation and the native source scan.

Physical validation remains necessary: first match after clearing graphics caches,
  subsequent match, five consecutive matches, and save/relaunch from a selected
  local folder. Compare timings and device logs against 1.5.3 on the same phone.
