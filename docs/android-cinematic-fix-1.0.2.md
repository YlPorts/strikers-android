# Strikers Android 1.0.2 — cinematic stability

Android keeps the full match/stadium data resident. This hotfix does not restore sector-based map streaming.

The NIS cinematic player previously used the GameCube asynchronous virtual-memory read path. A cinematic reset/transition could recycle its scratch buffer while an Android disc-image read was still completing, allowing a late callback to write into memory already reused by another NIS. Symptoms included cut/stuttering intro or post-match cinematics and crashes that varied by captain/sequence (for example Wario intro and Mario post-game).

On Android only, NIS virtual-memory reads are now completed deterministically into their final aligned destination before the NIS callback is allowed to advance the cinematic state. Other game/map loading paths remain unchanged.

Version: 1.0.2 (versionCode 102).
