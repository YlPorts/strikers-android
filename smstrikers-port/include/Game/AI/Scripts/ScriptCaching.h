#ifndef _SCRIPTCACHING_H_
#define _SCRIPTCACHING_H_

#include "NL/nlSingleton.h"
#include "NL/nlAVLTree.h"
#include "Game/AI/FuzzyVariant.h"
#include "Game/AI/Scripts/ScriptQuestionKey.h"
#include <string.h>

// The original Metrowerks tree implementation owns its own pair type. Android's
// libc++ also exposes std::pair through the __ndk1 inline namespace, so remap
// only while parsing this legacy header. The macro is immediately removed and
// cannot leak into normal libc++ headers or game code.
#if defined(__ANDROID__)
#define pair msl_pair
#endif
#include "PowerPC_EABI_Support/MSL_C++/MSL_Common/msl_tree.h"
#if defined(__ANDROID__)
#undef pair
#endif

extern unsigned char g_bScriptQuestionCachingOn;
extern unsigned char g_bScriptQuestionCachingUseSTD;

// PORT: keep the Android-only Metrowerks pair workaround for libc++ compatibility.
#if defined(__ANDROID__)
typedef std::msl_pair<const ScriptQuestionKey, FuzzyVariant> ScriptCachePair;
#else
typedef std::pair<const ScriptQuestionKey, FuzzyVariant> ScriptCachePair;
#endif
typedef std::map<ScriptQuestionKey, FuzzyVariant, std::less<ScriptQuestionKey>, std::allocator<ScriptCachePair> > ScriptCacheMap;
typedef std::__tree<ScriptCachePair, ScriptCacheMap::value_compare, std::allocator<ScriptCachePair> > ScriptCacheTree;

inline ScriptQuestionKey MakeScriptQuestionKey(uintptr_t question, const Variant& argument)
{
    ScriptQuestionKey key;
    key.question = question;
    key.tag = (int)argument.mType;
    if (argument.mType == FT_VECTOR)
    {
        // Variant::Reset initializes all three vector floats; preserve their raw bits.
        u32 x, y, z;
        memcpy(&x, &argument.mData.vector.x, sizeof x);
        memcpy(&y, &argument.mData.vector.y, sizeof y);
        memcpy(&z, &argument.mData.vector.z, sizeof z);
        key.subjectLo = ((uintptr_t)y << 32) | (uintptr_t)x;
        key.subjectHi = (uintptr_t)z;
    }
    else
    {
        key.subjectLo = argument.mData.u;
        key.subjectHi = 0;
    }
    return key;
}

class ScriptQuestionCache : public nlSingleton<ScriptQuestionCache>
{
public:
    static ScriptQuestionCache* const* InstanceStorage() { return &s_pInstance; }

    ScriptQuestionCache()
        : mQuestionCacheMap(16, 16)
    {
    }

    ~ScriptQuestionCache();
    unsigned char Lookup(const ScriptQuestionKey& hash, FuzzyVariant& returnVal, const char* name)
    {
        struct MapNodeBase
        {
            void* left;
            void* right;
            void* parent;
        };

        struct MapTree
        {
            unsigned long x0;
            MapNodeBase x4;
        };

        struct MapNode
        {
            MapNodeBase base;
            ScriptQuestionKey key;
            FuzzyVariant value;
        };

        FuzzyVariant* pValue;

        mTotalLookups++;

        if (g_bScriptQuestionCachingUseSTD)
        {
            MapNode* stdFound = (MapNode*)mQuestionCacheMapSTD.find(hash).ptr_;
            if ((MapNodeBase*)stdFound != &((MapTree*)&mQuestionCacheMapSTD)->x4)
            {
                mCacheHits++;
                returnVal = stdFound->value;
                return 1;
            }
        }
        else if (mQuestionCacheMap.FindGet(hash, &pValue))
        {
            mCacheHits++;
            returnVal = *pValue;
            return 1;
        }

        return 0;
    }
    const FuzzyVariant& AddToCache(const ScriptQuestionKey&, const FuzzyVariant&, const char*);
    void Clear();

    /* 0x00 */ nlAVLTreeSlotPool<ScriptQuestionKey, FuzzyVariant, DefaultKeyCompare<ScriptQuestionKey> > mQuestionCacheMap;
    /* 0x28 */ ScriptCacheMap mQuestionCacheMapSTD;
    /* 0x38 */ int mTotalLookups;
    /* 0x3C */ int mCacheHits;
}; // total size: 0x40

inline ScriptQuestionCache::~ScriptQuestionCache()
{
    Clear();
}

inline void ScriptQuestionCache::Clear()
{
    mQuestionCacheMap.Clear();
    mQuestionCacheMapSTD.tree_.clear();
    mCacheHits = 0;
    mTotalLookups = 0;
}
inline const FuzzyVariant& ScriptQuestionCache::AddToCache(
    const ScriptQuestionKey& key, const FuzzyVariant& variant, const char* name)
{
    if (g_bScriptQuestionCachingOn)
    {
        const FuzzyVariant& cacheValue = variant;
        if (g_bScriptQuestionCachingUseSTD)
        {
            mQuestionCacheMapSTD.tree_.find_or_insert<ScriptQuestionKey, FuzzyVariant>(key).second = cacheValue;
        }
        else
        {
            mQuestionCacheMap.Add(key, cacheValue);
        }
    }
    return variant;
}
#endif // _SCRIPTCACHING_H_
