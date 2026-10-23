package com.farside.workspace.ui.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farside.workspace.*
import com.farside.workspace.R
import com.farside.workspace.data.*
import com.farside.workspace.ssh.JumpChains
import com.farside.workspace.tunnel.CloudflaredOptions
import com.farside.workspace.ui.SshForwardingDialog

internal fun setupOptionsError(context:android.content.Context,profile:ConnectionProfile,all:List<ConnectionProfile>):String? {
    if(profile.proxyType !in ProxyType.choices) return context.getString(R.string.profile_proxy_type_invalid)
    if(profile.proxyType in listOf(ProxyType.HTTP,ProxyType.SOCKS5)) {
        if(profile.proxyHost.isNullOrBlank()) return context.getString(R.string.profile_proxy_host_required)
        if(profile.proxyPort !in 1..65535) return context.getString(R.string.profile_port_invalid)
    }
    if(profile.proxyType==ProxyType.CLOUDFLARED) {
        try {CloudflaredOptions.hostname(profile.host);CloudflaredOptions.parse(profile.cloudflaredParameters)}
        catch(error:Exception) {return error.localizedMessage(context)}
    }
    if(profile.type==ConnectionType.SSH) return JumpChains.preview(context,profile,all).second
    return null
}

@Composable
private fun SetupOptionGroup(title:Int,content:@Composable ColumnScope.()->Unit) {
    Surface(Modifier.fillMaxWidth(),color=SetupColors.card,shape=RoundedCornerShape(14.dp),border=BorderStroke(1.dp,SetupColors.border)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(title),fontSize=15.sp,fontWeight=FontWeight.Bold)
            content()
        }
    }
}

@Composable
internal fun SetupToggle(label:Int,checked:Boolean,onChange:(Boolean)->Unit,helper:Int?=null) {
    val text=stringResource(label)
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(text,fontSize=14.sp)
            helper?.let {Text(stringResource(it),fontSize=13.sp,color=SetupColors.muted)}
        }
        Switch(checked=checked,onCheckedChange=onChange,modifier=Modifier.semantics {contentDescription=text},
            colors=SwitchDefaults.colors(checkedTrackColor=SetupColors.accent,checkedThumbColor=SetupColors.background))
    }
}

@Composable
private fun SetupProfilePicker(label:Int,selected:String?,profiles:List<ConnectionProfile>,onSelect:(String?)->Unit) {
    var open by remember {mutableStateOf(false)}
    val name=profiles.find {it.id==selected}?.name ?: stringResource(R.string.setup_none)
    Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Text(stringResource(label),fontSize=13.sp,color=SetupColors.muted)
        Box {
            OutlinedButton(onClick={open=true},shape=RoundedCornerShape(11.dp),modifier=Modifier.fillMaxWidth()) {Text(name)}
            DropdownMenu(expanded=open,onDismissRequest={open=false}) {
                DropdownMenuItem(text={Text(stringResource(R.string.setup_none))},onClick={onSelect(null);open=false})
                profiles.forEach { profile ->DropdownMenuItem(text={Text(profile.name)},onClick={onSelect(profile.id);open=false})}
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupReviewOptions(profile:ConnectionProfile,profiles:List<ConnectionProfile>,onChange:(ConnectionProfile)->Unit,onEdit:(Int)->Unit,showErrors:Boolean=false) {
    val context=LocalContext.current
    val askAtConnect=stringResource(R.string.setup_ask_at_connect)
    var forwardsOpen by remember {mutableStateOf(false)}
    var relayConfirm by remember {mutableStateOf(false)}
    val mic=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted ->onChange(profile.copy(redirectMicrophone=granted && hasMicrophonePermission(context)))}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {_ ->onChange(profile.copy(enableCamera=hasCameraPermission(context)))}
    val storage=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {_ ->onChange(profile.copy(redirectStorage=hasStoragePermission(context)))}
    val sftp=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {_ ->onChange(profile.copy(enableSftp=hasStoragePermission(context)))}
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.setup_review),fontSize=20.sp,fontWeight=FontWeight.Bold)
        SetupOptionGroup(R.string.setup_summary) {
            Text(profile.name,fontWeight=FontWeight.Bold)
            Text("${profile.type.name} \u00b7 ${profile.host}:${profile.port}",fontSize=14.sp,color=SetupColors.muted)
            Text(if(profile.type==ConnectionType.VNC && profile.vncAuthMode==VncAuthMode.VNC_PASSWORD) stringResource(R.string.setup_using_vnc_password)
                else stringResource(R.string.setup_signed_in_as,profile.user.ifBlank {askAtConnect}),fontSize=13.sp,color=SetupColors.muted)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf(R.string.setup_protocol,R.string.setup_server,R.string.setup_sign_in).forEachIndexed {index,label ->
                    TextButton(onClick={onEdit(index)}) {Text(stringResource(R.string.setup_edit_step,stringResource(label)))}
                }
            }
        }
        when(profile.type) {
            ConnectionType.RDP -> SetupOptionGroup(R.string.setup_display_audio) {
                SetupToggle(R.string.setup_play_sound,profile.soundRedirection!=SoundRedirection.DO_NOT_PLAY,{onChange(profile.copy(soundRedirection=if(it) SoundRedirection.PLAY_ON_DEVICE else SoundRedirection.DO_NOT_PLAY))})
                SetupToggle(R.string.redirect_microphone,profile.redirectMicrophone,{
                    if(!it) onChange(profile.copy(redirectMicrophone=false)) else if(hasMicrophonePermission(context)) onChange(profile.copy(redirectMicrophone=true)) else mic.launch(android.Manifest.permission.RECORD_AUDIO)
                })
                SetupToggle(R.string.redirect_camera,profile.enableCamera,{
                    if(!it) onChange(profile.copy(enableCamera=false)) else if(hasCameraPermission(context)) onChange(profile.copy(enableCamera=true)) else camera.launch(REQUIRED_CAMERA_PERMISSIONS)
                })
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    CameraResolution.entries.forEach { resolution ->
                        FilterChip(selected=profile.cameraResolution==resolution,enabled=profile.enableCamera,onClick={onChange(profile.copy(cameraResolution=resolution))},
                            label={Text(stringResource(when(resolution) {CameraResolution.SD->R.string.setup_camera_sd;CameraResolution.HD_720->R.string.setup_camera_hd;CameraResolution.NATIVE_MAX->R.string.setup_camera_native}))})
                    }
                }
                SetupToggle(R.string.redirect_clipboard,profile.redirectClipboard,{onChange(profile.copy(redirectClipboard=it))})
                SetupToggle(R.string.redirect_storage,profile.redirectStorage,{
                    if(!it) onChange(profile.copy(redirectStorage=false)) else if(hasStoragePermission(context)) onChange(profile.copy(redirectStorage=true)) else requestStoragePermission(context) {intent->storage.launch(intent)}
                })
                SetupToggle(R.string.connect_to_console,profile.connectToConsole,{onChange(profile.copy(connectToConsole=it))},R.string.setup_console_help)
                SetupToggle(R.string.admin_mode,profile.adminMode,{onChange(profile.copy(adminMode=it))})
            }
            ConnectionType.SSH -> SetupOptionGroup(R.string.setup_ssh_options) {
                SetupProfilePicker(R.string.jump_host,profile.jumpHostProfileId,JumpChains.candidates(profile,profiles)) {onChange(profile.copy(jumpHostProfileId=it))}
                val preview=JumpChains.preview(context,profile,profiles)
                preview.first?.let {Text(it.describe(context),fontSize=13.sp,color=SetupColors.accent)}
                SetupToggle(R.string.enable_sftp_file_access,profile.enableSftp,{
                    if(!it) onChange(profile.copy(enableSftp=false)) else if(hasStoragePermission(context)) onChange(profile.copy(enableSftp=true)) else requestStoragePermission(context) {intent->sftp.launch(intent)}
                })
                OutlinedButton(onClick={forwardsOpen=true}) {Text(stringResource(R.string.setup_forwards,profile.sshForwards.size))}
                SetupToggle(R.string.auto_copy_selection_to_clipboard,profile.autoCopySelection,{onChange(profile.copy(autoCopySelection=it))})
            }
            ConnectionType.VNC -> Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                SetupOptionGroup(R.string.general) {
                    SetupToggle(R.string.redirect_clipboard,profile.redirectClipboard,{onChange(profile.copy(redirectClipboard=it))})
                }
                SetupOptionGroup(R.string.files) {
                SetupProfilePicker(R.string.setup_pair_ssh,profile.sftpPairProfileId,profiles.filter {it.type==ConnectionType.SSH && (it.enableSftp || it.id==profile.sftpPairProfileId)}) {
                    onChange(profile.copy(sftpPairProfileId=it,enableMacSshRelay=it!=null && profile.enableMacSshRelay))
                }
                if(profile.sftpPairProfileId!=null) {
                    SetupToggle(R.string.mac_ssh_relay_toggle,profile.enableMacSshRelay,{
                        if(it && profiles.none {p->p.enableMacSshRelay}) relayConfirm=true else onChange(profile.copy(enableMacSshRelay=it))
                    })
                }
                }
            }
        }
        SetupOptionGroup(R.string.setup_network) {
            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                listOf(ProxyType.NONE,ProxyType.CLOUDFLARED,ProxyType.SOCKS5,ProxyType.HTTP).forEach { proxy ->
                    FilterChip(selected=profile.proxyType==proxy,onClick={onChange(profile.copy(proxyType=proxy,useCloudflare=false))},
                        label={Text(when(proxy) {ProxyType.NONE->stringResource(R.string.setup_direct);ProxyType.CLOUDFLARED->stringResource(R.string.setup_cloudflare);else->proxy.name})},
                        colors=FilterChipDefaults.filterChipColors(selectedContainerColor=SetupColors.accent,selectedLabelColor=SetupColors.background))
                }
            }
            if(profile.proxyType==ProxyType.CLOUDFLARED) {
                SetupInput(R.string.extra_parameters_optional,profile.cloudflaredParameters,{onChange(profile.copy(cloudflaredParameters=it))},secret=true)
                Text(stringResource(R.string.routes_the_connection_through_cloudflare_access_browser_sign_in),fontSize=13.sp,color=SetupColors.muted)
            }
            if(profile.proxyType in listOf(ProxyType.HTTP,ProxyType.SOCKS5)) {
                SetupInput(R.string.proxy_hostname_or_ip,profile.proxyHost.orEmpty(),{onChange(profile.copy(proxyHost=it))})
                SetupInput(R.string.proxy_port,profile.proxyPort?.toString().orEmpty(),{onChange(profile.copy(proxyPort=it.toIntOrNull()))})
                SetupInput(R.string.proxy_username_optional,profile.proxyUser.orEmpty(),{onChange(profile.copy(proxyUser=it))})
                SetupInput(R.string.proxy_password_optional,profile.proxyPass.orEmpty(),{onChange(profile.copy(proxyPass=it))},secret=true)
            }
            SetupToggle(R.string.reconnect_automatically,profile.reconnectAutomatically,{onChange(profile.copy(reconnectAutomatically=it))})
            setupOptionsError(context,profile,profiles)?.takeIf {showErrors}?.let {Text(it,color=SetupColors.danger,fontSize=13.sp)}
        }
    }
    if(forwardsOpen) SshForwardingDialog(profile.sshForwards,onChange={onChange(profile.copy(sshForwards=it))},onDismiss={forwardsOpen=false})
    if(relayConfirm) AlertDialog(onDismissRequest={relayConfirm=false},title={Text(stringResource(R.string.mac_ssh_relay_dialog_title))},
        text={Text(stringResource(R.string.mac_ssh_relay_dialog_desc))},confirmButton={TextButton(onClick={onChange(profile.copy(enableMacSshRelay=true));relayConfirm=false}) {Text(stringResource(R.string.enable))}},
        dismissButton={TextButton(onClick={relayConfirm=false}) {Text(stringResource(R.string.cancel))}})
}
