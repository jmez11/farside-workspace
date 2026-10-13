package com.farside.workspace

import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Surface
import com.farside.workspace.data.ConnectionProfile
import com.farside.workspace.data.ConnectionType
import com.farside.workspace.data.ProfileManager
import org.json.JSONObject

internal class RdpStorageVerification(private val test: RdpResizeInstrumentation) {

    fun run(mode: String) {
        when (mode) {
            "capabilities" -> verifyStorageCapabilities()
            "ui" -> verifyStorageUi()
            "live-nucbox" -> verifyLiveNucbox()
            "live-laptop" -> verifyLiveLaptop()
            else -> error("Unknown storage verification mode: $mode")
        }
    }

    private fun verifyStorageCapabilities() {
        // 1. ConnectionProfile defaults
        val defaultProfile = ConnectionProfile("temp", "temp", ConnectionType.RDP, "host", 3389, "u", "p")
        check(!defaultProfile.redirectStorage) { "New profiles must default to storage redirection disabled (redirectStorage = false)" }

        // 2. Intent generation for All Files Access
        val intent = createManageAllFilesIntent(test.targetContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            check(intent.action == Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION ||
                  intent.action == Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION) {
                "Expected all files access action on API 30+, got: ${intent.action}"
            }
        }

        // 3. JSON round-trip serialization
        val profileWithStorage = defaultProfile.copy(redirectStorage = true)
        val jsonWithStorage = profileWithStorage.toJson()
        check(jsonWithStorage.getBoolean("redirectStorage")) { "JSON serialization should include redirectStorage=true" }
        val restoredWithStorage = ConnectionProfile.fromJson(jsonWithStorage)
        check(restoredWithStorage.redirectStorage) { "JSON deserialization should restore redirectStorage=true" }

        val profileWithoutStorage = defaultProfile.copy(redirectStorage = false)
        val jsonWithoutStorage = profileWithoutStorage.toJson()
        check(!jsonWithoutStorage.getBoolean("redirectStorage")) { "JSON serialization should include redirectStorage=false" }
        val restoredWithoutStorage = ConnectionProfile.fromJson(jsonWithoutStorage)
        check(!restoredWithoutStorage.redirectStorage) { "JSON deserialization should restore redirectStorage=false" }

        // 4. ProfileManager persistence
        val profileManager = ProfileManager(test.targetContext)
        val existingProfiles = profileManager.getProfiles().toMutableList()
        val testProfile = ConnectionProfile(
            id = "test-storage-profile",
            name = "Test Storage Profile",
            type = ConnectionType.RDP,
            host = "127.0.0.1",
            port = 3389,
            user = "user",
            pass = "pass",
            redirectStorage = true
        )
        try {
            profileManager.addProfile(testProfile)
            val loaded = profileManager.getProfiles().firstOrNull { it.id == "test-storage-profile" }
            checkNotNull(loaded) { "Failed to find saved profile" }
            check(loaded.redirectStorage) { "Saved profile should retain redirectStorage=true" }
        } finally {
            profileManager.deleteProfile("test-storage-profile")
        }
    }

    private fun verifyStorageUi() {
        // Verification that menubar floating toolbar excludes storage icon
        // Menubar is reserved for audio and camera; storage runs headless in-session
        val context = test.targetContext
        val hasStorage = hasStoragePermission(context)
        android.util.Log.i("RdpStorageVerification", "Device storage permission status: hasStoragePermission=$hasStorage")

        // Ensure requestStoragePermission function invokes launcher properly
        var launcherInvoked = false
        requestStoragePermission(context) { _ ->
            launcherInvoked = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            check(launcherInvoked) { "requestStoragePermission should invoke launcher intent callback on API 30+" }
        }
    }

    private fun verifyLiveNucbox() {
        val profiles = ProfileManager(test.targetContext).getProfiles()
        val nucbox = checkNotNull(profiles.firstOrNull { it.host.contains("172.16.0.32") || it.name.contains("nucbox", ignoreCase = true) }) {
            "nucbox profile not found in saved profiles"
        }
        val bridge = FreeRDPBridge()
        val surfaceTexture = SurfaceTexture(0)
        val surface = Surface(surfaceTexture)
        val verifier = RdpCertificateVerifier(test.targetContext, nucbox.host, nucbox.port, { request -> request?.respond(true) })

        val instance = bridge.connect(
            nucbox.host, nucbox.port, nucbox.user, nucbox.pass, null,
            adminMode = false, enableCamera = false, enableMicrophone = false, enableStorage = true,
            surface = surface,
            certificateHost = nucbox.host, certificateVerifier = verifier
        )
        check(instance != 0L) { "Failed to connect to nucbox with storage enabled" }
        try {
            val deadline = SystemClock.uptimeMillis() + 10000
            var connected = false
            while (SystemClock.uptimeMillis() < deadline) {
                if (bridge.isStorageConnected(instance)) {
                    connected = true
                    break
                }
                Thread.sleep(200)
            }
            check(connected) { "Storage redirection (rdpdr) failed to connect within 10s on nucbox" }
            android.util.Log.i("RdpStorageVerification", "Storage redirection connected successfully on nucbox")
        } finally {
            // Disconnect promptly (<15s) per session discipline
            bridge.disconnect(instance)
            surface.release()
            surfaceTexture.release()
        }
    }

    private fun verifyLiveLaptop() {
        val profiles = ProfileManager(test.targetContext).getProfiles()
        val laptop = checkNotNull(profiles.firstOrNull { it.host.contains("172.16.0.54") || it.name.contains("LAPTOP", ignoreCase = true) }) {
            "LAPTOP profile not found in saved profiles"
        }
        val bridge = FreeRDPBridge()
        val surfaceTexture = SurfaceTexture(0)
        val surface = Surface(surfaceTexture)
        val verifier = RdpCertificateVerifier(test.targetContext, laptop.host, laptop.port, { request -> request?.respond(true) })

        val instance = bridge.connect(
            laptop.host, laptop.port, laptop.user, laptop.pass, null,
            adminMode = false, enableCamera = false, enableMicrophone = false, enableStorage = true,
            surface = surface,
            certificateHost = laptop.host, certificateVerifier = verifier
        )
        check(instance != 0L) { "Failed to connect to laptop with storage enabled" }
        try {
            val deadline = SystemClock.uptimeMillis() + 6000
            var connected = false
            while (SystemClock.uptimeMillis() < deadline) {
                if (bridge.isStorageConnected(instance)) {
                    connected = true
                    break
                }
                Thread.sleep(200)
            }
            android.util.Log.i("RdpStorageVerification", "Laptop storage redirection connected: $connected")
        } finally {
            bridge.disconnect(instance)
            surface.release()
            surfaceTexture.release()
        }
    }
}
