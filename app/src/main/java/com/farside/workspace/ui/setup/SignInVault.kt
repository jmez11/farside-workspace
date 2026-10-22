package com.farside.workspace.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
fun SignInVault(profileManager:ProfileManager,onClose:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var revision by remember {mutableIntStateOf(0)}
    var search by remember {mutableStateOf("")}
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
        Surface(modifier=Modifier.widthIn(max=880.dp).fillMaxSize(),shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp),border=BorderStroke(2.dp,SetupColors.accent)) {
            Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=onClose) {Icon(Icons.Default.Close,stringResource(R.string.setup_close_vault))}
                    Text(stringResource(R.string.setup_vault),style=MaterialTheme.typography.headlineSmall)
                }
                SetupInput(R.string.setup_vault_search,search,{search=it})
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(R.string.setup_saved_sign_ins),style=MaterialTheme.typography.titleMedium)
                    credentials.filter {search.isBlank() || listOf(it.name,it.user,it.userShort.orEmpty()).any {s->s.contains(search,true)}}.forEach {entry ->
                        val used=profiles.filter {it.credentialId==entry.id}
                        Surface(color=SetupColors.card,border=BorderStroke(1.dp,SetupColors.border),shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text(entry.name,style=MaterialTheme.typography.titleMedium)
                                Text(entry.user,color=SetupColors.muted)
                                entry.userShort?.let {Text(stringResource(R.string.setup_vault_short,it),fontSize=13.sp,color=SetupColors.muted)}
                                Text(if(entry.user.isBlank()) "VNC" else used.map {it.type.name}.distinct().joinToString(" / ").ifBlank {"RDP / SSH / VNC"},fontSize=12.sp,color=SetupColors.accent)
                                if(entry.requirePin) Text(stringResource(R.string.pin_required),fontSize=12.sp,color=SetupColors.accent)
                                Text(stringResource(R.string.setup_used_by,used.size),fontSize=12.sp,color=SetupColors.muted)
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    val editLabel=stringResource(R.string.setup_edit_named,entry.name)
                                    OutlinedButton(modifier=Modifier.semantics {contentDescription=editLabel},onClick={gated(entry.requirePin) {credential=entry;credentialSheet=true}}) {Text(stringResource(R.string.edit))}
                                    TextButton(onClick={deletion={gated(entry.requirePin) {profileManager.deleteCredential(entry.id);revision++}}}) {Text(stringResource(R.string.delete))}
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick={credential=null;credentialSheet=true}) {Text(stringResource(R.string.setup_new_sign_in))}
                    Text(stringResource(R.string.setup_ssh_keys),style=MaterialTheme.typography.titleMedium)
                    keys.filter {search.isBlank() || it.name.contains(search,true)}.forEach {entry ->
                        val keyType by produceState("",entry.id,entry.privateKey,entry.passphrase) {
                            value=withContext(Dispatchers.Default) {runCatching {deriveSshPublicKey(entry.privateKey,entry.passphrase,entry.name).line.substringBefore(' ')}.getOrDefault("SSH")}
                        }
                        Surface(color=SetupColors.card,border=BorderStroke(1.dp,SetupColors.border),shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text(entry.name,style=MaterialTheme.typography.titleMedium)
                                Text(keyType,fontSize=12.sp,color=SetupColors.muted)
                                if(entry.requirePin) Text(stringResource(R.string.pin_required),fontSize=12.sp,color=SetupColors.accent)
                                Text(stringResource(R.string.setup_used_by,profiles.count {it.sshKeyId==entry.id}),fontSize=12.sp,color=SetupColors.muted)
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    val editLabel=stringResource(R.string.setup_edit_named,entry.name)
                                    OutlinedButton(modifier=Modifier.semantics {contentDescription=editLabel},onClick={gated(entry.requirePin) {key=entry;keySheet=true}}) {Text(stringResource(R.string.edit))}
                                    OutlinedButton(onClick={gated(entry.requirePin) {
                                        scope.launch {try {publicKey=withContext(Dispatchers.Default) {deriveSshPublicKey(entry.privateKey,entry.passphrase,entry.name)}} catch(_:Exception) {keyError=true}}
                                    }}) {Text(stringResource(R.string.ssh_public_key_title))}
                                    TextButton(onClick={deletion={gated(entry.requirePin) {profileManager.deleteSshKey(entry.id);revision++}}}) {Text(stringResource(R.string.delete))}
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick={key=null;keySheet=true}) {Text(stringResource(R.string.setup_import_key))}
                    Divider()
                    VaultProtectionAndBackup(profileManager) {revision++}
                }
            }
        }
        }
        if(credentialSheet) CredentialSheet(profileManager,allowPasswordOnly=credential==null,existing=credential,vncPassword=credential?.user?.isBlank()==true,onDismiss={credentialSheet=false},onSaved={revision++;credentialSheet=false})
        if(keySheet) SshKeySheet(profileManager,existing=key,onDismiss={keySheet=false},onSaved={revision++;keySheet=false})
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
