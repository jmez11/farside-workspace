package com.farside.workspace.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farside.workspace.BuildConfig
import com.farside.workspace.R
import com.farside.workspace.ui.AdbTunnelSection
import com.farside.workspace.ui.ClearCloudflareLoginsButton

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InfoScreen(onClose:()->Unit) {
    val context=LocalContext.current
    val certificatesCleared=stringResource(R.string.trusted_certificates_cleared)
    val permissionsScheduled=stringResource(R.string.permissions_revoke_scheduled)
    val permissionsUnsupported=stringResource(R.string.permissions_revoke_requires_android_13)
    var status by remember {mutableStateOf<String?>(null)}
    val actionColors=ButtonDefaults.buttonColors(containerColor=Color(0xFFEA5A00),contentColor=Color.White)
    MaterialTheme(colorScheme=darkColorScheme(primary=SetupColors.accent,surface=SetupColors.background,background=SetupColors.background,onPrimary=SetupColors.background)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(stringResource(R.string.about),fontSize=19.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                    IconButton(onClick=onClose,modifier=Modifier.size(32.dp)) {Icon(Icons.Default.Close,stringResource(R.string.close),Modifier.size(20.dp))}
                }
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.farside_workspace),fontSize=18.sp,fontWeight=FontWeight.Bold)
                    Text(stringResource(R.string.about_version,BuildConfig.VERSION_NAME),fontSize=13.sp,color=SetupColors.muted)
                    Text("OceanSideVR",fontSize=13.sp,color=SetupColors.muted)
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.open_source_licenses),fontSize=13.sp,color=SetupColors.muted,fontWeight=FontWeight.SemiBold)
                VaultCard {
                    listOf(R.string.license_freerdp,R.string.license_sshj,R.string.license_termux,R.string.license_compose,R.string.license_meta).forEachIndexed {index,label ->
                        if(index>0) Divider(color=SetupColors.border)
                        Text(stringResource(label),fontSize=14.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.wireless_adb_cloudflare_tunnel),fontSize=13.sp,color=SetupColors.muted,fontWeight=FontWeight.SemiBold)
                VaultCard {ProvideTextStyle(MaterialTheme.typography.bodySmall) {AdbTunnelSection(compact=true)}}
                if(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.debug_actions),fontSize=13.sp,color=SetupColors.muted,fontWeight=FontWeight.SemiBold)
                    VaultCard {
                        Text(stringResource(R.string.options_for_testing_sign_in_trust_store_prompts_and_permission_r),fontSize=13.sp,color=SetupColors.muted)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Button(shape=RoundedCornerShape(8.dp),colors=actionColors,contentPadding=PaddingValues(12.dp,8.dp),onClick={
                                com.farside.workspace.RdpCertificateStore(context).clearAll();status=certificatesCleared
                            }) {Text(stringResource(R.string.clear_trusted_certificates),fontSize=12.sp)}
                            Button(shape=RoundedCornerShape(8.dp),colors=actionColors,contentPadding=PaddingValues(12.dp,8.dp),onClick={
                                if(android.os.Build.VERSION.SDK_INT>=33) {
                                    context.revokeSelfPermissionsOnKill(listOf(android.Manifest.permission.CAMERA,android.Manifest.permission.RECORD_AUDIO,"horizonos.permission.HEADSET_CAMERA","horizonos.permission.AVATAR_CAMERA",android.Manifest.permission.READ_EXTERNAL_STORAGE))
                                    status=permissionsScheduled
                                } else status=permissionsUnsupported
                            }) {Text(stringResource(R.string.revoke_granted_permissions),fontSize=12.sp)}
                            ClearCloudflareLoginsButton(compact=true) {status=it}
                        }
                        status?.let {Text(it,color=SetupColors.muted,fontSize=13.sp)}
                    }
                }
            }
        }
    }
}
