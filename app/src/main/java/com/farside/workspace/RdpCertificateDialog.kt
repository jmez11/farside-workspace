package com.farside.workspace

import com.farside.workspace.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
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
        title = { Text(if (request.changed) stringResource(R.string.certificate_changed) else stringResource(R.string.certificate_unverified)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.certificate_target, request.host, request.port))
                Text(stringResource(R.string.certificate_subject, request.subject))
                if (request.changed) Text(stringResource(R.string.certificate_changed_help))
                Text(stringResource(R.string.certificate_connect_anyway))
                request.reasons.forEach { Text(it) }
                Row(Modifier.fillMaxWidth().clickable { rememberCertificate = !rememberCertificate },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberCertificate, onCheckedChange = { rememberCertificate = it })
                    Text(stringResource(R.string.remember_certificate))
                }
                TextButton(onClick = { details = !details }) { Text(if (details) stringResource(R.string.less_details) else stringResource(R.string.more_details)) }
                if (details) {
                    Text(stringResource(R.string.certificate_issuer, request.issuer))
                    Text(stringResource(R.string.certificate_validity, request.validFrom, request.validUntil))
                    Text(stringResource(R.string.certificate_fingerprint, request.fingerprint))
                    request.previousFingerprint?.let { Text(stringResource(R.string.certificate_previous_fingerprint, it)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { request.respond(rememberCertificate) }) { Text(stringResource(R.string.connect)) } },
        dismissButton = { TextButton(onClick = request::reject) { Text(stringResource(R.string.cancel)) } },
    )
}
