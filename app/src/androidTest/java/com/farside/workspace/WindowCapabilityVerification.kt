package com.farside.workspace

import android.app.Instrumentation
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File

/** Ordinary-app inventory; API availability alone does not prove physical drop support. */
internal class WindowCapabilityVerification(private val test: Instrumentation) {
    fun run() {
        val context = test.targetContext
        val version = Class.forName("metavr.os.Build\$Version")
        val sdk = version.getMethod("getApiVersion").invoke(null) as Int
        check(sdk > 0) { "Meta OS SDK query returned $sdk" }
        @Suppress("DEPRECATION")
        val home = context.packageManager.getPackageInfo("com.oculus.vrshell", PackageManager.GET_META_DATA)
        val result = JSONObject()
            .put("uid", android.os.Process.myUid())
            .put("metaOsSdkApi", sdk)
            .put("homeVersion", home.versionName)
            .put("homeVersionCode", home.longVersionCode)
            .put("activityDragSupport", "Not established by this inventory; requires physical release validation")
        File(context.cacheDir, "window-capability-results.json").writeText(result.toString(2))
        android.util.Log.i("SessionWindow", "Window capability inventory: $result")
    }
}
