#pragma once

#include <freerdp/client/cmdline.h>

// Shared with the Android native fixture so deprecated switches cannot silently
// prevent transport policy from being applied. Host/credentials are already set.
static inline int quest_apply_rdp_options(rdpSettings* settings, BOOL tcpOnly) {
    const char* args[] = {
        "freerdp",
        "/cache:bitmap:on,glyph:on,offscreen:on",
        "/gfx",
        "/rfx",
        "/bpp:32",
        "/compression",
        "/network:auto",
        // The CLI parser defaults this OFF. Farside's IME sends Unicode events.
        "/kbd:unicode:on",
        // Sets SOFTSYNC_TCP_TO_UDP too; a UDP boolean alone omits channel migration.
        tcpOnly ? "/multitransport:tcp-only" : "/multitransport:udp"
    };
    return freerdp_client_settings_parse_command_line(settings,
        sizeof(args) / sizeof(args[0]), (char**)args, FALSE);
}
