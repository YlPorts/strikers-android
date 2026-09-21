# Android 1.5.12: OpenGL ES and draw binding reuse

The latest A15 capture shows 18 FPS / 55.3 ms during a goal effect and a
41.0 °C battery reading. It identifies a slow scene, but does not distinguish
GPU fill cost, CPU encoding, or thermal throttling. It is not a GPU-temperature
measurement. No 1.5.12 A15 benchmark or full-match hardware test is available.

## Changes

- **Gráficos → Motor gráfico** now exposes Vulkan (default) and OpenGL ES
  (experimental). The launch intent carries the selection into the game process
  and `STRIKERS_BACKEND` selects the existing native Dawn backend. This is native
  OpenGL ES through Android's system EGL, not an OpenGL-to-Vulkan translation.
- GLES never installs/activates a custom Vulkan driver. That driver's selection
  is retained for a later Vulkan launch. Phones without custom-driver support
  use their system drivers as before.
- Failed backend initialization releases the adapter, device, queue, surface,
  and instance before trying another backend. Pending request callbacks own
  their results; a cancelled callback cannot refer to an expired stack frame
  or write into another attempt. Android excludes the non-rendering Null
  backend from fallback. An unsupported GLES initialization can fall back to
  Vulkan; a failure after a game has started is not a live backend switch.
- Consecutive GX draws reuse unchanged uniform, texture, index and blend
  constant bindings. The cache resets at every render pass, when switching
  built-in encoders, and after an external draw callback. Immediate vertex data
  still updates for every draw. Non-indexed draws no longer bind an unused
  index buffer. No effects, draws, texture resolution or selected resolution
  are removed by this optimization.
- Session diagnostics include `backend` (the backend actually initialized)
  and `bindings_saved` (redundant binding calls avoided since the last sample),
  alongside the existing interval peaks. A high counter is evidence of fewer
  API calls, not a measured FPS gain.

## Verification

- Binding-cache regressions cover identical draws, changed offsets/ranges,
  textures and alpha, unused bindings and explicit invalidation.
- `opengles_render_test` uses the packaged Dawn GLES backend with Mesa's system
  EGL and validation enabled. It runs real GX shaders, FIFO decoding, renderer
  and compiler threads, staging uploads and transparent draws; reads back
  pixels over three frames; and inserts an external encoder that overwrites
  binding state. It also checks failed-initialization cleanup followed by a
  fresh device. This headless Linux test does not test an Android window or
  the Samsung driver and is not a performance benchmark.
- Android launch tests cover the cross-process backend snapshot, defaults,
  invalid selections and custom-driver eligibility.
- CI builds the release APK and ARM64 game sources and runs the existing
  renderer, input, save, native crash/texture and frame-pacing regression gates.

GLES needs OpenGL ES 3.1 plus the EGL extensions and limits required by the
pinned Dawn build; advertising ES 3.1 alone does not guarantee compatibility.
Keep Vulkan as the default until GLES is compared on the actual device with
the same resolution, MSAA, scene and similar device temperature. After a slow
scene, let the session logger sample and export the current version's report
so the active backend and interval peaks can be checked together.
