// Android PCM devices for FreeRDP's rdpsnd/audin channels. FreeRDP owns the protocol and DSP.
#include "quest_audio.h"
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <thread>
#include <vector>
#include <mutex>

static bool supported(const AUDIO_FORMAT* f) {
    return f && f->wFormatTag == WAVE_FORMAT_PCM && f->wBitsPerSample == 16 &&
           f->nChannels >= 1 && f->nChannels <= 2 &&
           f->nSamplesPerSec >= 8000 && f->nSamplesPerSec <= 96000;
}
static AAudioStream* open_stream(const AUDIO_FORMAT* f, aaudio_direction_t direction) {
    AAudioStreamBuilder* b = nullptr;
    AAudioStream* stream = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return nullptr;
    AAudioStreamBuilder_setDirection(b, direction);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setSampleRate(b, f->nSamplesPerSec);
    AAudioStreamBuilder_setChannelCount(b, f->nChannels);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSessionId(b, AAUDIO_SESSION_ID_ALLOCATE);
    if (direction == AAUDIO_DIRECTION_INPUT)
        AAudioStreamBuilder_setInputPreset(b, AAUDIO_INPUT_PRESET_VOICE_RECOGNITION);
    const auto result = AAudioStreamBuilder_openStream(b, &stream);
    AAudioStreamBuilder_delete(b);
    if (result != AAUDIO_OK) {
        __android_log_print(ANDROID_LOG_ERROR, "QuestAudio", "open %s failed: %s",
            direction == AAUDIO_DIRECTION_INPUT ? "capture" : "playback", AAudio_convertResultToText(result));
        return nullptr;
    }
    if (direction == AAUDIO_DIRECTION_OUTPUT)
        AAudioStream_setBufferSizeInFrames(stream, AAudioStream_getFramesPerBurst(stream) * 2);
    if (AAudioStream_requestStart(stream) != AAUDIO_OK) {
        AAudioStream_close(stream);
        return nullptr;
    }
    __android_log_print(ANDROID_LOG_INFO, "QuestAudio", "opened %s %d Hz/%d channels",
        direction == AAUDIO_DIRECTION_INPUT ? "capture" : "playback", f->nSamplesPerSec, f->nChannels);
    return stream;
}
struct Speaker {
    rdpsndDevicePlugin iface{};
    QuestAudioState* state = nullptr;
    AAudioStream* stream = nullptr;
    unsigned channels = 2;
    UINT32 volume = 0xffffffff;
    // FreeRDP can close on the channel thread while its playback worker writes.
    std::recursive_mutex lock;
    bool pulseActive = false;
    unsigned quietFrames = 0;
};
static void speaker_close(rdpsndDevicePlugin* p) {
    auto* d = reinterpret_cast<Speaker*>(p);
    std::lock_guard<std::recursive_mutex> guard(d->lock);
    if (d->stream) { AAudioStream_close(d->stream); d->stream = nullptr; }
}
static Speaker* create_speaker(QuestAudioState* state) {
    auto* d = new Speaker();
    d->state = state;
    d->iface.FormatSupported = [](rdpsndDevicePlugin*, const AUDIO_FORMAT* f) -> BOOL { return supported(f); };
    d->iface.Open = [](rdpsndDevicePlugin* p, const AUDIO_FORMAT* f, UINT32) -> BOOL {
        auto* d = reinterpret_cast<Speaker*>(p);
        std::lock_guard<std::recursive_mutex> guard(d->lock);
        speaker_close(p);
        if (!supported(f)) return FALSE;
        d->channels = f->nChannels;
        d->stream = open_stream(f, AAUDIO_DIRECTION_OUTPUT);
        return d->stream != nullptr;
    };
    d->iface.GetVolume = [](rdpsndDevicePlugin* p) {
        auto* d = reinterpret_cast<Speaker*>(p);
        std::lock_guard<std::recursive_mutex> guard(d->lock);
        return d->volume;
    };
    d->iface.SetVolume = [](rdpsndDevicePlugin* p, UINT32 v) -> BOOL {
        auto* d = reinterpret_cast<Speaker*>(p);
        std::lock_guard<std::recursive_mutex> guard(d->lock);
        d->volume = v; return TRUE;
    };
    d->iface.Play = [](rdpsndDevicePlugin* p, const BYTE* data, size_t size) -> UINT {
        auto* d = reinterpret_cast<Speaker*>(p);
        std::lock_guard<std::recursive_mutex> guard(d->lock);
        if (!d->stream) return 0;
#ifndef NDEBUG
        const auto stall = d->state->stallNextPlaybackMs.exchange(0);
        if (stall) std::this_thread::sleep_for(std::chrono::milliseconds(stall));
#endif
        const auto* samples = reinterpret_cast<const int16_t*>(data);
        std::vector<int16_t> pcm(size / 2);
        for (size_t i = 0; i < pcm.size(); ++i) {
            const unsigned level = (d->volume >> ((i % d->channels) * 16)) & 0xffff;
            pcm[i] = d->state->speakerMuted ? 0 : static_cast<int16_t>((int32_t(samples[i]) * level) / 65535);
        }
        const int frames = pcm.size() / d->channels;
#ifndef NDEBUG
        const auto firstWrittenFrame = AAudioStream_getFramesWritten(d->stream);
        std::vector<int> pulses;
        for (int frame = 0; frame < frames; ++frame) {
            bool loud = false;
            for (unsigned channel = 0; channel < d->channels; ++channel)
                loud |= std::abs(int(pcm[frame * d->channels + channel])) > 256;
            if (loud) {
                if (!d->pulseActive) pulses.push_back(frame);
                d->pulseActive = true; d->quietFrames = 0;
            } else if (++d->quietFrames > unsigned(AAudioStream_getSampleRate(d->stream) / 50)) d->pulseActive = false;
        }
#endif
        int offset = 0;
        while (offset < frames) {
            int n = AAudioStream_write(d->stream, pcm.data() + offset * d->channels, frames - offset, 100000000);
            if (n <= 0) break;
            unsigned nonzero = 0;
            for (int i = offset * d->channels; i < (offset + n) * d->channels; ++i)
                if (pcm[i] != 0) ++nonzero;
            d->state->playbackNonzeroSamples += nonzero;
            offset += n;
            d->state->playbackFrames += n;
        }
        const auto queued = std::max<int64_t>(0, AAudioStream_getFramesWritten(d->stream) - AAudioStream_getFramesRead(d->stream));
        const auto latency = queued * 1000 / AAudioStream_getSampleRate(d->stream);
        d->state->outputLatencyMs = latency;
        auto maximum = d->state->maxOutputLatencyMs.load();
        while (latency > maximum && !d->state->maxOutputLatencyMs.compare_exchange_weak(maximum, latency)) {}
#ifndef NDEBUG
        int64_t position = 0, timeNs = 0;
        if (AAudioStream_getTimestamp(d->stream, CLOCK_MONOTONIC, &position, &timeNs) == AAUDIO_OK) {
            for (int frame : pulses) if (frame < offset) {
                const auto presentedMs = (timeNs + (firstWrittenFrame + frame - position) * 1000000000LL / AAudioStream_getSampleRate(d->stream)) / 1000000;
                const auto index = d->state->pulseCount.load();
                d->state->pulseTimesMs[index % 128] = presentedMs;
                d->state->pulseCount = index + 1;
            }
        }
#endif
        // rdpsnd uses this value in WaveConfirm to report actual render latency.
        return static_cast<UINT>(latency);
    };
    d->iface.Close = speaker_close;
    d->iface.Free = [](rdpsndDevicePlugin* p) { speaker_close(p); delete reinterpret_cast<Speaker*>(p); };
    return d;
}
extern "C" UINT VCAPITYPE quest_rdpsnd_entry(PFREERDP_RDPSND_DEVICE_ENTRY_POINTS e) {
    auto* d = create_speaker(quest_audio_state(freerdp_rdpsnd_get_context(e->rdpsnd)));
    e->pRegisterRdpsndDevice(e->rdpsnd, &d->iface);
    return CHANNEL_RC_OK;
}

bool quest_audio_verify_playback_teardown() {
#ifndef NDEBUG
    QuestAudioState state;
    auto* d = create_speaker(&state);
    AUDIO_FORMAT format{};
    format.wFormatTag = WAVE_FORMAT_PCM;
    format.wBitsPerSample = 16;
    format.nChannels = 2;
    format.nSamplesPerSec = 48000;
    if (!d->iface.Open(&d->iface, &format, 0)) { d->iface.Free(&d->iface); return false; }
    std::atomic<bool> running{true};
    std::thread writer([&] {
        const int16_t silence[960]{};
        while (running) {
            d->iface.Play(&d->iface, reinterpret_cast<const BYTE*>(silence), sizeof(silence));
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    });
    bool opened = true;
    for (int i = 0; i < 30; ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
        d->iface.Close(&d->iface);
        if (!d->iface.Open(&d->iface, &format, 0)) { opened = false; break; }
    }
    running = false;
    writer.join();
    d->iface.Free(&d->iface);
    return opened && state.playbackFrames > 0;
#else
    return false;
#endif
}
struct Microphone {
    IAudinDevice iface{};
    QuestAudioState* state = nullptr;
    AUDIO_FORMAT format{};
    AAudioStream* stream = nullptr;
    std::atomic<bool> running{false};
    std::thread worker;
    UINT32 frames = 960;
};
static UINT mic_close(IAudinDevice* p) {
    auto* d = reinterpret_cast<Microphone*>(p);
    d->running = false;
    if (d->worker.joinable()) d->worker.join();
    if (d->stream) { AAudioStream_close(d->stream); d->stream = nullptr; }
    d->state->recording = false;
    return CHANNEL_RC_OK;
}
extern "C" UINT VCAPITYPE quest_audin_entry(PFREERDP_AUDIN_DEVICE_ENTRY_POINTS e) {
    auto* d = new Microphone();
    d->state = quest_audio_state(e->rdpcontext);
    d->iface.FormatSupported = [](IAudinDevice*, const AUDIO_FORMAT* f) -> BOOL { return supported(f); };
    d->iface.SetFormat = [](IAudinDevice* p, const AUDIO_FORMAT* f, UINT32 frames) -> UINT {
        auto* d = reinterpret_cast<Microphone*>(p);
        mic_close(p);
        if (!supported(f)) return ERROR_NOT_SUPPORTED;
        d->format = *f;
        d->format.data = nullptr;
        d->frames = std::clamp<UINT32>(frames, 1, f->nSamplesPerSec / 10);
        return CHANNEL_RC_OK;
    };
    d->iface.Open = [](IAudinDevice* p, AudinReceive receive, void* user) -> UINT {
        auto* d = reinterpret_cast<Microphone*>(p);
        mic_close(p);
        d->stream = open_stream(&d->format, AAUDIO_DIRECTION_INPUT);
        if (!d->stream) return CHANNEL_RC_OK; // permission revocation/device failure: continue without capture
        d->running = true;
        d->worker = std::thread([d, receive, user] {
            std::vector<int16_t> pcm(d->frames * d->format.nChannels);
            while (d->running) {
                int n = AAudioStream_read(d->stream, pcm.data(), d->frames, 100000000);
                if (n < 0) break;
                if (n == 0) continue;
                d->state->recording = true;
                d->state->captureFrames += n;
                if (d->state->micMuted) std::fill(pcm.begin(), pcm.end(), 0);
                unsigned nonzero = 0;
                for (int i = 0; i < n * d->format.nChannels; ++i) if (pcm[i] != 0) ++nonzero;
                d->state->captureNonzeroSamples += nonzero;
                if (receive(&d->format, reinterpret_cast<BYTE*>(pcm.data()), n * d->format.nChannels * 2, user) != CHANNEL_RC_OK) break;
            }
            d->state->recording = false;
        });
        return CHANNEL_RC_OK;
    };
    d->iface.Close = mic_close;
    d->iface.Free = [](IAudinDevice* p) -> UINT { mic_close(p); delete reinterpret_cast<Microphone*>(p); return CHANNEL_RC_OK; };
    const UINT result = e->pRegisterAudinDevice(e->plugin, &d->iface);
    if (result != CHANNEL_RC_OK) delete d;
    return result;
}
