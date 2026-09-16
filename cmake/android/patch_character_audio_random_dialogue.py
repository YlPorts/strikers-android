from pathlib import Path

path = Path("smstrikers-port/src/Game/Audio/CharacterAudio.cpp")
source = path.read_text()

bounds_anchor = """    s32 baseIndex = charDialogueSFXInfo[dType].charDialogueSFXIndex;
    s32 numRandom = charDialogueSFXInfo[dType].numRandomSFX;
"""

bounds_replacement = """    const int dialogueTypeIndex = static_cast<int>(dType);
    const int dialogueTypeCount = static_cast<int>(sizeof(charDialogueSFXInfo) / sizeof(charDialogueSFXInfo[0]));
    const int dialogueSfxCount = static_cast<int>(sizeof(charDialogueSFX) / sizeof(charDialogueSFX[0]));

    // The original decomp used charDialogueSFX[baseIndex + numRandom] as an
    // end sentinel. For CHAR_CLAP the range ends at the physical end of the
    // 29-entry table, so that expression reads element 29 out of bounds. On
    // Android/arm64 the garbage value can make the following flag-clear loop
    // walk far beyond mCharSFX, committing anonymous pages until LMK kills us.
    if (dialogueTypeIndex < 0 || dialogueTypeIndex >= dialogueTypeCount)
    {
        return -1;
    }

    s32 baseIndex = charDialogueSFXInfo[dialogueTypeIndex].charDialogueSFXIndex;
    s32 numRandom = charDialogueSFXInfo[dialogueTypeIndex].numRandomSFX;
    if (baseIndex < 0 || numRandom <= 0 || baseIndex >= dialogueSfxCount || numRandom > dialogueSfxCount - baseIndex)
    {
        return -1;
    }
"""

if source.count(bounds_anchor) != 2:
    raise SystemExit(f"Expected two random-dialogue range declarations, found {source.count(bounds_anchor)}")
source = source.replace(bounds_anchor, bounds_replacement)

unsafe_loop = """    for (int i = charDialogueSFX[baseIndex]; i < (int)charDialogueSFX[baseIndex + numRandom]; i++)
    {
        mCharSFX[i].m_unk_0x40 = false;
    }
"""

safe_loop = """    const int firstSfx = static_cast<int>(charDialogueSFX[baseIndex]);
    const int nextDialogueIndex = baseIndex + numRandom;
    int endSfxExclusive = 0;
    if (nextDialogueIndex < dialogueSfxCount)
    {
        // Preserve the original grouping behaviour when a following group is
        // present: its first SFX acts as the exclusive end sentinel.
        endSfxExclusive = static_cast<int>(charDialogueSFX[nextDialogueIndex]);
    }
    else
    {
        // Last group (CHAR_CLAP): there is no sentinel entry after the table.
        endSfxExclusive = static_cast<int>(charDialogueSFX[dialogueSfxCount - 1]) + 1;
    }

    if (firstSfx < 0 || firstSfx >= NUM_CHARSFX)
    {
        return -1;
    }
    if (endSfxExclusive < firstSfx)
    {
        endSfxExclusive = firstSfx;
    }
    if (endSfxExclusive > NUM_CHARSFX)
    {
        endSfxExclusive = NUM_CHARSFX;
    }

    for (int i = firstSfx; i < endSfxExclusive; i++)
    {
        mCharSFX[i].m_unk_0x40 = false;
    }
"""

if source.count(unsafe_loop) != 2:
    raise SystemExit(f"Expected two unsafe random-dialogue flag loops, found {source.count(unsafe_loop)}")
source = source.replace(unsafe_loop, safe_loop)

path.write_text(source)
print("Patched CharacterAudio.cpp random dialogue bounds (2 overloads)")
