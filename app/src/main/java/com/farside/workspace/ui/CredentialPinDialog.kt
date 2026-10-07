package com.farside.workspace.ui

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.farside.workspace.CredentialPin
import com.farside.workspace.data.ProfileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CredentialPinDialog(
    title: String = "Unlock credential",
    message: String = "Enter the App PIN.",
    verifier: String? = null,
    onUnlocked: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val effectiveVerifier = verifier ?: remember { ProfileManager(context).getAppPinVerifier() }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text(title) },
        text = { Column {
            Text(message)
            OutlinedTextField(pin, { pin = it; error = null }, label = { Text("PIN") },
                singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrect = false),
                isError = error != null)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy && pin.isNotEmpty(), onClick = {
            busy = true
            scope.launch {
                error = withContext(Dispatchers.Default) { CredentialPin.unlock(context, effectiveVerifier, pin) }
                pin = ""
                busy = false
                if (error == null) onUnlocked()
            }
        }) { Text(if (busy) "Checking…" else "Unlock") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onCancel) { Text("Cancel") } }
    )
}
