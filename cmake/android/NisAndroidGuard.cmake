# Android-only hardening for the decompiled NIS/cinematic runtime.
#
# Keep the upstream-style source untouched and generate a guarded translation
# unit for Android. These checks are deliberately narrow: they do not disable
# cinematics and do not change stadium/map residency.

set(_nis_original "${STRIKERS_ROOT}/src/Game/Render/Nis.cpp")
set(_nis_generated "${CMAKE_CURRENT_BINARY_DIR}/Nis_android_guarded.cpp")
file(READ "${_nis_original}" _nis_source)

function(_strikers_nis_replace _label _needle _replacement)
    string(REPLACE "${_needle}" "${_replacement}" _patched "${_nis_source}")
    if(_patched STREQUAL _nis_source)
        message(FATAL_ERROR "NIS Android patch '${_label}' no longer matches Nis.cpp")
    endif()
    set(_nis_source "${_patched}" PARENT_SCOPE)
endfunction()

set(_needle [=[    while (chunk != end)
    {
        // PORT: the file is big-endian and each animation converts its own subtree, so read the header rather than trusting it.]=])
set(_replacement [=[    while ((char*)chunk + 8 <= (char*)end)
    {
        // PORT: the file is big-endian and each animation converts its own subtree, so read the header rather than trusting it.]=])
_strikers_nis_replace("chunk-loop" "${_needle}" "${_replacement}")

set(_needle [=[        const u32 uChunkID = port_be32(&chunk->m_ID) & 0x80FFFFFF;
        const u32 uChunkSize = port_be32(&chunk->m_Size);

        if (uChunkID == 0x80017000)]=])
set(_replacement [=[        const u32 uChunkID = port_be32(&chunk->m_ID) & 0x80FFFFFF;
        const u32 uChunkSize = port_be32(&chunk->m_Size);
        const uintptr_t bytesRemaining = (uintptr_t)((char*)end - (char*)chunk);
        if (bytesRemaining < 8 || (uintptr_t)uChunkSize > bytesRemaining - 8)
        {
            OSReport("[nis] malformed chunk in %s: size=%lu remaining=%lu\n",
                     mHeader != NULL ? mHeader->name : "(unknown)",
                     (unsigned long)uChunkSize, (unsigned long)bytesRemaining);
            break;
        }

        if (uChunkID == 0x80017000)]=])
_strikers_nis_replace("chunk-bounds" "${_needle}" "${_replacement}")

set(_needle [=[        if (mCharacterControllers[i] == NULL)
            continue;]=])
set(_replacement [=[        if (mCharacterControllers[i] == NULL || mCharacterControllers[i]->m_pSAnim == NULL)
            continue;]=])
_strikers_nis_replace("render-null-animation" "${_needle}" "${_replacement}")

set(_needle [=[void Nis::AddTrigger(NisTriggerType triggerType, float frameNumber, const char* name, const char* target, Nis::TriggerParams* trigParams)
{
    mTriggers[mNumTriggers].type = triggerType;]=])
set(_replacement [=[void Nis::AddTrigger(NisTriggerType triggerType, float frameNumber, const char* name, const char* target, Nis::TriggerParams* trigParams)
{
    if (mNumTriggers < 0 || mNumTriggers >= MAX_NUM_TRIGGERS)
    {
        OSReport("[nis] trigger overflow in %s; dropping trigger\n",
                 mHeader != NULL ? mHeader->name : "(unknown)");
        return;
    }
    if (name == NULL)
        name = "";
    if (target == NULL)
        target = "";

    mTriggers[mNumTriggers].type = triggerType;]=])
_strikers_nis_replace("trigger-capacity" "${_needle}" "${_replacement}")

set(_needle [=[void Nis::Trigger::FireEffect(const Nis& nis) const
{
    NisPlayer* player = NULL;]=])
set(_replacement [=[void Nis::Trigger::FireEffect(const Nis& nis) const
{
    if (name == NULL || target == NULL)
        return;

    NisPlayer* player = NULL;]=])
_strikers_nis_replace("effect-null-strings" "${_needle}" "${_replacement}")

set(_needle [=[        if (charIdx >= 10)
            return;]=])
set(_replacement [=[        if (charIdx < 0 || charIdx >= MAX_NUM_CHARACTERS || nis.mCharacterControllers[charIdx] == NULL)
        {
            OSReport("[nis] effect %s skipped: missing character slot %d\n", name, charIdx);
            return;
        }]=])
_strikers_nis_replace("effect-character-controller" "${_needle}" "${_replacement}")

set(_needle [=[        World* const world = WorldManager::s_World;
        HelperObject* helperObj = world->FindHelperObject(world->GetHashIdForGenericName(target));
        if (helperObj == NULL)
            return;
        nlVector3 velocity = { 0.0f, 0.0f, 1.0f };
        EmissionController* ctrl = EmissionManager::Create(fxGetGroup(name), 0);
        ctrl->m_uUserData = (uintptr_t)player;]=])
set(_replacement [=[        World* const world = WorldManager::s_World;
        if (world == NULL)
            return;
        HelperObject* helperObj = world->FindHelperObject(world->GetHashIdForGenericName(target));
        if (helperObj == NULL)
            return;
        EffectsGroup* group = fxGetGroup(name);
        if (group == NULL)
        {
            OSReport("[nis] missing effect group %s\n", name);
            return;
        }
        nlVector3 velocity = { 0.0f, 0.0f, 1.0f };
        EmissionController* ctrl = EmissionManager::Create(group, 0);
        if (ctrl == NULL)
            return;
        ctrl->m_uUserData = (uintptr_t)player;]=])
_strikers_nis_replace("effect-world-controller" "${_needle}" "${_replacement}")

set(_needle [=[        else
        {
            index = Audio::PlayCharSFXbyStr(name, (NisCharacterClass)params.param1, volume, -1.0f, true, false, &ReplayManager::Instance()->GetMutableRenderSnapshot().GetCharacter(nis.mAudioCharacterIndex).mBip01Position, &ReplayManager::Instance()->GetMutableRenderSnapshot().GetCharacter(nis.mAudioCharacterIndex).mVelocity, &soundType);]=])
set(_replacement [=[        else
        {
            if (nis.mAudioCharacterIndex < 0 || nis.mAudioCharacterIndex >= MAX_NUM_CHARACTERS)
                break;
            index = Audio::PlayCharSFXbyStr(name, (NisCharacterClass)params.param1, volume, -1.0f, true, false, &ReplayManager::Instance()->GetMutableRenderSnapshot().GetCharacter(nis.mAudioCharacterIndex).mBip01Position, &ReplayManager::Instance()->GetMutableRenderSnapshot().GetCharacter(nis.mAudioCharacterIndex).mVelocity, &soundType);]=])
_strikers_nis_replace("sound-character-index" "${_needle}" "${_replacement}")

set(_needle [=[    case NIS_TRIGGER_TYPE_PLAY_RANDOM_DIALOGUE:
    {
        uintptr_t index;   /* PORT: may hold an SFXEmitter* */]=])
set(_replacement [=[    case NIS_TRIGGER_TYPE_PLAY_RANDOM_DIALOGUE:
    {
        if (nis.mAudioCharacterIndex < 0 || nis.mAudioCharacterIndex >= MAX_NUM_CHARACTERS)
            break;
        uintptr_t index;   /* PORT: may hold an SFXEmitter* */]=])
_strikers_nis_replace("dialogue-character-index" "${_needle}" "${_replacement}")

set(_needle [=[        pSFXEmitter = pNisAudioData->identifier.pEmitter;
        if (pNisAudioData->soundType == pSFXEmitter->soundType)]=])
set(_replacement [=[        pSFXEmitter = pNisAudioData->identifier.pEmitter;
        if (pSFXEmitter == NULL)
            return RemoveNisAudioData(pNisAudioData);
        if (pNisAudioData->soundType == pSFXEmitter->soundType)]=])
_strikers_nis_replace("audio-null-emitter" "${_needle}" "${_replacement}")

file(WRITE "${_nis_generated}" "${_nis_source}")
set_source_files_properties("${_nis_generated}" PROPERTIES GENERATED TRUE)

get_target_property(_strikers_game_sources strikers_game SOURCES)
list(REMOVE_ITEM _strikers_game_sources "${_nis_original}")
set_property(TARGET strikers_game PROPERTY SOURCES "${_strikers_game_sources}")
target_sources(strikers_game PRIVATE "${_nis_generated}")
