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

static BOOL client_pre_connect(freerdp* instance) {
    // Configure settings
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_SoftwareGdi, TRUE);
    freerdp_settings_set_uint32(instance->context->settings, FreeRDP_ColorDepth, 16);
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_IgnoreCertificate, TRUE);
    freerdp_settings_set_uint32(instance->context->settings, FreeRDP_AuthenticationLevel, 0);
    
    // Force highly optimized protocols!
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_FastPathInput, TRUE);
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_FastPathOutput, TRUE);
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_RemoteFxCodec, TRUE);
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_SupportGraphicsPipeline, TRUE);
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_GfxH264, TRUE);
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_GfxAVC444, TRUE);
    
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
    const char* const disp_params[] = { "disp" };
    freerdp_client_add_dynamic_channel(instance->context->settings, 1, disp_params);
    
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
        "/gfx:AVC420:on",
        "/rfx",
        "/bpp:16",
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
        if (strcmp(e->name, DISP_DVC_CHANNEL_NAME) == 0) {
            auto* ctx = (RdpContext*)context;
            ctx->disp = (DispClientContext*)e->pInterface;
        }
    });

    PubSub_SubscribeChannelDisconnected(instance->context->pubSub, [](void* context, const ChannelDisconnectedEventArgs* e) {
        if (strcmp(e->name, DISP_DVC_CHANNEL_NAME) == 0) {
            auto* ctx = (RdpContext*)context;
            ctx->disp = nullptr;
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
