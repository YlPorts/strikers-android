# Android 1.5.6

This update preserves the YlPorts launcher, package/signing identity, editable touch controls,
SAF memory-card folder bridge, 60 FPS default, and the 1.5.5 pipeline/shader-cache fixes.
It targets reported first-use effects/object/transition stalls and adds optional graphics controls.

## System and custom drivers

- System Vulkan remains the default, including on Mali and devices without KGSL/custom-driver support.
  The APK does **not** contain a globally named `libvulkan.so`; the game has no dependency on
  libadrenotools or the optional loader. Android 8/API 26 remains supported.
- On Android 9+ with KGSL, the user may import a compatible ARM64 driver ZIP and explicitly select it.
  ZIP import validates metadata, paths, ELF architecture, minimum API, duplicate entries and bounded
  extracted size. Files are private and read-only; failed imports preserve existing drivers and saves.
- Only a selected supported driver adds a private runtime search path to Dawn. This path aliases our
  packaged `libstrikers_vulkan.so`; it does not change a phone's system libraries.
- The custom loader probes Vulkan 1.1 and physical-device enumeration, then falls back to system
  Vulkan on an ordinary load/probe failure. A persisted startup marker selects the system route on
  the next launch after a fatal custom-driver startup; selecting the driver again explicitly retries it.
  Arbitrary native driver crashes cannot be recovered inside the same process.
- Driver cache databases are separate. Selecting/deleting drivers never modifies memory-card data.
- No driver binaries are bundled, no root/all-files permission is required, and no GPU turbo mode is used.

## Rendering changes

- Adapted Aurora's texture pre-conversion worker: source data is copied on its owning thread; CPU
  conversion occurs at low priority; GPU resources/uploads remain on the normal rendering path.
  Results must match the exact content hash, size, format and mip count. A miss follows the existing
  inline conversion path, without skipping effects or blocking on the background worker.
- Mobile limits: 4 MiB queued source, 16 MiB ready results, at most 8 MiB expanded RGBA per job,
  30-second expiry, bounded deduplication sets, and join/cleanup at shutdown. Disabled on Android
  low-RAM devices and devices reporting less than 3 GiB total memory. Three Android staging buffers
  are retained. These limits avoid importing the donor's much larger memory defaults.
- Frame-slot waits use notification instead of repeated 100-microsecond polling. Staging waits are
  bounded and keep pumping pending GPU events, preserving callback progress.
- Identical-size presentation skips a redundant resampling pass.
- Primitive index generation uses a 32-bit count and fills contiguous memory. Large strips/fans no
  longer truncate the index count; primitives below three vertices no longer underflow allocation.
- Optional MSAA, anisotropic filtering, aspect and vsync controls live inside Settings. Existing
  defaults remain. An optional, one-second FPS display reads actual presentation timestamps and
  battery temperature; it does no sampling when disabled or paused. Its milliseconds are average
  presentation interval, not a separate measurement of GPU execution time.

## Provenance

Compared against [Luis Santos's Android port](https://github.com/luisxl15/Strikers-Android-Port)
at `0d56b2e363c5268e549ea13d8464cd542d9d0324`. The reviewed Aurora texture, wait, resampling and
index-generation improvements were adapted from its MIT-licensed Aurora tree (copyright Luke Street;
license preserved and bundled). Android UI, archive management and optional Vulkan integration were
implemented for this project; the donor's Android frontend was not copied.

[libadrenotools](https://github.com/bylaws/libadrenotools) is pinned to
`8fae8ce254dfc1344527e05301e43f37dea2df80`, with linkernsbypass pinned through its submodule to
`aa3975893d83ef1bc84c321ec60c65fbf1287887`. Both BSD-2-Clause notices are bundled in APK assets.
Custom-driver availability also depends on the GPU and driver package; see the
[Mesa Freedreno/Turnip documentation](https://docs.mesa3d.org/drivers/freedreno.html).

## Validation and limits

Automated coverage includes Android 26/35 archive rejection, safe system defaults, persisted settings,
touch/save regressions, native card I/O, renderer waits, pre-conversion ownership/content mismatch,
memory bounds/cleanup, and primitive counts. Native ARM64 release compilation and APK signature,
package, dependency and 16 KiB alignment checks are required before delivery.

No physical-device frame-time trace or multi-match gameplay run was available during development.
This update does not establish stable 1080p/120 FPS on every phone. Device validation should include
the first object throws, Bowser, super strikes, transitions, three-plus matches, and a relaunch after
changing drivers, testing the system path first. Crash reports retain the chosen driver and preparation
settings for diagnosing any remaining stalls.
