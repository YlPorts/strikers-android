// Headless integration gate: the packaged Dawn GLES backend, real GX shaders,
// FIFO, renderer thread, uploads and transparent draws. This is not a benchmark.
#include "gfx/frame.hpp"
#include "gfx/recording.hpp"
#include "gfx/runtime_metrics.hpp"
#include "gx/fifo.hpp"
#include "gx/gx.hpp"
#include "internal.hpp"
#include "webgpu/gpu.hpp"

#include <dawn/native/OpenGLBackend.h>
#include <dolphin/gx.h>
#include <dolphin/mtx.h>
#include <SDL3/SDL.h>
#include <atomic>
#include <array>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <dlfcn.h>
#include <filesystem>
#include <thread>
#include <vector>

using namespace aurora;
static std::atomic_uint errors{0};

static void require(bool ok, const char* message) {
  if (!ok) { std::fprintf(stderr, "FAIL: %s\n", message); std::fflush(nullptr); std::_Exit(1); }
}

static void create_device() {
  const auto feature = wgpu::InstanceFeatureName::TimedWaitAny;
  const wgpu::InstanceDescriptor instanceDesc{.requiredFeatureCount = 1, .requiredFeatures = &feature};
  webgpu::g_instance = wgpu::CreateInstance(&instanceDesc);
  auto egl = dlopen("libEGL.so.1", RTLD_NOW | RTLD_LOCAL);
  require(egl != nullptr, "system EGL loader");
  dawn::native::opengl::RequestAdapterOptionsGetGLProc glProc;
  glProc.display = nullptr;
  glProc.getProc = reinterpret_cast<dawn::native::opengl::EGLGetProcProc>(dlsym(egl, "eglGetProcAddress"));
  require(glProc.getProc != nullptr, "eglGetProcAddress");
  wgpu::RequestAdapterOptions options{.nextInChain = &glProc,
      .featureLevel = wgpu::FeatureLevel::Compatibility, .backendType = wgpu::BackendType::OpenGLES};
  wgpu::Adapter adapter;
  auto future = webgpu::g_instance.RequestAdapter(&options, wgpu::CallbackMode::WaitAnyOnly,
      [&](wgpu::RequestAdapterStatus status, wgpu::Adapter selected, wgpu::StringView message) {
        if (status == wgpu::RequestAdapterStatus::Success) adapter = std::move(selected);
        else std::fprintf(stderr, "GLES adapter: %.*s\n", int(message.length), message.data);
      });
  require(webgpu::g_instance.WaitAny(future, 5'000'000'000) == wgpu::WaitStatus::Success,
          "adapter callback");
  require(bool(adapter), "real GLES adapter (no backend fallback)");
  adapter.GetInfo(&webgpu::g_adapterInfo);
  require(webgpu::g_adapterInfo.backendType == wgpu::BackendType::OpenGLES, "actual OpenGL ES backend");
  webgpu::g_backendType = wgpu::BackendType::OpenGLES;
  std::vector<wgpu::FeatureName> features;
  for (auto f : {wgpu::FeatureName::CoreFeaturesAndLimits, wgpu::FeatureName::TextureCompressionBC,
                 wgpu::FeatureName::TextureCompressionASTC, wgpu::FeatureName::TextureComponentSwizzle}) {
    if (adapter.HasFeature(f)) features.push_back(f);
  }
  webgpu::g_hasCoreFeatures = adapter.HasFeature(wgpu::FeatureName::CoreFeaturesAndLimits);
  webgpu::g_bcTexturesSupported = adapter.HasFeature(wgpu::FeatureName::TextureCompressionBC);
  webgpu::g_astcTexturesSupported = adapter.HasFeature(wgpu::FeatureName::TextureCompressionASTC);
  webgpu::g_textureComponentSwizzleSupported = adapter.HasFeature(wgpu::FeatureName::TextureComponentSwizzle);
  wgpu::CompatibilityModeLimits compat{wgpu::CompatibilityModeLimits::Init{
      .maxStorageBuffersInVertexStage = 2, .maxStorageBuffersInFragmentStage = 2}};
  wgpu::Limits limits{.nextInChain = &compat, .maxStorageBuffersPerShaderStage = 2,
      .maxImmediateSize = sizeof(gx::DrawImmediateData)};
  const char* toggles[] = {"allow_unsafe_apis", "gl_allow_context_on_multi_threads"};
  wgpu::DawnTogglesDescriptor toggleDesc{wgpu::DawnTogglesDescriptor::Init{
      .enabledToggleCount = 2, .enabledToggles = toggles}};
  wgpu::DeviceDescriptor desc({.nextInChain = &toggleDesc, .requiredFeatureCount = features.size(),
      .requiredFeatures = features.data(), .requiredLimits = &limits});
  desc.SetUncapturedErrorCallback([](const wgpu::Device&, wgpu::ErrorType, wgpu::StringView message) {
    ++errors;
    std::fprintf(stderr, "GPU validation: %.*s\n", int(message.length), message.data);
  });
  future = adapter.RequestDevice(&desc, wgpu::CallbackMode::WaitAnyOnly,
      [](wgpu::RequestDeviceStatus status, wgpu::Device device, wgpu::StringView message) {
        if (status == wgpu::RequestDeviceStatus::Success) webgpu::g_device = std::move(device);
        else std::fprintf(stderr, "GLES device: %.*s\n", int(message.length), message.data);
      });
  require(webgpu::g_instance.WaitAny(future, 5'000'000'000) == wgpu::WaitStatus::Success, "device callback");
  require(bool(webgpu::g_device), "GLES device with GX limits");
  webgpu::g_queue = webgpu::g_device.GetQueue();
}

static void quad(uint8_t red, uint8_t green, uint8_t blue, uint8_t alpha) {
  GXBegin(GX_QUADS, GX_VTXFMT0, 4);
  for (const auto& xy : std::array<std::array<float, 2>, 4>{{{-1,-1}, {1,-1}, {1,1}, {-1,1}}}) {
    GXPosition3f32(xy[0], xy[1], -0.5f);
    GXColor4u8(red, green, blue, alpha);
  }
  GXEnd();
}

int main() {
  setenv("EGL_PLATFORM", "surfaceless", 1);
  require(SDL_Init(SDL_INIT_EVENTS), "SDL events");
  create_device();
  // Exercise the cleanup used between unsuccessful backend attempts. It must
  // release a device as well as the adapter, then permit a fresh GLES context.
  webgpu::reset_failed_initialization();
  require(!webgpu::g_device && !webgpu::g_instance, "failed initialization cleanup");
  create_device();
  auto cache = std::filesystem::temp_directory_path() / ("strikers-gles-render-test-" +
      std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
  std::filesystem::create_directories(cache);
  const auto cacheString = cache.string();
  g_config.cachePath = cacheString.c_str();
  g_config.userPath = cacheString.c_str();
  webgpu::g_graphicsConfig.surfaceConfiguration.format = wgpu::TextureFormat::RGBA8Unorm;
  webgpu::g_graphicsConfig.surfaceConfiguration.width = 64;
  webgpu::g_graphicsConfig.surfaceConfiguration.height = 64;
  webgpu::g_graphicsConfig.depthFormat = wgpu::TextureFormat::Depth32Float;
  webgpu::g_graphicsConfig.msaaSamples = 1;
  webgpu::g_graphicsConfig.textureAnisotropy = 1;
  webgpu::g_frameBuffer = webgpu::create_render_texture(64, 64, false);
  const wgpu::TextureDescriptor depthDesc{.usage = wgpu::TextureUsage::RenderAttachment | wgpu::TextureUsage::TextureBinding,
      .size = {64,64,1}, .format = wgpu::TextureFormat::Depth32Float};
  webgpu::g_depthBuffer.texture = webgpu::g_device.CreateTexture(&depthDesc);
  webgpu::g_depthBuffer.view = webgpu::g_depthBuffer.texture.CreateView();
  webgpu::g_depthBuffer.size = {64,64,1};
  webgpu::g_depthBuffer.format = wgpu::TextureFormat::Depth32Float;
  gfx::initialize();
  gx::fifo::init();
  GXInit(nullptr, 0);
  const auto custom = gfx::register_draw_type({
      .label = "External encoder invalidation test",
      .draw = [](const gfx::DrawContext&, const wgpu::RenderPassEncoder& pass, const void*, size_t, void*) {
        // This unused, zero-filled uniform range must not leak into the next GX draw.
        const uint32_t offset = 512 * 1024;
        pass.SetBindGroup(1, gfx::detail::resources().uniformBindGroup, 1, &offset);
        const wgpu::Color zero{};
        pass.SetBlendConstant(&zero);
      }});
  const wgpu::BufferDescriptor readDesc{.usage = wgpu::BufferUsage::CopyDst | wgpu::BufferUsage::MapRead, .size = 64*256};
  auto readback = webgpu::g_device.CreateBuffer(&readDesc);
  for (int frame = 0; frame < 3; ++frame) {
    require(gfx::begin_frame(), "begin real renderer frame");
    gx::fifo::begin_frame();
    Mtx44 proj{};
    proj[0][0] = proj[1][1] = proj[3][3] = 1.f;
    proj[2][2] = 1.f; // GX clip depth is [-w, 0], converted to WebGPU reversed Z.
    GXSetProjection(proj, GX_ORTHOGRAPHIC);
    GXSetViewport(0,0,640,480,0,1);
    GXSetScissor(0,0,640,480);
    GXSetCullMode(GX_CULL_NONE);
    GXSetZMode(GX_FALSE, GX_ALWAYS, GX_FALSE);
    GXClearVtxDesc();
    GXSetVtxDesc(GX_VA_POS, GX_DIRECT);
    GXSetVtxDesc(GX_VA_CLR0, GX_DIRECT);
    GXSetVtxAttrFmt(GX_VTXFMT0, GX_VA_POS, GX_POS_XYZ, GX_F32, 0);
    GXSetVtxAttrFmt(GX_VTXFMT0, GX_VA_CLR0, GX_CLR_RGBA, GX_RGBA8, 0);
    GXSetNumTexGens(0);
    GXSetNumChans(1);
    GXSetNumTevStages(1);
    GXSetTevOrder(GX_TEVSTAGE0, GX_TEXCOORD_NULL, GX_TEXMAP_NULL, GX_COLOR0A0);
    GXSetTevOp(GX_TEVSTAGE0, GX_PASSCLR);
    GXSetBlendMode(GX_BM_NONE, GX_BL_ONE, GX_BL_ZERO, GX_LO_CLEAR);
    GXSetDstAlpha(GX_FALSE, 0);
    quad(0,0,255,255);
    // Repeated overlapping translucent effects and a state change between frames.
    GXSetBlendMode(GX_BM_BLEND, GX_BL_SRCALPHA, GX_BL_INVSRCALPHA, GX_LO_CLEAR);
    GXSetDstAlpha(GX_TRUE, 128);
    for (int i = 0; i < 8; ++i) {
      // Break primitive merging without changing the center pixel, so real
      // draws reuse state across scissor commands and an external encoder.
      // A one-pixel logical shift rounds away at the 64-pixel test target.
      const int inset = (i % 2) * 80;
      GXSetScissor(inset, 0, 640 - inset, 480);
      if (i == 4) {
        gx::fifo::drain();
        require(gfx::push_custom_draw(custom, nullptr, 0), "custom draw boundary");
      }
      quad(frame == 1 ? 0 : 255, frame == 1 ? 255 : 0, 0, 64);
    }
    GXDrawDone();
    gx::fifo::drain();
    gx::fifo::end_frame();
    // Desktop Aurora permits an uncompiled pipeline to skip its first draw;
    // Android waits at binding. Wait here so this host gate checks pixels on
    // a cold cache too, while using the real compiler thread and GLES context.
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    while (std::atomic_ref(gfx::detail::resources().stats.queuedPipelines).load() != 0) {
      require(std::chrono::steady_clock::now() < deadline, "GLES pipeline compilation");
      webgpu::g_instance.ProcessEvents();
      std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    gfx::finish();
    gfx::end_frame([&](wgpu::CommandEncoder& encoder, auto callbacks) {
      wgpu::TexelCopyTextureInfo src{.texture = webgpu::g_frameBuffer.texture};
      wgpu::TexelCopyBufferInfo dst{.layout = {.bytesPerRow = 256, .rowsPerImage = 64}, .buffer = readback};
      wgpu::Extent3D extent{64,64,1};
      encoder.CopyTextureToBuffer(&src, &dst, &extent);
      auto command = encoder.Finish();
      webgpu::g_queue.Submit(1, &command);
      for (auto& cb : callbacks) cb();
    });
    gfx::synchronize();
    bool mapped = false;
    auto future = readback.MapAsync(wgpu::MapMode::Read, 0, readDesc.size, wgpu::CallbackMode::WaitAnyOnly,
        [&](wgpu::MapAsyncStatus status, wgpu::StringView) { mapped = status == wgpu::MapAsyncStatus::Success; });
    require(webgpu::g_instance.WaitAny(future, 5'000'000'000) == wgpu::WaitStatus::Success && mapped, "pixel readback");
    const auto* pixels = static_cast<const uint8_t*>(readback.GetConstMappedRange());
    const auto* center = pixels + 32*256 + 32*4;
    std::fprintf(stderr, "GLES frame %d center RGBA=%u,%u,%u,%u\n", frame, center[0],center[1],center[2],center[3]);
    require(center[frame == 1 ? 1 : 0] > 220 && center[frame == 1 ? 0 : 1] < 5 && center[2] < 35,
            "GX transparency pixels and per-frame state");
    // Existing alpha blend is source alpha (64) times constant (128 / 255).
    require(center[3] == 32, "alpha blend constant restored after external encoder");
    readback.Unmap();
  }
  std::fprintf(stderr, "Saved bindings=%u\n", gfx::runtime_metrics::bindingsSaved.load());
  gx::fifo::shutdown();
  gfx::unregister_draw_type(custom);
  gfx::shutdown();
  require(errors.load() == 0, "no WebGPU validation errors");
  require(gfx::runtime_metrics::bindingsSaved.load() > 0, "real draw binding reuse");
  std::puts("OpenGL ES real GX shaders, FIFO, uploads, alpha blending and readback: PASS");
  return 0;
}
