package com.farside.workspace.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.farside.workspace.R
import com.farside.workspace.data.*
import com.farside.workspace.utils.DiscoveredHost
import com.farside.workspace.utils.NetworkScanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/** Guided setup keeps secrets in memory until the user saves the connection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionSetupFlow(
    initialProfile: ConnectionProfile? = null,
    onSave: (ConnectionProfile) -> Unit,
    onCancel: () -> Unit,
    profileManager: ProfileManager,
    savedProfiles: List<ConnectionProfile> = profileManager.getProfiles(),
    onNavigateToSettings: (Int) -> Unit = {},
    credentialRevision: Int = 0,
    discovery: (suspend (ConnectionType) -> List<DiscoveredHost>)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var type by rememberSaveable { mutableStateOf(initialProfile?.type ?: ConnectionType.RDP) }
    var auth by rememberSaveable { mutableStateOf(initialProfile?.vncAuthMode ?: VncAuthMode.MAC_USER) }
    var name by rememberSaveable { mutableStateOf(initialProfile?.name ?: "") }
    var host by rememberSaveable { mutableStateOf(initialProfile?.host ?: "") }
    var port by rememberSaveable { mutableStateOf((initialProfile?.port ?: 3389).toString()) }
    val id = rememberSaveable { initialProfile?.id ?: UUID.randomUUID().toString() }
    val signIn=remember(id) {SetupSignInState(initialProfile)}
    val credentials=remember(signIn.revision,credentialRevision) {profileManager.getCredentials()}
    var options by remember(id) {mutableStateOf((initialProfile ?: ConnectionProfile(id=id,name=name,type=type,host=host,user="",pass="",macAudioEnabled=true)).let {
        if(it.useCloudflare) it.copy(proxyType=ProxyType.CLOUDFLARED,useCloudflare=false) else it
    })}
    val base=options.copy(
        name=name,type=type,host=host,port=port.toIntOrNull() ?: 0,vncAuthMode=auth,
        expMacHevc=type==ConnectionType.VNC && auth==VncAuthMode.MAC_USER)
    val signInValid=signIn.error(base,credentials)==null
    val draft=signIn.profile(base,credentials)
    val optionsValid=setupOptionsError(context,draft,savedProfiles)==null
    var found by remember { mutableStateOf(emptyList<DiscoveredHost>()) }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { scan?.cancel() } }
    val validServer = name.isNotBlank() && host.isNotBlank() && port.toIntOrNull() in 1..65535
        val accent = Color(0xFFD0BCFF)
    MaterialTheme(colorScheme = darkColorScheme(primary=accent, background=Color(0xFF1C1B1F), surface=Color(0xFF1C1B1F), onPrimary=Color(0xFF1C1B1F))) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment=Alignment.TopCenter) {
        Surface(modifier=Modifier.widthIn(max=880.dp).fillMaxSize(),shape=RoundedCornerShape(16.dp),border=BorderStroke(2.dp,accent)) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=onCancel) { Icon(if(initialProfile==null) Icons.Default.Close else Icons.Default.ArrowBack, stringResource(R.string.back)) }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(if(initialProfile==null) R.string.setup_new_connection else R.string.edit_connection),fontSize=17.sp,fontWeight=FontWeight.Bold)
                        Text(type.name+" \u00b7 "+stringResource(when(type) {ConnectionType.RDP->R.string.setup_rdp_description;ConnectionType.SSH->R.string.setup_ssh_description;ConnectionType.VNC->R.string.setup_vnc_description}),fontSize=13.sp,color=SetupColors.muted)
                    }
                    Text(stringResource(R.string.setup_step_of, step+1),fontSize=13.sp,color=SetupColors.muted)
                }
                SetupStepper(step,canVisit={index -> index<2 || validServer && (index==2 || signInValid)},onStep={step=it})
                when(step) {
                    0 -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.setup_connecting_to), fontSize=20.sp,fontWeight=FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        SetupProtocolCards(type) { choice ->
                            if(type!=choice) {
                                scan?.cancel();scanning=false;found=emptyList();scanError=false
                                type=choice;port=when(choice) {ConnectionType.RDP->"3389";ConnectionType.SSH->"22";ConnectionType.VNC->"5900"}
                            }
                        }
                        if(type==ConnectionType.VNC) Surface(shape=RoundedCornerShape(14.dp),color=SetupColors.card,border=BorderStroke(1.dp,SetupColors.border)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                Text(stringResource(R.string.setup_vnc_auth_question),fontSize=15.sp,fontWeight=FontWeight.Bold)
                                Text(stringResource(R.string.setup_vnc_auth_description),fontSize=13.sp,color=SetupColors.muted)
                                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                    VncAuthMode.entries.forEach { mode ->
                                        FilterChip(selected=auth==mode,onClick={auth=mode},label={Text(stringResource(if(mode==VncAuthMode.VNC_PASSWORD) R.string.setup_vnc_password else R.string.setup_user_password))},
                                            colors=FilterChipDefaults.filterChipColors(selectedContainerColor=accent,selectedLabelColor=SetupColors.background))
                                    }
                                }
                                Text(stringResource(R.string.setup_mac_example),fontSize=13.sp,color=SetupColors.faint)
                            }
                        }
                    }
                    1 -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.setup_which_computer),fontSize=20.sp,fontWeight=FontWeight.Bold)
                        Text(stringResource(R.string.setup_server_lede),fontSize=14.sp,color=SetupColors.muted)
                        SetupServerField(name,{name=it},R.string.connection_name,if(name.isBlank()) R.string.profile_name_required else null,Modifier.fillMaxWidth())
                        BoxWithConstraints {
                            if(maxWidth<600.dp) Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                SetupServerField(host,{host=it},R.string.host_server,if(host.isBlank()) R.string.profile_host_required else null,Modifier.fillMaxWidth())
                                SetupServerField(port,{port=it},R.string.setup_port,if(port.toIntOrNull() !in 1..65535) R.string.profile_port_invalid else null,Modifier.fillMaxWidth())
                            } else Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                SetupServerField(host,{host=it},R.string.host_server,if(host.isBlank()) R.string.profile_host_required else null,Modifier.weight(1f))
                                SetupServerField(port,{port=it},R.string.setup_port,if(port.toIntOrNull() !in 1..65535) R.string.profile_port_invalid else null,Modifier.width(130.dp))
                            }
                        }
                        if(type!=ConnectionType.SSH) {
                            OutlinedButton(shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,SetupColors.border),enabled=!scanning,onClick={
                                val requested=type
                                scanError=false;found=emptyList();scanning=true
                                scan=scope.launch {
                                    try {
                                        val results=discovery?.invoke(requested) ?: when(requested) {
                                            ConnectionType.RDP->NetworkScanner.scanLocalNetworkForRdp(context)
                                            ConnectionType.VNC->NetworkScanner.scanLocalNetworkForVnc(context)
                                            ConnectionType.SSH->emptyList()
                                        }
                                        if(type==requested) found=results
                                    } catch(cancelled:kotlinx.coroutines.CancellationException) { throw cancelled }
                                    catch(_:Exception) { if(type==requested) scanError=true }
                                    finally { if(type==requested) scanning=false }
                                }
                            }) { Text(stringResource(if(scanning) R.string.setup_finding else if(type==ConnectionType.RDP) R.string.setup_find_pcs else R.string.setup_find_devices)) }
                            if(scanError) Text(stringResource(R.string.setup_discovery_failed),color=MaterialTheme.colorScheme.error)
                            found.forEach { device ->
                                OutlinedCard(onClick={name=device.name;host=device.ip;port=device.port.toString()},modifier=Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp)) { Text(device.name);Text("${device.ip}:${device.port}") }
                                }
                            }
                        }
                    }
                    2 -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        SetupStepSignIn(base,signIn,profileManager)
                    }
                    3 -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        SetupReviewOptions(draft,savedProfiles,onChange={options=it},onEdit={step=it})
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    OutlinedButton(shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,SetupColors.border),enabled=step>0,onClick={step--}) { Text(stringResource(R.string.back)) }
                    Button(shape=RoundedCornerShape(12.dp),enabled=step==0 || validServer && (step<2 || signInValid) && (step!=3 || optionsValid),onClick={
                        if(step==3) onSave(draft)
                        else step++
                    }) { Text(stringResource(if(step!=3) R.string.continue_action else if(initialProfile==null) R.string.setup_save_connection else R.string.save_changes)) }
                }
            }
        }
    }
}
}
