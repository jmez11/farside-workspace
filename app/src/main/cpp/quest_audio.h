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
    std::atomic<unsigned long long> outputLatencyMs{0}, maxOutputLatencyMs{0};
    std::atomic<unsigned> pulseCount{0};
    std::atomic<unsigned> stallNextPlaybackMs{0}; // debug-only backlog recovery probe
    std::atomic<unsigned long long> pulseTimesMs[128]{};
};
QuestAudioState* quest_audio_state(rdpContext* context);
bool quest_audio_verify_playback_teardown();
extern "C" UINT VCAPITYPE quest_rdpsnd_entry(PFREERDP_RDPSND_DEVICE_ENTRY_POINTS entry);
extern "C" UINT VCAPITYPE quest_audin_entry(PFREERDP_AUDIN_DEVICE_ENTRY_POINTS entry);
