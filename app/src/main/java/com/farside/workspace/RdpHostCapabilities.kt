package com.farside.workspace

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Verified host diagnostics, separate from on-demand audio channel activity. */
internal class RdpHostCapabilities(context: Context) {
    private val report = File(context.filesDir, "rdp-host-capabilities.json")

    fun microphoneUnsupported(host: String): Boolean = try {
        val json = JSONObject(report.readText())
        val aliases = json.optJSONArray("HostAliases")
        val matchesHost = aliases != null && (0 until aliases.length()).any {
            aliases.optString(it).equals(host.trim(), ignoreCase = true)
        }
        matchesHost && json.optString("CaptureLicensePolicy") ==
            "TerminalServices-DeviceRedirection-Licenses-TSAudioCaptureAllowed" &&
            json.optString("CaptureLicenseResult") == "0x00000000" &&
            json.has("CaptureLicenseAllowed") && !json.isNull("CaptureLicenseAllowed") &&
            json.getInt("CaptureLicenseAllowed") == 0
    } catch (_: Exception) {
        false // Missing/incomplete evidence means unknown, not unsupported.
    }

    fun cameraUnsupported(host: String): Boolean = try {
        val json = JSONObject(report.readText())
        val aliases = json.optJSONArray("HostAliases")
        val matchesHost = aliases != null && (0 until aliases.length()).any {
            aliases.optString(it).equals(host.trim(), ignoreCase = true)
        }
        val isCore = json.optString("Edition").equals("Core", ignoreCase = true)
        val captureLicenseAllowed = if (json.has("CaptureLicenseAllowed") && !json.isNull("CaptureLicenseAllowed")) json.getInt("CaptureLicenseAllowed") else 1
        matchesHost && (isCore || captureLicenseAllowed == 0)
    } catch (_: Exception) {
        false
    }
}
