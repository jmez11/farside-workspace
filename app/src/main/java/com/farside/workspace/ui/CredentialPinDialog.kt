package com.farside.workspace.ui

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.farside.workspace.CredentialPin
import com.farside.workspace.data.SavedCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CredentialPinDialog(credential: SavedCredential, onUnlocked: () -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("Unlock credential") },
        text = { Column {
            Text("Enter the PIN for ${credential.name}.")
            OutlinedTextField(pin, { pin = it; error = null }, label = { Text("PIN") },
                singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrect = false),
                isError = error != null)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy && pin.isNotEmpty(), onClick = {
            busy = true
            scope.launch {
                error = withContext(Dispatchers.Default) { CredentialPin.unlock(context, credential, pin) }
                pin = ""
                busy = false
                if (error == null) onUnlocked()
            }
        }) { Text(if (busy) "Checking…" else "Unlock") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onCancel) { Text("Cancel") } }
    )
}
