#include <dolphin/gx.h>

// Aurora currently exposes the GameCube CPU<->EFB poke/peek API in its public
// headers but does not provide these legacy entry points. They are only used by
// Strikers' DVD-lid message and screenshot paths. Android has no DVD lid and the
// normal renderer never depends on direct EFB pokes, so keep them as compatibility
// no-ops until those optional paths are mapped to Aurora readback.
extern "C" {

void GXPokeColorUpdate(GXBool /*update_enable*/)
{
}

void GXPokeBlendMode(GXBlendMode /*type*/, GXBlendFactor /*src_factor*/,
                     GXBlendFactor /*dst_factor*/, GXLogicOp /*op*/)
{
}

void GXPokeARGB(u16 /*x*/, u16 /*y*/, u32 /*color*/)
{
}

void GXPeekARGB(u16 /*x*/, u16 /*y*/, u32* color)
{
    if (color != nullptr)
    {
        *color = 0;
    }
}

} // extern "C"
