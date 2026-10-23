package com.farside.workspace.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import kotlin.math.roundToInt
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
    val focus = LocalFocusManager.current
    val serverScroll = rememberScrollState()
    var resultsTop by remember { mutableIntStateOf(0) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var attempted by remember { mutableStateOf(emptySet<Int>()) }
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
    val displayName = name.ifBlank { if (initialProfile == null) host.trim() else "" }
    val base=options.copy(
        name=displayName,type=type,host=host,port=port.toIntOrNull() ?: 0,vncAuthMode=auth,
        expMacHevc=type==ConnectionType.VNC && auth==VncAuthMode.MAC_USER,
        enableSftp=if(initialProfile==null && type==ConnectionType.SSH && options.type!=ConnectionType.SSH) true else options.enableSftp)
    val signInValid=signIn.error(base,credentials)==null
    val draft=signIn.profile(base,credentials)
    val optionsValid=setupOptionsError(context,draft,savedProfiles)==null
    var found by remember { mutableStateOf(emptyList<DiscoveredHost>()) }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(found, step, resultsTop) {
        if (step == 1 && found.isNotEmpty()) {
            withFrameNanos { }
            serverScroll.animateScrollTo(resultsTop)
        }
    }
    DisposableEffect(Unit) { onDispose { scan?.cancel() } }
    val validServer = displayName.isNotBlank() && host.isNotBlank() && port.toIntOrNull() in 1..65535
        val accent = Color(0xFFD0BCFF)
    MaterialTheme(colorScheme = darkColorScheme(primary=accent, background=Color(0xFF1C1B1F), surface=Color(0xFF1C1B1F), onPrimary=Color(0xFF1C1B1F))) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment=Alignment.TopCenter) {
        Surface(modifier=Modifier.fillMaxSize(),shape=RoundedCornerShape(16.dp),border=BorderStroke(2.dp,accent)) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(if(initialProfile==null) R.string.setup_new_connection else R.string.edit_connection),fontSize=17.sp,fontWeight=FontWeight.Bold)
                        Text(type.name+" \u00b7 "+stringResource(when(type) {ConnectionType.RDP->R.string.setup_rdp_description;ConnectionType.SSH->R.string.setup_ssh_description;ConnectionType.VNC->R.string.setup_vnc_description}),fontSize=13.sp,color=SetupColors.muted)
                    }
                    IconButton(onClick=onCancel) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                }
                SetupStepper(step,canVisit={true},onStep={target ->
                    attempted=attempted+step
                    step=when {
                        target<=step -> target
                        target>=2 && !validServer -> 1
                        target>=3 && !signInValid -> 2
                        else -> target
                    }
                })
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
                    1 -> Column(Modifier.weight(1f).verticalScroll(serverScroll), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.setup_which_computer),fontSize=20.sp,fontWeight=FontWeight.Bold)
                        Text(stringResource(R.string.setup_server_lede),fontSize=14.sp,color=SetupColors.muted)
                        SetupServerField(displayName,{name=it},R.string.connection_name,if(1 in attempted && displayName.isBlank()) R.string.profile_name_required else null,Modifier.fillMaxWidth())
                        BoxWithConstraints {
                            if(maxWidth<600.dp) Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                SetupServerField(host,{host=it},R.string.host_server,if(1 in attempted && host.isBlank()) R.string.profile_host_required else null,Modifier.fillMaxWidth())
                                SetupServerField(port,{port=it},R.string.setup_port,if(1 in attempted && port.toIntOrNull() !in 1..65535) R.string.profile_port_invalid else null,Modifier.fillMaxWidth())
                            } else Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                SetupServerField(host,{host=it},R.string.host_server,if(1 in attempted && host.isBlank()) R.string.profile_host_required else null,Modifier.weight(1f))
                                SetupServerField(port,{port=it},R.string.setup_port,if(1 in attempted && port.toIntOrNull() !in 1..65535) R.string.profile_port_invalid else null,Modifier.width(130.dp))
                            }
                        }
                        if(type!=ConnectionType.SSH) {
                            OutlinedButton(shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,SetupColors.border),enabled=!scanning,onClick={
                                focus.clearFocus(force=true)
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
                            Column(Modifier.fillMaxWidth().onGloballyPositioned { resultsTop=it.positionInParent().y.roundToInt() },verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                found.forEach { device ->
                                    val selected=host==device.ip && port.toIntOrNull()==device.port
                                    OutlinedCard(modifier=Modifier.fillMaxWidth().selectable(selected=selected,role=Role.RadioButton,onClick={
                                        focus.clearFocus(force=true);name=device.name;host=device.ip;port=device.port.toString()
                                    })) {
                                        Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                            VaultIcon(setupProtocolIcon(type))
                                            Column(Modifier.weight(1f)) { Text(device.name);Text("${device.ip}:${device.port}") }
                                            RadioButton(selected=selected,onClick=null)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    2 -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        SetupStepSignIn(base,signIn,profileManager,showErrors=2 in attempted)
                    }
                    3 -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        SetupReviewOptions(draft,savedProfiles,onChange={options=it},onEdit={attempted=attempted+step;step=it},showErrors=3 in attempted)
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    OutlinedButton(shape=RoundedCornerShape(12.dp),border=BorderStroke(1.dp,SetupColors.border),onClick={if(step==0) onCancel() else step--}) { Text(stringResource(R.string.back)) }
                    Button(shape=RoundedCornerShape(12.dp),onClick={
                        attempted=attempted+step
                        if((step!=1 || validServer) && (step!=2 || signInValid)) {
                            if(step==3 && validServer && signInValid && optionsValid) onSave(draft)
                            else if(step<3) step++
                        }
                    }) { Text(stringResource(if(step!=3) R.string.continue_action else if(initialProfile==null) R.string.setup_save_connection else R.string.save_changes)) }
                }
            }
        }
    }
}
}
