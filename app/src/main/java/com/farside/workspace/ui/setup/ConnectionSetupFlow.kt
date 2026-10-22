package com.farside.workspace.ui.setup

import androidx.compose.foundation.BorderStroke
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
import com.farside.workspace.ui.AddProfileForm
import com.farside.workspace.utils.DiscoveredHost
import com.farside.workspace.utils.NetworkScanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/** Phase 1 keeps the existing sign-in/options editor until its replacement passes device checks. */
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
    var candidate by remember { mutableStateOf<ConnectionProfile?>(null) }
    var found by remember { mutableStateOf(emptyList<DiscoveredHost>()) }
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { scan?.cancel() } }
    val validServer = name.isNotBlank() && host.isNotBlank() && port.toIntOrNull() in 1..65535
    val labels = listOf(R.string.setup_protocol, R.string.setup_server, R.string.setup_sign_in, R.string.setup_review)
    val accent = Color(0xFFD0BCFF)
    MaterialTheme(colorScheme = darkColorScheme(primary=accent, background=Color(0xFF1C1B1F), surface=Color(0xFF1C1B1F), onPrimary=Color(0xFF1C1B1F))) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=onCancel) { Icon(if(initialProfile==null) Icons.Default.Close else Icons.Default.ArrowBack, stringResource(R.string.back)) }
                    Text(stringResource(if(initialProfile==null) R.string.setup_new_connection else R.string.edit_connection), style=MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(R.string.setup_step_of, step+1))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    labels.forEachIndexed { index, label ->
                        FilterChip(selected=step==index, onClick={ step=index }, enabled=index==0 || index==1 || validServer && (index==2 || candidate!=null),
                            label={ Text("${index+1} " + stringResource(label)) })
                    }
                }
                when(step) {
                    0 -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.setup_connecting_to), style=MaterialTheme.typography.titleLarge)
                        ConnectionType.entries.forEach { choice ->
                            OutlinedCard(onClick={
                                if(type!=choice) {
                                    scan?.cancel(); scanning=false; found=emptyList(); scanError=false
                                    type=choice; port=when(choice) { ConnectionType.RDP->"3389"; ConnectionType.SSH->"22"; ConnectionType.VNC->"5900" }
                                    candidate=null
                                }
                            }, modifier=Modifier.fillMaxWidth(), border=BorderStroke(if(type==choice) 2.dp else 1.dp, if(type==choice) accent else Color.Gray)) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(choice.name, style=MaterialTheme.typography.titleMedium, color=if(type==choice) accent else Color.White)
                                    Text(stringResource(when(choice) { ConnectionType.RDP->R.string.setup_rdp_description; ConnectionType.SSH->R.string.setup_ssh_description; ConnectionType.VNC->R.string.setup_vnc_description }))
                                }
                            }
                        }
                        if(type==ConnectionType.VNC) Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            VncAuthMode.entries.forEach { mode ->
                                FilterChip(selected=auth==mode, onClick={ auth=mode;candidate=null }, label={ Text(stringResource(if(mode==VncAuthMode.VNC_PASSWORD) R.string.setup_vnc_password else R.string.setup_user_password)) })
                            }
                        }
                    }
                    1 -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        SetupServerField(name,{name=it},R.string.connection_name,if(name.isBlank()) R.string.profile_name_required else null)
                        SetupServerField(host,{host=it},R.string.host_server,if(host.isBlank()) R.string.profile_host_required else null)
                        SetupServerField(port,{port=it},R.string.setup_port,if(port.toIntOrNull() !in 1..65535) R.string.profile_port_invalid else null)
                        if(type!=ConnectionType.SSH) {
                            OutlinedButton(enabled=!scanning,onClick={
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
                    2 -> Box(Modifier.weight(1f)) {
                        val draft=(candidate ?: initialProfile ?: ConnectionProfile(id=id,name=name,type=type,host=host,user="",pass="")).copy(
                            name=name,type=type,host=host,port=port.toIntOrNull() ?: 0,vncAuthMode=auth)
                        key(type,auth) { AddProfileForm(initialProfile=draft, profileManager=profileManager,savedProfiles=savedProfiles,
                            credentialRevision=credentialRevision,onNavigateToSettings=onNavigateToSettings,onCancel=onCancel,
                            onSave={ chosen ->
                                candidate=chosen;name=chosen.name;host=chosen.host;port=chosen.port.toString();type=chosen.type;auth=chosen.vncAuthMode;step=3
                            }) }
                    }
                    3 -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.setup_review),style=MaterialTheme.typography.titleLarge)
                        Text(name,style=MaterialTheme.typography.titleMedium)
                        Text("${type.name}  $host:$port")
                        TextButton(onClick={step=2}) { Text(stringResource(R.string.setup_edit_sign_in_options)) }
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    OutlinedButton(enabled=step>0,onClick={step--}) { Text(stringResource(R.string.back)) }
                    if(step!=2) Button(enabled=step==0 || validServer && (step!=3 || candidate!=null),onClick={
                        if(step==3) candidate?.let { onSave(it.copy(name=name,host=host,port=port.toInt(),type=type,vncAuthMode=auth)) }
                        else step++
                    }) { Text(stringResource(if(step!=3) R.string.continue_action else if(initialProfile==null) R.string.setup_save_connection else R.string.save_changes)) }
                }
            }
        }
    }
}

@Composable
private fun SetupServerField(value:String,onChange:(String)->Unit,label:Int,error:Int?) {
    OutlinedTextField(value=value,onValueChange=onChange,label={Text(stringResource(label))},singleLine=true,
        isError=error!=null,supportingText={if(error!=null) Text(stringResource(error))},modifier=Modifier.fillMaxWidth())
}
