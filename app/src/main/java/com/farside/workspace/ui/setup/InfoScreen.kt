package com.farside.workspace.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.farside.workspace.R
import com.farside.workspace.ui.AdbTunnelSection
import com.farside.workspace.ui.ClearCloudflareLoginsButton

@Composable
fun InfoScreen(onClose:()->Unit) {
    val context=LocalContext.current
    var status by remember {mutableStateOf<String?>(null)}
    MaterialTheme(colorScheme=darkColorScheme(primary=SetupColors.accent,surface=SetupColors.background,background=SetupColors.background,onPrimary=SetupColors.background)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Row {IconButton(onClick=onClose) {Icon(Icons.Default.Close,stringResource(R.string.close))};Text(stringResource(R.string.setup_info),style=MaterialTheme.typography.headlineSmall)}
                AdbTunnelSection()
                if(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    Text(stringResource(R.string.debug_actions),style=MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick={com.farside.workspace.RdpCertificateStore(context).clearAll();status=context.getString(R.string.trusted_certificates_cleared)}) {Text(stringResource(R.string.clear_trusted_certificates))}
                    OutlinedButton(onClick={
                        if(android.os.Build.VERSION.SDK_INT>=33) {
                            context.revokeSelfPermissionsOnKill(listOf(android.Manifest.permission.CAMERA,android.Manifest.permission.RECORD_AUDIO,"horizonos.permission.HEADSET_CAMERA","horizonos.permission.AVATAR_CAMERA",android.Manifest.permission.READ_EXTERNAL_STORAGE))
                            status=context.getString(R.string.permissions_revoke_scheduled)
                        } else status=context.getString(R.string.permissions_revoke_requires_android_13)
                    }) {Text(stringResource(R.string.revoke_granted_permissions))}
                    ClearCloudflareLoginsButton {status=it}
                    status?.let {Text(it,color=SetupColors.muted)}
                }
                Text(stringResource(R.string.about_open_source_licenses),style=MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.farside_workspace))
                listOf(R.string.license_freerdp,R.string.license_sshj,R.string.license_termux,R.string.license_compose,R.string.license_meta).forEach {Text(stringResource(it),color=SetupColors.muted)}
            }
        }
    }
}
