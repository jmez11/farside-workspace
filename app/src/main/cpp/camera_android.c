/**
 * FreeRDP: A Remote Desktop Protocol Implementation
 * MS-RDPECAM Implementation, Android NDK Camera2 HAL
 *
 * Copyright 2026 Ibrahim Sevinc <ibrahim.sevinc.mail@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <android/log.h>

#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadata.h>
#include <camera/NdkCameraCaptureSession.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>

#include "camera.h"
#include "quest_camera.h"

#define TAG CHANNELS_TAG("rdpecam-android.client")
#define LOG_TAG "rdpecam-farside"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define ANDROID_CAMERA_PREFIX "android-camera-"
#define ANDROID_CAMERA_PREFIX_LEN (sizeof(ANDROID_CAMERA_PREFIX) - 1)

#define CAM_ANDROID_FPS 30
#define DISPLAY_DEVICE_ID "display-0"

/* FreeRDP loads the HAL synchronously inside its DVC entry. Bind that HAL to
 * the calling session without sharing mutable state across registration threads. */
static _Thread_local QuestCameraState* registrationState;
extern UINT VCAPITYPE rdpecam_DVCPluginEntry(IDRDYNVC_ENTRY_POINTS* entry);

UINT VCAPITYPE quest_rdpecam_entry_with_state(IDRDYNVC_ENTRY_POINTS* entry, QuestCameraState* state)
{
    if (!entry || !state) return CHANNEL_RC_INITIALIZATION_ERROR;
    QuestCameraState* previous = registrationState;
    registrationState = state;
    const UINT result = rdpecam_DVCPluginEntry(entry);
    registrationState = previous;
    return result;
}
static volatile UINT g_camera_count = 0;
static volatile int g_camera_max_dimension = 720; /* Default 720p */

#ifndef NDEBUG
static volatile BOOL g_verification_pattern = FALSE;
#endif

BOOL cam_android_set_verification_pattern(BOOL enabled)
{
#ifndef NDEBUG
	g_verification_pattern = enabled;
	return TRUE;
#else
	WINPR_UNUSED(enabled);
	return FALSE;
#endif
}

UINT cam_android_get_camera_count(void)
{
	return g_camera_count;
}

void cam_android_set_max_dimension(int maxDim)
{
	if (maxDim == -1)
		g_camera_max_dimension = -1; /* Highest native Camera2 resolution. */
	else if (maxDim <= 0)
		g_camera_max_dimension = 720;
	else
		g_camera_max_dimension = maxDim;
	LOGI("cam_android_set_max_dimension: set to %d", g_camera_max_dimension);
}

int cam_android_get_max_dimension(void)
{
	return g_camera_max_dimension;
}

typedef struct
{
	ICamHal iHal;
	QuestCameraState* state;
	ACameraManager* manager;
	wHashTable* streams; /* key: deviceId, value: CamAndroidStream */
} CamAndroidHal;

typedef struct CamAndroidStream
{
	size_t streamIndex;
    struct CamAndroidStream* source;
    struct CamAndroidStream* subscribers;
    struct CamAndroidStream* nextSubscriber;
    BOOL producer;
    int width, height;
	QuestCameraState* state;
	BOOL recording;
    BOOL display;
    int64_t lastFrameTime;
    UINT displayCallbacks;
    BOOL displayRgba;
    HANDLE displayImageReady;
    volatile LONG displayReaderStatus;
    BYTE* planarBuffer;
    size_t planarBufferSize;

	ACameraDevice* device;
	ACameraCaptureSession* session;
	ACaptureSessionOutputContainer* outputContainer;
	ACaptureSessionOutput* sessionOutput;
	ACameraOutputTarget* outputTarget;
	ACaptureRequest* captureRequest;
	AImageReader* imageReader;
	ANativeWindow* window;

	ICamHalSampleCapturedCallback sampleCallback;
	CameraDevice* dev;
	BOOL streaming;
	BOOL deviceError;
	CRITICAL_SECTION lock;

	BYTE* nv12Buffer;
	size_t nv12BufferSize;
} CamAndroidStream;

/* One physical Camera2 producer; session mute/encoding remain on subscribers. */
static pthread_mutex_t cameraBrokerLock = PTHREAD_MUTEX_INITIALIZER;
static CamAndroidHal cameraBroker;

static void cam_android_apply_mute(QuestCameraState* state, BYTE* nv12, size_t ySize)
{
    if (quest_camera_is_muted(state))
    {
        memset(nv12, 0, ySize);
        memset(nv12 + ySize, 128, ySize / 2);
    }
}

static const char* cam_android_extract_camera_id(const char* deviceId)
{
	WINPR_ASSERT(deviceId);

	if (strncmp(deviceId, ANDROID_CAMERA_PREFIX, ANDROID_CAMERA_PREFIX_LEN) != 0)
		return NULL;
	return deviceId + ANDROID_CAMERA_PREFIX_LEN;
}

/* Tear down the capture session and its request objects. The ImageReader and device are kept. */
static void cam_android_close_session(CamAndroidStream* stream)
{
	WINPR_ASSERT(stream);

	if (stream->session)
	{
		ACameraCaptureSession_stopRepeating(stream->session);
		ACameraCaptureSession_close(stream->session);
		stream->session = NULL;
	}
	if (stream->captureRequest)
	{
		if (stream->outputTarget)
			ACaptureRequest_removeTarget(stream->captureRequest, stream->outputTarget);
		ACaptureRequest_free(stream->captureRequest);
		stream->captureRequest = NULL;
	}
	if (stream->outputTarget)
	{
		ACameraOutputTarget_free(stream->outputTarget);
		stream->outputTarget = NULL;
	}
	if (stream->outputContainer)
	{
		ACaptureSessionOutputContainer_free(stream->outputContainer);
		stream->outputContainer = NULL;
	}
	if (stream->sessionOutput)
	{
		ACaptureSessionOutput_free(stream->sessionOutput);
		stream->sessionOutput = NULL;
	}
}

/* Pack a YUV_420_888 frame into NV12 (handles NV12, NV21 and planar I420 layouts). */
static void cam_android_yuv420_888_to_nv12(BYTE* dst, int width, int height, const uint8_t* yData,
                                           int yRowStride, const uint8_t* uData, int uRowStride,
                                           int uPixelStride, const uint8_t* vData, int vRowStride,
                                           int vPixelStride)
{
	WINPR_ASSERT(dst);
	WINPR_ASSERT(yData);
	WINPR_ASSERT(uData);
	WINPR_ASSERT(vData);

	const size_t ySize = (size_t)width * height;

	/* Y plane */
	if (yRowStride == width)
		memcpy(dst, yData, ySize);
	else
	{
		for (int row = 0; row < height; row++)
			memcpy(dst + (size_t)row * width, yData + (size_t)row * yRowStride, (size_t)width);
	}

	/* UV planes -> interleaved NV12 */
	BYTE* uv = dst + ySize;
	const int uvHeight = height / 2;
	const int uvWidth = width / 2;
	const size_t uvRowBytes = (size_t)(uvWidth * 2);

	if (uPixelStride == 2 && vPixelStride == 2)
	{
		/* Semi-planar: NV12 (U first) or NV21 (V first). */
		if (uData < vData)
		{
			/* NV12: copy the UV block directly. */
			if (uRowStride == (int)uvRowBytes)
				memcpy(uv, uData, (size_t)uvHeight * uvRowBytes);
			else
			{
				for (int row = 0; row < uvHeight; row++)
					memcpy(uv + (size_t)row * uvRowBytes, uData + (size_t)row * uRowStride,
					       uvRowBytes);
			}
		}
		else
		{
			/* NV21: swap U and V bytes. */
			for (int row = 0; row < uvHeight; row++)
			{
				const uint8_t* u = uData + (size_t)row * uRowStride;
				const uint8_t* v = vData + (size_t)row * vRowStride;
				BYTE* d = uv + (size_t)row * uvRowBytes;
				for (int col = 0; col < uvWidth; col++, u += 2, v += 2, d += 2)
				{
					d[0] = u[0];
					d[1] = v[0];
				}
			}
		}
	}
	else
	{
		/* Planar I420: interleave U and V. */
		for (int row = 0; row < uvHeight; row++)
		{
			const uint8_t* uRow = uData + (size_t)row * uRowStride;
			const uint8_t* vRow = vData + (size_t)row * vRowStride;
			BYTE* dstRow = uv + (size_t)row * uvRowBytes;
			for (int col = 0; col < uvWidth; col++)
			{
				dstRow[col * 2] = uRow[col * uPixelStride];
				dstRow[col * 2 + 1] = vRow[col * vPixelStride];
			}
		}
	}
}

/* BT.601 limited range, matching QuestCameraEncoder; alpha and row padding are ignored. */
static void cam_android_rgba_to_yuv420(const BYTE* rgba, int width, int height, int stride,
                                     BYTE* planar)
{
    const size_t ySize = (size_t)width * height;
    BYTE* u = planar + ySize;
    BYTE* v = u + ySize / 4;
    for (int row = 0; row < height; row += 2)
        for (int col = 0; col < width; col += 2)
        {
            int sumR = 0, sumG = 0, sumB = 0;
            for (int dy = 0; dy < 2; dy++)
                for (int dx = 0; dx < 2; dx++)
                {
                    const BYTE* pixel = rgba + (size_t)(row + dy) * stride + (col + dx) * 4;
                    sumR += pixel[0]; sumG += pixel[1]; sumB += pixel[2];
                    planar[(size_t)(row + dy) * width + col + dx] =
                        (BYTE)(((66 * pixel[0] + 129 * pixel[1] + 25 * pixel[2] + 128) >> 8) + 16);
                }
            const size_t uv = (size_t)(row / 2) * (width / 2) + col / 2;
            u[uv] = (BYTE)(((-38 * sumR - 74 * sumG + 112 * sumB + 512) >> 10) + 128);
            v[uv] = (BYTE)(((112 * sumR - 94 * sumG - 18 * sumB + 512) >> 10) + 128);
        }
}

BOOL cam_android_verify_display_conversion(void)
{
#ifndef NDEBUG
    BYTE rgba[4 * 20], planar[24], nv12[24];
    memset(rgba, 0xCD, sizeof(rgba));
    const BYTE colors[4][3] = { {255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 255, 255} };
    const BYTE expected[4][3] = { {81, 90, 240}, {145, 54, 34}, {41, 240, 110}, {235, 128, 128} };
    for (int row = 0; row < 4; row++)
        for (int col = 0; col < 4; col++)
            memcpy(rgba + row * 20 + col * 4, colors[(row / 2) * 2 + col / 2], 3);
    cam_android_rgba_to_yuv420(rgba, 4, 4, 20, planar);
    cam_android_yuv420_888_to_nv12(nv12, 4, 4, planar, 4, planar + 16, 2, 1, planar + 20, 2, 1);
    for (int row = 0; row < 4; row++)
        for (int col = 0; col < 4; col++)
            if (abs(nv12[row * 4 + col] - expected[(row / 2) * 2 + col / 2][0]) > 1) return FALSE;
    for (int index = 0; index < 4; index++)
        if (abs(nv12[16 + index * 2] - expected[index][1]) > 1 ||
            abs(nv12[17 + index * 2] - expected[index][2]) > 1) return FALSE;
    return TRUE;
#else
    return FALSE;
#endif
}

static void cam_android_deliver_frame(CamAndroidStream* stream, AImage* image)
{
	WINPR_ASSERT(stream);
	WINPR_ASSERT(image);
    if (stream->display)
    {
        int64_t timestamp = 0;
        if (AImage_getTimestamp(image, &timestamp) != AMEDIA_OK) return;
        /* Track the next deadline, rather than spacing every accepted frame by 33ms:
         * a 36Hz compositor would otherwise be quantized down to 18fps. */
        if (stream->lastFrameTime && timestamp < stream->lastFrameTime) return;
        if (!stream->lastFrameTime || timestamp - stream->lastFrameTime > 66666666)
            stream->lastFrameTime = timestamp;
        stream->lastFrameTime += 33333333;
    }

	int32_t width = 0;
	int32_t height = 0;
	if (AImage_getWidth(image, &width) != AMEDIA_OK ||
	    AImage_getHeight(image, &height) != AMEDIA_OK)
		return;

	const size_t ySize = (size_t)(width * height);
	const size_t nv12Size = ySize + ySize / 2;

	if (stream->nv12BufferSize < nv12Size)
	{
		BYTE* tmp = (BYTE*)realloc(stream->nv12Buffer, nv12Size);
		if (!tmp)
			return;
		/* Initialize extra memory to neutral black: Y=0, UV=128 */
		memset(tmp, 0, ySize);
		memset(tmp + ySize, 128, ySize / 2);
		stream->nv12Buffer = tmp;
		stream->nv12BufferSize = nv12Size;
	}

	/* Horizon's compositor writes RGBA even when the consumer requests YUV. */
	int dataLength = 0;
	uint8_t* yData = NULL;
	uint8_t* uData = NULL;
	uint8_t* vData = NULL;
    int32_t format = 0;
    AImage_getFormat(image, &format);
    if (stream->display && format == AIMAGE_FORMAT_RGBA_8888)
    {
        uint8_t* rgba = NULL;
        int rowStride = 0;
        if (AImage_getPlaneData(image, 0, &rgba, &dataLength) != AMEDIA_OK ||
            AImage_getPlaneRowStride(image, 0, &rowStride) != AMEDIA_OK || rowStride < width * 4 ||
            (size_t)dataLength < (size_t)(height - 1) * rowStride + (size_t)width * 4)
            return;
        if (stream->planarBufferSize < nv12Size)
        {
            BYTE* planar = realloc(stream->planarBuffer, nv12Size);
            if (!planar) return;
            stream->planarBuffer = planar;
            stream->planarBufferSize = nv12Size;
        }
        BYTE* planes[3] = { stream->planarBuffer, stream->planarBuffer + ySize,
                            stream->planarBuffer + ySize + ySize / 4 };
        cam_android_rgba_to_yuv420(rgba, width, height, rowStride, stream->planarBuffer);
        cam_android_yuv420_888_to_nv12(stream->nv12Buffer, width, height, planes[0], width,
                                        planes[1], width / 2, 1, planes[2], width / 2, 1);
        goto packed;
    }
    if (AImage_getPlaneData(image, 0, &yData, &dataLength) != AMEDIA_OK ||
	    AImage_getPlaneData(image, 1, &uData, &dataLength) != AMEDIA_OK ||
	    AImage_getPlaneData(image, 2, &vData, &dataLength) != AMEDIA_OK)
		return;

	int yRowStride = 0;
	int uRowStride = 0;
	int uPixelStride = 0;
	int vRowStride = 0;
	int vPixelStride = 0;
	AImage_getPlaneRowStride(image, 0, &yRowStride);
	AImage_getPlaneRowStride(image, 1, &uRowStride);
	AImage_getPlanePixelStride(image, 1, &uPixelStride);
	AImage_getPlaneRowStride(image, 2, &vRowStride);
	AImage_getPlanePixelStride(image, 2, &vPixelStride);

	cam_android_yuv420_888_to_nv12(stream->nv12Buffer, width, height, yData, yRowStride, uData,
	                               uRowStride, uPixelStride, vData, vRowStride, vPixelStride);
packed:

#ifndef NDEBUG
	if (g_verification_pattern)
	{
		/* Known BT.601 limited-range colors, repeated at different image positions.
		 * This replaces content only: production packing/encoder/transport stays intact. */
		static const BYTE colors[8][3] = {
			{81, 90, 240}, {145, 54, 34}, {41, 240, 110}, {235, 128, 128},
			{210, 16, 146}, {170, 166, 16}, {106, 202, 222}, {16, 128, 128}
		};
		for (int row = 0; row < height; row++)
			for (int col = 0; col < width; col++)
				stream->nv12Buffer[(size_t)row * width + col] =
					colors[(col * 8 / width + (row * 4 / height) * 3) % 8][0];
		for (int row = 0; row < height / 2; row++)
			for (int col = 0; col < width / 2; col++)
			{
				const BYTE* color = colors[(col * 16 / width + (row * 8 / height) * 3) % 8];
				const size_t offset = ySize + (size_t)row * width + col * 2;
				stream->nv12Buffer[offset] = color[1];
				stream->nv12Buffer[offset + 1] = color[2];
			}
	}
#endif

    if (stream->producer)
    {
        for (CamAndroidStream* peer = stream->subscribers; peer; peer = peer->nextSubscriber)
        {
            const size_t peerY = (size_t)peer->width * peer->height;
            const size_t peerSize = peerY * 3 / 2;
            if (peer->nv12BufferSize < peerSize)
            {
                BYTE* buffer = realloc(peer->nv12Buffer, peerSize);
                if (!buffer) continue;
                peer->nv12Buffer = buffer;
                peer->nv12BufferSize = peerSize;
            }
            if (width == peer->width && height == peer->height)
                memcpy(peer->nv12Buffer, stream->nv12Buffer, peerSize);
            else
            {
                /* Center crop to the requested native aspect ratio, then downsample. */
                int cropWidth = width, cropHeight = height;
                if ((int64_t)width * peer->height > (int64_t)height * peer->width)
                    cropWidth = (height * peer->width / peer->height) & ~1;
                else cropHeight = (width * peer->height / peer->width) & ~1;
#ifndef NDEBUG
                /* The transport chart covers the entire output at every negotiated size. */
                if (g_verification_pattern) { cropWidth = width; cropHeight = height; }
#endif
                const int cropX = ((width - cropWidth) / 2) & ~1;
                const int cropY = ((height - cropHeight) / 2) & ~1;
                for (int y = 0; y < peer->height; y++)
                    for (int x = 0; x < peer->width; x++)
                        peer->nv12Buffer[(size_t)y * peer->width + x] =
                            stream->nv12Buffer[(size_t)(cropY + y * cropHeight / peer->height) * width + cropX + x * cropWidth / peer->width];
                for (int y = 0; y < peer->height / 2; y++)
                    for (int x = 0; x < peer->width / 2; x++)
                    {
                        const size_t input = ySize + (size_t)(cropY / 2 + y * cropHeight / peer->height) * width + cropX + (x * cropWidth / peer->width) * 2;
                        const size_t output = peerY + (size_t)y * peer->width + x * 2;
                        peer->nv12Buffer[output] = stream->nv12Buffer[input];
                        peer->nv12Buffer[output + 1] = stream->nv12Buffer[input + 1];
                    }
            }
            cam_android_apply_mute(peer->state, peer->nv12Buffer, peerY);
            peer->sampleCallback(peer->dev, peer->streamIndex, peer->nv12Buffer, peerSize);
        }
        return;
    }
	cam_android_apply_mute(stream->state, stream->nv12Buffer, ySize);

	static uint32_t frameCount = 0;
	if ((++frameCount % 30) == 1)
	{
		LOGI("cam_android_deliver_frame: delivered frame #%u (%dx%d, size=%zu)",
		     frameCount, width, height, nv12Size);
        if (format == AIMAGE_FORMAT_YUV_420_888)
		LOGI("Camera2 plane layout: Y stride=%d U stride=%d pixel=%d V stride=%d pixel=%d",
		     yRowStride, uRowStride, uPixelStride, vRowStride, vPixelStride);
	}

	stream->sampleCallback(stream->dev, stream->streamIndex, stream->nv12Buffer, nv12Size);
}

static void cam_android_on_image_available(void* context, AImageReader* reader)
{
	CamAndroidStream* stream = (CamAndroidStream*)context;
	WINPR_ASSERT(stream);
	WINPR_ASSERT(reader);

	EnterCriticalSection(&stream->lock);
	const BOOL streaming = stream->streaming;
	LeaveCriticalSection(&stream->lock);

	if (!streaming)
		return;

	AImage* image = NULL;
    const media_status_t imageStatus = AImageReader_acquireLatestImage(reader, &image);
    if (stream->display && stream->displayImageReady)
    {
        if (imageStatus == AMEDIA_OK && image)
        {
            InterlockedExchange(&stream->displayReaderStatus, 1);
            SetEvent(stream->displayImageReady);
        }
        else if (imageStatus == AMEDIA_ERROR_UNKNOWN)
        {
            InterlockedExchange(&stream->displayReaderStatus, -1);
            SetEvent(stream->displayImageReady);
        }
    }
    if (stream->display && stream->displayCallbacks++ < 3)
    {
        int32_t format = 0, planes = 0;
        if (image) { AImage_getFormat(image, &format); AImage_getNumberOfPlanes(image, &planes); }
        LOGI("Display ImageReader: status=%d format=%d planes=%d", imageStatus, format, planes);
    }
	if (imageStatus == AMEDIA_OK && image)
	{
        /* Stop/reconfigure may wait for ImageReader callbacks. Drop rather than
         * wait on the broker while it closes the reader. */
        if (!stream->producer || pthread_mutex_trylock(&cameraBrokerLock) == 0)
        {
            cam_android_deliver_frame(stream, image);
            if (stream->producer) pthread_mutex_unlock(&cameraBrokerLock);
        }
		AImage_delete(image);
	}
}

static BOOL cam_android_create_reader(CamAndroidStream* stream, int width, int height, int format)
{
    if (AImageReader_new(width, height, format, 6, &stream->imageReader) != AMEDIA_OK)
        return FALSE;
    const AImageReader_ImageListener listener = {
        .context = stream, .onImageAvailable = cam_android_on_image_available
    };
    AImageReader_setImageListener(stream->imageReader, &listener);
    return AImageReader_getWindow(stream->imageReader, &stream->window) == AMEDIA_OK;
}

/* Mark the device broken so it is reopened on next use. */
static void cam_android_mark_device_error(CamAndroidStream* stream)
{
	if (!stream)
		return;
	EnterCriticalSection(&stream->lock);
	stream->deviceError = TRUE;
	stream->streaming = FALSE;
	LeaveCriticalSection(&stream->lock);
}

static void cam_android_device_disconnected(void* context, ACameraDevice* device)
{
	WINPR_UNUSED(device);
	WLog_WARN(TAG, "Camera device disconnected");
	cam_android_mark_device_error((CamAndroidStream*)context);
}

static void cam_android_device_error(void* context, ACameraDevice* device, int error)
{
	WINPR_UNUSED(device);
	WLog_ERR(TAG, "Camera device error %d", error);
	cam_android_mark_device_error((CamAndroidStream*)context);
}

static void cam_android_session_active(void* context, ACameraCaptureSession* session)
{
	WINPR_UNUSED(context);
	WINPR_UNUSED(session);
	WLog_DBG(TAG, "Capture session active");
}

static void cam_android_session_closed(void* context, ACameraCaptureSession* session)
{
	WINPR_UNUSED(context);
	WINPR_UNUSED(session);
	WLog_DBG(TAG, "Capture session closed");
}

static void cam_android_session_ready(void* context, ACameraCaptureSession* session)
{
	WINPR_UNUSED(context);
	WINPR_UNUSED(session);
	WLog_DBG(TAG, "Capture session ready");
}

/* TRUE if the camera is BACKWARD_COMPATIBLE. Depth/IR/sub-sensor cameras lack this and cannot
 * configure a normal YUV session, so they must not be offered to the server. */
static BOOL cam_android_is_backward_compatible(ACameraMetadata* characteristics)
{
	WINPR_ASSERT(characteristics);

	ACameraMetadata_const_entry caps;
	if (ACameraMetadata_getConstEntry(characteristics, ACAMERA_REQUEST_AVAILABLE_CAPABILITIES,
	                                  &caps) != ACAMERA_OK)
		return FALSE;

	for (uint32_t i = 0; i < caps.count; i++)
	{
		if (caps.data.u8[i] == ACAMERA_REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE)
			return TRUE;
	}
	return FALSE;
}

static UINT cam_android_enumerate(ICamHal* ihal, ICamHalEnumCallback callback, CameraPlugin* ecam,
                                  GENERIC_CHANNEL_CALLBACK* hchannel)
{
	CamAndroidHal* hal = (CamAndroidHal*)ihal;
	WINPR_ASSERT(hal);

	UINT count = 0;
    IFCALL(callback, ecam, hchannel, DISPLAY_DEVICE_ID, "Headset Display");
    count++;

	ACameraIdList* idList = NULL;
	if (ACameraManager_getCameraIdList(hal->manager, &idList) != ACAMERA_OK || !idList)
        return g_camera_count = count;

	/* Offer only the primary camera per facing direction: auxiliary back cameras (ultrawide/tele/
	 * macro/depth) advertise resolutions but fail to configure a YUV session and freeze the
	 * channel. Indexed by ACAMERA_LENS_FACING_* (FRONT=0, BACK=1, EXTERNAL=2). */
	BOOL seenFacing[ACAMERA_LENS_FACING_EXTERNAL + 1] = WINPR_C_ARRAY_INIT;

	for (int i = 0; i < idList->numCameras; i++)
	{
		const char* cameraId = idList->cameraIds[i];

		ACameraMetadata* characteristics = NULL;
		if (ACameraManager_getCameraCharacteristics(hal->manager, cameraId, &characteristics) !=
		    ACAMERA_OK)
			continue;

		/* Skip depth/IR/sub-sensor cameras that can't configure a normal capture session. */
		if (!cam_android_is_backward_compatible(characteristics))
		{
			WLog_DBG(TAG, "Skipping camera %s: not BACKWARD_COMPATIBLE", cameraId);
			ACameraMetadata_free(characteristics);
			continue;
		}

		ACameraMetadata_const_entry facingEntry;
		const char* facingName = "Passthrough Camera";
		uint8_t facing = ACAMERA_LENS_FACING_EXTERNAL;
		if (ACameraMetadata_getConstEntry(characteristics, ACAMERA_LENS_FACING, &facingEntry) ==
		    ACAMERA_OK)
		{
			facing = facingEntry.data.u8[0];
			switch (facing)
			{
				case ACAMERA_LENS_FACING_FRONT:
					facingName = "Virtual Camera";
					break;
				case ACAMERA_LENS_FACING_BACK:
				default:
					facingName = "Passthrough Camera";
					break;
			}
		}

		/* Bounds-guard seenFacing[] against an out-of-range facing value. */
		if (facing <= ACAMERA_LENS_FACING_EXTERNAL)
		{
			if (seenFacing[facing])
			{
				WLog_DBG(TAG, "Skipping camera %s: already have a %s", cameraId, facingName);
				ACameraMetadata_free(characteristics);
				continue;
			}
			seenFacing[facing] = TRUE;
		}

		char deviceId[64];
		(void)_snprintf(deviceId, sizeof(deviceId), ANDROID_CAMERA_PREFIX "%s", cameraId);

		WLog_INFO(TAG, "Encountered camera %s, registered as '%s' (deviceId=%s)", cameraId, facingName, deviceId);
		IFCALL(callback, ecam, hchannel, deviceId, facingName);
		count++;

		ACameraMetadata_free(characteristics);
	}

	ACameraManager_deleteCameraIdList(idList);
	g_camera_count = count;
	return count;
}

/* Stop streaming and fully release the camera. Order matters: close the session and device before
 * the ImageReader, or the camera service fails to unconfigure and leaves the device running. */
static void cam_android_close_device(CamAndroidStream* stream)
{
	WINPR_ASSERT(stream);

	EnterCriticalSection(&stream->lock);
	stream->streaming = FALSE;
	if (stream->recording)
	{
		stream->recording = FALSE;
		InterlockedDecrement(&stream->state->activeStreams);
	}
	LeaveCriticalSection(&stream->lock);

	if (stream->imageReader)
		AImageReader_setImageListener(stream->imageReader, NULL);

    if (stream->display && stream->imageReader)
        quest_display_stop(stream->state);

    if (stream->source)
    {
        pthread_mutex_lock(&cameraBrokerLock);
        CamAndroidStream* source = stream->source;
        CamAndroidStream** link = &source->subscribers;
        while (*link && *link != stream) link = &(*link)->nextSubscriber;
        if (*link) *link = stream->nextSubscriber;
        stream->source = NULL;
        stream->nextSubscriber = NULL;
        if (source->display) quest_display_stop(stream->state);
        if (!source->subscribers) {
            cam_android_close_device(source);
            if (source->display) source->state = NULL;
        } else if (source->display) source->state = source->subscribers->state;
        pthread_mutex_unlock(&cameraBrokerLock);
    }
	cam_android_close_session(stream);

	if (stream->device)
	{
		ACameraDevice_close(stream->device);
		stream->device = NULL;
	}

	if (stream->imageReader)
	{
		AImageReader_delete(stream->imageReader);
		stream->imageReader = NULL;
		stream->window = NULL;
	}
	free(stream->nv12Buffer);
	stream->nv12Buffer = NULL;
	stream->nv12BufferSize = 0;
    free(stream->planarBuffer);
    stream->planarBuffer = NULL;
    stream->planarBufferSize = 0;
    if (stream->displayImageReady) CloseHandle(stream->displayImageReady);
    stream->displayImageReady = NULL;
    InterlockedExchange(&stream->displayReaderStatus, 0);
	stream->deviceError = FALSE;
    stream->lastFrameTime = 0;
    stream->displayCallbacks = 0;
}

static CamAndroidStream* cam_android_get_or_create_stream(CamAndroidHal* hal, const char* deviceId,
                                                          CAM_ERROR_CODE* errorCode)
{
	WINPR_ASSERT(hal);
	WINPR_ASSERT(deviceId);
	WINPR_ASSERT(errorCode);

	CamAndroidStream* stream = (CamAndroidStream*)HashTable_GetItemValue(hal->streams, deviceId);
	if (stream)
		return stream;

	stream = (CamAndroidStream*)calloc(1, sizeof(CamAndroidStream));
	if (!stream)
	{
		*errorCode = CAM_ERROR_CODE_OutOfMemory;
		return NULL;
	}
	stream->state = hal->state;
	if (!InitializeCriticalSectionEx(&stream->lock, 0, 0))
	{
		free(stream);
		*errorCode = CAM_ERROR_CODE_UnexpectedError;
		return NULL;
	}
	if (!HashTable_Insert(hal->streams, deviceId, stream))
	{
		DeleteCriticalSection(&stream->lock);
		free(stream);
		*errorCode = CAM_ERROR_CODE_UnexpectedError;
		return NULL;
	}
	return stream;
}

/* Open a broker-owned camera lazily so probing never takes over another stream. */
static BOOL cam_android_open_device(CamAndroidHal* hal, CamAndroidStream* stream,
                                    const char* deviceId, const char* cameraId,
                                    CAM_ERROR_CODE* errorCode)
{
	WINPR_ASSERT(hal);
	WINPR_ASSERT(stream);
	WINPR_ASSERT(deviceId);
	WINPR_ASSERT(cameraId);
	WINPR_ASSERT(errorCode);

	/* Reopen a device left broken by a previous error. */
	EnterCriticalSection(&stream->lock);
	const BOOL hadError = stream->deviceError;
	LeaveCriticalSection(&stream->lock);
	if (stream->device && hadError)
		cam_android_close_device(stream);

	if (stream->device)
		return TRUE; /* already open */

	/* Existing producers must survive another session opening a camera. */

	ACameraDevice_StateCallbacks deviceCallbacks = {
		.context = stream,
		.onDisconnected = cam_android_device_disconnected,
		.onError = cam_android_device_error,
	};

	camera_status_t status =
	    ACameraManager_openCamera(hal->manager, cameraId, &deviceCallbacks, &stream->device);
	if (status != ACAMERA_OK)
	{
		LOGE("ACameraManager_openCamera failed for camera %s: status=%d", cameraId, status);
		WLog_ERR(TAG, "ACameraManager_openCamera failed: %d", status);
		*errorCode = CAM_ERROR_CODE_InvalidRequest;
		return FALSE;
	}

	LOGI("Opened camera %s successfully", cameraId);
	WLog_DBG(TAG, "Opened camera %s", cameraId);
	return TRUE;
}

static BOOL cam_android_activate(ICamHal* ihal, const char* deviceId, CAM_ERROR_CODE* errorCode)
{
	CamAndroidHal* hal = (CamAndroidHal*)ihal;
	WINPR_ASSERT(hal);
	WINPR_ASSERT(deviceId);
	WINPR_ASSERT(errorCode);

	*errorCode = CAM_ERROR_CODE_None;

	const char* cameraId = cam_android_extract_camera_id(deviceId);
	LOGI("cam_android_activate called for deviceId=%s (cameraId=%s)", deviceId, cameraId ? cameraId : "NULL");
	if (!cameraId && strcmp(deviceId, DISPLAY_DEVICE_ID) != 0)
	{
		*errorCode = CAM_ERROR_CODE_InvalidRequest;
		return FALSE;
	}

	/* Hardware is opened lazily in StartStream, not here. */
	return cam_android_get_or_create_stream(hal, deviceId, errorCode) != NULL;
}

static BOOL cam_android_deactivate(ICamHal* ihal, const char* deviceId, CAM_ERROR_CODE* errorCode)
{
	CamAndroidHal* hal = (CamAndroidHal*)ihal;
	WINPR_ASSERT(hal);
	WINPR_ASSERT(deviceId);
	WINPR_ASSERT(errorCode);

	*errorCode = CAM_ERROR_CODE_None;

	CamAndroidStream* stream = (CamAndroidStream*)HashTable_GetItemValue(hal->streams, deviceId);
	if (!stream)
		return TRUE;

	cam_android_close_device(stream);
	return TRUE;
}

static INT16 cam_android_get_media_type_descriptions(ICamHal* ihal, const char* deviceId,
                                                     size_t streamIndex,
                                                     const CAM_MEDIA_FORMAT_INFO* supportedFormats,
                                                     size_t nSupportedFormats,
                                                     CAM_MEDIA_TYPE_DESCRIPTION* mediaTypes,
                                                     size_t* nMediaTypes)
{
	WINPR_UNUSED(streamIndex);
	CamAndroidHal* hal = (CamAndroidHal*)ihal;
	WINPR_ASSERT(hal);
	WINPR_ASSERT(deviceId);
	WINPR_ASSERT(supportedFormats || (nSupportedFormats == 0));
	WINPR_ASSERT(mediaTypes);
	WINPR_ASSERT(nMediaTypes);

	size_t maxMediaTypes = *nMediaTypes;
	*nMediaTypes = 0;

	const char* cameraId = cam_android_extract_camera_id(deviceId);
	if (!cameraId && strcmp(deviceId, DISPLAY_DEVICE_ID) != 0)
		return -1;

	/* Find preferred format: first try NV12 -> H264, then fallback to uncompressed NV12 -> NV12 */
	INT16 formatIndex = -1;
	for (size_t i = 0; i < nSupportedFormats; i++)
	{
		if (supportedFormats[i].inputFormat == CAM_MEDIA_FORMAT_NV12 &&
		    supportedFormats[i].outputFormat == CAM_MEDIA_FORMAT_H264)
		{
			formatIndex = (INT16)i;
			break;
		}
	}
	if (formatIndex < 0)
	{
		for (size_t i = 0; i < nSupportedFormats; i++)
		{
			if (supportedFormats[i].inputFormat == CAM_MEDIA_FORMAT_NV12)
			{
				formatIndex = (INT16)i;
				break;
			}
		}
	}
	if (formatIndex < 0)
	{
		WLog_WARN(TAG, "Server does not offer NV12 format");
		return -1;
	}

    if (strcmp(deviceId, DISPLAY_DEVICE_ID) == 0)
    {
        if (maxMediaTypes == 0 || supportedFormats[formatIndex].outputFormat != CAM_MEDIA_FORMAT_H264)
            return -1;
        mediaTypes[0] = (CAM_MEDIA_TYPE_DESCRIPTION){
            .Format = CAM_MEDIA_FORMAT_NV12, .Width = 1280, .Height = 720,
            .FrameRateNumerator = CAM_ANDROID_FPS, .FrameRateDenominator = 1,
            .PixelAspectRatioNumerator = 1, .PixelAspectRatioDenominator = 1
        };
        *nMediaTypes = 1;
        return formatIndex;
    }

	ACameraMetadata* characteristics = NULL;
	if (ACameraManager_getCameraCharacteristics(hal->manager, cameraId, &characteristics) !=
	    ACAMERA_OK)
		return -1;

	/* Stream configs: [format, width, height, isInput] tuples. */
	ACameraMetadata_const_entry entry;
	if (ACameraMetadata_getConstEntry(
	        characteristics, ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, &entry) != ACAMERA_OK)
	{
		ACameraMetadata_free(characteristics);
		return -1;
	}

	const BOOL isH264 = (supportedFormats[formatIndex].outputFormat == CAM_MEDIA_FORMAT_H264);

	size_t nTypes = 0;
	for (uint32_t i = 0; i + 3 < entry.count; i += 4)
	{
		const int32_t fmt = entry.data.i32[i];
		const int32_t w = entry.data.i32[i + 1];
		const int32_t h = entry.data.i32[i + 2];
		const int32_t isInput = entry.data.i32[i + 3];

		if (isInput != 0)
			continue;
		if (fmt != AIMAGE_FORMAT_YUV_420_888)
			continue;
		/* Cap resolutions: For H.264 hardware encoding, cap by selected camera max dimension
		 * (480 for SD, 720 for 720p, 1080 for 1080p).
		 * For uncompressed NV12, cap to 640x480 to avoid bandwidth saturation. */
		const int maxDim = g_camera_max_dimension > 0 ? g_camera_max_dimension : 720;
		if (isH264)
		{
			if (g_camera_max_dimension != -1 && (h > maxDim || w > (maxDim * 16 / 9 + 64)))
				continue;
		}
		else
		{
			if (w > 640 || h > 480 || h > maxDim)
				continue;
		}

		mediaTypes[nTypes].Format = CAM_MEDIA_FORMAT_NV12;
		mediaTypes[nTypes].Width = (UINT32)w;
		mediaTypes[nTypes].Height = (UINT32)h;
		mediaTypes[nTypes].FrameRateNumerator = CAM_ANDROID_FPS;
		mediaTypes[nTypes].FrameRateDenominator = 1;
		mediaTypes[nTypes].PixelAspectRatioNumerator = 1;
		mediaTypes[nTypes].PixelAspectRatioDenominator = 1;

		WLog_DBG(TAG, "Camera format NV12 %dx%d @ %dfps", w, h, CAM_ANDROID_FPS);

		if (++nTypes >= maxMediaTypes)
			break;
	}

	/* Sort media types descending by pixel area so the highest resolution (up to maxDim)
	 * appears first in the advertised list. */
	for (size_t i = 0; i < nTypes; i++)
	{
		for (size_t j = i + 1; j < nTypes; j++)
		{
			UINT64 areaI = (UINT64)mediaTypes[i].Width * mediaTypes[i].Height;
			UINT64 areaJ = (UINT64)mediaTypes[j].Width * mediaTypes[j].Height;
			if (areaJ > areaI)
			{
				CAM_MEDIA_TYPE_DESCRIPTION tmp = mediaTypes[i];
				mediaTypes[i] = mediaTypes[j];
				mediaTypes[j] = tmp;
			}
		}
	}

	*nMediaTypes = nTypes;
	ACameraMetadata_free(characteristics);

	LOGI("cam_android_get_media_type_descriptions: deviceId=%s (cameraId=%s), formatIndex=%d, count=%zu, maxDim=%d",
	     deviceId, cameraId ? cameraId : "NULL", formatIndex, nTypes, g_camera_max_dimension);

	if (nTypes == 0)
	{
		LOGE("No supported YUV resolutions found for camera %s", cameraId);
		WLog_ERR(TAG, "No supported YUV resolutions found for camera %s", cameraId);
		return -1;
	}

	return formatIndex;
}

static CAM_ERROR_CODE cam_android_start_capture(ICamHal* ihal, CameraDevice* dev, size_t streamIndex,
                                               const CAM_MEDIA_TYPE_DESCRIPTION* mediaType,
                                               ICamHalSampleCapturedCallback callback)
{
	CamAndroidHal* hal = (CamAndroidHal*)ihal;
	WINPR_ASSERT(hal);
	WINPR_ASSERT(dev);
	WINPR_ASSERT(mediaType);
	WINPR_ASSERT(callback);

	const char* deviceId = dev->deviceId;
    const BOOL display = strcmp(deviceId, DISPLAY_DEVICE_ID) == 0;

	const char* cameraId = cam_android_extract_camera_id(deviceId);
	if (!cameraId && !display)
		return CAM_ERROR_CODE_InvalidRequest;

	const int32_t width = (int32_t)mediaType->Width;
	const int32_t height = (int32_t)mediaType->Height;
	LOGI("cam_android_start_stream: requesting deviceId=%s (cameraId=%s) resolution=%dx%d",
	     deviceId, cameraId, width, height);

	CAM_ERROR_CODE errorCode = CAM_ERROR_CODE_None;
	CamAndroidStream* stream = cam_android_get_or_create_stream(hal, deviceId, &errorCode);
	if (!stream)
		return errorCode;

    if (display && (width != 1280 || height != 720 || stream->imageReader))
        return CAM_ERROR_CODE_InvalidRequest;
    stream->display = display;

	if (!display && !cam_android_open_device(hal, stream, deviceId, cameraId, &errorCode))
		return errorCode;

	stream->dev = dev;
	stream->streamIndex = streamIndex;
	stream->sampleCallback = callback;

	if (AImageReader_new(width, height, display && stream->displayRgba ? AIMAGE_FORMAT_RGBA_8888 : AIMAGE_FORMAT_YUV_420_888, 6, &stream->imageReader) !=
	    AMEDIA_OK)
	{
		LOGE("AImageReader_new failed for %s (%dx%d)", deviceId, width, height);
		WLog_ERR(TAG, "AImageReader_new failed");
		return CAM_ERROR_CODE_UnexpectedError;
	}

	AImageReader_ImageListener listener = {
		.context = stream,
		.onImageAvailable = cam_android_on_image_available,
	};
	AImageReader_setImageListener(stream->imageReader, &listener);

	if (AImageReader_getWindow(stream->imageReader, &stream->window) != AMEDIA_OK)
	{
		LOGE("AImageReader_getWindow failed for %s", deviceId);
		WLog_ERR(TAG, "AImageReader_getWindow failed");
		goto error;
	}

    if (display)
    {
        stream->displayImageReady = CreateEvent(NULL, TRUE, FALSE, NULL);
        if (!stream->displayImageReady) goto error;
        EnterCriticalSection(&stream->lock);
        stream->streaming = TRUE;
        LeaveCriticalSection(&stream->lock);
        if (!quest_display_start(stream->state, stream->window))
            goto error;
        if (WaitForSingleObject(stream->displayImageReady, 2500) != WAIT_OBJECT_0) goto error;
        if (InterlockedCompareExchange(&stream->displayReaderStatus, 0, 0) == -1)
        {
            LOGW("Display producer overrides YUV format; attaching an RGBA reader to the same projection");
            stream->displayRgba = TRUE;
            quest_display_stop(stream->state);
            EnterCriticalSection(&stream->lock);
            stream->streaming = FALSE;
            LeaveCriticalSection(&stream->lock);
            AImageReader_setImageListener(stream->imageReader, NULL);
            AImageReader_delete(stream->imageReader);
            stream->imageReader = NULL;
            stream->window = NULL;
            stream->lastFrameTime = 0;
            stream->displayCallbacks = 0;
            InterlockedExchange(&stream->displayReaderStatus, 0);
            ResetEvent(stream->displayImageReady);
            if (!cam_android_create_reader(stream, width, height, AIMAGE_FORMAT_RGBA_8888)) goto error;
            EnterCriticalSection(&stream->lock);
            stream->streaming = TRUE;
            LeaveCriticalSection(&stream->lock);
            if (!quest_display_start(stream->state, stream->window)) goto error;
            if (WaitForSingleObject(stream->displayImageReady, 2500) != WAIT_OBJECT_0 ||
                InterlockedCompareExchange(&stream->displayReaderStatus, 0, 0) != 1) goto error;
        }
        goto started;
    }

	ACaptureSessionOutput_create(stream->window, &stream->sessionOutput);
	ACaptureSessionOutputContainer_create(&stream->outputContainer);
	ACaptureSessionOutputContainer_add(stream->outputContainer, stream->sessionOutput);
	ACameraOutputTarget_create(stream->window, &stream->outputTarget);

	/* Quest passthrough cameras require TEMPLATE_RECORD to stream YUV frames. */
	ACameraDevice_createCaptureRequest(stream->device, TEMPLATE_RECORD, &stream->captureRequest);
	ACaptureRequest_addTarget(stream->captureRequest, stream->outputTarget);

	/* Request 30 FPS AE target range. */
	const int32_t fpsRange[2] = { CAM_ANDROID_FPS, CAM_ANDROID_FPS };
	ACaptureRequest_setEntry_i32(stream->captureRequest, ACAMERA_CONTROL_AE_TARGET_FPS_RANGE, 2, fpsRange);

	ACameraCaptureSession_stateCallbacks sessionCallbacks = {
		.context = stream,
		.onActive = cam_android_session_active,
		.onClosed = cam_android_session_closed,
		.onReady = cam_android_session_ready,
	};

	if (ACameraDevice_createCaptureSession(stream->device, stream->outputContainer,
	                                       &sessionCallbacks, &stream->session) != ACAMERA_OK)
	{
		LOGE("ACameraDevice_createCaptureSession failed for %s", deviceId);
		WLog_ERR(TAG, "ACameraDevice_createCaptureSession failed");
		goto error;
	}

	if (ACameraCaptureSession_setRepeatingRequest(stream->session, NULL, 1, &stream->captureRequest,
	                                              NULL) != ACAMERA_OK)
	{
		LOGE("ACameraCaptureSession_setRepeatingRequest failed for %s", deviceId);
		WLog_ERR(TAG, "ACameraCaptureSession_setRepeatingRequest failed");
		goto error;
	}

started:
	EnterCriticalSection(&stream->lock);
	stream->streaming = TRUE;
	if (!stream->producer && stream->state && !stream->recording)
	{
		stream->recording = TRUE;
		InterlockedIncrement(&stream->state->activeStreams);
	}
	LeaveCriticalSection(&stream->lock);

	LOGI("Camera stream successfully started: %s %dx%d", deviceId, width, height);
	WLog_DBG(TAG, "Camera stream started: %dx%d", width, height);
	return CAM_ERROR_CODE_None;

error:
	cam_android_close_device(stream);
	return CAM_ERROR_CODE_UnexpectedError;
}

static CAM_ERROR_CODE cam_android_start_stream(ICamHal* ihal, CameraDevice* dev, size_t streamIndex,
                                               const CAM_MEDIA_TYPE_DESCRIPTION* mediaType,
                                               ICamHalSampleCapturedCallback callback)
{
    const BOOL display = strcmp(dev->deviceId, DISPLAY_DEVICE_ID) == 0;
    CamAndroidHal* hal = (CamAndroidHal*)ihal;
    CAM_ERROR_CODE error = CAM_ERROR_CODE_None;
    CamAndroidStream* peer = cam_android_get_or_create_stream(hal, dev->deviceId, &error);
    if (!peer) return error;
    pthread_mutex_lock(&cameraBrokerLock);
    const BOOL alreadyStreaming = peer->source != NULL;
    pthread_mutex_unlock(&cameraBrokerLock);
    if (alreadyStreaming) return CAM_ERROR_CODE_InvalidRequest;
    /* A null surface requests only this session's once-only consent. Waiting must
     * not hold the producer lock or pause another session's capture. */
    if (display && !quest_display_start(peer->state, NULL)) return CAM_ERROR_CODE_InvalidRequest;
    pthread_mutex_lock(&cameraBrokerLock);
    if (peer->source) { error = CAM_ERROR_CODE_InvalidRequest; goto done; }
    if (!cameraBroker.manager)
    {
        cameraBroker.manager = ACameraManager_create();
        cameraBroker.streams = HashTable_New(FALSE);
        if (!cameraBroker.streams || !HashTable_SetupForStringData(cameraBroker.streams, FALSE))
        {
            error = CAM_ERROR_CODE_OutOfMemory;
            goto done;
        }
    }
    CamAndroidStream* source = cam_android_get_or_create_stream(&cameraBroker, dev->deviceId, &error);
    if (!source) goto done;
    source->producer = TRUE;
    if (!source->device && !source->imageReader)
    {
        if (display) source->state = peer->state;
        CAM_MEDIA_TYPE_DESCRIPTION capture = *mediaType;
        /* Capture the largest native mode once, then pack to subscriber sizes. */
        ACameraMetadata* metadata = NULL;
        const char* cameraId = cam_android_extract_camera_id(dev->deviceId);
        if (!display && ACameraManager_getCameraCharacteristics(cameraBroker.manager, cameraId, &metadata) == ACAMERA_OK)
        {
            ACameraMetadata_const_entry entry;
            if (ACameraMetadata_getConstEntry(metadata, ACAMERA_SCALER_AVAILABLE_STREAM_CONFIGURATIONS, &entry) == ACAMERA_OK)
                for (uint32_t i = 0; i + 3 < entry.count; i += 4)
                    if (entry.data.i32[i] == AIMAGE_FORMAT_YUV_420_888 && entry.data.i32[i + 3] == 0 &&
                        (int64_t)entry.data.i32[i + 1] * entry.data.i32[i + 2] > (int64_t)capture.Width * capture.Height)
                    { capture.Width = entry.data.i32[i + 1]; capture.Height = entry.data.i32[i + 2]; }
            ACameraMetadata_free(metadata);
        }
        error = cam_android_start_capture(&cameraBroker.iHal, dev, streamIndex, &capture, callback);
        if (error) goto done;
    }
    peer->width = mediaType->Width;
    peer->height = mediaType->Height;
    peer->dev = dev;
    peer->streamIndex = streamIndex;
    peer->sampleCallback = callback;
    peer->source = source;
    peer->nextSubscriber = source->subscribers;
    source->subscribers = peer;
    peer->streaming = TRUE;
    peer->recording = TRUE;
    InterlockedIncrement(&peer->state->activeStreams);
    LOGI("Shared camera subscriber: %s %dx%d", dev->deviceId, peer->width, peer->height);
done:
    pthread_mutex_unlock(&cameraBrokerLock);
    if (error && display && !peer->source) quest_display_stop(peer->state);
    return error;
}

static CAM_ERROR_CODE cam_android_stop_stream(ICamHal* ihal, const char* deviceId,
                                              size_t streamIndex)
{
	WINPR_UNUSED(streamIndex);
	CamAndroidHal* hal = (CamAndroidHal*)ihal;
	WINPR_ASSERT(hal);
	WINPR_ASSERT(deviceId);

	CamAndroidStream* stream = (CamAndroidStream*)HashTable_GetItemValue(hal->streams, deviceId);
	if (!stream)
		return CAM_ERROR_CODE_None;

	LOGI("cam_android_stop_stream called for %s", deviceId);
	cam_android_close_device(stream);

	WLog_DBG(TAG, "Camera stream stopped for %s", deviceId);
	return CAM_ERROR_CODE_None;
}

static void cam_android_stream_free(void* obj)
{
	CamAndroidStream* stream = (CamAndroidStream*)obj;
	if (!stream)
		return;

	cam_android_close_device(stream);
	DeleteCriticalSection(&stream->lock);
	free(stream);
}

static CAM_ERROR_CODE cam_android_free(ICamHal* ihal)
{
	CamAndroidHal* hal = (CamAndroidHal*)ihal;
	if (!hal)
		return CAM_ERROR_CODE_NotInitialized;

	HashTable_Free(hal->streams);
	if (hal->manager)
		ACameraManager_delete(hal->manager);
	free(hal);
	return CAM_ERROR_CODE_None;
}

FREERDP_ENTRY_POINT(UINT VCAPITYPE android_freerdp_rdpecam_client_subsystem_entry(
    PFREERDP_CAMERA_HAL_ENTRY_POINTS pEntryPoints))
{
	WINPR_ASSERT(pEntryPoints);
	if (!registrationState) return CHANNEL_RC_INITIALIZATION_ERROR;

	CamAndroidHal* hal = (CamAndroidHal*)calloc(1, sizeof(CamAndroidHal));
	if (!hal)
		return CHANNEL_RC_NO_MEMORY;

	hal->state = registrationState;
	hal->manager = ACameraManager_create();
	if (!hal->manager)
	{
		free(hal);
		return ERROR_INTERNAL_ERROR;
	}

	hal->streams = HashTable_New(FALSE);
	if (!hal->streams)
		goto error;

	if (!HashTable_SetupForStringData(hal->streams, FALSE))
		goto error;

	wObject* obj = HashTable_ValueObject(hal->streams);
	WINPR_ASSERT(obj);
	obj->fnObjectFree = cam_android_stream_free;

	hal->iHal.Enumerate = cam_android_enumerate;
	hal->iHal.Activate = cam_android_activate;
	hal->iHal.Deactivate = cam_android_deactivate;
	hal->iHal.GetMediaTypeDescriptions = cam_android_get_media_type_descriptions;
	hal->iHal.StartStream = cam_android_start_stream;
	hal->iHal.StopStream = cam_android_stop_stream;
	hal->iHal.Free = cam_android_free;

	UINT ret = pEntryPoints->pRegisterCameraHal(pEntryPoints->plugin, &hal->iHal);
	if (ret != CHANNEL_RC_OK)
	{
		WLog_ERR(TAG, "RegisterCameraHal failed: %" PRIu32, ret);
		goto error;
	}

	return ret;

error:
	cam_android_free(&hal->iHal);
	return ERROR_INTERNAL_ERROR;
}

#ifndef NDEBUG
static UINT cam_test_register(IWTSPlugin* plugin, ICamHal* hal)
{
    ((CameraPlugin*)plugin)->ihal = hal;
    return CHANNEL_RC_OK;
}

/* Exercises real HAL registration, frame masking and teardown with synthetic
 * streams. No Camera2 capture or host is implied by this fixture. */
BOOL cam_android_verify_isolation(QuestCameraState* first, QuestCameraState* second)
{
    CameraPlugin plugins[2] = {0};
    QuestCameraState* states[2] = {first, second};
    BOOL success = FALSE;
    QuestCameraState* previous = registrationState;
    for (size_t i = 0; i < 2; i++)
    {
        registrationState = states[i];
        FREERDP_CAMERA_HAL_ENTRY_POINTS entry = {0};
        entry.plugin = &plugins[i].iface;
        entry.pRegisterCameraHal = cam_test_register;
        if (android_freerdp_rdpecam_client_subsystem_entry(&entry) != CHANNEL_RC_OK)
            goto cleanup;
        CamAndroidHal* hal = (CamAndroidHal*)plugins[i].ihal;
        if (hal->state != states[i]) goto cleanup;
        CAM_ERROR_CODE error;
        CamAndroidStream* stream = cam_android_get_or_create_stream(hal, "test-camera", &error);
        if (!stream) goto cleanup;
        stream->recording = TRUE;
        stream->streaming = TRUE;
        InterlockedIncrement(&states[i]->activeStreams);
        /* A second stream on the same HAL device must fail before consent or
         * hardware mutation, without re-linking the existing subscriber. */
        CameraDevice duplicate = {0};
        memcpy(duplicate.deviceId, "test-camera", sizeof("test-camera"));
        CAM_MEDIA_TYPE_DESCRIPTION mediaType = {0};
        stream->source = stream;
        const CAM_ERROR_CODE duplicateError =
            hal->iHal.StartStream(&hal->iHal, &duplicate, 1, &mediaType, NULL);
        stream->source = NULL;
        if (duplicateError != CAM_ERROR_CODE_InvalidRequest || !quest_camera_is_recording(states[i]))
            goto cleanup;
    }
    BYTE frames[2][24];
    memset(frames, 85, sizeof(frames));
    cam_android_apply_mute(first, frames[0], 16);
    cam_android_apply_mute(second, frames[1], 16);
    for (size_t i = 0; i < 24; i++)
        if (frames[0][i] != (i < 16 ? 0 : 128) || frames[1][i] != 85) goto cleanup;
    if (!quest_camera_is_recording(first) || !quest_camera_is_recording(second)) goto cleanup;
    plugins[1].ihal->Free(plugins[1].ihal);
    plugins[1].ihal = NULL;
    if (!quest_camera_is_muted(first) || !quest_camera_is_recording(first) ||
        quest_camera_is_recording(second)) goto cleanup;
    success = TRUE;
cleanup:
    for (size_t i = 0; i < 2; i++)
        if (plugins[i].ihal) plugins[i].ihal->Free(plugins[i].ihal);
    registrationState = previous;
    return success && !quest_camera_is_recording(first);
}
#endif
