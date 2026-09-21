#pragma once

#include "pipeline.hpp"

namespace aurora::gx {
// All GX pipelines share their layouts and frame buffers. Cache only state
// that is actually bound, within one uninterrupted run of GX draws in a pass.
class DrawBindingCache {
  bool m_uniformValid = false, m_indexValid = false, m_alphaValid = false;
  uint32_t m_uniformOffset = 0, m_alpha = 0;
  gfx::BindGroupRef m_texture = 0;
  gfx::Range m_indices{};

public:
  struct Changes {
    bool uniform, texture, indices, alpha;
    uint32_t saved;
  };

  Changes update(const DrawData& data) {
    Changes changes{};
    changes.uniform = !m_uniformValid || m_uniformOffset != data.uniformRange.offset;
    m_uniformValid = true;
    m_uniformOffset = data.uniformRange.offset;
    if (!changes.uniform) ++changes.saved;
    if (data.bindGroups.textureBindGroup != 0) {
      changes.texture = m_texture != data.bindGroups.textureBindGroup;
      m_texture = data.bindGroups.textureBindGroup;
      if (!changes.texture) ++changes.saved;
    }
    if (data.indexCount != 0) {
      changes.indices = !m_indexValid || m_indices != data.idxRange;
      m_indexValid = true;
      m_indices = data.idxRange;
      if (!changes.indices) ++changes.saved;
    }
    if (data.dstAlpha != UINT32_MAX) {
      changes.alpha = !m_alphaValid || m_alpha != data.dstAlpha;
      m_alphaValid = true;
      m_alpha = data.dstAlpha;
      if (!changes.alpha) ++changes.saved;
    }
    return changes;
  }
};
} // namespace aurora::gx
