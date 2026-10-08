package com.farside.workspace

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

internal val REQUIRED_CAMERA_PERMISSIONS = arrayOf(
    Manifest.permission.CAMERA,
    "horizonos.permission.HEADSET_CAMERA",
    "horizonos.permission.AVATAR_CAMERA"
)

internal fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
