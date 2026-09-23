#ifndef _SCRIPTQUESTIONKEY_H_
#define _SCRIPTQUESTIONKEY_H_

#include "types.h"

// Keep the question and full subject value separate so equal sums or lossy
// Variant::GetHash results cannot make unrelated AI questions share a cache entry.
struct ScriptQuestionKey
{
    uintptr_t question;
    uintptr_t subjectLo;
    uintptr_t subjectHi;
    int tag;

    bool operator==(const ScriptQuestionKey& other) const
    {
        return question == other.question && subjectLo == other.subjectLo
            && subjectHi == other.subjectHi && tag == other.tag;
    }

    bool operator<(const ScriptQuestionKey& other) const
    {
        if (question != other.question)
            return question < other.question;
        if (subjectLo != other.subjectLo)
            return subjectLo < other.subjectLo;
        if (subjectHi != other.subjectHi)
            return subjectHi < other.subjectHi;
        return tag < other.tag;
    }
};

#endif // _SCRIPTQUESTIONKEY_H_
