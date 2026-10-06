#pragma once
#include <atomic>
#include <freerdp/client/audin.h>
#include <freerdp/client/rdpsnd.h>

struct QuestAudioState {
    std::atomic<bool> speakerMuted{false};
    std::atomic<bool> micMuted{false};
    std::atomic<bool> recording{false};
    std::atomic<unsigned long long> playbackFrames{0}, captureFrames{0};
    std::atomic<unsigned long long> playbackNonzeroSamples{0}, captureNonzeroSamples{0};
};
QuestAudioState* quest_audio_state(rdpContext* context);
extern "C" UINT VCAPITYPE quest_rdpsnd_entry(PFREERDP_RDPSND_DEVICE_ENTRY_POINTS entry);
extern "C" UINT VCAPITYPE quest_audin_entry(PFREERDP_AUDIN_DEVICE_ENTRY_POINTS entry);
