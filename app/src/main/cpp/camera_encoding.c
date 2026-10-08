/**
 * FreeRDP: A Remote Desktop Protocol Implementation
 * MS-RDPECAM Implementation, Video Encoding & Hardware MediaCodec Encoder for Quest
 */

#include <winpr/assert.h>
#include <winpr/winpr.h>

#include "camera.h"

#define TAG CHANNELS_TAG("rdpecam-video.client")
#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <time.h>

#define LOG_TAG "rdpecam-farside"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>

/* The override owns FreeRDP's opaque per-stream video slot. It is never passed
 * to generic freerdp_video_* functions: this context wraps the Android encoder. */
typedef struct { jobject encoder; } QuestEncoderContext;
static JavaVM* g_encoderVm;
static jclass g_encoderClass;
static jmethodID g_encoderCtor, g_encoderEncode, g_encoderClose;
static pthread_mutex_t g_encoderInitLock = PTHREAD_MUTEX_INITIALIZER;

BOOL quest_camera_encoder_register(JNIEnv* env)
{
    pthread_mutex_lock(&g_encoderInitLock);
    if (!g_encoderClass)
    {
        jclass local = (*env)->FindClass(env, "com/farside/workspace/QuestCameraEncoder");
        if (local)
        {
            g_encoderCtor = (*env)->GetMethodID(env, local, "<init>", "(IIII)V");
            g_encoderEncode = (*env)->GetMethodID(env, local, "encode", "(Ljava/nio/ByteBuffer;J)[B");
            g_encoderClose = (*env)->GetMethodID(env, local, "close", "()V");
            if (g_encoderCtor && g_encoderEncode && g_encoderClose)
            {
                (*env)->GetJavaVM(env, &g_encoderVm);
                g_encoderClass = (*env)->NewGlobalRef(env, local);
            }
            (*env)->DeleteLocalRef(env, local);
        }
    }
    const BOOL ready = g_encoderClass != NULL;
    pthread_mutex_unlock(&g_encoderInitLock);
    return ready;
}

static JNIEnv* quest_encoder_env(BOOL* attached)
{
    JNIEnv* env = NULL;
    *attached = FALSE;
    if (!g_encoderVm) return NULL;
    if ((*g_encoderVm)->GetEnv(g_encoderVm, (void**)&env, JNI_VERSION_1_6) != JNI_OK)
    {
        if ((*g_encoderVm)->AttachCurrentThread(g_encoderVm, &env, NULL) != JNI_OK) return NULL;
        *attached = TRUE;
    }
    return env;
}

static BOOL quest_encoder_exception(JNIEnv* env)
{
    if (!(*env)->ExceptionCheck(env)) return FALSE;
    (*env)->ExceptionDescribe(env);
    (*env)->ExceptionClear(env);
    LOGE("Camera encoder Java call failed");
    return TRUE;
}

/**
 * Function description
 *
 * @return bitrate in bps
 */
UINT32 h264_get_max_bitrate(UINT32 height)
{
	static struct Bitrates
	{
		UINT32 height;
		UINT32 bitrate; /* kbps */

	} bitrates[] = {
		/* source: https://livekit.io/webrtc/bitrate-guide (webcam streaming)
		 * sorted by height in descending order
		 */
		{ 1080, 2700 }, { 720, 1250 }, { 480, 700 }, { 360, 400 },
		{ 240, 170 },   { 180, 140 },  { 0, 100 },
	};
	const size_t nBitrates = ARRAYSIZE(bitrates);

	for (size_t i = 0; i < nBitrates; i++)
	{
		if (height >= bitrates[i].height)
		{
			UINT32 bitrate = bitrates[i].bitrate;
			WLog_DBG(TAG, "Setting h264 max bitrate: %u kbps", bitrate);
			return bitrate * 1000;
		}
	}

	return 700 * 1000;
}

/**
 * Function description
 *
 * @return FREERDP_VIDEO_FORMAT value
 */
FREERDP_VIDEO_FORMAT ecamToVideoFormat(CAM_MEDIA_FORMAT ecamFormat)
{
	switch (ecamFormat)
	{
		case CAM_MEDIA_FORMAT_YUY2:
			return FREERDP_VIDEO_FORMAT_YUYV422;
		case CAM_MEDIA_FORMAT_NV12:
			return FREERDP_VIDEO_FORMAT_NV12;
		case CAM_MEDIA_FORMAT_I420:
			return FREERDP_VIDEO_FORMAT_YUV420P;
		case CAM_MEDIA_FORMAT_RGB24:
			return FREERDP_VIDEO_FORMAT_RGB24;
		case CAM_MEDIA_FORMAT_RGB32:
			return FREERDP_VIDEO_FORMAT_RGB32;
		case CAM_MEDIA_FORMAT_H264:
			return FREERDP_VIDEO_FORMAT_H264;
		case CAM_MEDIA_FORMAT_MJPG:
			return FREERDP_VIDEO_FORMAT_MJPEG;
		default:
			WLog_ERR(TAG, "Unsupported ecamFormat %u", ecamFormat);
			return FREERDP_VIDEO_FORMAT_NONE;
	}
}

BOOL ecam_encoder_context_init(CameraDeviceStream* stream)
{
    if (!stream) return FALSE;
    if (streamOutputFormat(stream) != CAM_MEDIA_FORMAT_H264 || stream->video) return TRUE;
    BOOL attached;
    JNIEnv* env = quest_encoder_env(&attached);
    if (!env) return FALSE;
    const UINT32 fps = stream->currMediaType.FrameRateNumerator /
        (stream->currMediaType.FrameRateDenominator ? stream->currMediaType.FrameRateDenominator : 1);
    jobject encoder = (*env)->NewObject(env, g_encoderClass, g_encoderCtor,
        (jint)stream->currMediaType.Width, (jint)stream->currMediaType.Height,
        (jint)(fps ? fps : 30), (jint)h264_get_max_bitrate(stream->currMediaType.Height));
    const BOOL error = quest_encoder_exception(env);
    QuestEncoderContext* context = NULL;
    if (!error && encoder)
    {
        context = calloc(1, sizeof(*context));
        if (context) context->encoder = (*env)->NewGlobalRef(env, encoder);
        if (!context || !context->encoder)
        {
            (*env)->CallVoidMethod(env, encoder, g_encoderClose);
            quest_encoder_exception(env);
            free(context);
            context = NULL;
        }
        (*env)->DeleteLocalRef(env, encoder);
    }
    stream->video = (FREERDP_VIDEO_CONTEXT*)context;
    if (attached) (*g_encoderVm)->DetachCurrentThread(g_encoderVm);
    return context != NULL;
}

BOOL ecam_encoder_context_free(CameraDeviceStream* stream)
{
    if (!stream || !stream->video) return TRUE;
    QuestEncoderContext* context = (QuestEncoderContext*)stream->video;
    BOOL attached;
    JNIEnv* env = quest_encoder_env(&attached);
    if (!env) return FALSE;
    (*env)->CallVoidMethod(env, context->encoder, g_encoderClose);
    quest_encoder_exception(env);
    (*env)->DeleteGlobalRef(env, context->encoder);
    free(context);
    stream->video = NULL;
    if (attached) (*g_encoderVm)->DetachCurrentThread(g_encoderVm);
    return TRUE;
}

BOOL ecam_encoder_compress(CameraDeviceStream* stream, const BYTE* srcData, size_t srcSize,
                           wStream* output)
{
    if (!stream || !srcData || !output) return FALSE;
    if (streamInputFormat(stream) == streamOutputFormat(stream))
    {
        if (!Stream_EnsureRemainingCapacity(output, srcSize)) return FALSE;
        Stream_Write(output, srcData, srcSize);
        return TRUE;
    }
    if (streamInputFormat(stream) != CAM_MEDIA_FORMAT_NV12 ||
        streamOutputFormat(stream) != CAM_MEDIA_FORMAT_H264 || !ecam_encoder_context_init(stream))
        return FALSE;
    BOOL attached;
    JNIEnv* env = quest_encoder_env(&attached);
    if (!env) return FALSE;
    QuestEncoderContext* context = (QuestEncoderContext*)stream->video;
    jobject source = (*env)->NewDirectByteBuffer(env, (void*)srcData, (jlong)srcSize);
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    const jlong pts = (jlong)now.tv_sec * 1000000 + now.tv_nsec / 1000;
    jbyteArray encoded = source ? (*env)->CallObjectMethod(env, context->encoder,
        g_encoderEncode, source, pts) : NULL;
    BOOL success = FALSE;
    if (!quest_encoder_exception(env) && encoded)
    {
        const jsize size = (*env)->GetArrayLength(env, encoded);
        if (size > 0 && Stream_EnsureRemainingCapacity(output, (size_t)size))
        {
            (*env)->GetByteArrayRegion(env, encoded, 0, size, (jbyte*)Stream_Pointer(output));
            if (!quest_encoder_exception(env))
            {
                Stream_Seek(output, (size_t)size);
                success = TRUE;
            }
        }
    }
    if (encoded) (*env)->DeleteLocalRef(env, encoded);
    if (source) (*env)->DeleteLocalRef(env, source);
    if (attached) (*g_encoderVm)->DetachCurrentThread(g_encoderVm);
    return success;
}
