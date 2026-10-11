# Keep VNC options scoped: do not change the FreeRDP build/cache.
set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)
set(BUILD_SHARED_LIBS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(ENABLE_SHARED OFF)
set(ENABLE_STATIC ON)
set(WITH_TURBOJPEG OFF)
set(WITH_TOOLS OFF)
set(WITH_TESTS OFF)
set(WITH_SIMD OFF) # portable software decode, including Android NDK cross builds
FetchContent_Declare(vnc_jpeg GIT_REPOSITORY https://github.com/libjpeg-turbo/libjpeg-turbo.git
    GIT_TAG 4e151a4ad91001b3aa8c2ece2205c15f487ce320)
FetchContent_GetProperties(vnc_jpeg)
if(NOT vnc_jpeg_POPULATED)
    FetchContent_Populate(vnc_jpeg)
endif()
include(ExternalProject)
ExternalProject_Add(vnc_jpeg_build SOURCE_DIR "${vnc_jpeg_SOURCE_DIR}" BINARY_DIR "${vnc_jpeg_BINARY_DIR}"
    CMAKE_ARGS -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM} -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE} -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM} -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DENABLE_SHARED=OFF -DENABLE_STATIC=ON
        -DWITH_SIMD=OFF -DWITH_TURBOJPEG=OFF -DWITH_TOOLS=OFF -DWITH_TESTS=OFF
    BUILD_BYPRODUCTS "${vnc_jpeg_BINARY_DIR}/libjpeg.a" INSTALL_COMMAND "")
add_library(vnc-jpeg STATIC IMPORTED)
set_target_properties(vnc-jpeg PROPERTIES IMPORTED_LOCATION "${vnc_jpeg_BINARY_DIR}/libjpeg.a")
add_dependencies(vnc-jpeg vnc_jpeg_build)
set(JPEG_FOUND TRUE)
set(JPEG_LIBRARY vnc-jpeg)
set(JPEG_LIBRARIES vnc-jpeg)
set(JPEG_INCLUDE_DIR "${vnc_jpeg_SOURCE_DIR}/src")
set(JPEG_INCLUDE_DIRS "${vnc_jpeg_SOURCE_DIR}/src;${vnc_jpeg_BINARY_DIR}")
set(WITH_JPEG ON)
set(WITH_ZLIB ON)
foreach(feature LIBVNCSERVER EXAMPLES TESTS SDL GTK QT PNG LZO LIBSSHTUNNEL GNUTLS OPENSSL GCRYPT SYSTEMD FFMPEG SASL XCB WEBSOCKETS TIGHTVNC_FILETRANSFER)
    set(WITH_${feature} OFF)
endforeach()
set(OPENSSL_FOUND FALSE)
set(GNUTLS_FOUND FALSE)
set(PNG_FOUND FALSE)
set(LZO_FOUND FALSE)
set(WITH_LIBVNCCLIENT ON)
set(LIBVNCSERVER_INSTALL OFF)
add_subdirectory(../libvncserver "${CMAKE_CURRENT_BINARY_DIR}/libvncserver")
set_target_properties(copy-compile-commands PROPERTIES EXCLUDE_FROM_ALL TRUE)
# Upstream advertises ZlibHex but has no receive handler. Add a bounded 32bpp adapter
# to a generated copy; strict security-type check also covers old RFB 3.3 servers.
file(READ "${CMAKE_CURRENT_SOURCE_DIR}/../libvncserver/src/libvncclient/rfbclient.c" vnc_source)
# Screen Sharing switches its framebuffer producer at account login, including
# same-size NewFBSize messages. Reassert the negotiated pixel format/encodings
# before requesting its replacement frame; otherwise macOS sends a black ZRLE
# desktop while small menu-bar updates continue. Keep the preference list intact.
string(REPLACE "\tSendFramebufferUpdateRequest(client, 0, 0, rect.r.w, rect.r.h, FALSE);"
    "\tif (!SetFormatAndEncodings(client) || !SendFramebufferUpdateRequest(client, 0, 0, rect.r.w, rect.r.h, FALSE)) return FALSE;"
    vnc_source "${vnc_source}")
string(REPLACE "  client->authScheme = authScheme;"
    "  client->authScheme = authScheme;\n  if (authScheme != rfbVncAuth) return FALSE;" vnc_source "${vnc_source}")
string(REPLACE "      case rfbEncodingZlib:" "      case rfbEncodingZlibHex: {\n        extern rfbBool farsideZlibHex(rfbClient*, int, int, int, int);\n        if (!farsideZlibHex(client, rect.r.x, rect.r.y, rect.r.w, rect.r.h)) return FALSE;\n        break;\n      }\n      case rfbEncodingZlib:" vnc_source "${vnc_source}")
# Upstream silently accepts zero-sized cursor shapes without notifying the viewer.
string(REPLACE "if (!HandleCursorShape(client,"
    "if ((!rect.r.w || !rect.r.h) && client->GotCursorShape) client->GotCursorShape(client, rect.r.x, rect.r.y, 0, 0, client->format.bitsPerPixel / 8);\n\tif (!HandleCursorShape(client,"
    vnc_source "${vnc_source}")
# Route protocol diagnostics per call, without changing the library's global log callbacks.
string(REPLACE "rfbClientLogProc rfbClientErr=rfbDefaultClientLog;"
    "rfbClientLogProc rfbClientErr=rfbDefaultClientLog;\n#include <android/log.h>\n#define rfbClientLog(...) __android_log_print(ANDROID_LOG_DEBUG, \"VncProtocol\", __VA_ARGS__)\n#define rfbClientErr(...) __android_log_print(ANDROID_LOG_WARN, \"VncProtocol\", __VA_ARGS__)"
    vnc_source "${vnc_source}")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/farside_rfbclient.c" "${vnc_source}")
file(READ "${CMAKE_CURRENT_SOURCE_DIR}/../libvncserver/src/libvncclient/sockets.c" vnc_socket_source)
string(REPLACE "#include <rfb/rfbclient.h>"
    "#include <rfb/rfbclient.h>\n#include <android/log.h>\n#define rfbClientLog(...) __android_log_print(ANDROID_LOG_DEBUG, \"VncSocket\", __VA_ARGS__)\n#define rfbClientErr(...) __android_log_print(ANDROID_LOG_WARN, \"VncSocket\", __VA_ARGS__)"
    vnc_socket_source "${vnc_socket_source}")
string(REPLACE "  int retries = 0;" "  const uint64_t read_start = farsideReadClock();" vnc_socket_source "${vnc_socket_source}")
string(REPLACE "++retries > (client->readTimeout * 1000 * 1000 / USECS_WAIT_PER_RETRY)"
    "farsideReadExpired(read_start, client->readTimeout)" vnc_socket_source "${vnc_socket_source}")
string(REPLACE "WaitForMessage(client, USECS_WAIT_PER_RETRY);" "farsideWaitSocket(client, USECS_WAIT_PER_RETRY);"
    vnc_socket_source "${vnc_socket_source}")
string(REPLACE "#include <android/log.h>" "#include <android/log.h>\n#include <vnc_socket_wait.h>"
    vnc_socket_source "${vnc_socket_source}")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/farside_sockets.c" "${vnc_socket_source}")
get_target_property(vnc_sources vncclient SOURCES)
list(FILTER vnc_sources EXCLUDE REGEX "(^|/)rfbclient\\.c$")
list(FILTER vnc_sources EXCLUDE REGEX "libvncclient/sockets\\.c$")
set_property(TARGET vncclient PROPERTY SOURCES ${vnc_sources} "${CMAKE_CURRENT_BINARY_DIR}/farside_rfbclient.c" "${CMAKE_CURRENT_BINARY_DIR}/farside_sockets.c")
add_dependencies(vncclient vnc_jpeg_build)
target_include_directories(vncclient BEFORE PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" "${CMAKE_CURRENT_SOURCE_DIR}/../libvncserver/src/libvncclient" ${JPEG_INCLUDE_DIRS})
add_library(vnc-jni SHARED ../vnc_bridge.cpp)
target_compile_features(vnc-jni PRIVATE cxx_std_17)
target_include_directories(vnc-jni PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../libvncserver/include"
    "${CMAKE_CURRENT_BINARY_DIR}/libvncserver/include")
target_link_libraries(vnc-jni PRIVATE vncclient android log z)
