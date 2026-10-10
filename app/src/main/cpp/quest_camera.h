#pragma once

#include <freerdp/dvc.h>
#include <winpr/interlocked.h>

/* Owned by the RDP context; outlives its channel HAL and capture callbacks. */
struct ANativeWindow;
typedef struct
{
    volatile LONG muted;
    volatile LONG activeStreams;
    void* owner;
    BOOL (*displayStart)(void* owner, struct ANativeWindow* window);
    void (*displayStop)(void* owner);
} QuestCameraState;

#ifdef __cplusplus
extern "C" {
#endif
QuestCameraState* quest_camera_state(rdpContext* context);
UINT VCAPITYPE quest_rdpecam_entry(IDRDYNVC_ENTRY_POINTS* entry);
UINT VCAPITYPE quest_rdpecam_entry_with_state(IDRDYNVC_ENTRY_POINTS* entry, QuestCameraState* state);
#ifdef __cplusplus
}
#endif

static inline BOOL quest_display_start(QuestCameraState* state, struct ANativeWindow* window)
{
    return state && state->displayStart && state->displayStart(state->owner, window);
}
static inline void quest_display_stop(QuestCameraState* state)
{
    if (state && state->displayStop) state->displayStop(state->owner);
}

static inline BOOL quest_camera_is_muted(QuestCameraState* state)
{
    return state && InterlockedCompareExchange(&state->muted, 0, 0) != 0;
}

static inline BOOL quest_camera_is_recording(QuestCameraState* state)
{
    return state && InterlockedCompareExchange(&state->activeStreams, 0, 0) > 0;
}
