#include <gtest/gtest.h>

#include "gfx/frame_packet.hpp"
#include "gfx/recording.hpp"
#include "gfx/resources.hpp"
#include "gfx/texture.hpp"
#include "webgpu/gpu.hpp"
#include "gx/draw_bindings.hpp"

#include <algorithm>
#include <memory>

namespace aurora::gfx {
namespace {

constexpr auto ColorFormat = wgpu::TextureFormat::RGBA8Unorm;
constexpr auto DepthFormat = wgpu::TextureFormat::Depth24Plus;

TEST(GxDrawBindings, RepeatedEffectsKeepDrawsButAvoidRepeatedDriverState) {
  gx::DrawBindingCache cache;
  gx::DrawData draw{};
  draw.uniformRange = {256, 64};
  draw.idxRange = {0, 12};
  draw.indexCount = 6;
  draw.bindGroups.textureBindGroup = 42;
  draw.dstAlpha = 128;
  uint32_t binds = 0, saved = 0;
  for (uint32_t i = 0; i < 100; ++i) {
    draw.immediateData.vtxStart = i * 64;
    const auto changes = cache.update(draw);
    binds += changes.uniform + changes.texture + changes.indices + changes.alpha;
    saved += changes.saved;
  }
  EXPECT_EQ(binds, 4u);
  EXPECT_EQ(saved, 396u);
  draw.uniformRange.offset = 512;
  draw.idxRange = {12, 24};
  draw.bindGroups.textureBindGroup = 43;
  draw.dstAlpha = 64;
  const auto changed = cache.update(draw);
  EXPECT_TRUE(changed.uniform && changed.texture && changed.indices && changed.alpha);
  EXPECT_EQ(changed.saved, 0u);
  cache = {}; // New pass, custom callback or another encoder invalidates state.
  const auto restored = cache.update(draw);
  EXPECT_TRUE(restored.uniform && restored.texture && restored.indices && restored.alpha);
}

TEST(GxDrawBindings, UnusedBindingsDoNotEraseStateOrBindZeroLengthIndexBuffers) {
  gx::DrawBindingCache cache;
  gx::DrawData draw{};
  draw.dstAlpha = UINT32_MAX;
  auto changes = cache.update(draw);
  EXPECT_TRUE(changes.uniform);
  EXPECT_FALSE(changes.texture || changes.indices || changes.alpha);
  draw.indexCount = 6;
  draw.idxRange = {16, 12};
  draw.bindGroups.textureBindGroup = 99;
  draw.dstAlpha = 255;
  changes = cache.update(draw);
  EXPECT_TRUE(changes.texture && changes.indices && changes.alpha);
  auto untextured = draw;
  untextured.bindGroups.textureBindGroup = 0;
  untextured.indexCount = 0;
  untextured.dstAlpha = UINT32_MAX;
  cache.update(untextured);
  changes = cache.update(draw);
  EXPECT_FALSE(changes.uniform || changes.texture || changes.indices || changes.alpha);
}

class GfxRecordingTest : public ::testing::Test {
protected:
  void SetUp() override {
    // No GPU is created in these recorder tests. Dawn's default limit is the
    // undefined sentinel, not a usable alignment for successive uniforms.
    detail::resources().limits.minUniformBufferOffsetAlignment = 256;
    webgpu::g_graphicsConfig.surfaceConfiguration.format = ColorFormat;
    webgpu::g_graphicsConfig.depthFormat = DepthFormat;
    webgpu::g_graphicsConfig.msaaSamples = 1;
    webgpu::g_frameBuffer.size = {640, 480, 1};
    webgpu::g_frameBuffer.format = ColorFormat;
    webgpu::g_depthBuffer.size = {640, 480, 1};
    webgpu::g_depthBuffer.format = DepthFormat;
    detail::testing::suppress_render_worker(true);
    detail::begin_recording(frame, 0);
  }

  void TearDown() override {
    if (recordingActive) {
      if (is_offscreen()) {
        end_offscreen();
      }
      finish();
      detail::end_recording();
    }
    detail::shutdown_recording();
  }

  void seed(uint32_t width, uint32_t height) {
    detail::testing::seed_offscreen_cache(width, height, ColorFormat, DepthFormat);
  }

  void copy_current_offscreen() {
    const auto& pass = frame.renderPasses.back();
    const auto& size = pass.colorAttachments[SceneColorAttachmentIndex].size;
    auto target = std::make_shared<TextureRef>(wgpu::Texture{}, wgpu::TextureView{}, wgpu::TextureView{}, size,
                                               ColorFormat, 1, GX_TF_RGBA8);
    resolve_pass_into(std::move(target), {0, 0, static_cast<int32_t>(size.width), static_cast<int32_t>(size.height)},
                      false, false, false, {}, 1.f);
  }

  size_t count_efb_passes() const {
    return static_cast<size_t>(
        std::ranges::count_if(frame.renderPasses, [](const auto& pass) { return pass.label.starts_with("EFB"); }));
  }

  detail::FramePacket frame;
  bool recordingActive = true;
};

TEST_F(GfxRecordingTest, CreateRestoreReturnsToEfb) {
  seed(320, 180);
  begin_offscreen(320, 180);
  ASSERT_TRUE(is_offscreen());

  end_offscreen();

  EXPECT_FALSE(is_offscreen());
  ASSERT_EQ(frame.renderPasses.size(), 2u);
  EXPECT_TRUE(frame.renderPasses[0].sealed);
  EXPECT_TRUE(frame.renderPasses[0].discardable);
  EXPECT_EQ(count_efb_passes(), 1u);
}

TEST_F(GfxRecordingTest, ClearOnlyHasNoCopyOrUploadAndRetainsAttachments) {
  for (bool color : {false, true}) {
    const auto beforePass = frame.renderPasses.size();
    const auto beforeUniforms = frame.uniforms.size();
    const auto layout = frame.renderPasses.back().target_layout();
    resolve_pass_into({}, {}, color, color, true, {0.2f, 0.4f, 0.6f, 1.f}, 0.75f);
    ASSERT_EQ(frame.renderPasses.size(), beforePass + 1);
    const auto& previous = frame.renderPasses[beforePass - 1];
    const auto& next = frame.renderPasses.back();
    EXPECT_TRUE(previous.sealed);
    EXPECT_FALSE(previous.resolveTarget);
    EXPECT_EQ(frame.uniforms.size(), beforeUniforms);
    EXPECT_TRUE(frame.textureCopies.empty());
    EXPECT_TRUE(frame.textureUploads.empty());
    EXPECT_EQ(next.target_layout().key, layout.key);
    EXPECT_EQ(next.colorAttachments[SceneColorAttachmentIndex].clear, color);
    EXPECT_EQ(next.colorAttachments[SceneColorAttachmentIndex].size.width, 640u);
    EXPECT_TRUE(next.clearDepth);
    EXPECT_EQ(next.clearDepthValue, 0.75f);
    if (color) EXPECT_EQ(next.colorAttachments[SceneColorAttachmentIndex].clearValue.w(), 1.f);
  }
}

TEST_F(GfxRecordingTest, EfbPassUsesDiscoveredSceneLayout) {
  ASSERT_FALSE(frame.renderPasses.empty());
  const auto discovered = scene_render_target_layout();
  const auto targetLayout = frame.renderPasses.front().target_layout();

  EXPECT_EQ(targetLayout.key, discovered.key);
  EXPECT_EQ(targetLayout.colorAttachmentCount, discovered.colorAttachmentCount);
  EXPECT_EQ(targetLayout.colorAttachments[SceneColorAttachmentIndex].semantic, ColorAttachmentSemantic::SceneColor);
}

TEST_F(GfxRecordingTest, ConsecutiveShadowCopiesKeepEveryTargetWithoutEmptyAttachmentPasses) {
  std::array<TextureHandle, 10> targets;
  for (size_t i = 0; i < targets.size(); ++i) {
    targets[i] = std::make_shared<TextureRef>(wgpu::Texture{}, wgpu::TextureView{}, wgpu::TextureView{},
                                            wgpu::Extent3D{80, 74, 1}, ColorFormat, 1, GX_CTF_A8);
    resolve_pass_into(targets[i], {static_cast<int32_t>((i % 4) * 160),
                                  static_cast<int32_t>((i / 4) * 148), 160, 148},
                      false, false, false, {}, 1.f, GX_CTF_A8);
  }
  finish();
  ASSERT_EQ(frame.renderPasses.size(), 11u);
  ASSERT_EQ(frame.ops.size(), 11u);
  size_t attachmentPasses = 0;
  for (size_t i = 0; i < frame.renderPasses.size(); ++i) {
    const auto& pass = frame.renderPasses[i];
    EXPECT_TRUE(pass.sealed);
    EXPECT_FALSE(pass.discardable);
    EXPECT_EQ(frame.ops[i].renderPass, &pass);
    attachmentPasses += pass.needs_attachment_pass();
    if (i < targets.size()) {
      EXPECT_EQ(pass.resolveTarget, targets[i]);
      EXPECT_EQ(pass.resolveFormat, GX_CTF_A8);
      EXPECT_EQ(pass.resolveRect.x, static_cast<int32_t>((i % 4) * 160));
      EXPECT_EQ(pass.resolveRect.y, static_cast<int32_t>((i / 4) * 148));
      EXPECT_EQ(pass.resolveRect.width, 160);
      EXPECT_EQ(pass.resolveRect.height, 148);
    }
  }
  // The initial clear remains. Ten subsequent load/store-only passes need no
  // raster work, while all ten shadow conversions and the final snapshot remain.
  EXPECT_EQ(attachmentPasses, 1u);
  EXPECT_TRUE(frame.renderPasses.back().captureDepthSnapshot);
}

TEST_F(GfxRecordingTest, DepthAndColorClearsSurviveEmptyCopyContinuations) {
  copy_current_offscreen();
  EXPECT_FALSE(frame.renderPasses.back().needs_attachment_pass());
  resolve_pass_into({}, {}, false, false, true, {}, 0.25f);
  EXPECT_TRUE(frame.renderPasses.back().needs_attachment_pass());
  EXPECT_EQ(frame.renderPasses.back().clearDepthValue, 0.25f);
  copy_current_offscreen();
  EXPECT_FALSE(frame.renderPasses.back().needs_attachment_pass());
  resolve_pass_into({}, {}, true, true, false, {0.2f, 0.4f, 0.6f, 1.f}, 1.f);
  EXPECT_TRUE(frame.renderPasses.back().needs_attachment_pass());
  EXPECT_EQ(frame.renderPasses.back().colorAttachments[0].clearValue.w(), 1.f);
}

TEST_F(GfxRecordingTest, AttachmentSideEffectsAreNeverSkipped) {
  copy_current_offscreen();
  const auto loadOnly = frame.renderPasses.back();
  ASSERT_FALSE(loadOnly.needs_attachment_pass());
  {
    auto pass = loadOnly;
    pass.hasDraws = true; // Includes masked clears and registered custom draws.
    EXPECT_TRUE(pass.needs_attachment_pass());
  }
  {
    auto pass = loadOnly;
    pass.msaaSamples = 4;
    EXPECT_TRUE(pass.needs_attachment_pass());
  }
  {
    auto pass = loadOnly;
    pass.colorAttachments[0].loadOp = wgpu::LoadOp::ExpandResolveTexture;
    EXPECT_TRUE(pass.needs_attachment_pass());
  }
  for (const bool depth : {false, true}) {
    auto pass = loadOnly;
    if (depth) pass.depthLoadOp = wgpu::LoadOp::Clear;
    else pass.colorAttachments[0].loadOp = wgpu::LoadOp::Clear;
    EXPECT_TRUE(pass.needs_attachment_pass());
    pass = loadOnly;
    if (depth) pass.depthStoreOp = wgpu::StoreOp::Discard;
    else pass.colorAttachments[0].storeOp = wgpu::StoreOp::Discard;
    EXPECT_TRUE(pass.needs_attachment_pass());
  }
  {
    auto pass = loadOnly;
    pass.hasStencil = true;
    pass.stencilLoadOp = wgpu::LoadOp::Clear;
    EXPECT_TRUE(pass.needs_attachment_pass());
    pass.stencilLoadOp = wgpu::LoadOp::Load;
    pass.stencilStoreOp = wgpu::StoreOp::Discard;
    EXPECT_TRUE(pass.needs_attachment_pass());
    pass.stencilStoreOp = wgpu::StoreOp::Store;
    EXPECT_FALSE(pass.needs_attachment_pass());
  }
  {
    auto pass = loadOnly;
    pass.colorAttachments[0].clear = true;
    pass.clearDepth = true;
    pass.colorAttachments[0].loadOp = wgpu::LoadOp::Load;
    pass.depthLoadOp = wgpu::LoadOp::Load;
    // Explicit load operations override the default clear flags, as in the encoder.
    EXPECT_FALSE(pass.needs_attachment_pass());
  }
}

TEST_F(GfxRecordingTest, CopiedOffscreenPassIsRetainedOnRestore) {
  seed(320, 180);
  begin_offscreen(320, 180);
  copy_current_offscreen();

  end_offscreen();

  ASSERT_EQ(frame.renderPasses.size(), 3u);
  EXPECT_TRUE(frame.renderPasses[0].sealed);
  EXPECT_FALSE(frame.renderPasses[0].discardable);
  EXPECT_TRUE(frame.renderPasses[0].has_consumer());
  EXPECT_TRUE(frame.renderPasses[1].sealed);
  EXPECT_TRUE(frame.renderPasses[1].discardable);
}

TEST_F(GfxRecordingTest, ReplacementRetainsCopiedPassesAndDiscardsContinuations) {
  seed(320, 180);
  seed(160, 90);
  begin_offscreen(320, 180);
  copy_current_offscreen();
  begin_offscreen(160, 90);
  copy_current_offscreen();

  end_offscreen();

  ASSERT_EQ(frame.renderPasses.size(), 5u);
  EXPECT_TRUE(frame.renderPasses[0].has_consumer());
  EXPECT_FALSE(frame.renderPasses[0].discardable);
  EXPECT_TRUE(frame.renderPasses[1].discardable);
  EXPECT_TRUE(frame.renderPasses[2].has_consumer());
  EXPECT_FALSE(frame.renderPasses[2].discardable);
  EXPECT_TRUE(frame.renderPasses[3].discardable);
  EXPECT_EQ(count_efb_passes(), 1u);
}

TEST_F(GfxRecordingTest, RepeatedUncopiedCreatesDiscardEarlierPasses) {
  seed(320, 180);
  seed(160, 90);
  begin_offscreen(320, 180);
  begin_offscreen(160, 90);
  end_offscreen();

  ASSERT_EQ(frame.renderPasses.size(), 3u);
  EXPECT_TRUE(frame.renderPasses[0].sealed);
  EXPECT_TRUE(frame.renderPasses[0].discardable);
  EXPECT_TRUE(frame.renderPasses[1].sealed);
  EXPECT_TRUE(frame.renderPasses[1].discardable);
  EXPECT_EQ(count_efb_passes(), 1u);
}

TEST_F(GfxRecordingTest, PublicCreatePassRejectsExistingOffscreenPass) {
  seed(320, 180);
  seed(160, 90);
  ASSERT_TRUE(create_pass(320, 180));

  EXPECT_FALSE(create_pass(160, 90));

  ResolvedTargets ignored;
  EXPECT_TRUE(resolve_pass({.color = false, .depth = false}, ignored));
}

TEST_F(GfxRecordingTest, FinalizedPassesAreSealedOrDeliberatelyDiscarded) {
  seed(320, 180);
  seed(160, 90);
  begin_offscreen(320, 180);
  copy_current_offscreen();
  begin_offscreen(160, 90);
  end_offscreen();
  finish();

  ASSERT_FALSE(frame.renderPasses.empty());
  for (const auto& pass : frame.renderPasses) {
    EXPECT_TRUE(pass.sealed);
    if (!pass.has_consumer() && pass.label.starts_with("Offscreen")) {
      EXPECT_TRUE(pass.discardable);
    }
  }
  detail::end_recording();
  recordingActive = false;
}

} // namespace
} // namespace aurora::gfx
