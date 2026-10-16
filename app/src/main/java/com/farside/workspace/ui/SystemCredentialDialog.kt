package com.farside.workspace.ui

import com.farside.workspace.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.CancellationSignal
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.farside.workspace.DeviceCredentialAuth

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
fun SystemCredentialDialog(
    title: String = stringResource(R.string.unlock_credential),
    message: String = stringResource(R.string.confirm_headset_passcode),
    allowRemembered: Boolean = true,
    onUnlocked: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val systemAuthRequiresWindowMsg = stringResource(R.string.system_auth_requires_window)
    val latestUnlocked by rememberUpdatedState(onUnlocked)
    var rememberFor5Min by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val request = remember { arrayOfNulls<CancellationSignal>(1) }
    DisposableEffect(Unit) { onDispose { request[0]?.cancel() } }
    LaunchedEffect(Unit) {
        if (allowRemembered && DeviceCredentialAuth.hasRememberedUnlock(context)) latestUnlocked()
    }
    AlertDialog(
        onDismissRequest = { request[0]?.cancel(); onCancel() },
        title = { Text(title) },
        text = { Column {
            Text(message)
            if (allowRemembered) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = rememberFor5Min, onCheckedChange = { rememberFor5Min = it }, enabled = !busy)
                Text(pluralStringResource(R.plurals.pin_remember_minutes, 5, 5))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val activity = context.activity()
            if (activity == null) { error = systemAuthRequiresWindowMsg }
            else {
                busy = true
                error = null
                request[0] = DeviceCredentialAuth.authenticate(activity, title, allowRemembered && rememberFor5Min) { failure ->
                    busy = false
                    error = failure
                    if (failure == null) latestUnlocked()
                }
            }
        }) { Text(if (busy) stringResource(R.string.waiting_for_system) else stringResource(R.string.unlock_with_headset_passcode)) } },
        dismissButton = { TextButton(onClick = { request[0]?.cancel(); onCancel() }) { Text(stringResource(R.string.cancel)) } }
    )
}
