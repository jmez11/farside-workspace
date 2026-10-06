package com.farside.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

@Composable
internal fun RdpCertificateDialog(request: RdpCertificateRequest) {
    var rememberCertificate by remember(request) { mutableStateOf(false) }
    var details by remember(request) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = request::reject,
        title = { Text(if (request.changed) "Certificate has changed" else "Certificate can't be verified") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You are connecting to: ${request.host}:${request.port}")
                Text("Name in certificate from the remote PC: ${request.subject}")
                if (request.changed) Text("This certificate differs from the one you saved. Review it before connecting. Saving replaces the previous certificate.")
                Text("Do you want to connect anyway?")
                request.reasons.forEach { Text(it) }
                Row(Modifier.fillMaxWidth().clickable { rememberCertificate = !rememberCertificate },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberCertificate, onCheckedChange = { rememberCertificate = it })
                    Text("Remember this certificate for this PC")
                }
                TextButton(onClick = { details = !details }) { Text(if (details) "Less details" else "More details") }
                if (details) {
                    Text("Issuer: ${request.issuer}")
                    Text("Valid from: ${request.validFrom}\nValid until: ${request.validUntil}")
                    Text("SHA-256 fingerprint:\n${request.fingerprint}")
                    request.previousFingerprint?.let { Text("Previously saved SHA-256 fingerprint:\n$it") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { request.respond(rememberCertificate) }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = request::reject) { Text("Cancel") } },
    )
}
