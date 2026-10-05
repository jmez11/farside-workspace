#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <freerdp/freerdp.h>
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
#include <mutex>
#include <algorithm>
#include <thread>
#include <winpr/synch.h>

#include <chrono>

#include <atomic>
#include <freerdp/client/rdpgfx.h>
#include <freerdp/codec/h264.h>

// ---- Ground-truth codec logging -------------------------------------------------------
// Wraps the GDI GFX SurfaceCommand handler so we log the codec the *server actually sends*,
// rather than echoing the settings we requested.
static pcRdpgfxSurfaceCommand g_origSurfaceCommand = nullptr;
static std::atomic<uint32_t> g_codecCounts[16];

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

static UINT logging_surface_command(RdpgfxClientContext* context, const RDPGFX_SURFACE_COMMAND* cmd) {
    uint32_t slot = cmd->codecId & 0x0F;
    uint32_t n = ++g_codecCounts[slot];
    if (n == 1 || (n % 300) == 0) {
        LOGI("RDP_CODEC: server sent codec %s (0x%04x), count=%u, rect=%ux%u",
             gfx_codec_name(cmd->codecId), cmd->codecId, n,
             cmd->right - cmd->left, cmd->bottom - cmd->top);
    }
    return g_origSurfaceCommand ? g_origSurfaceCommand(context, cmd) : CHANNEL_RC_OK;
}
struct RdpContext {
    rdpContext _p;
    std::vector<ANativeWindow*> windows;
    std::mutex windowMutex;
    DispClientContext* disp;
    std::chrono::steady_clock::time_point last_render_time;
    std::atomic<bool> render_running;
    std::thread render_thread;
    std::atomic<bool> dirty;
};


#include <freerdp/addin.h>

extern "C" UINT VCAPITYPE rdpgfx_DVCPluginEntry(PVOID pEntryPoints);
extern "C" UINT VCAPITYPE disp_DVCPluginEntry(PVOID pEntryPoints);
extern "C" BOOL VCAPITYPE drdynvc_VirtualChannelEntryEx(PVOID pInitHandle, PVOID pContext);

// Channels are compiled statically (BUILD_SHARED_LIBS OFF), so dlopen-based addin
// lookup cannot find them. This provider maps channel names to the linked entry points.
static PVOID my_addin_provider(LPCSTR pszName, LPCSTR pszSubsystem, LPCSTR pszType, DWORD dwFlags) {
    LOGI("my_addin_provider called for: %s (flags=0x%x)", pszName ? pszName : "NULL", dwFlags);
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
    return res == 0;
}

static BOOL client_pre_connect(freerdp* instance) {
    rdpSettings* settings = instance->context->settings;

    // Configure settings
    freerdp_settings_set_bool(settings, FreeRDP_SoftwareGdi, TRUE);
    freerdp_settings_set_uint32(settings, FreeRDP_ColorDepth, 32);
    freerdp_settings_set_bool(settings, FreeRDP_IgnoreCertificate, TRUE);
    freerdp_settings_set_uint32(settings, FreeRDP_AuthenticationLevel, 0);
    
    freerdp_settings_set_bool(settings, FreeRDP_FastPathInput, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_FastPathOutput, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_RemoteFxCodec, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_SupportGraphicsPipeline, TRUE);
    freerdp_settings_set_bool(settings, FreeRDP_GfxProgressive, TRUE);
    // No H.264 decoder is compiled in yet (FFmpeg/OpenH264/MediaCodec all off). Advertising
    // AVC would make the server send frames we cannot decode, so keep it off for now.
    freerdp_settings_set_bool(settings, FreeRDP_GfxH264, FALSE);
    freerdp_settings_set_bool(settings, FreeRDP_GfxAVC444, FALSE);
    freerdp_settings_set_bool(settings, FreeRDP_GfxAVC444v2, FALSE);
    freerdp_settings_set_bool(settings, FreeRDP_SupportDynamicChannels, TRUE);
    
    freerdp_register_addin_provider((FREERDP_LOAD_CHANNEL_ADDIN_ENTRY_FN)my_addin_provider, 0);
    add_dynamic_channel_once(settings, "rdpgfx");
    add_dynamic_channel_once(settings, "disp");
    
    return TRUE;
}


static BOOL client_end_paint(rdpContext* context) {
    auto* ctx = (RdpContext*)context;
    ctx->dirty = true;
    return TRUE;
}

static BOOL client_desktop_resize(rdpContext* context) {
    auto* ctx = (RdpContext*)context;
    int desktopWidth = freerdp_settings_get_uint32(context->settings, FreeRDP_DesktopWidth);
    int desktopHeight = freerdp_settings_get_uint32(context->settings, FreeRDP_DesktopHeight);
    
    std::lock_guard<std::mutex> lock(ctx->windowMutex);
    for (auto* window : ctx->windows) {
        if (window) {
            ANativeWindow_setBuffersGeometry(window, desktopWidth, desktopHeight, WINDOW_FORMAT_RGBA_8888);
        }
    }
    return TRUE;
}

static BOOL client_post_connect(freerdp* instance) {
    LOGI("Post connect!");
    
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
    ctx->render_thread = std::thread([ctx, instance]() {
        while (ctx->render_running) {
            std::this_thread::sleep_for(std::chrono::milliseconds(16));
            
            rdpGdi* gdi = instance->context->gdi;
            if (!gdi || !gdi->primary_buffer) continue;
            
            if (!ctx->dirty.exchange(false)) continue;
            auto start = std::chrono::steady_clock::now();
            
            int desktopWidth = freerdp_settings_get_uint32(instance->context->settings, FreeRDP_DesktopWidth);
            int desktopHeight = freerdp_settings_get_uint32(instance->context->settings, FreeRDP_DesktopHeight);
            int rowBytes = desktopWidth * 4;
            
            std::lock_guard<std::mutex> lock(ctx->windowMutex);
            for (auto* window : ctx->windows) {
                if (!window) continue;
                ANativeWindow_Buffer buffer;
                if (ANativeWindow_lock(window, &buffer, nullptr) == 0) {
                    uint8_t* dst = (uint8_t*)buffer.bits;
                    uint8_t* src = (uint8_t*)gdi->primary_buffer;
                    
                    for (int y = 0; y < desktopHeight && y < buffer.height; ++y) {
                        memcpy(dst + (y * buffer.stride * 4), src + (y * rowBytes), rowBytes);
                    }
                    
                    ANativeWindow_unlockAndPost(window);
                }
            }
            
            auto end = std::chrono::steady_clock::now();
            int took = std::chrono::duration_cast<std::chrono::milliseconds>(end - start).count();
            if (took > 5) {
                LOGI("RDP_LAG: Render thread blit took %d ms", took);
            }
        }
    });
    
    return TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_multidesk_explorer_FreeRDPBridge_connect(
        JNIEnv* env,
        jobject /* this */,
        jstring hostname,
        jint port,
        jstring username,
        jstring password,
        jstring remoteApp,
        jboolean enableCamera,
        jstring cameraId,
        jobject surface) {
        
    if (!getenv("HOME")) {
        setenv("HOME", "/data/data/com.multidesk.explorer/files", 1);
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
    
    new (&ctx->windows) std::vector<ANativeWindow*>();
    new (&ctx->windowMutex) std::mutex();
    new (&ctx->render_running) std::atomic<bool>(false);
    new (&ctx->dirty) std::atomic<bool>(false);
    new (&ctx->render_thread) std::thread();
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
            ctx->disp = (DispClientContext*)e->pInterface;
        } else if (strcmp(e->name, RDPGFX_DVC_CHANNEL_NAME) == 0) {
            auto* gfx = (RdpgfxClientContext*)e->pInterface;
            rdpGdi* gdi = ((rdpContext*)context)->gdi;
            if (gdi && gfx && gdi_graphics_pipeline_init(gdi, gfx)) {
                g_origSurfaceCommand = gfx->SurfaceCommand;
                gfx->SurfaceCommand = logging_surface_command;
                for (auto& c : g_codecCounts) c = 0;
                LOGI("RDP_CODEC: GFX pipeline initialized - server will use GFX codecs");
            } else {
                LOGE("RDP_CODEC: gdi_graphics_pipeline_init FAILED (gdi=%p gfx=%p)", gdi, gfx);
            }
        }
    });

    PubSub_SubscribeChannelDisconnected(instance->context->pubSub, [](void* context, const ChannelDisconnectedEventArgs* e) {
        auto* ctx = (RdpContext*)context;
        if (strcmp(e->name, DISP_DVC_CHANNEL_NAME) == 0) {
            ctx->disp = nullptr;
        } else if (strcmp(e->name, RDPGFX_DVC_CHANNEL_NAME) == 0) {
            auto* gfx = (RdpgfxClientContext*)e->pInterface;
            rdpGdi* gdi = ((rdpContext*)context)->gdi;
            if (gdi && gfx) gdi_graphics_pipeline_uninit(gdi, gfx);
            g_origSurfaceCommand = nullptr;
        }
    });
    
    LOGI("Connecting...");
    if (!freerdp_connect(instance)) {
        LOGE("Connection failed");
        for (auto* win : ctx->windows) ANativeWindow_release(win);
        ctx->windows.~vector();
        ctx->windowMutex.~mutex();
        freerdp_context_free(instance);
        freerdp_free(instance);
        return 0;
    }
    
    LOGI("Connection succeeded");
    
    std::thread([instance]() {
        DWORD status;
        DWORD count;
        HANDLE handles[64];
        
        while (!freerdp_shall_disconnect(instance)) {
            count = freerdp_get_event_handles(instance->context, handles, 64);
            if (count == 0) {
                LOGE("Failed to get FreeRDP event handles");
                break;
            }
            status = WaitForMultipleObjects(count, handles, FALSE, 100);
            if (status == WAIT_FAILED) {
                LOGE("WaitForMultipleObjects failed");
                break;
            }
            if (!freerdp_check_event_handles(instance->context)) {
                LOGE("Failed to check FreeRDP event handles");
                break;
            }
        }
        LOGI("Disconnecting event loop");
    }).detach();

    return (jlong)instance;
}

extern "C" JNIEXPORT void JNICALL
Java_com_multidesk_explorer_FreeRDPBridge_disconnect(
        JNIEnv* env,
        jobject /* this */,
        jlong instance_ptr) {
    if (!instance_ptr) return;
    
    freerdp* instance = (freerdp*)instance_ptr;
    auto* ctx = (RdpContext*)instance->context;
    
    ctx->render_running = false;
    if (ctx->render_thread.joinable()) {
        ctx->render_thread.join();
    }
    
    freerdp_disconnect(instance);
    
    std::lock_guard<std::mutex> lock(ctx->windowMutex);
    for (auto* win : ctx->windows) {
        if (win) ANativeWindow_release(win);
    }
    
    ctx->windows.~vector();
    ctx->windowMutex.~mutex();
    ctx->render_running.~atomic();
    ctx->dirty.~atomic();
    ctx->render_thread.~thread();
    
    freerdp_context_free(instance);
    freerdp_free(instance);
}

extern "C" JNIEXPORT void JNICALL
Java_com_multidesk_explorer_FreeRDPBridge_addSurface(
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
Java_com_multidesk_explorer_FreeRDPBridge_removeSurface(
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
Java_com_multidesk_explorer_FreeRDPBridge_sendMouseEvent(
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
Java_com_multidesk_explorer_FreeRDPBridge_updateDisplay(
        JNIEnv* env,
        jobject /* this */,
        jlong instance_ptr,
        jint width,
        jint height,
        jfloat zoom) {
    if (!instance_ptr) return;
    
    freerdp* instance = (freerdp*)instance_ptr;
    auto* ctx = (RdpContext*)instance->context;
    
    if (ctx->disp && ctx->disp->SendMonitorLayout) {
        DISPLAY_CONTROL_MONITOR_LAYOUT layout = {0};
        layout.Flags = DISPLAY_CONTROL_MONITOR_PRIMARY;
        layout.Left = 0;
        layout.Top = 0;
        layout.Width = width;
        layout.Height = height;
        layout.PhysicalWidth = (UINT32)(width / 96.0 * 25.4);
        layout.PhysicalHeight = (UINT32)(height / 96.0 * 25.4);
        layout.Orientation = ORIENTATION_LANDSCAPE;
        
        UINT32 scale = (UINT32)(zoom * 100.0f);
        layout.DesktopScaleFactor = scale;
        layout.DeviceScaleFactor = scale;
        
        ctx->disp->SendMonitorLayout(ctx->disp, 1, &layout);
    }
}
extern "C" JNIEXPORT void JNICALL
Java_com_multidesk_explorer_FreeRDPBridge_sendKeyEvent(
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
Java_com_multidesk_explorer_FreeRDPBridge_sendUnicodeKeyEvent(
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
Java_com_multidesk_explorer_FreeRDPBridge_getDesktopWidth(
        JNIEnv* env,
        jobject /* this */,
        jlong instance_ptr) {
    if (!instance_ptr) return 1920;
    freerdp* instance = (freerdp*)instance_ptr;
    return freerdp_settings_get_uint32(instance->context->settings, FreeRDP_DesktopWidth);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_multidesk_explorer_FreeRDPBridge_getDesktopHeight(
        JNIEnv* env,
        jobject /* this */,
        jlong instance_ptr) {
    if (!instance_ptr) return 1080;
    freerdp* instance = (freerdp*)instance_ptr;
    return freerdp_settings_get_uint32(instance->context->settings, FreeRDP_DesktopHeight);
}
