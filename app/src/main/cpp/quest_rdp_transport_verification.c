#include <jni.h>
#include <freerdp/freerdp.h>
#include <freerdp/settings.h>
#include <freerdp/version.h>
#include "quest_rdp_settings.h"
#include "freerdp/libfreerdp/core/rdpemt.h"

int TestRdpUdp(int argc, char* argv[]);

JNIEXPORT jint JNICALL
Java_com_farside_workspace_RdpTransportVerification_nativeKeyboardFixture(JNIEnv* env, jclass clazz)
{
    (void)env;
    (void)clazz;
    for (int tcpOnly = 0; tcpOnly <= 1; tcpOnly++) {
        rdpSettings* settings = freerdp_settings_new(0);
        if (!settings) return -1;
        freerdp_settings_set_string(settings, FreeRDP_ServerHostname, "127.0.0.1");
        freerdp_settings_set_bool(settings, FreeRDP_UnicodeInput, FALSE);
        BOOL ok = quest_apply_rdp_options(settings, tcpOnly) == 0 &&
            freerdp_settings_get_bool(settings, FreeRDP_UnicodeInput);
        freerdp_settings_free(settings);
        if (!ok) return tcpOnly ? -3 : -2;
    }
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_farside_workspace_RdpTransportVerification_nativeFixture(JNIEnv* env, jclass clazz)
{
    (void)env;
    (void)clazz;
    if (FREERDP_VERSION_MAJOR != 3 || FREERDP_VERSION_MINOR < 33) return -3;
    if (!rdpemt_is_supported()) return -2;
    rdpSettings* settings = freerdp_settings_new(0);
    if (!settings) return -4;
    freerdp_settings_set_string(settings, FreeRDP_ServerHostname, "127.0.0.1");
    BOOL ok = quest_apply_rdp_options(settings, FALSE) == 0 &&
        freerdp_settings_get_bool(settings, FreeRDP_UnicodeInput) &&
        freerdp_settings_get_bool(settings, FreeRDP_SupportMultitransportUdp) &&
        (freerdp_settings_get_uint32(settings, FreeRDP_MultitransportFlags) & SOFTSYNC_TCP_TO_UDP);
    ok = ok && quest_apply_rdp_options(settings, TRUE) == 0 &&
        freerdp_settings_get_bool(settings, FreeRDP_UnicodeInput) &&
        !freerdp_settings_get_bool(settings, FreeRDP_SupportMultitransportUdp);
    freerdp_settings_free(settings);
    if (!ok) return -5;
    return TestRdpUdp(0, NULL);
}

JNIEXPORT jboolean JNICALL
Java_com_farside_workspace_RdpTransportVerification_udpEnabled(JNIEnv* env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
    freerdp* instance = (freerdp*)handle;
    return instance && instance->context &&
           freerdp_settings_get_bool(instance->context->settings, FreeRDP_SupportMultitransportUdp);
}

JNIEXPORT jboolean JNICALL
Java_com_farside_workspace_RdpTransportVerification_unicodeEnabled(JNIEnv* env, jclass clazz, jlong handle)
{
    (void)env;
    (void)clazz;
    freerdp* instance = (freerdp*)handle;
    return instance && instance->context &&
           freerdp_settings_get_bool(instance->context->settings, FreeRDP_UnicodeInput);
}
