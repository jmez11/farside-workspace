package com.farside.workspace.ui

import com.farside.workspace.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.farside.workspace.CredentialPin
import com.farside.workspace.data.ProfileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CredentialPinDialog(
    title: String = stringResource(R.string.unlock_credential),
    message: String = stringResource(R.string.enter_app_pin),
    verifier: String? = null,
    onUnlocked: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val effectiveVerifier = verifier ?: remember { ProfileManager(context).getAppPinVerifier() }

    val isCachedValid = remember(effectiveVerifier) {
        effectiveVerifier != null && CredentialPin.hasValidCachedPin(effectiveVerifier)
    }
    if (isCachedValid) {
        LaunchedEffect(Unit) {
            onUnlocked()
        }
        return
    }

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var rememberFor5Min by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(title) },
        text = { Column {
            Text(message)
            OutlinedTextField(pin, { pin = it; error = null }, label = { Text(stringResource(R.string.pin)) },
                singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false),
                isError = error != null)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clickable(enabled = !busy) { rememberFor5Min = !rememberFor5Min }
            ) {
                Checkbox(
                    checked = rememberFor5Min,
                    onCheckedChange = null,
                    enabled = !busy
                )
                Spacer(Modifier.width(8.dp))
                Text(pluralStringResource(R.plurals.pin_remember_minutes, 5, 5), style = MaterialTheme.typography.bodyMedium)
            }
        } },
        confirmButton = { TextButton(enabled = !busy && pin.isNotEmpty(), onClick = {
            busy = true
            val enteredPin = pin
            scope.launch {
                error = withContext(Dispatchers.Default) { CredentialPin.unlock(context, effectiveVerifier, enteredPin) }
                pin = ""
                busy = false
                if (error == null) {
                    if (rememberFor5Min) {
                        CredentialPin.cachePin(enteredPin)
                    }
                    onUnlocked()
                }
            }
        }) { Text(if (busy) stringResource(R.string.checking) else stringResource(R.string.unlock)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onCancel) { Text(stringResource(R.string.cancel)) } }
    )
}
