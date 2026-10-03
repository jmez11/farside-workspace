#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <freerdp/freerdp.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/gdi/gfx.h>
#include <freerdp/client/cmdline.h>

#define LOG_TAG "FreeRDP-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#include <vector>
#include <mutex>
#include <algorithm>

struct RdpContext {
    rdpContext _p;
    std::vector<ANativeWindow*> windows;
    std::mutex windowMutex;
};

static BOOL client_pre_connect(freerdp* instance) {
    // Configure settings
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_SoftwareGdi, TRUE);
    freerdp_settings_set_uint32(instance->context->settings, FreeRDP_ColorDepth, 32);
    freerdp_settings_set_bool(instance->context->settings, FreeRDP_IgnoreCertificate, TRUE);
    freerdp_settings_set_uint32(instance->context->settings, FreeRDP_AuthenticationLevel, 0);
    
    // GDI initialization (FreeRDP 3.x API: 2 arguments)
    if (!gdi_init(instance, PIXEL_FORMAT_RGBA32)) {
        LOGE("Failed to init GDI");
        return FALSE;
    }
    
    return TRUE;
}

static BOOL client_post_connect(freerdp* instance) {
    LOGI("Post connect!");
    return TRUE;
}

static BOOL client_end_paint(rdpContext* context) {
    auto* ctx = (RdpContext*)context;
    
    rdpGdi* gdi = context->gdi;
    if (!gdi || !gdi->primary_buffer) return TRUE;
    
    std::lock_guard<std::mutex> lock(ctx->windowMutex);
    for (auto* window : ctx->windows) {
        if (!window) continue;
        ANativeWindow_Buffer buffer;
        if (ANativeWindow_lock(window, &buffer, nullptr) == 0) {
            uint8_t* dst = (uint8_t*)buffer.bits;
            uint8_t* src = (uint8_t*)gdi->primary_buffer;
            
            int desktopWidth = freerdp_settings_get_uint32(context->settings, FreeRDP_DesktopWidth);
            int desktopHeight = freerdp_settings_get_uint32(context->settings, FreeRDP_DesktopHeight);
            
            int rowBytes = desktopWidth * 4;
            for (int y = 0; y < desktopHeight && y < buffer.height; ++y) {
                memcpy(dst + (y * buffer.stride * 4), src + (y * rowBytes), rowBytes);
            }
            
            ANativeWindow_unlockAndPost(window);
        }
    }
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
        
    freerdp* instance = freerdp_new();
    if (!instance) return 0;
    
    instance->PreConnect = client_pre_connect;
    instance->PostConnect = client_post_connect;
    instance->ContextSize = sizeof(RdpContext);
    
    freerdp_context_new(instance);
    auto* ctx = (RdpContext*)instance->context;
    
    // Use placement new to initialize C++ structures in C-allocated struct
    new (&ctx->windows) std::vector<ANativeWindow*>();
    new (&ctx->windowMutex) std::mutex();
    
    if (surface) {
        ctx->windows.push_back(ANativeWindow_fromSurface(env, surface));
    }
    
    instance->context->update->EndPaint = client_end_paint;
    
    const char* host_c = env->GetStringUTFChars(hostname, 0);
    const char* user_c = env->GetStringUTFChars(username, 0);
    const char* pass_c = env->GetStringUTFChars(password, 0);
    
    freerdp_settings_set_string(instance->context->settings, FreeRDP_ServerHostname, host_c);
    if (port > 0) {
        freerdp_settings_set_uint32(instance->context->settings, FreeRDP_ServerPort, port);
    }
    freerdp_settings_set_string(instance->context->settings, FreeRDP_Username, user_c);
    freerdp_settings_set_string(instance->context->settings, FreeRDP_Password, pass_c);
    
    if (remoteApp) {
        const char* app_c = env->GetStringUTFChars(remoteApp, 0);
        freerdp_settings_set_bool(instance->context->settings, FreeRDP_RemoteApplicationMode, TRUE);
        freerdp_settings_set_string(instance->context->settings, FreeRDP_RemoteApplicationProgram, app_c);
        env->ReleaseStringUTFChars(remoteApp, app_c);
    }

    if (enableCamera) {
        freerdp_settings_set_bool(instance->context->settings, FreeRDP_SupportDynamicChannels, TRUE);
        // Add rdpecam dynamic channel
        char* args[] = { (char*)"freerdp", (char*)"/camera", nullptr };
        if (cameraId) {
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
    
    env->ReleaseStringUTFChars(hostname, host_c);
    env->ReleaseStringUTFChars(username, user_c);
    env->ReleaseStringUTFChars(password, pass_c);
    
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
    
    freerdp_disconnect(instance);
    
    std::lock_guard<std::mutex> lock(ctx->windowMutex);
    for (auto* win : ctx->windows) {
        if (win) ANativeWindow_release(win);
    }
    
    ctx->windows.~vector();
    ctx->windowMutex.~mutex();
    
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
