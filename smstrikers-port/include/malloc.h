#ifndef PORT_MSL_MALLOC_H
#define PORT_MSL_MALLOC_H
// Port shim for MSL's <malloc.h>. Android/Bionic's <stdlib.h> itself includes
// <malloc.h>; because this shim is ahead of the NDK sysroot on the include path,
// including <stdlib.h> here recursively hides Bionic's malloc/calloc/free
// declarations. Skip to the next malloc.h on Android so Bionic can expose its
// real heap API, while preserving the original host behavior elsewhere.
#if defined(__ANDROID__)
#include_next <malloc.h>
#else
#include <stdlib.h>
#endif
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
void MallocInit(void* base, size_t len);
void MallocCleanup(void);
int  MallocIsInitalized(void);
void GetFreeArena(size_t* max_size, size_t* free_size, size_t* alloc_size);
int  CheckArena(void);
void DisplayArena(void);
#ifdef __cplusplus
}
#endif
#endif // PORT_MSL_MALLOC_H
