cmake_minimum_required(VERSION 3.22)
project("freerdp-jni" LANGUAGES C CXX)

# Native codec/color-conversion loops need optimization in on-headset debug builds too.
# Keep debug symbols, but avoid benchmarking the software stages at -O0.
add_compile_options($<$<CONFIG:Debug>:-O2>)

include(FetchContent)

# 1. Fetch OpenSSL (CMake port to avoid Perl requirement on Windows)
FetchContent_Declare(
    openssl
    GIT_REPOSITORY https://github.com/janbar/openssl-cmake.git
    GIT_TAG        master
)
FetchContent_MakeAvailable(openssl)
set_target_properties(ssl PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "")
set_target_properties(crypto PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "")

# 2. Fetch cJSON (Required by FreeRDP)
FetchContent_Declare(
    cjson
    GIT_REPOSITORY https://github.com/DaveGamble/cJSON.git
    GIT_TAG        v1.7.18
)
set(ENABLE_CJSON_TEST OFF CACHE BOOL "Disable cJSON tests" FORCE)
FetchContent_MakeAvailable(cjson)
# 3. Add FetchContent targets to FreeRDP's export set to avoid CMake install(EXPORT) errors
install(TARGETS ssl crypto cjson EXPORT FreeRDPTargets)

# 4. Mock the Find module variables so FreeRDP uses our FetchContent targets natively!
list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")

# 4. Configure FreeRDP Options
set(WITH_OPENSSL ON CACHE BOOL "Enable OpenSSL" FORCE)
set(WITH_MBEDTLS OFF CACHE BOOL "Disable MbedTLS" FORCE)
set(WITH_CJSON ON CACHE BOOL "Enable cJSON" FORCE)
set(WITH_SERVER OFF CACHE BOOL "Disable server" FORCE)
set(WITH_CLIENT_ANDROID ON CACHE BOOL "Enable Android client" FORCE)
set(WITH_INTERNAL_MD4 ON CACHE BOOL "Internal MD4" FORCE)
set(WITH_INTERNAL_MD5 ON CACHE BOOL "Internal MD5" FORCE)
set(WITH_INTERNAL_RC4 ON CACHE BOOL "Internal RC4" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Static build" FORCE)

# Disable unnecessary heavy dependencies for now
set(WITH_FFMPEG OFF CACHE BOOL "Disable FFmpeg" FORCE)
set(WITH_SWSCALE OFF CACHE BOOL "Disable SWSCALE" FORCE)
set(WITH_DSP_FFMPEG OFF CACHE BOOL "Disable DSP FFmpeg" FORCE)
set(WITH_VIDEO_FFMPEG OFF CACHE BOOL "Disable Video FFmpeg" FORCE)
set(WITH_CAIRO OFF CACHE BOOL "Disable Cairo" FORCE)
set(WITH_OPENH264 OFF CACHE BOOL "Disable OpenH264" FORCE)
set(WITH_MEDIACODEC ON CACHE BOOL "Android hardware H.264 decoder" FORCE)
set(WITH_WAYLAND OFF CACHE BOOL "Disable Wayland" FORCE)
set(WITH_X11 OFF CACHE BOOL "Disable X11" FORCE)
set(WITH_ALSA OFF CACHE BOOL "Disable ALSA" FORCE)
set(WITH_PULSE OFF CACHE BOOL "Disable PulseAudio" FORCE)
set(WITH_OSS OFF CACHE BOOL "Disable OSS" FORCE)
set(WITH_CUPS OFF CACHE BOOL "Disable CUPS" FORCE)
set(WITH_PCSC OFF CACHE BOOL "Disable PCSC" FORCE)
set(WITH_OPUS OFF CACHE BOOL "Disable Opus" FORCE)
set(WITH_PNG OFF CACHE BOOL "Disable PNG" FORCE)
set(WITH_JPEG OFF CACHE BOOL "Disable JPEG" FORCE)
set(WITH_MANPAGES OFF CACHE BOOL "Disable manpages" FORCE)
set(WITH_CLIENT_SDL OFF CACHE BOOL "Disable SDL client" FORCE)


set(CHANNEL_RDPSND_CLIENT ON CACHE BOOL "RDP playback" FORCE)
set(CHANNEL_AUDIN_CLIENT ON CACHE BOOL "RDP microphone" FORCE)
set(CHANNEL_RDPECAM_CLIENT ON CACHE BOOL "RDP camera" FORCE)
set(CHANNEL_RDPDR ON CACHE BOOL "RDP device redirection" FORCE)
set(CHANNEL_RDPDR_CLIENT ON CACHE BOOL "RDP device redirection" FORCE)
set(CHANNEL_DRIVE ON CACHE BOOL "RDP drive redirection" FORCE)
set(CHANNEL_DRIVE_CLIENT ON CACHE BOOL "RDP drive redirection" FORCE)

# 5. Build FreeRDP natively
add_subdirectory(freerdp)

# FreeRDP 3.32.1 rejects non-IPv6 HTTP CONNECT targets: both checks must fail.
# Apply the guarded correction to the build copy; leave the upstream submodule untouched.
set(quest_proxy_upstream "${CMAKE_CURRENT_SOURCE_DIR}/freerdp/libfreerdp/core/proxy.c")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${quest_proxy_upstream}")
file(READ "${quest_proxy_upstream}" quest_proxy_source)
set(quest_proxy_anchor "if (!is_ipv6_addr(hostname, hostLen) || !winpr_str_is_valid_urlN(hostname, hostLen))")
string(FIND "${quest_proxy_source}" "${quest_proxy_anchor}" quest_proxy_anchor_index)
if(quest_proxy_anchor_index EQUAL -1)
    message(FATAL_ERROR "Upstream HTTP proxy validation changed; recheck CONNECT target validation")
endif()
string(REPLACE "${quest_proxy_anchor}"
    "if (!is_ipv6_addr(hostname, hostLen) && !winpr_str_is_valid_urlN(hostname, hostLen))"
    quest_proxy_source "${quest_proxy_source}")
set(quest_socks_anchor "if (!is_ipv6_addr(hostname, hostnlen) || !winpr_str_is_valid_urlN(hostname, hostnlen))")
string(FIND "${quest_proxy_source}" "${quest_socks_anchor}" quest_socks_anchor_index)
if(quest_socks_anchor_index EQUAL -1)
    message(FATAL_ERROR "Upstream SOCKS5 proxy validation changed; recheck CONNECT target validation")
endif()
string(REPLACE "${quest_socks_anchor}"
    "if (!is_ipv6_addr(hostname, hostnlen) && !winpr_str_is_valid_urlN(hostname, hostnlen))"
    quest_proxy_source "${quest_proxy_source}")
set(quest_proxy_auth_anchor "crypto_base64_encode_len(creds, size - 1, &b64len)")
string(FIND "${quest_proxy_source}" "${quest_proxy_auth_anchor}" quest_proxy_auth_index)
if(quest_proxy_auth_index EQUAL -1)
    message(FATAL_ERROR "Upstream HTTP proxy Basic encoding changed; recheck credential length")
endif()
string(REPLACE "${quest_proxy_auth_anchor}" "crypto_base64_encode_len(creds, size, &b64len)"
    quest_proxy_source "${quest_proxy_source}")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/quest_proxy.c" "${quest_proxy_source}")
get_target_property(quest_freerdp_sources freerdp SOURCES)
list(FILTER quest_freerdp_sources EXCLUDE REGEX "(^|/)core/proxy\\.c$")
set_property(TARGET freerdp PROPERTY SOURCES ${quest_freerdp_sources} "${CMAKE_CURRENT_BINARY_DIR}/quest_proxy.c")
target_include_directories(freerdp PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/freerdp/libfreerdp/core")

# Drain/join rdpsnd's playback worker before freeing its device/DSP state.
# Keep these two upstream teardown corrections outside the submodule.
set(quest_rdpsnd_upstream "${CMAKE_CURRENT_SOURCE_DIR}/freerdp/channels/rdpsnd/client/rdpsnd_main.c")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${quest_rdpsnd_upstream}")
file(READ "${quest_rdpsnd_upstream}" quest_rdpsnd_source)
foreach(quest_close_anchor IN ITEMS
    "\tWINPR_ASSERT(!rdpsnd->dynamic);\n\tif (rdpsnd->OpenHandle != 0)"
    "\trdpsnd->OnOpenCalled = FALSE;\n\tif (rdpsnd->device)")
    string(FIND "${quest_rdpsnd_source}" "${quest_close_anchor}" quest_anchor_index)
    if(quest_anchor_index EQUAL -1)
        message(FATAL_ERROR "Upstream rdpsnd teardown changed; recheck the worker lifetime fix")
    endif()
    string(REPLACE "\n\tif (" "\n\trdpsnd_terminate_thread(rdpsnd);\n\tif (" quest_close_replacement "${quest_close_anchor}")
    string(REPLACE "${quest_close_anchor}" "${quest_close_replacement}" quest_rdpsnd_source "${quest_rdpsnd_source}")
endforeach()
set(quest_queue_anchor "\t\ts = message.wParam;\n\t\terror = rdpsnd_recv_pdu(rdpsnd, s);")
string(FIND "${quest_rdpsnd_source}" "${quest_queue_anchor}" quest_queue_index)
if(quest_queue_index EQUAL -1)
    message(FATAL_ERROR "Upstream rdpsnd queue changed; recheck latency instrumentation")
endif()
string(PREPEND quest_rdpsnd_source "#include <android/log.h>\n")
string(REPLACE "UINT64 wArrivalTime;" "UINT64 wArrivalTime;\n\tUINT64 questQueueAgeMs, questMaxQueueAgeMs, questQueueSamples;" quest_rdpsnd_source "${quest_rdpsnd_source}")
string(REPLACE "${quest_queue_anchor}" "\t\trdpsnd->questQueueAgeMs = GetTickCount64() - message.time;\n\t\tif (rdpsnd->questQueueAgeMs > rdpsnd->questMaxQueueAgeMs) rdpsnd->questMaxQueueAgeMs = rdpsnd->questQueueAgeMs;\n\t\tif (rdpsnd->questQueueSamples++ % 10 == 0) __android_log_print(ANDROID_LOG_INFO, \"QuestAudioQueue\", \"age=%llu max=%llu\", (unsigned long long)rdpsnd->questQueueAgeMs, (unsigned long long)rdpsnd->questMaxQueueAgeMs);\n${quest_queue_anchor}" quest_rdpsnd_source "${quest_rdpsnd_source}")
# WaveConfirm must include time spent waiting for the playback worker. AAudio's
# blocking writes otherwise hide that delay from the server. A stalled worker
# discards old audio while still parsing/acknowledging each PDU in protocol order.
set(quest_wave_anchor "if (rdpsnd->device && rdpsnd->attached && !rdpsnd_detect_overrun(rdpsnd, format, size))")
string(FIND "${quest_rdpsnd_source}" "${quest_wave_anchor}" quest_wave_index)
if(quest_wave_index EQUAL -1)
    message(FATAL_ERROR "Upstream rdpsnd wave handling changed; recheck bounded audio playback")
endif()
string(REPLACE "${quest_wave_anchor}"
    "if (rdpsnd->questQueueAgeMs > 250)\n\t\t__android_log_print(ANDROID_LOG_WARN, \"QuestAudioQueue\", \"Discarding stale audio age=%llu ms\", (unsigned long long)rdpsnd->questQueueAgeMs);\n\tif (rdpsnd->device && rdpsnd->attached && rdpsnd->questQueueAgeMs <= 250 && !rdpsnd_detect_overrun(rdpsnd, format, size))"
    quest_rdpsnd_source "${quest_rdpsnd_source}")
string(REPLACE "rdpsnd->wArrivalTime = GetTickCount64();"
    "rdpsnd->wArrivalTime = GetTickCount64() - rdpsnd->questQueueAgeMs;"
    quest_rdpsnd_source "${quest_rdpsnd_source}")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/quest_rdpsnd_main.c" "${quest_rdpsnd_source}")
get_target_property(quest_rdpsnd_sources rdpsnd-client SOURCES)
list(FILTER quest_rdpsnd_sources EXCLUDE REGEX "(^|/)rdpsnd_main\\.c$")
set_property(TARGET rdpsnd-client PROPERTY SOURCES ${quest_rdpsnd_sources}
    "${CMAKE_CURRENT_BINARY_DIR}/quest_rdpsnd_main.c")
target_include_directories(rdpsnd-client PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/freerdp/channels/rdpsnd/client")

# Keep the Quest MediaCodec fixes reviewable outside the upstream submodule.
# FreeRDP 3.27.1's experimental backend has a compile error and assumes unpadded I420.
get_target_property(quest_codec_sources freerdp-codecs SOURCES)
list(FILTER quest_codec_sources EXCLUDE REGEX "(^|/)h264_mediacodec\\.c$")
set_property(TARGET freerdp-codecs PROPERTY SOURCES ${quest_codec_sources}
    "${CMAKE_CURRENT_SOURCE_DIR}/h264_mediacodec.c")
target_include_directories(freerdp-codecs PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/freerdp/libfreerdp/codec")

get_target_property(quest_camera_sources rdpecam-client-android SOURCES)
list(FILTER quest_camera_sources EXCLUDE REGEX "(^|/)camera_android\\.c$")
set_property(TARGET rdpecam-client-android PROPERTY SOURCES ${quest_camera_sources}
    "${CMAKE_CURRENT_SOURCE_DIR}/camera_android.c")
target_include_directories(rdpecam-client-android PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/freerdp/channels/rdpecam/client")

get_target_property(quest_rdpecam_sources rdpecam-client SOURCES)
list(FILTER quest_rdpecam_sources EXCLUDE REGEX "(^|/)camera_device_main\\.c$")
list(FILTER quest_rdpecam_sources EXCLUDE REGEX "(^|/)encoding\\.c$")
set_property(TARGET rdpecam-client PROPERTY SOURCES ${quest_rdpecam_sources}
    "${CMAKE_CURRENT_SOURCE_DIR}/camera_device_main.c"
    "${CMAKE_CURRENT_SOURCE_DIR}/camera_encoding.c")
target_include_directories(rdpecam-client PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/freerdp/channels/rdpecam/client"
    "${CMAKE_CURRENT_SOURCE_DIR}/freerdp/channels/rdpecam/common")
target_link_libraries(rdpecam-client PRIVATE mediandk)

# 6. Build our JNI Bridge
add_library(freerdp-jni SHARED freerdp_bridge.cpp quest_audio.cpp)
target_compile_features(freerdp-jni PRIVATE cxx_std_17)
target_include_directories(freerdp-jni PRIVATE 
    ${OPENSSL_INCLUDE_DIR}
    freerdp/include
    freerdp/winpr/include
    ${CMAKE_CURRENT_BINARY_DIR}/freerdp/include
    ${CMAKE_CURRENT_BINARY_DIR}/freerdp/winpr/include
)

find_library(log-lib log)
find_library(dl-lib dl)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
        freerdp-jni
        PRIVATE
        freerdp-client
        freerdp
        winpr
        ${log-lib}
        ${dl-lib}
        ${jnigraphics-lib}
        android
        aaudio
        camera2ndk
        mediandk
)

# Independent client-only VNC build; all platform adapters stay outside upstream.
add_subdirectory(vnc)
