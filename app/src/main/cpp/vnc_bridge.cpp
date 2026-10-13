#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <rfb/rfbclient.h>
#include <zlib.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <sys/socket.h>
#include <unistd.h>

namespace {
constexpr size_t maxPixels = 32 * 1024 * 1024, maxClip = 1024 * 1024;
struct Command {
    enum Kind { Pointer, Mouse, Key, Clipboard } kind;
    int x = 0, y = 0, value = 0;
    bool down = false;
    std::string utf8, latin1;
};
struct Session {
    JavaVM* vm = nullptr;
    jobject bridge = nullptr;
    jmethodID disconnected = nullptr, clipboard = nullptr;
    rfbClient* client = nullptr;
    std::atomic<bool> stop{false};
    std::atomic<int> width{0}, height{0};
    std::thread worker, renderer;
    std::mutex queueMutex, frameMutex, surfaceMutex;
    std::deque<Command> commands;
    std::condition_variable renderWake;
    std::vector<uint32_t> frame;
    std::vector<uint32_t> cursor;
    int cursorWidth = 0, cursorHeight = 0, cursorHotX = 0, cursorHotY = 0;
    int cursorX = 0, cursorY = 0;
    bool cursorLocated = false;
    bool dirty = false;
    ANativeWindow* window = nullptr;
    int buttons = 0;
    std::string password;
    z_stream hexStreams[2]{};
    bool hexInitialized[2]{};
    Session() {
        // macOS may omit cursor-shape updates even when requested. Keep a local
        // pointer until the peer supplies a shape (including an explicit hide).
        const char* arrow[] = {"B...........", "BB..........", "BWB.........", "BWWB........",
            "BWWWB.......", "BWWWWB......", "BWWWWWB.....", "BWWWWWWB....", "BWWWWWWWB...",
            "BWWWWWWWWB..", "BWWWWWBBBBB.", "BWWBWWB.....", "BWB.BWWB....", "BB..BWWB....",
            "B....BWWB...", ".....BWWB...", "......BB...."};
        cursorWidth = 12; cursorHeight = 17;
        for (const auto row : arrow) for (int x = 0; x < cursorWidth; ++x)
            cursor.push_back(row[x] == 'B' ? 0xff000000 : row[x] == 'W' ? 0xffffffff : 0);
    }
    ~Session() {
        // Both threads must already be stopped and joined.
        for (int i = 0; i < 2; ++i) if (hexInitialized[i]) inflateEnd(&hexStreams[i]);
        if (client) { free(client->frameBuffer); client->frameBuffer = nullptr; rfbClientCleanup(client); }
        if (window) ANativeWindow_release(window);
        std::fill(password.begin(), password.end(), '\0');
    }
};
Session* owner(rfbClient* c) { return static_cast<Session*>(rfbClientGetClientData(c, nullptr)); }
struct Env {
    JavaVM* vm; JNIEnv* env = nullptr; bool attached = false;
    explicit Env(JavaVM* v) : vm(v) {
        if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK)
            attached = vm->AttachCurrentThread(&env, nullptr) == JNI_OK;
    }
    ~Env() { if (attached) vm->DetachCurrentThread(); }
};
void cutText(rfbClient* c, const char* text, int length, bool utf8) {
    if (length < 0 || static_cast<size_t>(length) > maxClip) return;
    auto s = owner(c); Env j(s->vm); if (!j.env) return;
    auto data = j.env->NewByteArray(length);
    if (data) {
        j.env->SetByteArrayRegion(data, 0, length, reinterpret_cast<const jbyte*>(text));
        j.env->CallVoidMethod(s->bridge, s->clipboard, data, static_cast<jboolean>(utf8));
        j.env->DeleteLocalRef(data);
    }
    if (j.env->ExceptionCheck()) j.env->ExceptionClear();
}
rfbBool allocateFrame(rfbClient* c) {
    if (c->width <= 0 || c->height <= 0 || static_cast<size_t>(c->width) * c->height > maxPixels) return FALSE;
    const auto count = static_cast<size_t>(c->width) * c->height;
    auto pixels = static_cast<uint8_t*>(calloc(count, 4));
    if (!pixels) return FALSE;
    free(c->frameBuffer); c->frameBuffer = pixels;
    auto s = owner(c);
    std::lock_guard<std::mutex> lock(s->frameMutex);
    s->frame.assign(count, 0xff000000);
    s->width = c->width; s->height = c->height;
    return TRUE;
}
void updated(rfbClient* c) {
    auto s = owner(c);
    std::lock_guard<std::mutex> lock(s->frameMutex);
    if (!c->frameBuffer || s->frame.size() != static_cast<size_t>(c->width) * c->height) return;
    memcpy(s->frame.data(), c->frameBuffer, s->frame.size() * 4);
    for (auto& pixel : s->frame) pixel |= 0xff000000;
    s->dirty = true;
    s->renderWake.notify_one();
}
rfbBool cursorPosition(rfbClient* c, int x, int y) {
    auto s = owner(c);
    std::lock_guard<std::mutex> lock(s->frameMutex);
    s->cursorX = x; s->cursorY = y; s->cursorLocated = true;
    s->dirty = true; s->renderWake.notify_one();
    return TRUE;
}
void cursorShape(rfbClient* c, int hotX, int hotY, int w, int h, int bytesPerPixel) {
    auto s = owner(c);
    std::lock_guard<std::mutex> lock(s->frameMutex);
    s->cursor.clear(); s->cursorWidth = s->cursorHeight = 0;
    if (w > 0 && h > 0 && w < 1024 && h < 1024 && bytesPerPixel == 4 && c->rcSource && c->rcMask) {
        s->cursor.resize(static_cast<size_t>(w) * h);
        for (size_t i = 0; i < s->cursor.size(); ++i) {
            uint32_t color; memcpy(&color, c->rcSource + i * 4, 4);
            s->cursor[i] = c->rcMask[i] ? color | 0xff000000 : 0;
        }
        s->cursorWidth = w; s->cursorHeight = h;
        s->cursorHotX = std::clamp(hotX, 0, w - 1); s->cursorHotY = std::clamp(hotY, 0, h - 1);
    }
    s->dirty = true; s->renderWake.notify_one();
}
void render(Session* s) {
    using namespace std::chrono;
    auto deadline = steady_clock::now();
    while (!s->stop) {
        std::unique_lock<std::mutex> frame(s->frameMutex);
        s->renderWake.wait_until(frame, deadline, [&] { return s->stop.load(); });
        if (s->stop) break;
        deadline = steady_clock::now() + milliseconds(33);
        if (!s->dirty || s->frame.empty()) continue;
        std::lock_guard<std::mutex> surface(s->surfaceMutex);
        if (!s->window) continue;
        const int w = s->width, h = s->height;
        ANativeWindow_setBuffersGeometry(s->window, w, h, WINDOW_FORMAT_RGBA_8888);
        ANativeWindow_Buffer buffer{};
        if (ANativeWindow_lock(s->window, &buffer, nullptr) != 0) continue;
        if (buffer.width == w && buffer.height == h) {
            for (int y = 0; y < h; ++y) memcpy(static_cast<uint32_t*>(buffer.bits) + y * buffer.stride,
                s->frame.data() + static_cast<size_t>(y) * w, static_cast<size_t>(w) * 4);
            // Composite into the window buffer, never the decoded desktop: moving/hiding
            // the cursor restores its old pixels even when the server sends no new frame.
            if (s->cursorLocated) for (int y = 0; y < s->cursorHeight; ++y) {
                const int dy = s->cursorY - s->cursorHotY + y;
                if (dy < 0 || dy >= h) continue;
                for (int x = 0; x < s->cursorWidth; ++x) {
                    const int dx = s->cursorX - s->cursorHotX + x;
                    const uint32_t pixel = s->cursor[static_cast<size_t>(y) * s->cursorWidth + x];
                    if (dx >= 0 && dx < w && pixel >> 24) static_cast<uint32_t*>(buffer.bits)[dy * buffer.stride + dx] = pixel;
                }
            }
            s->dirty = false;
        }
        ANativeWindow_unlockAndPost(s->window);
    }
}
bool send(Session* s, const Command& cmd) {
    auto c = s->client;
    if (cmd.kind == Command::Pointer || cmd.kind == Command::Mouse)
        cursorPosition(c, std::clamp(cmd.x, 0, c->width - 1), std::clamp(cmd.y, 0, c->height - 1));
    switch (cmd.kind) {
        case Command::Pointer:
            s->buttons = cmd.value & 255;
            return SendPointerEvent(c, std::clamp(cmd.x, 0, c->width - 1), std::clamp(cmd.y, 0, c->height - 1), s->buttons);
        case Command::Mouse: {
            int x = std::clamp(cmd.x, 0, c->width - 1), y = std::clamp(cmd.y, 0, c->height - 1), flags = cmd.value;
            if (flags & (0x200 | 0x400)) {
                const int wheel = flags & 0x400 ? (flags & 0x100 ? 5 : 6) : (flags & 0x100 ? 4 : 3);
                return SendPointerEvent(c, x, y, s->buttons | (1 << wheel)) && SendPointerEvent(c, x, y, s->buttons);
            }
            int mask = flags & 0x1000 ? 1 : flags & 0x2000 ? 4 : flags & 0x4000 ? 2 : 0;
            if (mask) { if (flags & 0x8000) s->buttons |= mask; else s->buttons &= ~mask; }
            return SendPointerEvent(c, x, y, s->buttons);
        }
        case Command::Key: return SendKeyEvent(c, static_cast<uint32_t>(cmd.value), cmd.down);
        case Command::Clipboard:
            if (c->extendedClipboardServerCapabilities & rfbExtendedClipboard_Text)
                return SendClientCutTextUTF8(c, const_cast<char*>(cmd.utf8.data()), static_cast<int>(cmd.utf8.size()));
            return SendClientCutText(c, const_cast<char*>(cmd.latin1.data()), static_cast<int>(cmd.latin1.size()));
    }
    return false;
}
void work(Session* s) {
    while (!s->stop) {
        std::deque<Command> pending;
        { std::lock_guard<std::mutex> lock(s->queueMutex); pending.swap(s->commands); }
        bool okay = true;
        for (auto& cmd : pending) { if (s->stop || !send(s, cmd)) { okay = false; break; } }
        if (!okay) break;
        int ready = WaitForMessage(s->client, 20000);
        if (ready < 0 || (ready && !HandleRFBServerMessage(s->client))) break;
    }
    if (!s->stop.exchange(true)) {
        s->renderWake.notify_all();
        Env j(s->vm);
        if (j.env) { j.env->CallVoidMethod(s->bridge, s->disconnected); if (j.env->ExceptionCheck()) j.env->ExceptionClear(); }
    }
}
void enqueue(Session* s, Command cmd) {
    if (!s || s->stop) return;
    std::lock_guard<std::mutex> lock(s->queueMutex);
    // Coalesce hover motion; never discard key/button-up transitions.
    if (cmd.kind == Command::Mouse && cmd.value == 0x800 && !s->commands.empty() &&
        s->commands.back().kind == Command::Mouse && s->commands.back().value == 0x800) s->commands.back() = std::move(cmd);
    else s->commands.push_back(std::move(cmd));
}
std::string bytes(JNIEnv* env, jbyteArray data) {
    std::string out(env->GetArrayLength(data), '\0');
    env->GetByteArrayRegion(data, 0, out.size(), reinterpret_cast<jbyte*>(out.data())); return out;
}
} // namespace

// ZlibHex fills the upstream decoder gap for the fixed 32bpp RGBX format.
// RFB spec: https://github.com/rfbproto/rfbproto/blob/master/rfbproto.rst#zlibhex-encoding
// Two persistent streams: raw tiles and hextile tile bodies. Bounds checked before writes.
extern "C" rfbBool farsideZlibHex(rfbClient* c, int rx, int ry, int rw, int rh) {
    auto s = owner(c);
    if (c->format.bitsPerPixel != 32) return FALSE;
    uint32_t bg = 0, fg = 0;
    for (int y = ry; y < ry + rh; y += 16) for (int x = rx; x < rx + rw; x += 16) {
        const int w = std::min(16, rx + rw - x), h = std::min(16, ry + rh - y);
        uint8_t mask;
        if (!ReadFromRFBServer(c, reinterpret_cast<char*>(&mask), 1) || mask & 128) return FALSE;
        uint8_t decoded[2048]; size_t length = 0, offset = 0;
        bool compressed = mask & (32 | 64);
        if (compressed) {
            uint8_t size[2];
            if (!ReadFromRFBServer(c, reinterpret_cast<char*>(size), 2)) return FALSE;
            unsigned packedLength = (size[0] << 8) | size[1];
            if (!packedLength) return FALSE;
            std::vector<uint8_t> packed(packedLength);
            if (!ReadFromRFBServer(c, reinterpret_cast<char*>(packed.data()), packedLength)) return FALSE;
            int index = mask & 32 ? 0 : 1;
            auto& stream = s->hexStreams[index];
            if (!s->hexInitialized[index]) { if (inflateInit(&stream) != Z_OK) return FALSE; s->hexInitialized[index] = true; }
            stream.next_in = packed.data(); stream.avail_in = packed.size();
            stream.next_out = decoded; stream.avail_out = sizeof(decoded);
            int status = inflate(&stream, Z_SYNC_FLUSH);
            if ((status != Z_OK && status != Z_BUF_ERROR) || stream.avail_in || !stream.avail_out) return FALSE;
            length = sizeof(decoded) - stream.avail_out;
        }
        auto read = [&](void* out, size_t n) -> bool {
            if (!compressed) return ReadFromRFBServer(c, static_cast<char*>(out), n);
            if (offset + n > length) return false;
            memcpy(out, decoded + offset, n); offset += n; return true;
        };
        if (mask & (1 | 32)) {
            std::vector<uint8_t> raw(static_cast<size_t>(w) * h * 4);
            if (!read(raw.data(), raw.size())) return FALSE;
            c->GotBitmap(c, raw.data(), x, y, w, h);
        } else {
            if ((mask & 2) && !read(&bg, 4)) return FALSE;
            c->GotFillRect(c, x, y, w, h, bg);
            if ((mask & 4) && !read(&fg, 4)) return FALSE;
            if (mask & 8) {
                uint8_t count;
                if (!read(&count, 1)) return FALSE;
                for (int i = 0; i < count; ++i) {
                    uint8_t xy, wh;
                    if ((mask & 16) && !read(&fg, 4)) return FALSE;
                    if (!read(&xy, 1) || !read(&wh, 1)) return FALSE;
                    int sx = xy >> 4, sy = xy & 15, sw = (wh >> 4) + 1, sh = (wh & 15) + 1;
                    if (sx + sw > w || sy + sh > h) return FALSE;
                    c->GotFillRect(c, x + sx, y + sy, sw, sh, fg);
                }
            }
        }
        if (compressed && offset != length) return FALSE;
    }
    return TRUE;
}

jlong connectInternal(JNIEnv* env, jobject bridge, jstring host, jint port, jbyteArray password,
    jobject surface, jint authenticatedFd) {
    auto s = new Session(); env->GetJavaVM(&s->vm);
    s->bridge = env->NewGlobalRef(bridge);
    auto cls = env->GetObjectClass(bridge);
    s->disconnected = env->GetMethodID(cls, "onNativeDisconnected", "()V");
    s->clipboard = env->GetMethodID(cls, "onNativeClipboardChanged", "([BZ)V");
    env->DeleteLocalRef(cls);
    if (password) s->password = bytes(env, password);
    s->window = ANativeWindow_fromSurface(env, surface);
    s->client = rfbGetClient(8, 3, 4);
    if (!s->client && authenticatedFd >= 0) close(authenticatedFd);
    if (s->client) {
        auto c = s->client;
        rfbClientSetClientData(c, nullptr, s);
        const char* hostname = env->GetStringUTFChars(host, nullptr);
        c->serverHost = strdup(hostname); env->ReleaseStringUTFChars(host, hostname);
        c->serverPort = port; c->connectTimeout = 5; c->readTimeout = 10;
        c->appData.encodingsString = "zrle tight zlibhex hextile rre raw";
        c->appData.compressLevel = 6; c->appData.enableJPEG = FALSE;
        c->appData.useRemoteCursor = TRUE; c->canHandleNewFBSize = TRUE;
        c->GotCursorShape = cursorShape; c->HandleCursorPos = cursorPosition;
        c->format.bitsPerPixel = 32; c->format.depth = 24; c->format.trueColour = TRUE; c->format.bigEndian = FALSE;
        c->format.redMax = c->format.greenMax = c->format.blueMax = 255;
        c->format.redShift = 0; c->format.greenShift = 8; c->format.blueShift = 16;
        c->GetPassword = [](rfbClient* cl) { return strdup(owner(cl)->password.c_str()); };
        c->MallocFrameBuffer = allocateFrame;
        c->FinishedFrameBufferUpdate = updated;
        c->GotXCutText = [](rfbClient* cl, const char* text, int len) { cutText(cl, text, len, false); };
        c->GotXCutTextUTF8 = [](rfbClient* cl, const char* text, int len) { cutText(cl, text, len, true); };
        const uint32_t auth[] = {rfbVncAuth}; SetClientAuthSchemes(c, auth, 1);
        // Split connect/init avoids rfbInitClient freeing the client behind our owner on error.
        const bool connected = authenticatedFd >= 0 ? (c->sock = authenticatedFd, c->authScheme = rfbARD, c->major = 3, c->minor = 8, true)
            : rfbClientConnect(c);
        const bool initialized = connected && rfbClientInitialise(c);
        __android_log_print(ANDROID_LOG_INFO, "VncSession", "connect=%d initialized=%d security=%u size=%dx%d", connected, initialized, c->authScheme, c->width, c->height);
        if (initialized && c->authScheme == (authenticatedFd >= 0 ? rfbARD : rfbVncAuth)) {
            std::fill(s->password.begin(), s->password.end(), '\0'); s->password.clear();
            s->renderer = std::thread(render, s); s->worker = std::thread(work, s);
            return reinterpret_cast<jlong>(s);
        }
    }
    env->DeleteGlobalRef(s->bridge); delete s; return 0;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_farside_workspace_VncBridge_nativeConnect(
    JNIEnv* env, jobject bridge, jstring host, jint port, jbyteArray password, jobject surface) {
    return connectInternal(env, bridge, host, port, password, surface, -1);
}
extern "C" JNIEXPORT jlong JNICALL Java_com_farside_workspace_VncBridge_nativeConnectAuthenticated(
    JNIEnv* env, jobject bridge, jstring host, jint port, jint fd, jobject surface) {
    return connectInternal(env, bridge, host, port, nullptr, surface, fd);
}
extern "C" JNIEXPORT void JNICALL Java_com_farside_workspace_VncBridge_nativeDisconnect(JNIEnv* env, jobject, jlong id) {
    auto s = reinterpret_cast<Session*>(id); if (!s) return;
    s->stop = true; s->renderWake.notify_all();
    if (s->client && s->client->sock != RFB_INVALID_SOCKET) shutdown(s->client->sock, SHUT_RDWR);
    if (s->worker.joinable()) s->worker.join();
    if (s->renderer.joinable()) s->renderer.join();
    env->DeleteGlobalRef(s->bridge); delete s;
}
extern "C" JNIEXPORT void JNICALL Java_com_farside_workspace_VncBridge_nativeSetSurface(JNIEnv* env, jobject, jlong id, jobject surface) {
    auto s = reinterpret_cast<Session*>(id);
    auto next = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    { std::lock_guard<std::mutex> lock(s->surfaceMutex); if (s->window) ANativeWindow_release(s->window); s->window = next; }
    { std::lock_guard<std::mutex> lock(s->frameMutex); s->dirty = true; }
    s->renderWake.notify_one();
}
extern "C" JNIEXPORT void JNICALL Java_com_farside_workspace_VncBridge_nativePointer(JNIEnv*, jobject, jlong id, jint x, jint y, jint mask) {
    Command cmd{Command::Pointer}; cmd.x = x; cmd.y = y; cmd.value = mask; enqueue(reinterpret_cast<Session*>(id), std::move(cmd));
}
extern "C" JNIEXPORT void JNICALL Java_com_farside_workspace_VncBridge_nativeMouse(JNIEnv*, jobject, jlong id, jint x, jint y, jint flags) {
    Command cmd{Command::Mouse}; cmd.x = x; cmd.y = y; cmd.value = flags; enqueue(reinterpret_cast<Session*>(id), std::move(cmd));
}
extern "C" JNIEXPORT void JNICALL Java_com_farside_workspace_VncBridge_nativeKey(JNIEnv*, jobject, jlong id, jint key, jboolean down) {
    Command cmd{Command::Key}; cmd.value = key; cmd.down = down; enqueue(reinterpret_cast<Session*>(id), std::move(cmd));
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_farside_workspace_VncBridge_nativeClipboard(JNIEnv* env, jobject, jlong id, jbyteArray utf8, jbyteArray latin1) {
    auto s = reinterpret_cast<Session*>(id);
    if (s->stop || env->GetArrayLength(utf8) > maxClip || env->GetArrayLength(latin1) > maxClip) return false;
    Command cmd{Command::Clipboard}; cmd.utf8 = bytes(env, utf8); cmd.latin1 = bytes(env, latin1);
    enqueue(s, std::move(cmd)); return true;
}
extern "C" JNIEXPORT jint JNICALL Java_com_farside_workspace_VncBridge_nativeSize(JNIEnv*, jobject, jlong id, jboolean width) {
    auto s = reinterpret_cast<Session*>(id); return width ? s->width.load() : s->height.load();
}
