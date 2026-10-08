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

/* Hardware H.264 Encoder state using Android NDK AMediaCodec */
typedef struct
{
	AMediaCodec* codec;
	UINT32 width;
	UINT32 height;
	UINT32 framerate;
	UINT32 bitrate;
	BOOL configured;
	int64_t frameIndex;
} QuestH264Encoder;

static QuestH264Encoder g_h264Encoder = { 0 };

static int64_t get_time_us(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (int64_t)ts.tv_sec * 1000000LL + (ts.tv_nsec / 1000LL);
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

static void quest_h264_encoder_cleanup(QuestH264Encoder* enc)
{
	if (!enc)
		return;

	if (enc->codec)
	{
		LOGI("Stopping and deleting Quest AMediaCodec H.264 hardware encoder");
		AMediaCodec_stop(enc->codec);
		AMediaCodec_delete(enc->codec);
		enc->codec = NULL;
	}
	enc->configured = FALSE;
	enc->frameIndex = 0;
}

static BOOL quest_h264_encoder_init(QuestH264Encoder* enc, UINT32 width, UINT32 height, UINT32 framerate)
{
	if (!enc)
		return FALSE;

	if (enc->configured && enc->width == width && enc->height == height && enc->framerate == framerate)
		return TRUE;

	quest_h264_encoder_cleanup(enc);

	UINT32 bitrate = h264_get_max_bitrate(height);
	LOGI("Initializing Quest AMediaCodec H.264 encoder: %ux%u @ %u fps, bitrate=%u bps",
	     width, height, framerate, bitrate);

	AMediaCodec* codec = AMediaCodec_createEncoderByType("video/avc");
	if (!codec)
	{
		LOGE("AMediaCodec_createEncoderByType('video/avc') failed");
		return FALSE;
	}

	AMediaFormat* format = AMediaFormat_new();
	if (!format)
	{
		AMediaCodec_delete(codec);
		return FALSE;
	}

	AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
	AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, (int32_t)width);
	AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, (int32_t)height);
	/* Explicitly inform encoder of geometry and crop rect */
	AMediaFormat_setInt32(format, "stride", (int32_t)width);
	AMediaFormat_setInt32(format, "slice-height", (int32_t)height);
	AMediaFormat_setInt32(format, "crop-left", 0);
	AMediaFormat_setInt32(format, "crop-top", 0);
	AMediaFormat_setInt32(format, "crop-right", (int32_t)(width - 1));
	AMediaFormat_setInt32(format, "crop-bottom", (int32_t)(height - 1));
	/* 21 = COLOR_FormatYUV420SemiPlanar (NV12) */
	AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, 21);
	AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, (int32_t)bitrate);
	AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE, (int32_t)(framerate > 0 ? framerate : 30));
	/* I-frame interval: 1 second for fast stream synchronization and remote decode start */
	AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);
	/* 2 = BITRATE_MODE_CBR, 1 = BITRATE_MODE_VBR. VBR works well for webcam streams */
	AMediaFormat_setInt32(format, "bitrate-mode", 1);
	/* Low latency configuration for real-time camera redirection */
	AMediaFormat_setInt32(format, "latency", 1);
	AMediaFormat_setInt32(format, "priority", 0);

	media_status_t status = AMediaCodec_configure(codec, format, NULL, NULL, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
	AMediaFormat_delete(format);

	if (status != AMEDIA_OK)
	{
		LOGE("AMediaCodec_configure failed: %d", status);
		AMediaCodec_delete(codec);
		return FALSE;
	}

	status = AMediaCodec_start(codec);
	if (status != AMEDIA_OK)
	{
		LOGE("AMediaCodec_start failed: %d", status);
		AMediaCodec_delete(codec);
		return FALSE;
	}

	enc->codec = codec;
	enc->width = width;
	enc->height = height;
	enc->framerate = framerate;
	enc->bitrate = bitrate;
	enc->configured = TRUE;
	enc->frameIndex = 0;

	LOGI("Quest AMediaCodec H.264 encoder initialized and started successfully");
	return TRUE;
}

static BOOL quest_h264_encoder_encode(QuestH264Encoder* enc, const BYTE* srcData, size_t srcSize, wStream* output)
{
	if (!enc || !enc->codec || !enc->configured)
		return FALSE;

	/* Feed input frame (NV12) */
	ssize_t inputBufIdx = AMediaCodec_dequeueInputBuffer(enc->codec, 10000); /* 10ms wait */
	if (inputBufIdx >= 0)
	{
		size_t inputBufSize = 0;
		uint8_t* inputBuf = AMediaCodec_getInputBuffer(enc->codec, (size_t)inputBufIdx, &inputBufSize);
		if (inputBuf && inputBufSize >= srcSize)
		{
			memcpy(inputBuf, srcData, srcSize);
			if (inputBufSize > srcSize)
			{
				/* Zero any extra padding bytes in input buffer with neutral black (UV=128)
				 * to prevent hardware encoder from reading uninitialized memory as green rows. */
				memset(inputBuf + srcSize, 128, inputBufSize - srcSize);
			}
			int64_t pts = get_time_us();
			AMediaCodec_queueInputBuffer(enc->codec, (size_t)inputBufIdx, 0, srcSize, pts, 0);
		}
		else
		{
			LOGW("Input buffer null or too small: inputBufSize=%zu, srcSize=%zu", inputBufSize, srcSize);
			AMediaCodec_queueInputBuffer(enc->codec, (size_t)inputBufIdx, 0, 0, 0, 0);
		}
	}
	else
	{
		LOGW("AMediaCodec_dequeueInputBuffer returned %zd (busy)", inputBufIdx);
	}

	/* Drain output NAL units */
	AMediaCodecBufferInfo info;
	BOOL gotOutput = FALSE;
	int drainAttempts = 0;

	while (drainAttempts < 5)
	{
		ssize_t outputBufIdx = AMediaCodec_dequeueOutputBuffer(enc->codec, &info, 10000); /* 10ms wait */
		if (outputBufIdx >= 0)
		{
			if (info.size > 0)
			{
				size_t outBufCapacity = 0;
				uint8_t* outBuf = AMediaCodec_getOutputBuffer(enc->codec, (size_t)outputBufIdx, &outBufCapacity);
				if (outBuf)
				{
					if (!Stream_EnsureRemainingCapacity(output, (size_t)info.size))
					{
						LOGE("Stream_EnsureRemainingCapacity failed for size %d", info.size);
						AMediaCodec_releaseOutputBuffer(enc->codec, (size_t)outputBufIdx, false);
						return FALSE;
					}
					Stream_Write(output, outBuf + info.offset, (size_t)info.size);
					gotOutput = TRUE;
					enc->frameIndex++;

					if ((enc->frameIndex % 30) == 1)
					{
						LOGI("AMediaCodec encoded frame #%lld: size=%d flags=0x%x",
						     (long long)enc->frameIndex, info.size, info.flags);
					}
				}
			}
			AMediaCodec_releaseOutputBuffer(enc->codec, (size_t)outputBufIdx, false);
			/* If we got an output packet, we can break or continue draining if more slices available */
			if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM)
				break;
			break;
		}
		else if (outputBufIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED)
		{
			AMediaFormat* newFormat = AMediaCodec_getOutputFormat(enc->codec);
			if (newFormat)
			{
				LOGI("AMediaCodec encoder output format changed: %s", AMediaFormat_toString(newFormat));
				AMediaFormat_delete(newFormat);
			}
			drainAttempts++;
		}
		else if (outputBufIdx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED)
		{
			drainAttempts++;
		}
		else if (outputBufIdx == AMEDIACODEC_INFO_TRY_AGAIN_LATER)
		{
			/* No output ready yet */
			break;
		}
		else
		{
			LOGW("AMediaCodec_dequeueOutputBuffer unexpected index %zd", outputBufIdx);
			break;
		}
	}

	return gotOutput;
}

/**
 * Function description
 *
 * @return success/failure
 */
BOOL ecam_encoder_context_init(CameraDeviceStream* stream)
{
	if (!stream)
		return FALSE;

	const CAM_MEDIA_FORMAT outFmt = streamOutputFormat(stream);
	if (outFmt == CAM_MEDIA_FORMAT_H264)
	{
		const UINT32 framerate =
		    stream->currMediaType.FrameRateNumerator / (stream->currMediaType.FrameRateDenominator ? stream->currMediaType.FrameRateDenominator : 1);
		return quest_h264_encoder_init(&g_h264Encoder, stream->currMediaType.Width,
		                               stream->currMediaType.Height, framerate);
	}

	return TRUE;
}

/**
 * Function description
 *
 * @return success/failure
 */
BOOL ecam_encoder_context_free(CameraDeviceStream* stream)
{
	WINPR_UNUSED(stream);
	quest_h264_encoder_cleanup(&g_h264Encoder);
	return TRUE;
}

/**
 * Function description
 *
 * @return success/failure
 */
BOOL ecam_encoder_compress(CameraDeviceStream* stream, const BYTE* srcData, size_t srcSize,
                           wStream* output)
{
	if (!stream || !srcData || !output)
		return FALSE;

	const CAM_MEDIA_FORMAT inFmt = streamInputFormat(stream);
	const CAM_MEDIA_FORMAT outFmt = streamOutputFormat(stream);

	if (inFmt == outFmt)
	{
		if (!Stream_EnsureRemainingCapacity(output, srcSize))
			return FALSE;
		Stream_Write(output, srcData, srcSize);
		return TRUE;
	}

	if (inFmt == CAM_MEDIA_FORMAT_NV12 && outFmt == CAM_MEDIA_FORMAT_H264)
	{
		if (!ecam_encoder_context_init(stream))
			return FALSE;

		return quest_h264_encoder_encode(&g_h264Encoder, srcData, srcSize, output);
	}

	LOGE("ecam_encoder_compress: unsupported format conversion %u -> %u", inFmt, outFmt);
	return FALSE;
}
