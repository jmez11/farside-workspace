package com.farside.workspace.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farside.workspace.R
import com.farside.workspace.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SignInVault(profileManager:ProfileManager,onClose:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var revision by remember {mutableIntStateOf(0)}
    var credentialSheet by remember {mutableStateOf(false)}
    var keySheet by remember {mutableStateOf(false)}
    var credential by remember {mutableStateOf<SavedCredential?>(null)}
    var key by remember {mutableStateOf<SavedSshKey?>(null)}
    var authenticate by remember {mutableStateOf<(() -> Unit)?>(null)}
    var deletion by remember {mutableStateOf<(() -> Unit)?>(null)}
    var publicKey by remember {mutableStateOf<SshPublicKey?>(null)}
    var keyError by remember {mutableStateOf(false)}
    val credentials=remember(revision) {profileManager.getCredentials()}
    val keys=remember(revision) {profileManager.getSshKeys()}
    val profiles=remember(revision) {profileManager.getProfiles()}
    fun gated(required:Boolean,action:()->Unit) {
        if(required && (profileManager.getAppPinVerifier()!=null || profileManager.usesSystemPin())) authenticate=action else action()
    }
    MaterialTheme(colorScheme=darkColorScheme(primary=SetupColors.accent,onPrimary=SetupColors.background,surface=SetupColors.background,background=SetupColors.background)) {
        Box(Modifier.fillMaxSize().padding(16.dp),contentAlignment=Alignment.TopCenter) {
        Surface(modifier=Modifier.fillMaxSize(),shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp),border=BorderStroke(2.dp,SetupColors.accent)) {
            Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.setup_vault),fontSize=19.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(stringResource(R.string.setup_vault_lede),fontSize=13.sp,color=SetupColors.muted)
                    }
                    IconButton(onClick=onClose) {Icon(Icons.Default.Close,stringResource(R.string.setup_close_vault))}
                }
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    VaultButton(stringResource(R.string.setup_new_sign_in),{credential=null;credentialSheet=true})
                    VaultButton(stringResource(R.string.setup_import_key),{key=null;keySheet=true})
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.setup_sign_ins),fontSize=13.sp,color=SetupColors.muted)
                    credentials.forEach {entry ->
                        val used=profiles.filter {it.credentialId==entry.id}
                        VaultCard {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                VaultIcon(if(entry.user.isBlank()) Icons.Default.Lock else Icons.Default.PersonOutline)
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                        Text(entry.name,fontSize=14.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
                                        used.map { it.type }.distinct().sortedBy { it.ordinal }.forEach { VaultBadge(it.name) }
                                        if(entry.userShort!=null) VaultBadge("Mac")
                                        if(entry.requirePin) VaultBadge("PIN",androidx.compose.ui.graphics.Color(0xFFE3AD65))
                                    }
                                    Text((if(entry.user.isBlank()) stringResource(R.string.setup_vnc_password) else if(entry.userShort!=null)
                                        entry.user+" \u00b7 "+stringResource(R.string.setup_vault_short,entry.userShort!!) else "Username · "+entry.user)+" · "+stringResource(R.string.setup_used_by,used.size),fontSize=12.sp,color=SetupColors.muted)
                                }
                                VaultButton(stringResource(R.string.edit),{gated(entry.requirePin) {credential=entry;credentialSheet=true}},description=stringResource(R.string.setup_edit_named,entry.name))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.setup_ssh_keys),fontSize=13.sp,color=SetupColors.muted)
                    keys.forEach {entry ->
                        val keyType by produceState("SSH",entry.id,entry.privateKey,entry.passphrase) {
                            value=withContext(Dispatchers.Default) {runCatching {deriveSshPublicKey(entry.privateKey,entry.passphrase,entry.name).line.substringBefore(' ').removePrefix("ssh-").uppercase()}.getOrDefault("SSH")}
                        }
                        VaultCard {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                VaultIcon(Icons.Default.Key)
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                    Row(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically) {
                                        Text(entry.name,fontSize=14.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
                                        if(entry.requirePin) VaultBadge("PIN",androidx.compose.ui.graphics.Color(0xFFE3AD65))
                                    }
                                    Text(keyType+" · "+stringResource(R.string.setup_used_by,profiles.count {it.sshKeyId==entry.id}),fontSize=12.sp,color=SetupColors.muted)
                                }
                                VaultButton(stringResource(R.string.setup_public_key),{gated(entry.requirePin) {
                                    scope.launch {try {publicKey=withContext(Dispatchers.Default) {deriveSshPublicKey(entry.privateKey,entry.passphrase,entry.name)}} catch(_:Exception) {keyError=true}}
                                }},description=stringResource(R.string.ssh_public_key_title))
                                VaultButton(stringResource(R.string.edit),{gated(entry.requirePin) {key=entry;keySheet=true}},description=stringResource(R.string.setup_edit_named,entry.name))
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    VaultProtectionAndBackup(profileManager,externalRevision=revision) {revision++}
                }
            }
        }
        }
        if(credentialSheet) CredentialSheet(profileManager,allowPasswordOnly=credential==null,existing=credential,vncPassword=credential?.user?.isBlank()==true,onDelete=credential?.let {entry -> {credentialSheet=false;deletion={gated(entry.requirePin) {profileManager.deleteCredential(entry.id);revision++}}}},onDismiss={credentialSheet=false},onSaved={revision++;credentialSheet=false})
        if(keySheet) SshKeySheet(profileManager,existing=key,onDelete=key?.let {entry -> {keySheet=false;deletion={gated(entry.requirePin) {profileManager.deleteSshKey(entry.id);revision++}}}},onDismiss={keySheet=false},onSaved={revision++;keySheet=false})
        authenticate?.let {action ->VaultAuthentication(profileManager,onUnlocked={authenticate=null;action()},onCancel={authenticate=null})}
        deletion?.let {action ->AlertDialog(onDismissRequest={deletion=null},title={Text(stringResource(R.string.delete))},
            text={Text(stringResource(R.string.setup_delete_vault_confirm))},confirmButton={TextButton(onClick={deletion=null;action()}) {Text(stringResource(R.string.delete))}},
            dismissButton={TextButton(onClick={deletion=null}) {Text(stringResource(R.string.cancel))}})}
        publicKey?.let {shown ->AlertDialog(onDismissRequest={publicKey=null},title={Text(stringResource(R.string.ssh_public_key_title))},text={Column {
            Text(shown.fingerprint);SelectionContainer {Text(shown.line)}
        }},confirmButton={TextButton(onClick={publicKey=null}) {Text(stringResource(R.string.close))}},dismissButton={TextButton(onClick={
            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("SSH public key",shown.line))
        }) {Text(stringResource(R.string.ssh_public_key_copy))}})}
        if(keyError) AlertDialog(onDismissRequest={keyError=false},text={Text(stringResource(R.string.ssh_public_key_invalid))},confirmButton={TextButton(onClick={keyError=false}) {Text(stringResource(R.string.close))}})
    }
}

@Composable
internal fun VaultCard(content:@Composable ColumnScope.()->Unit) {
    Surface(Modifier.fillMaxWidth(),color=SetupColors.card,border=BorderStroke(1.dp,SetupColors.border),shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}
@Composable
internal fun VaultBadge(label:String,color:androidx.compose.ui.graphics.Color=SetupColors.accent) {
    Surface(color=SetupColors.field,shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp),border=BorderStroke(1.dp,color.copy(alpha=.5f))) {
        Text(label,Modifier.padding(horizontal=7.dp,vertical=3.dp),fontSize=10.sp,color=color,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
    }
}
@Composable
internal fun VaultIcon(icon:androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(color=SetupColors.field,border=BorderStroke(1.dp,SetupColors.border),shape=androidx.compose.foundation.shape.RoundedCornerShape(9.dp)) {
        Box(Modifier.size(36.dp),contentAlignment=Alignment.Center) {Icon(icon,null,Modifier.size(18.dp),tint=SetupColors.accent)}
    }
}
@Composable
internal fun VaultButton(label:String,onClick:()->Unit,description:String=label,danger:Boolean=false,enabled:Boolean=true) {
    OutlinedButton(onClick=onClick,enabled=enabled,modifier=Modifier.heightIn(min=36.dp).semantics {contentDescription=description},
        shape=androidx.compose.foundation.shape.RoundedCornerShape(9.dp),border=BorderStroke(1.dp,if(danger) SetupColors.danger.copy(alpha=.5f) else SetupColors.border),
        colors=ButtonDefaults.outlinedButtonColors(contentColor=if(danger) SetupColors.danger else androidx.compose.ui.graphics.Color.White),contentPadding=PaddingValues(horizontal=14.dp,vertical=8.dp)) {Text(label,fontSize=12.sp)}
}
