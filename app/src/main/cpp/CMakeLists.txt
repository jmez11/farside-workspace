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


# 5. Build FreeRDP natively
add_subdirectory(freerdp)

# Keep the Quest MediaCodec fixes reviewable outside the upstream submodule.
# FreeRDP 3.27.1's experimental backend has a compile error and assumes unpadded I420.
get_target_property(quest_codec_sources freerdp-codecs SOURCES)
list(FILTER quest_codec_sources EXCLUDE REGEX "(^|/)h264_mediacodec\\.c$")
set_property(TARGET freerdp-codecs PROPERTY SOURCES ${quest_codec_sources}
    "${CMAKE_CURRENT_SOURCE_DIR}/h264_mediacodec.c")
target_include_directories(freerdp-codecs PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/freerdp/libfreerdp/codec")

# 6. Build our JNI Bridge
add_library(freerdp-jni SHARED freerdp_bridge.cpp)
target_compile_features(freerdp-jni PRIVATE cxx_std_17)
target_include_directories(freerdp-jni PRIVATE 
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
)
