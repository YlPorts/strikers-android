#include "pipeline.hpp"
#include "draw_bindings.hpp"

#include "../gfx/encoding.hpp"
#include "../gfx/resources.hpp"
#include "../gfx/pipeline_cache.hpp"
#include "../gfx/resource_cache.hpp"
#include "../gfx/runtime_metrics.hpp"

#include "gx_fmt.hpp"
#include "shader_info.hpp"

#include <tracy/Tracy.hpp>

namespace aurora::gx {
namespace {
thread_local DrawBindingCache g_drawBindings;
}

void reset_render_bindings() { g_drawBindings = {}; }

wgpu::RenderPipeline create_pipeline(const PipelineConfig& config) {
  ZoneScoped;
  const auto shader = build_shader(config.shaderConfig);
  const auto label =
      fmt::format("GX Pipeline {:x} shader {:x}", xxh3_hash(config, static_cast<HashType>(gfx::ShaderType::GX)),
                  xxh3_hash(config.shaderConfig));
  return build_pipeline(config, {}, shader, label.c_str());
}

void render(const DrawData& data, const wgpu::RenderPassEncoder& pass) {
  if (!gfx::bind_pipeline(data.pipeline, pass)) {
    return;
  }

  const auto& resources = gfx::detail::resources();
  const auto changes = g_drawBindings.update(data);
  if (changes.saved != 0) {
    gfx::runtime_metrics::bindingsSaved.fetch_add(changes.saved, std::memory_order_relaxed);
  }
  pass.SetImmediates(0, &data.immediateData, sizeof(data.immediateData));
  if (changes.uniform) {
    const std::array offsets{data.uniformRange.offset};
    pass.SetBindGroup(1, resources.uniformBindGroup, offsets.size(), offsets.data());
  }
  if (changes.texture) {
    pass.SetBindGroup(2, gfx::find_bind_group(data.bindGroups.textureBindGroup));
  }
  if (changes.indices) {
    pass.SetIndexBuffer(resources.indexBuffer, wgpu::IndexFormat::Uint16, data.idxRange.offset, data.idxRange.size);
  }
  if (changes.alpha) {
    const wgpu::Color color{0.f, 0.f, 0.f, data.dstAlpha / 255.f};
    pass.SetBlendConstant(&color);
  }
  if (data.indexCount == 0) {
    pass.Draw(data.vtxCount, data.instanceCount);
  } else {
    pass.DrawIndexed(data.indexCount, data.instanceCount);
  }
}

} // namespace aurora::gx
