#include <jni.h>
#include <openssl/pem.h>
#include <openssl/x509v3.h>
#include <arpa/inet.h>
#include <climits>
#include <string>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <freerdp/freerdp.h>
#include <freerdp/error.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/gdi/gfx.h>
#include <freerdp/client/cmdline.h>
#include <freerdp/client/disp.h>
#include <freerdp/client/cliprdr.h>
#include <freerdp/event.h>
#include <freerdp/channels/channels.h>
#include <freerdp/channels/rdpdr.h>
#include <winpr/string.h>

#define LOG_TAG "FreeRDP-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#include <vector>
#include <unordered_map>
#include <mutex>
#include <algorithm>
#include <thread>
#include <winpr/synch.h>

#include <chrono>

#include <atomic>
#include "quest_audio.h"
#include "quest_camera.h"
#include <freerdp/client/rdpgfx.h>
#include <freerdp/codec/h264.h>

// ---- Ground-truth codec logging -------------------------------------------------------
// Wraps the GDI GFX SurfaceCommand handler so we log the codec the *server actually sends*,
// rather than echoing the settings we requested.

static const char* gfx_codec_name(UINT32 codecId) {
    switch (codecId) {
        case RDPGFX_CODECID_UNCOMPRESSED: return "UNCOMPRESSED";
        case RDPGFX_CODECID_CAVIDEO: return "RemoteFX(CAVIDEO)";
        case RDPGFX_CODECID_CLEARCODEC: return "ClearCodec";
        case RDPGFX_CODECID_PLANAR: return "Planar";
        case RDPGFX_CODECID_AVC444: return "AVC444(H.264)";
        case RDPGFX_CODECID_AVC420: return "AVC420(H.264)";
        case RDPGFX_CODECID_ALPHA: return "Alpha";
        case RDPGFX_CODECID_AVC444v2: return "AVC444v2(H.264)";
        case RDPGFX_CODECID_CAPROGRESSIVE: return "Progressive";
        case RDPGFX_CODECID_CAPROGRESSIVE_V2: return "ProgressiveV2";
        default: return "UNKNOWN";
    }
}

struct RdpContext {
    rdpContext _p;
    QuestAudioState* audio;
    QuestCameraState camera;
    JavaVM* javaVm;
    jobject certificateVerifier;
    jmethodID verifyCertificates;
    jobject eventReceiver;
    jmethodID disconnectedMethod;
    jmethodID clipboardMethod;
    jmethodID displayStartMethod;
    jmethodID displayStopMethod;
    std::string certificateHost;
    pcRdpgfxSurfaceCommand origSurfaceCommand;
    std::atomic<uint32_t> codecCounts[16];
    std::vector<ANativeWindow*> windows;
    std::mutex windowMutex;
    DispClientContext* disp;
    std::mutex displayMutex;
    bool displayReady;
    std::atomic<uint64_t> pendingDisplay;
    std::atomic<int> desktopWidth, desktopHeight;
    std::atomic<bool> network_running;
    std::thread network_thread;
    std::atomic<bool> render_running;
    std::thread render_thread;
    std::atomic<bool> dirty;
    std::atomic<bool> cameraRequested;
    std::atomic<bool> cameraConnected;
    std::atomic<bool> storageRequested;
    std::atomic<bool> storageConnected;
    std::string storagePath;
    CliprdrClientContext* cliprdr;
    std::mutex clipMutex;
    std::string clientClipText;
    std::atomic<bool> clipConnected;
    std::chrono::steady_clock::time_point connectTime;
};

static BOOL display_capture_start(void* owner, ANativeWindow* window) {
    auto* ctx = static_cast<RdpContext*>(owner);
    if (!ctx || !ctx->javaVm || !ctx->eventReceiver) return FALSE;
    JNIEnv* env = nullptr;
    bool attached = ctx->javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK;
    if (attached && ctx->javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return FALSE;
    jobject surface = window ? ANativeWindow_toSurface(env, window) : nullptr;
    jboolean result = (!window || surface) ? env->CallBooleanMethod(ctx->eventReceiver, ctx->displayStartMethod, surface) : JNI_FALSE;
    if (surface) env->DeleteLocalRef(surface);
    if (env->ExceptionCheck()) { env->ExceptionClear(); result = JNI_FALSE; LOGE("Display capture start failed"); }
    if (attached) ctx->javaVm->DetachCurrentThread();
    return result == JNI_TRUE;
}

static void display_capture_stop(void* owner) {
    auto* ctx = static_cast<RdpContext*>(owner);
    if (!ctx || !ctx->javaVm || !ctx->eventReceiver) return;
    JNIEnv* env = nullptr;
    bool attached = ctx->javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK;
    if (attached && ctx->javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
    env->CallVoidMethod(ctx->eventReceiver, ctx->displayStopMethod);
    if (env->ExceptionCheck()) { env->ExceptionClear(); LOGE("Display capture stop failed"); }
    if (attached) ctx->javaVm->DetachCurrentThread();
}

static void report_disconnect(freerdp* instance, bool established, const char* fallback = "") {
    auto* ctx = reinterpret_cast<RdpContext*>(instance->context);
    std::string reason;
    const UINT32 info = freerdp_error_info(instance);
    const UINT32 error = freerdp_get_last_error(instance->context);
    if (info != ERRINFO_SUCCESS) {
        const char* description = freerdp_get_error_info_string(info);
        if (description) reason = description;
    }
    if (reason.empty() && error != FREERDP_ERROR_SUCCESS) {
        const char* description = freerdp_get_last_error_string(error);
        if (description) reason = description;
    }
    if (reason.empty()) {
        switch (freerdp_get_disconnect_ultimatum(instance->context)) {
            case Disconnect_Ultimatum_provider_initiated: reason = "The remote server ended the connection."; break;
            case Disconnect_Ultimatum_user_requested: reason = "The remote session was disconnected by a user."; break;
            case Disconnect_Ultimatum_token_purged:
            case Disconnect_Ultimatum_channel_purged: reason = "The remote server removed the session channel."; break;
            default: reason = fallback; break;
        }
    }
    LOGI("RDP_DISCONNECTED: established=%d error=0x%08x info=0x%08x reason=%s", established, error, info, reason.c_str());
    if (!ctx->javaVm || !ctx->eventReceiver || !ctx->disconnectedMethod) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (ctx->javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (ctx->javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    jstring message = env->NewStringUTF(reason.c_str());
    if (message) {
        env->CallVoidMethod(ctx->eventReceiver, ctx->disconnectedMethod, message, established ? JNI_TRUE : JNI_FALSE);
        env->DeleteLocalRef(message);
    }
    if (env->ExceptionCheck()) { env->ExceptionClear(); LOGE("Cannot deliver disconnect notification"); }
    if (attached) ctx->javaVm->DetachCurrentThread();
}

static bool certificate_matches_host(const BYTE* data, size_t length, const char* host) {
    if (!data || length == 0 || length > INT_MAX || !host || !*host) return false;
    BIO* bio = BIO_new_mem_buf(data, static_cast<int>(length));
    if (!bio) return false;
    X509* certificate = PEM_read_bio_X509(bio, nullptr, nullptr, nullptr);
    BIO_free(bio);
    if (!certificate) return false;
    unsigned char address[16];
    bool ip = inet_pton(AF_INET, host, address) == 1 || inet_pton(AF_INET6, host, address) == 1;
    int match = ip ? X509_check_ip_asc(certificate, host, 0)
                   : X509_check_host(certificate, host, 0, X509_CHECK_FLAG_NO_PARTIAL_WILDCARDS, nullptr);
    X509_free(certificate);
    return match == 1;
}

static int client_verify_certificate(freerdp* instance, const BYTE* data, size_t length,
                                     const char*, UINT16, DWORD) {
    auto* ctx = reinterpret_cast<RdpContext*>(instance->context);
    if (!ctx->javaVm || !ctx->certificateVerifier || !ctx->verifyCertificates || length > INT_MAX) return 0;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (ctx->javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (ctx->javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return 0;
        attached = true;
    }
    jbyteArray pem = env->NewByteArray(static_cast<jsize>(length));
    jboolean accepted = JNI_FALSE;
    if (pem) {
        env->SetByteArrayRegion(pem, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte*>(data));
        if (!env->ExceptionCheck()) accepted = env->CallBooleanMethod(ctx->certificateVerifier,
            ctx->verifyCertificates, pem, certificate_matches_host(data, length, ctx->certificateHost.c_str()));
        env->DeleteLocalRef(pem);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        LOGE("Certificate verifier failed; rejecting connection");
        accepted = JNI_FALSE;
    }
    if (attached) ctx->javaVm->DetachCurrentThread();
    // Persistence belongs to the app, not FreeRDP's known_hosts file.
    return accepted == JNI_TRUE ? 2 : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_farside_workspace_FreeRDPBridge_certificateMatchesHost(JNIEnv* env, jobject, jbyteArray pem, jstring host) {
    if (!pem || !host) return JNI_FALSE;
    jbyte* data = env->GetByteArrayElements(pem, nullptr);
    const char* hostname = env->GetStringUTFChars(host, nullptr);
    bool match = data && hostname && certificate_matches_host(reinterpret_cast<BYTE*>(data), env->GetArrayLength(pem), hostname);
    if (data) env->ReleaseByteArrayElements(pem, data, JNI_ABORT);
    if (hostname) env->ReleaseStringUTFChars(host, hostname);
    return match ? JNI_TRUE : JNI_FALSE;
}

static UINT logging_surface_command(RdpgfxClientContext* context, const RDPGFX_SURFACE_COMMAND* cmd) {
    // GDI owns custom; all its callbacks expect an rdpGdi, not an RdpContext.
    auto* gdi = static_cast<rdpGdi*>(context->custom);
    auto* ctx = gdi ? reinterpret_cast<RdpContext*>(gdi->context) : nullptr;
    if (!ctx) {
        static std::atomic_flag missingContextLogged = ATOMIC_FLAG_INIT;
        if (!missingContextLogged.test_and_set())
            LOGE("RDP_CODEC: SurfaceCommand missing session context");
        return CHANNEL_RC_OK;
    }
    uint32_t slot = cmd->codecId & 0x0F;
    uint32_t n = ++ctx->codecCounts[slot];
    if (n == 1 || (n % 300) == 0) {
        LOGI("RDP_CODEC: server sent codec %s (0x%04x), count=%u, rect=%ux%u, session=%p",
             gfx_codec_name(cmd->codecId), cmd->codecId, n,
             cmd->right - cmd->left, cmd->bottom - cmd->top, static_cast<void*>(ctx));
    }
    return ctx->origSurfaceCommand ? ctx->origSurfaceCommand(context, cmd) : CHANNEL_RC_OK;
}


#include <freerdp/addin.h>

extern "C" UINT VCAPITYPE rdpgfx_DVCPluginEntry(PVOID pEntryPoints);
extern "C" UINT VCAPITYPE disp_DVCPluginEntry(PVOID pEntryPoints);
extern "C" UINT VCAPITYPE audin_DVCPluginEntry(PVOID pEntryPoints);
extern "C" UINT VCAPITYPE android_freerdp_rdpecam_client_subsystem_entry(PVOID pEntryPoints);
extern "C" BOOL VCAPITYPE rdpsnd_VirtualChannelEntryEx(PVOID, PVOID);
extern "C" BOOL VCAPITYPE rdpdr_VirtualChannelEntryEx(PVOID, PVOID);
extern "C" BOOL VCAPITYPE cliprdr_VirtualChannelEntryEx(PVOID, PVOID);
extern "C" UINT VCAPITYPE drive_DeviceServiceEntry(PDEVICE_SERVICE_ENTRY_POINTS);
extern "C" UINT cam_android_get_camera_count(void);
extern "C" void cam_android_set_max_dimension(int maxDim);
extern "C" BOOL cam_android_set_verification_pattern(BOOL enabled);
#ifndef NDEBUG
extern "C" BOOL cam_android_verify_isolation(QuestCameraState*, QuestCameraState*);
#endif
extern "C" BOOL quest_camera_encoder_register(JNIEnv* env);

QuestAudioState* quest_audio_state(rdpContext* context) { return ((RdpContext*)context)->audio; }

extern "C" JNIEXPORT jboolean JNICALL
Java_com_farside_workspace_QuestAudioVerification_verifyPlaybackTeardown(JNIEnv*, jclass) {
    return quest_audio_verify_playback_teardown() ? JNI_TRUE : JNI_FALSE;
}
extern "C" QuestCameraState* quest_camera_state(rdpContext* context) { return &((RdpContext*)context)->camera; }
extern "C" UINT VCAPITYPE quest_rdpecam_entry(IDRDYNVC_ENTRY_POINTS* entry) {
    if (!entry || !entry->GetRdpContext) return CHANNEL_RC_INITIALIZATION_ERROR;
    auto* context = entry->GetRdpContext(entry);
    if (!context) return CHANNEL_RC_INITIALIZATION_ERROR;
    return quest_rdpecam_entry_with_state(entry, quest_camera_state(context));
}
extern "C" BOOL VCAPITYPE drdynvc_VirtualChannelEntryEx(PVOID pInitHandle, PVOID pContext);

// Channels are compiled statically (BUILD_SHARED_LIBS OFF), so dlopen-based addin
// lookup cannot find them. This provider maps channel names to the linked entry points.
static PVOID my_addin_provider(LPCSTR pszName, LPCSTR pszSubsystem, LPCSTR pszType, DWORD dwFlags) {
    LOGI("my_addin_provider called for: %s (subsystem=%s, flags=0x%x)",
         pszName ? pszName : "NULL", pszSubsystem ? pszSubsystem : "NULL", dwFlags);
    if (pszName && pszSubsystem && strcmp(pszSubsystem, "quest") == 0) {
        if (strcmp(pszName, "rdpsnd") == 0) return (PVOID)quest_rdpsnd_entry;
        if (strcmp(pszName, "audin") == 0) return (PVOID)quest_audin_entry;
    }
    if (pszName && strcmp(pszName, "rdpecam") == 0) {
        if (pszSubsystem && strcmp(pszSubsystem, "android") == 0)
            return (PVOID)android_freerdp_rdpecam_client_subsystem_entry;
        return (PVOID)quest_rdpecam_entry;
    }
    if (pszName && strcmp(pszName, "audin") == 0) return (PVOID)audin_DVCPluginEntry;
    if (pszName && strcmp(pszName, "rdpsnd") == 0) return (PVOID)rdpsnd_VirtualChannelEntryEx;
    if (pszName && strcmp(pszName, "cliprdr") == 0) return (PVOID)cliprdr_VirtualChannelEntryEx;
    if (pszName && strcmp(pszName, "drdynvc") == 0) return (PVOID)drdynvc_VirtualChannelEntryEx;
    if (pszName && strcmp(pszName, "rdpgfx") == 0) return (PVOID)rdpgfx_DVCPluginEntry;
    if (pszName && strcmp(pszName, "disp") == 0) return (PVOID)disp_DVCPluginEntry;
    if (pszName && strcmp(pszName, "drive") == 0) return (PVOID)drive_DeviceServiceEntry;
    return nullptr;
}

static void add_dynamic_channel_once(rdpSettings* settings, const char* name) {
    if (freerdp_dynamic_channel_collection_find(settings, name)) return;
    const char* const params[] = { name };
    if (!freerdp_client_add_dynamic_channel(settings, 1, params)) {
        LOGE("Failed to add dynamic channel %s", name);
    }
}

// IMPORTANT: freerdp_connect() calls utils_reload_channels() *after* PreConnect, which frees
// context->channels and rebuilds it by invoking instance->LoadChannels. Static channels must
// therefore be loaded here, not in PreConnect (anything loaded there is discarded).
static BOOL client_load_channels(freerdp* instance) {
    int res = freerdp_channels_client_load_ex(instance->context->channels, instance->context->settings,
                                              (PVIRTUALCHANNELENTRYEX)drdynvc_VirtualChannelEntryEx,
                                              instance->context->settings);
    LOGI("RDP_CODEC: drdynvc static channel load res = %d (0 = ok)", res);
    if (res != 0) return FALSE;
    if (freerdp_channels_client_load_ex(instance->context->channels, instance->context->settings,
        (PVIRTUALCHANNELENTRYEX)rdpdr_VirtualChannelEntryEx, instance->context->settings) != 0) return FALSE;
    const auto* clipSettings = freerdp_static_channel_collection_find(instance->context->settings, "cliprdr");
    if (clipSettings) {
        if (freerdp_channels_client_load_ex(instance->context->channels, instance->context->settings,
            (PVIRTUALCHANNELENTRYEX)cliprdr_VirtualChannelEntryEx, (void*)clipSettings) != 0) return FALSE;
    }
    return freerdp_channels_client_load_ex(instance->context->channels, instance->context->settings,
        (PVIRTUALCHANNELENTRYEX)rdpsnd_VirtualChannelEntryEx,
        (void*)freerdp_static_channel_collection_find(instance->context->settings, "rdpsnd")) == 0;
}

static BOOL client_pre_connect(freerdp* instance) {
    rdpSettings* settings = instance->context->settings;

    // Configure settings
    freerdp_settings_set_bool(settings, FreeRDP_SoftwareGdi, TRUE);
    freerdp_settings_set_uint32(settings, FreeRDP_ColorDepth, 32);
    freerdp_settings_set_bool(settings, FreeRDP_IgnoreCertificate, FALSE);
    freerdp_settings_set_bool(settings, FreeRDP_ExternalCertificateManagement, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_RdpSecurity, FALSE); // Require certificate-backed TLS/NLA.
    
    freerdp_settings_set_bool(settings, FreeRDP_FastPathInput, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_FastPathOutput, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_RemoteFxCodec, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_SupportGraphicsPipeline, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_GfxProgressive, FALSE);
    freerdp_settings_set_bool(settings, FreeRDP_GfxThinClient, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_GfxSmallCache, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_GfxH264, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_GfxAVC444, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_GfxAVC444v2, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_SupportDynamicChannels, TRUE);
    
    freerdp_register_addin_provider((FREERDP_LOAD_CHANNEL_ADDIN_ENTRY_FN)my_addin_provider, 0);
    add_dynamic_channel_once(settings, "rdpgfx");
    add_dynamic_channel_once(settings, "disp");

    auto* ctx = (RdpContext*)instance->context;
    if (ctx && ctx->storageRequested) {
        const char* const args[] = { "Quest", ctx->storagePath.c_str() };
        RDPDR_DEVICE* dev = freerdp_device_new(RDPDR_DTYP_FILESYSTEM, 2, args);
        if (dev) {
            if (!freerdp_device_collection_add(settings, dev)) {
                LOGE("RDP_STORAGE: freerdp_device_collection_add failed");
                freerdp_device_free(dev);
            } else {
                LOGI("RDP_STORAGE: Added redirected drive Quest -> %s", ctx->storagePath.c_str());
            }
        } else {
            LOGE("RDP_STORAGE: freerdp_device_new failed");
        }
    }
    
    return TRUE;
}


static BOOL client_end_paint(rdpContext* context) {
    auto* ctx = (RdpContext*)context;
    auto* gdi = context->gdi;
    if (gdi && gdi->primary && gdi->primary->hdc && gdi->primary->hdc->hwnd) {
        auto* hwnd = gdi->primary->hdc->hwnd;
        if (hwnd->ninvalid > 0 || !hwnd->invalid->null) ctx->dirty = true;
        hwnd->invalid->null = TRUE;
        hwnd->ninvalid = 0;
    }
    return TRUE;
}

static BOOL client_desktop_resize(rdpContext* context) {
    auto* ctx = (RdpContext*)context;
    int desktopWidth = freerdp_settings_get_uint32(context->settings, FreeRDP_DesktopWidth);
    int desktopHeight = freerdp_settings_get_uint32(context->settings, FreeRDP_DesktopHeight);
    
    if (!context->gdi || !gdi_resize(context->gdi, desktopWidth, desktopHeight))
        return FALSE;
    ctx->desktopWidth = desktopWidth;
    ctx->desktopHeight = desktopHeight;
    ctx->dirty = true;
    LOGI("RDP_SURFACE: desktop resized to %dx%d", desktopWidth, desktopHeight);
    return TRUE;
}

static BOOL client_post_connect(freerdp* instance) {
    LOGI("Post connect! RDP_AUDIO capture=%d playback=%d",
         freerdp_settings_get_bool(instance->context->settings, FreeRDP_AudioCapture),
         freerdp_settings_get_bool(instance->context->settings, FreeRDP_AudioPlayback));
    
    BOOL rfx = freerdp_settings_get_bool(instance->context->settings, FreeRDP_RemoteFxCodec);
    BOOL gfxH264 = freerdp_settings_get_bool(instance->context->settings, FreeRDP_GfxH264);
    BOOL gfxAVC444 = freerdp_settings_get_bool(instance->context->settings, FreeRDP_GfxAVC444);
    BOOL fastPathIn = freerdp_settings_get_bool(instance->context->settings, FreeRDP_FastPathInput);
    BOOL fastPathOut = freerdp_settings_get_bool(instance->context->settings, FreeRDP_FastPathOutput);
    
    LOGI("Negotiated Codecs -> RFX: %d, GfxH264: %d, GfxAVC444: %d", rfx, gfxH264, gfxAVC444);
    LOGI("Negotiated FastPath -> In: %d, Out: %d", fastPathIn, fastPathOut);
    if (!gdi_init(instance, PIXEL_FORMAT_RGBA32)) {
        LOGE("Failed to init GDI");
        return FALSE;
    }
    
    instance->context->update->EndPaint = client_end_paint;
    instance->context->update->DesktopResize = client_desktop_resize;
    
    auto* ctx = (RdpContext*)instance->context;
    ctx->render_running = true;
    ctx->desktopWidth = instance->context->gdi->width;
    ctx->desktopHeight = instance->context->gdi->height;
    ctx->render_thread = std::thread([ctx, instance]() {
        std::vector<uint8_t> pixels;
        std::unordered_map<ANativeWindow*, std::pair<int, int>> bufferSizes;
        auto nextFrame = std::chrono::steady_clock::now();
        auto statsStart = nextFrame;
        unsigned frames = 0;
        double totalMs = 0, maxMs = 0;
        while (ctx->render_running) {
            // Pace from frame start: a 6ms blit must not add 6ms to every 16ms interval.
            std::this_thread::sleep_until(nextFrame);
            nextFrame = std::chrono::steady_clock::now() + std::chrono::milliseconds(16);
            if (!ctx->dirty.load()) continue;
            const auto start = std::chrono::steady_clock::now();

            // FreeRDP's paint/resize lock protects only the CPU snapshot. Never hold it
            // while Android waits for a compositor buffer or while posting a frame.
            rdp_update_lock(instance->context->update);
            rdpGdi* gdi = instance->context->gdi;
            const int width = gdi ? gdi->width : 0;
            const int height = gdi ? gdi->height : 0;
            if (!gdi || !gdi->primary_buffer || width <= 0 || height <= 0) {
                rdp_update_unlock(instance->context->update);
                continue;
            }
            pixels.resize(static_cast<size_t>(width) * height * 4);
            for (int y = 0; y < height; ++y)
                memcpy(pixels.data() + static_cast<size_t>(y) * width * 4,
                       gdi->primary_buffer + static_cast<size_t>(y) * gdi->stride, width * 4);
            ctx->dirty = false;
            rdp_update_unlock(instance->context->update);


            {
                std::lock_guard<std::mutex> lock(ctx->windowMutex);
                for (auto* window : ctx->windows) {
                    if (!window) continue;
                    // getWidth/getHeight report the consumer's default, which can change
                    // before the producer buffer size. Track our actual geometry requests.
                    auto& configured = bufferSizes[window];
                    if (configured.first != width || configured.second != height) {
                        if (ANativeWindow_setBuffersGeometry(window, width, height, WINDOW_FORMAT_RGBA_8888) != 0)
                            continue;
                        configured = {width, height};
                        LOGI("RDP_BUFFER: configured %dx%d", width, height);
                    }
                    ANativeWindow_Buffer buffer;
                    if (ANativeWindow_lock(window, &buffer, nullptr) != 0) continue;
                    // A consumer resize can race the producer geometry update. Fill the
                    // whole destination even for that transitional buffer.
                    if (buffer.width == width && buffer.height == height) {
                        for (int y = 0; y < height; ++y)
                            memcpy(static_cast<uint8_t*>(buffer.bits) + static_cast<size_t>(y) * buffer.stride * 4,
                                   pixels.data() + static_cast<size_t>(y) * width * 4, width * 4);
                    } else {
                        configured = {0, 0};
                        // Our FreeRDP build has no swscale/Cairo scaler. Fill a rare
                        // transitional buffer directly, then reapply geometry next frame.
                        for (int y = 0; y < buffer.height; ++y) {
                            const int srcY = static_cast<int>(static_cast<int64_t>(y) * height / buffer.height);
                            auto* dst = static_cast<uint8_t*>(buffer.bits) + static_cast<size_t>(y) * buffer.stride * 4;
                            for (int x = 0; x < buffer.width; ++x) {
                                const int srcX = static_cast<int>(static_cast<int64_t>(x) * width / buffer.width);
                                memcpy(dst + x * 4, pixels.data() + (static_cast<size_t>(srcY) * width + srcX) * 4, 4);
                            }
                        }
                        ctx->dirty = true;
                    }
                    ANativeWindow_unlockAndPost(window);
                }
            }
            const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
            totalMs += ms;
            maxMs = std::max(maxMs, ms);
            frames++;
            if (std::chrono::steady_clock::now() - statsStart >= std::chrono::seconds(3)) {
                LOGI("RDP_RENDER: frames=%u snapshot+post avg=%.2fms max=%.2fms desktop=%dx%d", frames, totalMs / frames, maxMs, width, height);
                statsStart = std::chrono::steady_clock::now();
                frames = 0;
                totalMs = maxMs = 0;
            }
        }
    });
    
    return TRUE;
}

static void destroy_client(freerdp* instance, bool connected) {
    auto* ctx = (RdpContext*)instance->context;
    ctx->network_running = false;
    if (ctx->network_thread.joinable()) ctx->network_thread.join();
    ctx->render_running = false;
    if (ctx->render_thread.joinable()) ctx->render_thread.join();
    if (connected) freerdp_disconnect(instance);
    if (instance->context->gdi) gdi_free(instance);
    // Windows in ctx->windows are owned references (see connect() and setSurface()).
    for (auto* win : ctx->windows) if (win) ANativeWindow_release(win);
    JNIEnv* env = nullptr;
    if (ctx->javaVm && ctx->javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK && ctx->certificateVerifier)
        env->DeleteGlobalRef(ctx->certificateVerifier);
    if (env && ctx->eventReceiver) env->DeleteGlobalRef(ctx->eventReceiver);
    ctx->eventReceiver = nullptr;
    // Explicit destructors below pair with the placement-new constructions in
    // Java_com_farside_workspace_FreeRDPBridge_connect(); keep the two lists in sync.
    ctx->certificateHost.~basic_string();
    ctx->storagePath.~basic_string();
    ctx->windows.~vector();
    ctx->windowMutex.~mutex();
    ctx->displayMutex.~mutex();
    ctx->pendingDisplay.~atomic();
    ctx->desktopWidth.~atomic();
    ctx->desktopHeight.~atomic();
    ctx->network_running.~atomic();
    ctx->network_thread.~thread();
    ctx->render_running.~atomic();
    ctx->dirty.~atomic();
    ctx->cameraRequested.~atomic();
    ctx->cameraConnected.~atomic();
    ctx->storageRequested.~atomic();
    ctx->storageConnected.~atomic();
    ctx->cliprdr = nullptr;
    ctx->clipConnected.~atomic();
    ctx->clipMutex.~mutex();
    ctx->clientClipText.~basic_string();
    for (auto& count : ctx->codecCounts) count.~atomic();
    auto* audio = ctx->audio;
    ctx->render_thread.~thread();
    freerdp_context_free(instance);
    delete audio;
    freerdp_free(instance);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_farside_workspace_FreeRDPBridge_connect(
        JNIEnv* env,
        jobject bridge,
        jstring hostname,
        jint port,
        jstring username,
        jstring password,
        jstring remoteApp,
        jboolean adminMode,
        jboolean enableCamera,
        jboolean enableMicrophone,
        jboolean enableStorage,
        jobject surface,
        jstring certificateHost,
        jobject certificateVerifier,
        jstring proxyHost,
        jint proxyPort,
        jstring proxyUser,
        jstring proxyPassword) {
        
    if (!getenv("HOME")) {
        setenv("HOME", "/data/data/com.farside.workspace/files", 1);
    }
    
    if (enableCamera && !quest_camera_encoder_register(env)) return 0;
    LOGI("freerdp_new called");
    freerdp* instance = freerdp_new();
    if (!instance) {
        LOGE("freerdp_new failed");
        return 0;
    }
    
    instance->PreConnect = client_pre_connect;
    instance->PostConnect = client_post_connect;
    instance->LoadChannels = client_load_channels;
    instance->VerifyX509Certificate = client_verify_certificate;
    instance->ContextSize = sizeof(RdpContext);
    
    LOGI("freerdp_context_new called");
    if (!freerdp_context_new(instance)) {
        LOGE("freerdp_context_new failed");
        freerdp_free(instance);
        return 0;
    }
    
    LOGI("freerdp_context_new succeeded");
    auto* ctx = (RdpContext*)instance->context;
    
    if (!ctx) {
        LOGE("Context is NULL");
        return 0;
    }
    
    ctx->javaVm = nullptr;
    env->GetJavaVM(&ctx->javaVm);
    ctx->certificateVerifier = certificateVerifier ? env->NewGlobalRef(certificateVerifier) : nullptr;
    ctx->eventReceiver = env->NewGlobalRef(bridge);
    jclass bridgeClass = env->GetObjectClass(bridge);
    ctx->disconnectedMethod = env->GetMethodID(bridgeClass, "onNativeDisconnected", "(Ljava/lang/String;Z)V");
    ctx->clipboardMethod = env->GetMethodID(bridgeClass, "onNativeClipboardChanged", "(Ljava/lang/String;)V");
    ctx->displayStartMethod = env->GetMethodID(bridgeClass, "onNativeDisplayStart", "(Landroid/view/Surface;)Z");
    ctx->displayStopMethod = env->GetMethodID(bridgeClass, "onNativeDisplayStop", "()V");
    env->DeleteLocalRef(bridgeClass);
    ctx->verifyCertificates = nullptr;
    if (certificateVerifier) {
        jclass verifierClass = env->GetObjectClass(certificateVerifier);
        ctx->verifyCertificates = env->GetMethodID(verifierClass, "verifyCertificates", "([BZ)Z");
        env->DeleteLocalRef(verifierClass);
    }
    const char* certificateHostChars = certificateHost ? env->GetStringUTFChars(certificateHost, nullptr) : nullptr;
    // Placement-new below: each member constructed here is destroyed explicitly in destroy_client()
    // (before freerdp_context_free). Keep the two lists in sync.
    new (&ctx->certificateHost) std::string(certificateHostChars ? certificateHostChars : "");  // destroyed in destroy_client()
    if (certificateHostChars) env->ReleaseStringUTFChars(certificateHost, certificateHostChars);
    ctx->origSurfaceCommand = nullptr;
    for (auto& count : ctx->codecCounts) new (&count) std::atomic<uint32_t>(0);  // destroyed in destroy_client()
    ctx->audio = new QuestAudioState();
    new (&ctx->windows) std::vector<ANativeWindow*>();  // destroyed in destroy_client()
    new (&ctx->windowMutex) std::mutex();  // destroyed in destroy_client()
    new (&ctx->render_running) std::atomic<bool>(false);
    new (&ctx->dirty) std::atomic<bool>(false);
    new (&ctx->render_thread) std::thread();
    new (&ctx->network_thread) std::thread();
    new (&ctx->network_running) std::atomic<bool>(false);
    new (&ctx->displayMutex) std::mutex();
    new (&ctx->pendingDisplay) std::atomic<uint64_t>(0);
    new (&ctx->desktopWidth) std::atomic<int>(1920);
    new (&ctx->desktopHeight) std::atomic<int>(1080);
    ctx->displayReady = false;
    ctx->disp = nullptr;
    ctx->camera = {};
    ctx->camera.owner = ctx;
    ctx->camera.displayStart = display_capture_start;
    ctx->camera.displayStop = display_capture_stop;
    new (&ctx->cameraRequested) std::atomic<bool>(enableCamera == JNI_TRUE);
    new (&ctx->cameraConnected) std::atomic<bool>(false);  // destroyed in destroy_client()
    new (&ctx->storageRequested) std::atomic<bool>(enableStorage == JNI_TRUE);
    new (&ctx->storageConnected) std::atomic<bool>(false);  // destroyed in destroy_client()
    new (&ctx->storagePath) std::string("/sdcard");  // destroyed in destroy_client()
    ctx->cliprdr = nullptr;
    new (&ctx->clipMutex) std::mutex();  // destroyed in destroy_client()
    new (&ctx->clientClipText) std::string();  // destroyed in destroy_client()
    new (&ctx->clipConnected) std::atomic<bool>(false);  // destroyed in destroy_client()
    ctx->connectTime = std::chrono::steady_clock::now();
    
    if (surface) {
        // Display 0 window: ANativeWindow_fromSurface acquires a reference now owned by
        // ctx->windows. Released in destroy_client() or when setSurface() replaces it.
        ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
        ANativeWindow_setBuffersGeometry(win, 1920, 1080, WINDOW_FORMAT_RGBA_8888);
        ctx->windows.push_back(win);
    }
    
    LOGI("Getting strings");
    const char* host_c = env->GetStringUTFChars(hostname, 0);
    const char* user_c = env->GetStringUTFChars(username, 0);
    const char* pass_c = env->GetStringUTFChars(password, 0);
    
    LOGI("Setting freerdp settings");
    if (!instance->context->settings) {
        LOGE("Settings is NULL!");
        env->ReleaseStringUTFChars(hostname, host_c);
        env->ReleaseStringUTFChars(username, user_c);
        env->ReleaseStringUTFChars(password, pass_c);
        return 0;
    }
    freerdp_settings_set_string(instance->context->settings, FreeRDP_ServerHostname, host_c);
    freerdp_settings_set_uint32(instance->context->settings, FreeRDP_DesktopWidth, 1920);
    freerdp_settings_set_uint32(instance->context->settings, FreeRDP_DesktopHeight, 1080);
    freerdp_settings_set_uint32(instance->context->settings, FreeRDP_DesktopScaleFactor, 100);
    freerdp_settings_set_uint32(instance->context->settings, FreeRDP_DeviceScaleFactor, 100);
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_SupportDisplayControl, TRUE);
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_SupportDynamicChannels, TRUE);
    
    if (port > 0) {
        freerdp_settings_set_uint32(instance->context->settings, FreeRDP_ServerPort, port);
    }
    freerdp_settings_set_string(instance->context->settings, FreeRDP_Username, user_c);
    freerdp_settings_set_string(instance->context->settings, FreeRDP_Password, pass_c);
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_RestrictedAdminModeRequired, adminMode == JNI_TRUE);
    
    LOGI("Applying performance optimizations");
    const char* opt_args[] = {
        "freerdp",
        "+glyph-cache",
        "+bitmap-cache",
        "+offscreen-cache",
        "/gfx",
        "/rfx",
        "/bpp:32",
        "/compression",
        "/network:auto"
    };
    freerdp_client_settings_parse_command_line(instance->context->settings, 9, (char**)opt_args, FALSE);
    if (proxyHost && proxyPort > 0 && proxyPort <= 65535) {
        const char* proxy_host = env->GetStringUTFChars(proxyHost, nullptr);
        const char* proxy_user = proxyUser ? env->GetStringUTFChars(proxyUser, nullptr) : nullptr;
        const char* proxy_pass = proxyPassword ? env->GetStringUTFChars(proxyPassword, nullptr) : nullptr;
        freerdp_settings_set_uint32(instance->context->settings, FreeRDP_ProxyType, PROXY_TYPE_HTTP);
        freerdp_settings_set_string(instance->context->settings, FreeRDP_ProxyHostname, proxy_host);
        freerdp_settings_set_uint16(instance->context->settings, FreeRDP_ProxyPort, static_cast<UINT16>(proxyPort));
        if (proxy_user) freerdp_settings_set_string(instance->context->settings, FreeRDP_ProxyUsername, proxy_user);
        if (proxy_user) freerdp_settings_set_string(instance->context->settings, FreeRDP_ProxyPassword, proxy_pass ? proxy_pass : "");
        freerdp_settings_set_uint32(instance->context->settings, FreeRDP_TcpConnectTimeout, 10000);
        env->ReleaseStringUTFChars(proxyHost, proxy_host);
        if (proxy_user) env->ReleaseStringUTFChars(proxyUser, proxy_user);
        if (proxy_pass) env->ReleaseStringUTFChars(proxyPassword, proxy_pass);
    }
    
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_DeviceRedirection, TRUE);
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_RedirectClipboard, TRUE);
    const char* clipArgs[] = { "cliprdr" };
    freerdp_client_add_static_channel(instance->context->settings, 1, clipArgs);
    LOGI("RDP_CLIPBOARD: FreeRDP_RedirectClipboard set and cliprdr static channel registered");

    freerdp_settings_set_bool(instance->context->settings, FreeRDP_AudioPlayback, TRUE);
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_RemoteConsoleAudio, FALSE);
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_AudioCapture, enableMicrophone);
    // Keep stale-block recovery independent of compressed decoder history.
    const char* soundArgs[] = { "rdpsnd", "sys:quest", "format:1", "quality:dynamic" };
    freerdp_client_add_static_channel(instance->context->settings, 4, soundArgs);
    if (enableMicrophone) {
        const char* micArgs[] = { "audin", "sys:quest" };
        freerdp_client_add_dynamic_channel(instance->context->settings, 2, micArgs);
    }
    LOGI("RDP_AUDIO: playback enabled, microphone=%d", enableMicrophone);
    LOGI("Setting remote app");
    if (remoteApp && env->GetStringLength(remoteApp) > 0) {
        const char* app_c = env->GetStringUTFChars(remoteApp, 0);
        freerdp_settings_set_bool(instance->context->settings, FreeRDP_RemoteApplicationMode, TRUE);
        freerdp_settings_set_string(instance->context->settings, FreeRDP_RemoteApplicationProgram, app_c);
        env->ReleaseStringUTFChars(remoteApp, app_c);
    }

    if (enableCamera) {
        freerdp_settings_set_bool(instance->context->settings, FreeRDP_SupportDynamicChannels, TRUE);
        const char* camArgs[] = { "rdpecam" };
        freerdp_client_add_dynamic_channel(instance->context->settings, 1, camArgs);
        LOGI("RDP_CAMERA: rdpecam dynamic channel added");
    }
    
    env->ReleaseStringUTFChars(hostname, host_c);
    env->ReleaseStringUTFChars(username, user_c);
    env->ReleaseStringUTFChars(password, pass_c);
    
    PubSub_SubscribeChannelConnected(instance->context->pubSub, [](void* context, const ChannelConnectedEventArgs* e) {
        LOGI("RDP_CODEC: channel connected: %s", e->name);
        auto* ctx = (RdpContext*)context;
        if (strcmp(e->name, DISP_DVC_CHANNEL_NAME) == 0) {
            std::lock_guard<std::mutex> lock(ctx->displayMutex);
            ctx->disp = (DispClientContext*)e->pInterface;
            ctx->disp->custom = ctx;
            ctx->disp->DisplayControlCaps = [](DispClientContext* disp, UINT32, UINT32, UINT32) -> UINT {
                auto* ctx = static_cast<RdpContext*>(disp->custom);
                std::lock_guard<std::mutex> lock(ctx->displayMutex);
                ctx->displayReady = true;
                return CHANNEL_RC_OK;
            };
        } else if (strcmp(e->name, RDPGFX_DVC_CHANNEL_NAME) == 0) {
            auto* gfx = (RdpgfxClientContext*)e->pInterface;
            rdpGdi* gdi = ((rdpContext*)context)->gdi;
            if (gdi && gfx && gdi_graphics_pipeline_init(gdi, gfx)) {
                ctx->origSurfaceCommand = gfx->SurfaceCommand;
                gfx->SurfaceCommand = logging_surface_command;
                for (auto& c : ctx->codecCounts) c = 0;
                LOGI("RDP_CODEC: GFX pipeline initialized - server will use GFX codecs");
            } else {
                LOGE("RDP_CODEC: gdi_graphics_pipeline_init FAILED (gdi=%p gfx=%p)", gdi, gfx);
            }
        } else if (strcmp(e->name, "RDCamera_Device_Enumerator") == 0 || strcmp(e->name, "rdpecam") == 0) {
            LOGI("RDP_CAMERA: camera channel connected: %s", e->name);
            ctx->cameraConnected = true;
        } else if (strcmp(e->name, "rdpdr") == 0) {
            LOGI("RDP_STORAGE: rdpdr channel connected");
            ctx->storageConnected = true;
        } else if (strcmp(e->name, CLIPRDR_SVC_CHANNEL_NAME) == 0) {
            LOGI("RDP_CLIPBOARD: cliprdr channel connected");
            auto* clip = (CliprdrClientContext*)e->pInterface;
            ctx->cliprdr = clip;
            clip->custom = ctx;
            ctx->clipConnected = true;

            clip->MonitorReady = [](CliprdrClientContext* clip, const CLIPRDR_MONITOR_READY*) -> UINT {
                LOGI("RDP_CLIPBOARD: MonitorReady received");
                CLIPRDR_CAPABILITIES caps = {};
                CLIPRDR_GENERAL_CAPABILITY_SET gen = {};
                caps.cCapabilitiesSets = 1;
                caps.capabilitySets = (CLIPRDR_CAPABILITY_SET*)&gen;
                gen.capabilitySetType = CB_CAPSTYPE_GENERAL;
                gen.capabilitySetLength = CB_CAPSTYPE_GENERAL_LEN;
                gen.version = CB_CAPS_VERSION_2;
                gen.generalFlags = CB_USE_LONG_FORMAT_NAMES;
                UINT res = clip->ClientCapabilities ? clip->ClientCapabilities(clip, &caps) : CHANNEL_RC_OK;
                LOGI("RDP_CLIPBOARD: ClientCapabilities sent res=%u", res);

                auto* ctx = (RdpContext*)clip->custom;
                CLIPRDR_FORMAT formats[2] = {};
                formats[0].formatId = CF_UNICODETEXT;
                formats[0].formatName = nullptr;
                formats[1].formatId = CF_TEXT;
                formats[1].formatName = nullptr;
                CLIPRDR_FORMAT_LIST formatList = {};
                formatList.common.msgType = CB_FORMAT_LIST;
                formatList.numFormats = 2;
                formatList.formats = formats;
                res = clip->ClientFormatList ? clip->ClientFormatList(clip, &formatList) : CHANNEL_RC_OK;
                LOGI("RDP_CLIPBOARD: Initial ClientFormatList sent res=%u", res);
                return CHANNEL_RC_OK;
            };

            clip->ServerFormatList = [](CliprdrClientContext* clip, const CLIPRDR_FORMAT_LIST* formatList) -> UINT {
                LOGI("RDP_CLIPBOARD: ServerFormatList received with %u formats", formatList ? formatList->numFormats : 0);
                CLIPRDR_FORMAT_LIST_RESPONSE response = {};
                response.common.msgType = CB_FORMAT_LIST_RESPONSE;
                response.common.msgFlags = CB_RESPONSE_OK;
                if (clip->ClientFormatListResponse) {
                    clip->ClientFormatListResponse(clip, &response);
                }
                if (!formatList) return CHANNEL_RC_OK;

                bool requested = false;
                for (UINT32 i = 0; i < formatList->numFormats; ++i) {
                    if (formatList->formats[i].formatId == CF_UNICODETEXT) {
                        LOGI("RDP_CLIPBOARD: Requesting CF_UNICODETEXT from server");
                        CLIPRDR_FORMAT_DATA_REQUEST req = {};
                        req.common.msgType = CB_FORMAT_DATA_REQUEST;
                        req.requestedFormatId = CF_UNICODETEXT;
                        if (clip->ClientFormatDataRequest) clip->ClientFormatDataRequest(clip, &req);
                        requested = true;
                        break;
                    }
                }
                if (!requested) {
                    for (UINT32 i = 0; i < formatList->numFormats; ++i) {
                        if (formatList->formats[i].formatId == CF_TEXT) {
                            LOGI("RDP_CLIPBOARD: Requesting CF_TEXT from server");
                            CLIPRDR_FORMAT_DATA_REQUEST req = {};
                            req.common.msgType = CB_FORMAT_DATA_REQUEST;
                            req.requestedFormatId = CF_TEXT;
                            if (clip->ClientFormatDataRequest) clip->ClientFormatDataRequest(clip, &req);
                            break;
                        }
                    }
                }
                return CHANNEL_RC_OK;
            };

            clip->ServerFormatListResponse = [](CliprdrClientContext*, const CLIPRDR_FORMAT_LIST_RESPONSE* resp) -> UINT {
                LOGI("RDP_CLIPBOARD: ServerFormatListResponse flags=0x%04x", resp ? resp->common.msgFlags : 0);
                return CHANNEL_RC_OK;
            };

            clip->ServerFormatDataRequest = [](CliprdrClientContext* clip, const CLIPRDR_FORMAT_DATA_REQUEST* req) -> UINT {
                auto* ctx = (RdpContext*)clip->custom;
                LOGI("RDP_CLIPBOARD: ServerFormatDataRequest for format 0x%08x", req ? req->requestedFormatId : 0);
                if (!req || !clip->ClientFormatDataResponse) return CHANNEL_RC_OK;

                CLIPRDR_FORMAT_DATA_RESPONSE resp = {};
                resp.common.msgType = CB_FORMAT_DATA_RESPONSE;

                if (req->requestedFormatId == CF_UNICODETEXT) {
                    std::string text;
                    if (ctx) {
                        std::lock_guard<std::mutex> lock(ctx->clipMutex);
                        text = ctx->clientClipText;
                    }
                    // Normalize line endings to Windows CRLF for remote host
                    std::string crlfText;
                    crlfText.reserve(text.size() + 16);
                    for (size_t i = 0; i < text.size(); ++i) {
                        if (text[i] == '\n' && (i == 0 || text[i - 1] != '\r')) {
                            crlfText.push_back('\r');
                        }
                        crlfText.push_back(text[i]);
                    }
                    size_t wlen = 0;
                    WCHAR* wstr = ConvertUtf8ToWCharAlloc(crlfText.c_str(), &wlen);
                    if (wstr) {
                        resp.common.msgFlags = CB_RESPONSE_OK;
                        resp.common.dataLen = static_cast<UINT32>((wlen + 1) * sizeof(WCHAR));
                        resp.requestedFormatData = reinterpret_cast<const BYTE*>(wstr);
                        clip->ClientFormatDataResponse(clip, &resp);
                        free(wstr);
                    } else {
                        static const WCHAR nullTerm = 0;
                        resp.common.msgFlags = CB_RESPONSE_OK;
                        resp.common.dataLen = sizeof(WCHAR);
                        resp.requestedFormatData = reinterpret_cast<const BYTE*>(&nullTerm);
                        clip->ClientFormatDataResponse(clip, &resp);
                    }
                } else if (req->requestedFormatId == CF_TEXT) {
                    std::string text;
                    if (ctx) {
                        std::lock_guard<std::mutex> lock(ctx->clipMutex);
                        text = ctx->clientClipText;
                    }
                    std::string crlfText;
                    crlfText.reserve(text.size() + 16);
                    for (size_t i = 0; i < text.size(); ++i) {
                        if (text[i] == '\n' && (i == 0 || text[i - 1] != '\r')) {
                            crlfText.push_back('\r');
                        }
                        crlfText.push_back(text[i]);
                    }
                    crlfText.push_back('\0');
                    resp.common.msgFlags = CB_RESPONSE_OK;
                    resp.common.dataLen = static_cast<UINT32>(crlfText.size());
                    resp.requestedFormatData = reinterpret_cast<const BYTE*>(crlfText.data());
                    clip->ClientFormatDataResponse(clip, &resp);
                } else {
                    resp.common.msgFlags = CB_RESPONSE_FAIL;
                    resp.common.dataLen = 0;
                    resp.requestedFormatData = nullptr;
                    clip->ClientFormatDataResponse(clip, &resp);
                }
                return CHANNEL_RC_OK;
            };

            clip->ServerFormatDataResponse = [](CliprdrClientContext* clip, const CLIPRDR_FORMAT_DATA_RESPONSE* resp) -> UINT {
                auto* ctx = (RdpContext*)clip->custom;
                if (!resp || !ctx) return CHANNEL_RC_OK;
                LOGI("RDP_CLIPBOARD: ServerFormatDataResponse flags=0x%04x len=%u", resp->common.msgFlags, resp->common.dataLen);
                if ((resp->common.msgFlags & CB_RESPONSE_OK) && resp->requestedFormatData && resp->common.dataLen > 0) {
                    std::string utf8Text;
                    const BYTE* data = resp->requestedFormatData;
                    const UINT32 len = resp->common.dataLen;

                    // If it was UNICODETEXT:
                    if (len >= sizeof(WCHAR)) {
                        const WCHAR* wstr = reinterpret_cast<const WCHAR*>(data);
                        size_t wlen = len / sizeof(WCHAR);
                        size_t utf8Len = 0;
                        char* converted = ConvertWCharNToUtf8Alloc(wstr, wlen, &utf8Len);
                        if (converted) {
                            utf8Text.assign(converted, utf8Len);
                            free(converted);
                            while (!utf8Text.empty() && utf8Text.back() == '\0') {
                                utf8Text.pop_back();
                            }
                        }
                    }
                    if (utf8Text.empty() && len > 0) {
                        // Fallback as raw ascii/utf8
                        utf8Text.assign(reinterpret_cast<const char*>(data), len);
                        while (!utf8Text.empty() && utf8Text.back() == '\0') {
                            utf8Text.pop_back();
                        }
                    }

                    if (!utf8Text.empty()) {
                        LOGI("RDP_CLIPBOARD: Converted remote clipboard text len=%zu: '%s'", utf8Text.size(), utf8Text.substr(0, 50).c_str());
                        {
                            std::lock_guard<std::mutex> lock(ctx->clipMutex);
                            ctx->clientClipText = utf8Text;
                        }
                        if (ctx->javaVm && ctx->eventReceiver && ctx->clipboardMethod) {
                            JNIEnv* env = nullptr;
                            bool attached = false;
                            if (ctx->javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
                                if (ctx->javaVm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                                    attached = true;
                                }
                            }
                            if (env) {
                                jstring jtext = env->NewStringUTF(utf8Text.c_str());
                                if (jtext) {
                                    env->CallVoidMethod(ctx->eventReceiver, ctx->clipboardMethod, jtext);
                                    env->DeleteLocalRef(jtext);
                                }
                                if (env->ExceptionCheck()) {
                                    env->ExceptionClear();
                                    LOGE("RDP_CLIPBOARD: Exception calling onNativeClipboardChanged");
                                }
                                if (attached) ctx->javaVm->DetachCurrentThread();
                            }
                        }
                    }
                }
                return CHANNEL_RC_OK;
            };
        }
    });

    PubSub_SubscribeChannelDisconnected(instance->context->pubSub, [](void* context, const ChannelDisconnectedEventArgs* e) {
        auto* ctx = (RdpContext*)context;
        if (strcmp(e->name, DISP_DVC_CHANNEL_NAME) == 0) {
            std::lock_guard<std::mutex> lock(ctx->displayMutex);
            ctx->disp = nullptr;
            ctx->displayReady = false;
        } else if (strcmp(e->name, RDPGFX_DVC_CHANNEL_NAME) == 0) {
            auto* gfx = (RdpgfxClientContext*)e->pInterface;
            rdpGdi* gdi = ((rdpContext*)context)->gdi;
            if (gdi && gfx) gdi_graphics_pipeline_uninit(gdi, gfx);
            ctx->origSurfaceCommand = nullptr;
        } else if (strcmp(e->name, "RDCamera_Device_Enumerator") == 0 || strcmp(e->name, "rdpecam") == 0) {
            LOGI("RDP_CAMERA: camera channel disconnected: %s", e->name);
            ctx->cameraConnected = false;
        } else if (strcmp(e->name, "rdpdr") == 0) {
            LOGI("RDP_STORAGE: rdpdr channel disconnected");
            ctx->storageConnected = false;
        } else if (strcmp(e->name, CLIPRDR_SVC_CHANNEL_NAME) == 0) {
            LOGI("RDP_CLIPBOARD: cliprdr channel disconnected");
            ctx->clipConnected = false;
            ctx->cliprdr = nullptr;
        }
    });
    
    LOGI("Connecting...");
    if (!freerdp_connect(instance)) {
        LOGE("Connection failed");
        report_disconnect(instance, false);
        destroy_client(instance, false);
        return 0;
    }
    
    LOGI("Connection succeeded");
    
    ctx->network_running = true;
    ctx->network_thread = std::thread([instance, ctx]() {
        DWORD status;
        DWORD count;
        HANDLE handles[64];
        const char* endReason = "";
        
        while (ctx->network_running && !freerdp_shall_disconnect_context(instance->context)) {
            {
                std::lock_guard<std::mutex> lock(ctx->displayMutex);
                if (ctx->disp && ctx->displayReady) {
                    const uint64_t pending = ctx->pendingDisplay.exchange(0);
                    if (pending) {
                        DISPLAY_CONTROL_MONITOR_LAYOUT layout = {};
                        layout.Flags = DISPLAY_CONTROL_MONITOR_PRIMARY;
                        layout.Width = pending >> 32;
                        layout.Height = pending & 0xFFFFFFFF;
                        layout.DesktopScaleFactor = layout.DeviceScaleFactor = 100;
                        const UINT result = ctx->disp->SendMonitorLayout(ctx->disp, 1, &layout);
                        LOGI("RDP_SURFACE: requested desktop %ux%u result=%u", layout.Width, layout.Height, result);
                    }
                }
            }
            count = freerdp_get_event_handles(instance->context, handles, 64);
            if (count == 0) {
                LOGE("Failed to get FreeRDP event handles");
                endReason = "The RDP connection no longer has an active network channel.";
                break;
            }
            status = WaitForMultipleObjects(count, handles, FALSE, 100);
            if (status == WAIT_FAILED) {
                LOGE("WaitForMultipleObjects failed");
                endReason = "An error occurred while waiting for network data.";
                break;
            }
            if (!freerdp_check_event_handles(instance->context)) {
                LOGE("Failed to check FreeRDP event handles");
                endReason = "The connection to the remote PC was lost.";
                break;
            }
        }
        if (ctx->network_running) {
            ctx->render_running = false;
            report_disconnect(instance, true, endReason);
        }
        LOGI("Disconnecting event loop");
    });

    return (jlong)instance;
}

extern "C" JNIEXPORT void JNICALL
Java_com_farside_workspace_FreeRDPBridge_disconnect(
        JNIEnv* env,
        jobject /* this */,
        jlong instance_ptr) {
    if (!instance_ptr) return;
    
    destroy_client((freerdp*)instance_ptr, true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_farside_workspace_FreeRDPBridge_setSurface(
        JNIEnv* env, jobject, jlong instance_ptr, jobject surface) {
    if (!instance_ptr) return;
    auto* ctx = (RdpContext*)((freerdp*)instance_ptr)->context;
    // Ownership: ctx->windows owns one reference per ANativeWindow* (each acquired via
    // ANativeWindow_fromSurface, which adds a reference). `replacement` is a new reference
    // transferred into ctx->windows below. References are released in exactly two places:
    // here (all current windows released before replacement) and destroy_client().
    // addSurface() pushes further owned references; removeSurface() is a no-op, so those
    // are only released here or in destroy_client(). The connect() path pushes display 0's
    // initial window; if connect() returns early, nothing releases it (see PROGRESS.md).
    ANativeWindow* replacement = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    std::lock_guard<std::mutex> lock(ctx->windowMutex);
    for (auto* window : ctx->windows) if (window) ANativeWindow_release(window);
    ctx->windows.clear();
    if (replacement) {
        ANativeWindow_setBuffersGeometry(replacement, ctx->desktopWidth, ctx->desktopHeight,
                                         WINDOW_FORMAT_RGBA_8888);
        ctx->windows.push_back(replacement);
    }
    ctx->dirty = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_farside_workspace_FreeRDPBridge_addSurface(
        JNIEnv* env,
        jobject /* this */,
        jlong instance_ptr,
        jobject surface) {
    if (!instance_ptr || !surface) return;
    
    freerdp* instance = (freerdp*)instance_ptr;
    auto* ctx = (RdpContext*)instance->context;
    
    std::lock_guard<std::mutex> lock(ctx->windowMutex);
    ctx->windows.push_back(ANativeWindow_fromSurface(env, surface));
}

extern "C" JNIEXPORT void JNICALL
Java_com_farside_workspace_FreeRDPBridge_removeSurface(
        JNIEnv* env,
        jobject /* this */,
        jlong instance_ptr,
        jobject surface) {
    // Note: It's hard to match `jobject surface` to `ANativeWindow*` directly without keeping a map.
    // In practice, Android cleans up the window automatically, or we can just pop_back if we simplify.
    // For a robust implementation, we'd store a map of jobject global refs to ANativeWindow.
    // Since this is a spike for multiple sessions, we'll keep it simple: we won't manually release them
    // here, they will be released on disconnect.
}

extern "C" JNIEXPORT void JNICALL
Java_com_farside_workspace_FreeRDPBridge_sendMouseEvent(
        JNIEnv* env,
        jobject /* this */,
        jlong instance_ptr,
        jint x,
        jint y,
        jint flags) {
    if (!instance_ptr) return;
    
    freerdp* instance = (freerdp*)instance_ptr;
    rdpInput* input = instance->context->input;
    if (input) {
        input->MouseEvent(input, flags, x, y);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_farside_workspace_FreeRDPBridge_updateDisplay(
        JNIEnv* env,
        jobject /* this */,
        jlong instance_ptr,
        jint width,
        jint height,
        jfloat zoom) {
    if (!instance_ptr) return;
    
    freerdp* instance = (freerdp*)instance_ptr;
    auto* ctx = (RdpContext*)instance->context;
    
    // Debounced by Compose. The network thread sends after display capabilities arrive.
    const uint32_t w = std::clamp(static_cast<int>(width), 200, 8192) & ~1;
    const uint32_t h = std::clamp(static_cast<int>(height), 200, 8192) & ~1;
    ctx->pendingDisplay = (static_cast<uint64_t>(w) << 32) | h;
    ctx->dirty = true;
}
extern "C" JNIEXPORT void JNICALL
Java_com_farside_workspace_FreeRDPBridge_sendKeyEvent(
        JNIEnv* env,
        jobject /* this */,
        jlong instance_ptr,
        jint scancode,
        jboolean isDown) {
    if (!instance_ptr) return;
    
    freerdp* instance = (freerdp*)instance_ptr;
    rdpInput* input = instance->context->input;
    if (input) {
        UINT16 flags = isDown ? KBD_FLAGS_DOWN : KBD_FLAGS_RELEASE;
        if (scancode & 0x100) {
            flags |= KBD_FLAGS_EXTENDED;
            scancode &= 0xFF;
        }
        input->KeyboardEvent(input, flags, scancode);
    }
}
extern "C" JNIEXPORT void JNICALL
Java_com_farside_workspace_FreeRDPBridge_sendUnicodeKeyEvent(
        JNIEnv* env,
        jobject /* this */,
        jlong instance_ptr,
        jint unicode_char,
        jboolean isDown) {
    if (!instance_ptr) return;
    
    freerdp* instance = (freerdp*)instance_ptr;
    rdpInput* input = instance->context->input;
    if (input) {
        UINT16 flags = isDown ? KBD_FLAGS_DOWN : KBD_FLAGS_RELEASE;
        input->UnicodeKeyboardEvent(input, flags, unicode_char);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_farside_workspace_FreeRDPBridge_getDesktopWidth(
        JNIEnv* env,
        jobject /* this */,
        jlong instance_ptr) {
    if (!instance_ptr) return 1920;
    freerdp* instance = (freerdp*)instance_ptr;
    return ((RdpContext*)instance->context)->desktopWidth.load();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_farside_workspace_FreeRDPBridge_getDesktopHeight(
        JNIEnv* env,
        jobject /* this */,
        jlong instance_ptr) {
    if (!instance_ptr) return 1080;
    freerdp* instance = (freerdp*)instance_ptr;
    return ((RdpContext*)instance->context)->desktopHeight.load();
}

extern "C" JNIEXPORT void JNICALL
Java_com_farside_workspace_FreeRDPBridge_setAudioMuted(JNIEnv*, jobject, jlong instance, jboolean speaker, jboolean mic) {
    if (!instance) return;
    auto* state = quest_audio_state(((freerdp*)instance)->context);
    state->speakerMuted = speaker;
    state->micMuted = mic;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_farside_workspace_FreeRDPBridge_isMicrophoneRecording(JNIEnv*, jobject, jlong instance) {
    return instance && quest_audio_state(((freerdp*)instance)->context)->recording;
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_farside_workspace_FreeRDPBridge_getAudioFrameCounts(JNIEnv* env, jobject, jlong instance) {
    jlong counts[4] = {};
    if (instance) {
        auto* state = quest_audio_state(((freerdp*)instance)->context);
        counts[0] = state->playbackFrames.load(); counts[1] = state->captureFrames.load();
        counts[2] = state->playbackNonzeroSamples.load(); counts[3] = state->captureNonzeroSamples.load();
    }
    auto result = env->NewLongArray(4);
    env->SetLongArrayRegion(result, 0, 4, counts);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_farside_workspace_FreeRDPBridge_setCameraMuted(JNIEnv*, jobject, jlong instance, jboolean muted) {
    if (instance) InterlockedExchange(&quest_camera_state(((freerdp*)instance)->context)->muted, muted == JNI_TRUE);
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_farside_workspace_FreeRDPBridge_getAudioLatencyMetrics(JNIEnv* env, jobject, jlong instance) {
    jlong metrics[2]{};
    if (instance) {
        auto* state = quest_audio_state(((freerdp*)instance)->context);
        metrics[0] = state->outputLatencyMs; metrics[1] = state->maxOutputLatencyMs;
    }
    auto result = env->NewLongArray(2);
    env->SetLongArrayRegion(result, 0, 2, metrics);
    return result;
}
extern "C" JNIEXPORT void JNICALL
Java_com_farside_workspace_FreeRDPBridge_stallAudioForVerification(JNIEnv*, jobject, jlong instance) {
#ifndef NDEBUG
    if (instance) quest_audio_state(((freerdp*)instance)->context)->stallNextPlaybackMs = 1500;
#endif
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_farside_workspace_FreeRDPBridge_getAudioPulseTimes(JNIEnv* env, jobject, jlong instance) {
    jlong pulses[128]{};
    unsigned length = 0;
    if (instance) {
        auto* state = quest_audio_state(((freerdp*)instance)->context);
        const auto count = state->pulseCount.load();
        length = std::min(count, 128u);
        for (unsigned i = 0; i < length; ++i) pulses[i] = state->pulseTimesMs[(count - length + i) % 128];
    }
    auto result = env->NewLongArray(length);
    env->SetLongArrayRegion(result, 0, length, pulses);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_farside_workspace_FreeRDPBridge_isCameraRecording(JNIEnv*, jobject, jlong instance) {
    return instance && quest_camera_is_recording(quest_camera_state(((freerdp*)instance)->context));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_farside_workspace_FreeRDPBridge_isCameraMuted(JNIEnv*, jobject, jlong instance) {
    return instance && quest_camera_is_muted(quest_camera_state(((freerdp*)instance)->context));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_farside_workspace_QuestCameraVerification_verifySessionIsolation(JNIEnv* env, jclass) {
#ifndef NDEBUG
    RdpContext firstContext{}, secondContext{};
    freerdp first{}, second{};
    first.context = &firstContext._p;
    second.context = &secondContext._p;
    const jlong firstId = reinterpret_cast<jlong>(&first);
    const jlong secondId = reinterpret_cast<jlong>(&second);
    Java_com_farside_workspace_FreeRDPBridge_setCameraMuted(env, nullptr, firstId, JNI_TRUE);
    if (!Java_com_farside_workspace_FreeRDPBridge_isCameraMuted(env, nullptr, firstId) ||
        Java_com_farside_workspace_FreeRDPBridge_isCameraMuted(env, nullptr, secondId)) return JNI_FALSE;
    const BOOL result = cam_android_verify_isolation(&firstContext.camera, &secondContext.camera);
    Java_com_farside_workspace_FreeRDPBridge_setCameraMuted(env, nullptr, secondId, JNI_TRUE);
    Java_com_farside_workspace_FreeRDPBridge_setCameraMuted(env, nullptr, firstId, JNI_FALSE);
    return result && !Java_com_farside_workspace_FreeRDPBridge_isCameraMuted(env, nullptr, firstId) &&
        Java_com_farside_workspace_FreeRDPBridge_isCameraMuted(env, nullptr, secondId);
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_farside_workspace_FreeRDPBridge_setCameraMaxDimension(JNIEnv*, jobject, jint maxDim) {
    cam_android_set_max_dimension(maxDim);
}

extern "C" BOOL cam_android_verify_display_conversion(void);
extern "C" JNIEXPORT jboolean JNICALL
Java_com_farside_workspace_QuestDisplayVerification_verifyConversion(JNIEnv*, jclass) {
    return cam_android_verify_display_conversion();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_farside_workspace_FreeRDPBridge_setCameraVerificationPattern(JNIEnv*, jobject, jboolean enabled) {
    return cam_android_set_verification_pattern(enabled) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_farside_workspace_QuestCameraPlaneCopy_copyNv12(
    JNIEnv* env, jclass, jobject source, jint width, jint height,
    jobject yBuffer, jint yRow, jint yPixel, jobject uBuffer, jint uRow, jint uPixel,
    jobject vBuffer, jint vRow, jint vPixel) {
    if (width <= 0 || height <= 0 || (width & 1) || (height & 1)) return JNI_FALSE;
    auto* src = static_cast<const uint8_t*>(env->GetDirectBufferAddress(source));
    const size_t ySize = static_cast<size_t>(width) * height;
    if (!src || env->GetDirectBufferCapacity(source) < static_cast<jlong>(ySize + ySize / 2))
        return JNI_FALSE;
    struct Plane { jobject buffer; int row; int pixel; int width; int height; } planes[] = {
        {yBuffer, yRow, yPixel, width, height},
        {uBuffer, uRow, uPixel, width / 2, height / 2},
        {vBuffer, vRow, vPixel, width / 2, height / 2}
    };
    uint8_t* destinations[3] = {};
    for (int i = 0; i < 3; i++) {
        const auto& p = planes[i];
        destinations[i] = static_cast<uint8_t*>(env->GetDirectBufferAddress(p.buffer));
        if (!destinations[i] || p.row <= 0 || p.pixel <= 0 ||
            static_cast<int64_t>(p.row) < static_cast<int64_t>(p.width - 1) * p.pixel + 1)
            return JNI_FALSE;
        const int64_t required = static_cast<int64_t>(p.height - 1) * p.row +
            static_cast<int64_t>(p.width - 1) * p.pixel + 1;
        if (env->GetDirectBufferCapacity(p.buffer) < required) return JNI_FALSE;
    }
    for (int row = 0; row < height; row++) {
        auto* dst = destinations[0] + static_cast<size_t>(row) * yRow;
        if (yPixel == 1) memcpy(dst, src + static_cast<size_t>(row) * width, width);
        else for (int col = 0; col < width; col++)
            dst[static_cast<size_t>(col) * yPixel] = src[static_cast<size_t>(row) * width + col];
    }
    // Write each chroma plane using its own offset/stride. U/V may be planar or alias.
    for (int plane = 1; plane < 3; plane++) {
        const auto& p = planes[plane];
        for (int row = 0; row < p.height; row++) {
            auto* dst = destinations[plane] + static_cast<size_t>(row) * p.row;
            const auto* uv = src + ySize + static_cast<size_t>(row) * width + (plane - 1);
            for (int col = 0; col < p.width; col++) dst[static_cast<size_t>(col) * p.pixel] = uv[col * 2];
        }
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_farside_workspace_FreeRDPBridge_isCameraUnsupported(JNIEnv*, jobject, jlong instance) {
    if (!instance) return JNI_FALSE;
    auto* ctx = reinterpret_cast<RdpContext*>(((freerdp*)instance)->context);
    if (!ctx || !ctx->cameraRequested.load()) return JNI_FALSE;
    if (ctx->cameraConnected.load()) return JNI_FALSE;
    auto elapsed = std::chrono::steady_clock::now() - ctx->connectTime;
    if (elapsed > std::chrono::seconds(4)) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_farside_workspace_FreeRDPBridge_isStorageConnected(JNIEnv*, jobject, jlong instance) {
    if (!instance) return JNI_FALSE;
    auto* ctx = reinterpret_cast<RdpContext*>(((freerdp*)instance)->context);
    if (!ctx || !ctx->storageRequested.load()) return JNI_FALSE;
    return ctx->storageConnected.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_farside_workspace_FreeRDPBridge_isClipboardConnected(JNIEnv*, jobject, jlong instance) {
    if (!instance) return JNI_FALSE;
    auto* ctx = reinterpret_cast<RdpContext*>(((freerdp*)instance)->context);
    return (ctx && ctx->clipConnected.load()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_farside_workspace_FreeRDPBridge_sendClipboardText(JNIEnv* env, jobject, jlong instance, jstring text) {
    if (!instance || !text) return JNI_FALSE;
    auto* ctx = reinterpret_cast<RdpContext*>(((freerdp*)instance)->context);
    if (!ctx) return JNI_FALSE;

    const char* utf8Chars = env->GetStringUTFChars(text, nullptr);
    if (!utf8Chars) return JNI_FALSE;
    {
        std::lock_guard<std::mutex> lock(ctx->clipMutex);
        ctx->clientClipText = utf8Chars;
    }
    env->ReleaseStringUTFChars(text, utf8Chars);

    if (ctx->cliprdr && ctx->clipConnected.load() && ctx->cliprdr->ClientFormatList) {
        CLIPRDR_FORMAT formats[2] = {};
        formats[0].formatId = CF_UNICODETEXT;
        formats[0].formatName = nullptr;
        formats[1].formatId = CF_TEXT;
        formats[1].formatName = nullptr;

        CLIPRDR_FORMAT_LIST formatList = {};
        formatList.common.msgType = CB_FORMAT_LIST;
        formatList.numFormats = 2;
        formatList.formats = formats;

        UINT res = ctx->cliprdr->ClientFormatList(ctx->cliprdr, &formatList);
        LOGI("RDP_CLIPBOARD: sendClipboardText sent ClientFormatList res=%u", res);
        return res == CHANNEL_RC_OK ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

