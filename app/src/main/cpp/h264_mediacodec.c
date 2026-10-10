/**
 * FreeRDP MediaCodec decoder, adapted from FreeRDP's h264_mediacodec.c.
 * Copyright 2022 Ely Ronnen <elyronnen@gmail.com>
 * Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 * Distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 *
 * Quest fixes: configure at stream size, bounded waits, low latency, and padded
 * planar/semi-planar output. FreeRDP still performs AVC444 reconstruction and RGB conversion.
 */
#include <android/log.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "h264.h"

#define MC_LOG(priority, ...) __android_log_print(priority, "RDP-MediaCodec", __VA_ARGS__)
#define I420 19
#define NV12 21

typedef struct
{
    AMediaCodec* decoder;
    BOOL started;
    UINT32 width, height;
    int32_t outputWidth, outputHeight, stride, sliceHeight, color, cropLeft, cropTop;
    ssize_t outputIndex;
    BYTE* chroma;
    size_t chromaCapacity;
    UINT32 frames;
    double decodeTotalMs, decodeMaxMs;
} QuestMediaCodec;

static double monotonic_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1000000.0;
}

static void release_output(QuestMediaCodec* sys)
{
    if (sys->outputIndex >= 0)
        AMediaCodec_releaseOutputBuffer(sys->decoder, sys->outputIndex, false);
    sys->outputIndex = -1;
}

static BOOL read_output_format(QuestMediaCodec* sys)
{
    AMediaFormat* format = AMediaCodec_getOutputFormat(sys->decoder);
    if (!format)
        return FALSE;
    MC_LOG(ANDROID_LOG_INFO, "Output %s", AMediaFormat_toString(format));
    BOOL ok = AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_WIDTH, &sys->outputWidth) &&
              AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_HEIGHT, &sys->outputHeight) &&
              AMediaFormat_getInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, &sys->color);
    sys->stride = sys->outputWidth;
    sys->sliceHeight = sys->outputHeight;
    sys->cropLeft = sys->cropTop = 0;
    AMediaFormat_getInt32(format, "stride", &sys->stride);
    AMediaFormat_getInt32(format, "slice-height", &sys->sliceHeight);
    AMediaFormat_getInt32(format, "crop-left", &sys->cropLeft);
    AMediaFormat_getInt32(format, "crop-top", &sys->cropTop);
    if (sys->sliceHeight == 0)
        sys->sliceHeight = sys->outputHeight;
    AMediaFormat_delete(format);
    return ok && (sys->color == I420 || sys->color == NV12) &&
           sys->outputWidth > 0 && sys->outputHeight > 0 &&
           sys->stride <= 65536 && sys->sliceHeight <= 8192 &&
           sys->stride >= sys->outputWidth && sys->sliceHeight >= sys->outputHeight &&
           sys->cropLeft >= 0 && sys->cropTop >= 0 &&
           sys->cropLeft < sys->outputWidth && sys->cropTop < sys->outputHeight &&
           !(sys->cropLeft & 1) && !(sys->cropTop & 1) &&
           (UINT32)(sys->outputWidth - sys->cropLeft) >= sys->width &&
           (UINT32)(sys->outputHeight - sys->cropTop) >= sys->height;
}

static BOOL configure_decoder(H264_CONTEXT* h264)
{
    QuestMediaCodec* sys = h264->pSystemData;
    if (!h264->width || !h264->height || h264->width > 8192 || h264->height > 8192)
        return FALSE;
    if (!sys->decoder)
    {
        sys->decoder = AMediaCodec_createCodecByName("c2.qti.avc.decoder.low_latency");
        if (!sys->decoder)
            sys->decoder = AMediaCodec_createDecoderByType("video/avc");
        if (!sys->decoder)
            return FALSE;
        char* name = NULL;
        if (AMediaCodec_getName(sys->decoder, &name) == AMEDIA_OK)
        {
            MC_LOG(ANDROID_LOG_INFO, "Selected decoder: %s", name);
            AMediaCodec_releaseName(sys->decoder, name);
        }
    }
    release_output(sys);
    if (sys->started)
    {
        AMediaCodec_stop(sys->decoder);
        sys->started = FALSE;
    }
    AMediaFormat* format = AMediaFormat_new();
    if (!format)
        return FALSE;
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, h264->width);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, h264->height);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, I420);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, h264->width * h264->height * 2);
    AMediaFormat_setInt32(format, "low-latency", 1);
    media_status_t status = AMediaCodec_configure(sys->decoder, format, NULL, NULL, 0);
    AMediaFormat_delete(format);
    if (status != AMEDIA_OK)
    {
        MC_LOG(ANDROID_LOG_ERROR, "Configure failed: %d", status);
        return FALSE;
    }
    status = AMediaCodec_start(sys->decoder);
    if (status != AMEDIA_OK)
        return FALSE;
    sys->started = TRUE;
    sys->width = h264->width;
    sys->height = h264->height;
    sys->color = 0; // Read the negotiated output format before exposing planes.
    MC_LOG(ANDROID_LOG_INFO, "Started H.264 %ux%u, low-latency requested", sys->width, sys->height);
    return TRUE;
}

static int mediacodec_decompress(H264_CONTEXT* h264, const BYTE* data, UINT32 size)
{
    QuestMediaCodec* sys = h264->pSystemData;
    const double start = monotonic_ms();
    release_output(sys);
    if ((!sys->started || sys->width != h264->width || sys->height != h264->height) &&
        !configure_decoder(h264))
        return -1;

    // One RDP H.264 payload is one access unit; never split it into unrelated frames.
    ssize_t input = AMediaCodec_dequeueInputBuffer(sys->decoder, 1000000);
    if (input < 0)
    {
        MC_LOG(ANDROID_LOG_ERROR, "Input timeout/error: %zd", input);
        return -1;
    }
    size_t capacity = 0;
    BYTE* buffer = AMediaCodec_getInputBuffer(sys->decoder, input, &capacity);
    if (!buffer || size > capacity)
    {
        MC_LOG(ANDROID_LOG_ERROR, "Input too large: %u / %zu", size, capacity);
        return -1;
    }
    memcpy(buffer, data, size);
    if (AMediaCodec_queueInputBuffer(sys->decoder, input, 0, size, sys->frames * 16667ULL, 0) != AMEDIA_OK)
        return -1;

    const double deadline = monotonic_ms() + 1000;
    while (monotonic_ms() < deadline)
    {
        AMediaCodecBufferInfo info = { 0 };
        ssize_t output = AMediaCodec_dequeueOutputBuffer(sys->decoder, &info, 10000);
        if (output == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED)
        {
            if (!read_output_format(sys))
            {
                MC_LOG(ANDROID_LOG_ERROR, "Unsupported MediaCodec output layout");
                return -1;
            }
            continue;
        }
        if (output == AMEDIACODEC_INFO_TRY_AGAIN_LATER || output == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED)
            continue;
        if (output < 0)
            return -1;
        sys->outputIndex = output;
        if (info.size == 0)
        {
            release_output(sys);
            continue;
        }
        if (!sys->color && !read_output_format(sys))
            return -1;
        buffer = AMediaCodec_getOutputBuffer(sys->decoder, output, &capacity);
        const size_t ySize = (size_t)sys->stride * sys->sliceHeight;
        const size_t uvStride = sys->color == I420 ? (sys->stride + 1) / 2 : sys->stride;
        const size_t uvSize = uvStride * ((sys->sliceHeight + 1) / 2);
        const size_t required = ySize + uvSize * (sys->color == I420 ? 2 : 1);
        if (!buffer || info.offset < 0 || (size_t)info.offset > capacity ||
            required > capacity - (size_t)info.offset)
        {
            MC_LOG(ANDROID_LOG_ERROR, "Invalid output buffer: capacity=%zu required=%zu offset=%d", capacity, required, info.offset);
            return -1;
        }
        buffer += info.offset;
        h264->iStride[0] = sys->stride;
        h264->pYUVData[0] = buffer + (size_t)sys->cropTop * sys->stride + sys->cropLeft;
        const BYTE* chroma = buffer + ySize + (size_t)(sys->cropTop / 2) * uvStride;
        if (sys->color == I420)
        {
            h264->iStride[1] = h264->iStride[2] = uvStride;
            h264->pYUVData[1] = (BYTE*)chroma + sys->cropLeft / 2;
            h264->pYUVData[2] = h264->pYUVData[1] + uvSize;
        }
        else
        {
            const size_t cw = (sys->width + 1) / 2, ch = (sys->height + 1) / 2;
            if (sys->chromaCapacity < cw * ch * 2)
            {
                BYTE* resized = realloc(sys->chroma, cw * ch * 2);
                if (!resized)
                    return -1;
                sys->chroma = resized;
                sys->chromaCapacity = cw * ch * 2;
            }
            h264->iStride[1] = h264->iStride[2] = cw;
            h264->pYUVData[1] = sys->chroma;
            h264->pYUVData[2] = sys->chroma + cw * ch;
            for (size_t y = 0; y < ch; y++)
                for (size_t x = 0; x < cw; x++)
                {
                    h264->pYUVData[1][y * cw + x] = chroma[y * uvStride + sys->cropLeft + x * 2];
                    h264->pYUVData[2][y * cw + x] = chroma[y * uvStride + sys->cropLeft + x * 2 + 1];
                }
        }
        // FreeRDP 3.32 validates rectangles and reconstructs AVC444 against the
        // decoded plane geometry. NV12 chroma is copied only for these dimensions.
        h264->YUVWidth = sys->width;
        h264->YUVHeight = sys->height;
        const double elapsed = monotonic_ms() - start;
        sys->decodeTotalMs += elapsed;
        if (elapsed > sys->decodeMaxMs)
            sys->decodeMaxMs = elapsed;
        sys->frames++;
        if (sys->frames == 1 || sys->frames % 30 == 0)
            MC_LOG(ANDROID_LOG_INFO, "Decoded frames=%u, last=%.2fms avg=%.2fms max=%.2fms", sys->frames,
                   elapsed, sys->decodeTotalMs / sys->frames, sys->decodeMaxMs);
        return 1;
    }
    MC_LOG(ANDROID_LOG_ERROR, "No decoded output within 1000ms");
    return -1;
}

static void mediacodec_uninit(H264_CONTEXT* h264)
{
    QuestMediaCodec* sys = h264->pSystemData;
    if (!sys)
        return;
    if (sys->decoder)
    {
        release_output(sys);
        if (sys->started)
            AMediaCodec_stop(sys->decoder);
        AMediaCodec_delete(sys->decoder);
    }
    free(sys->chroma);
    free(sys);
    h264->pSystemData = NULL;
}

static BOOL mediacodec_init(H264_CONTEXT* h264)
{
    if (h264->Compressor)
        return FALSE;
    QuestMediaCodec* sys = calloc(1, sizeof(*sys));
    if (!sys)
        return FALSE;
    h264->pSystemData = sys;
    sys->outputIndex = -1;
    // FreeRDP allocates H.264 contexts even for non-AVC surfaces. Acquire hardware
    // resources only when the server actually supplies the first AVC frame.
    return TRUE;
}

const H264_CONTEXT_SUBSYSTEM g_Subsystem_mediacodec = {
    "MediaCodec", mediacodec_init, mediacodec_uninit, mediacodec_decompress, NULL
};
