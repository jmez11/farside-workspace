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
#include <freerdp/event.h>
#include <freerdp/channels/channels.h>

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
        case RDPGFX_CODECID_AVC420: return "AVC420(H.264)";
        case RDPGFX_CODECID_ALPHA: return "Alpha";
        case RDPGFX_CODECID_AVC444: return "AVC444(H.264)";
        case RDPGFX_CODECID_AVC444v2: return "AVC444v2(H.264)";
        case RDPGFX_CODECID_CAPROGRESSIVE: return "Progressive";
        case RDPGFX_CODECID_CAPROGRESSIVE_V2: return "ProgressiveV2";
        default: return "UNKNOWN";
    }
}

struct RdpContext {
    rdpContext _p;
    QuestAudioState* audio;
    JavaVM* javaVm;
    jobject certificateVerifier;
    jmethodID verifyCertificates;
    jobject eventReceiver;
    jmethodID disconnectedMethod;
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
};

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
extern "C" BOOL VCAPITYPE rdpsnd_VirtualChannelEntryEx(PVOID, PVOID);
extern "C" BOOL VCAPITYPE rdpdr_VirtualChannelEntryEx(PVOID, PVOID);

QuestAudioState* quest_audio_state(rdpContext* context) { return ((RdpContext*)context)->audio; }
extern "C" BOOL VCAPITYPE drdynvc_VirtualChannelEntryEx(PVOID pInitHandle, PVOID pContext);

// Channels are compiled statically (BUILD_SHARED_LIBS OFF), so dlopen-based addin
// lookup cannot find them. This provider maps channel names to the linked entry points.
static PVOID my_addin_provider(LPCSTR pszName, LPCSTR pszSubsystem, LPCSTR pszType, DWORD dwFlags) {
    LOGI("my_addin_provider called for: %s (flags=0x%x)", pszName ? pszName : "NULL", dwFlags);
    if (pszName && pszSubsystem && strcmp(pszSubsystem, "quest") == 0) {
        if (strcmp(pszName, "rdpsnd") == 0) return (PVOID)quest_rdpsnd_entry;
        if (strcmp(pszName, "audin") == 0) return (PVOID)quest_audin_entry;
    }
    if (pszName && strcmp(pszName, "audin") == 0) return (PVOID)audin_DVCPluginEntry;
    if (pszName && strcmp(pszName, "rdpsnd") == 0) return (PVOID)rdpsnd_VirtualChannelEntryEx;
    if (pszName && strcmp(pszName, "drdynvc") == 0) return (PVOID)drdynvc_VirtualChannelEntryEx;
    if (pszName && strcmp(pszName, "rdpgfx") == 0) return (PVOID)rdpgfx_DVCPluginEntry;
    if (pszName && strcmp(pszName, "disp") == 0) return (PVOID)disp_DVCPluginEntry;
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
    return freerdp_channels_client_load_ex(instance->context->channels, instance->context->settings,
        (PVIRTUALCHANNELENTRYEX)rdpsnd_VirtualChannelEntryEx,
        freerdp_static_channel_collection_find(instance->context->settings, "rdpsnd")) == 0;
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
    for (auto* win : ctx->windows) if (win) ANativeWindow_release(win);
    JNIEnv* env = nullptr;
    if (ctx->javaVm && ctx->javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK && ctx->certificateVerifier)
        env->DeleteGlobalRef(ctx->certificateVerifier);
    if (env && ctx->eventReceiver) env->DeleteGlobalRef(ctx->eventReceiver);
    ctx->certificateHost.~basic_string();
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
        jboolean enableCamera,
        jstring cameraId,
        jboolean enableMicrophone,
        jobject surface,
        jstring certificateHost,
        jobject certificateVerifier) {
        
    if (!getenv("HOME")) {
        setenv("HOME", "/data/data/com.farside.workspace/files", 1);
    }
    
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
    env->DeleteLocalRef(bridgeClass);
    ctx->verifyCertificates = nullptr;
    if (certificateVerifier) {
        jclass verifierClass = env->GetObjectClass(certificateVerifier);
        ctx->verifyCertificates = env->GetMethodID(verifierClass, "verifyCertificates", "([BZ)Z");
        env->DeleteLocalRef(verifierClass);
    }
    const char* certificateHostChars = certificateHost ? env->GetStringUTFChars(certificateHost, nullptr) : nullptr;
    new (&ctx->certificateHost) std::string(certificateHostChars ? certificateHostChars : "");
    if (certificateHostChars) env->ReleaseStringUTFChars(certificateHost, certificateHostChars);
    ctx->origSurfaceCommand = nullptr;
    for (auto& count : ctx->codecCounts) new (&count) std::atomic<uint32_t>(0);
    ctx->audio = new QuestAudioState();
    new (&ctx->windows) std::vector<ANativeWindow*>();
    new (&ctx->windowMutex) std::mutex();
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
    
    if (surface) {
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
    
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_DeviceRedirection, TRUE);
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_AudioPlayback, TRUE);
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_RemoteConsoleAudio, FALSE);
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_AudioCapture, enableMicrophone);
    const char* soundArgs[] = { "rdpsnd", "sys:quest" };
    freerdp_client_add_static_channel(instance->context->settings, 2, soundArgs);
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
        // Add rdpecam dynamic channel
        char* args[] = { (char*)"freerdp", (char*)"/camera", nullptr };
        if (cameraId && env->GetStringLength(cameraId) > 0) {
            const char* cam_c = env->GetStringUTFChars(cameraId, 0);
            char cam_arg[256];
            snprintf(cam_arg, sizeof(cam_arg), "/camera:dev:%s", cam_c);
            char* args_with_id[] = { (char*)"freerdp", cam_arg, nullptr };
            freerdp_client_settings_parse_command_line(instance->context->settings, 2, args_with_id, FALSE);
            env->ReleaseStringUTFChars(cameraId, cam_c);
        } else {
            freerdp_client_settings_parse_command_line(instance->context->settings, 2, args, FALSE);
        }
    }
    
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
