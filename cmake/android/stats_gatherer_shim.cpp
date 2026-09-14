#include "Game/AI/StatsGatherer.h"

// StatsGatherer is a retail/debug-only task. The decomp intentionally omits the
// bodies below because the shipping game never constructs this class, but its
// vtable is still emitted by Clang when all game objects are packed into the
// Android shared library. Keep the unused task link-complete without changing
// gameplay behavior.
void StatsGatherer::Run(float /*deltaTime*/)
{
}

const char* StatsGatherer::GetName()
{
    return "StatsGatherer";
}

void StatsGatherer::DoFunctionCall(unsigned int /*functionId*/)
{
}
