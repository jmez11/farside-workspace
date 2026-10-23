package com.farside.workspace.ui.setup

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farside.workspace.R
import com.farside.workspace.data.*
import com.farside.workspace.ui.profileCredentialErrorRes

internal fun credentialMatchesConnection(credential:SavedCredential,base:ConnectionProfile):Boolean = when {
    credential.user.isBlank() -> base.type==ConnectionType.VNC && base.vncAuthMode==VncAuthMode.VNC_PASSWORD
    credential.userShort!=null -> base.type==ConnectionType.VNC && base.vncAuthMode==VncAuthMode.MAC_USER
    else -> base.type!=ConnectionType.VNC
}

/** A saved credential is indivisible. Specific fields never borrow one of its components. */
internal class SetupSignInState(initial:ConnectionProfile?) {
    private val originalCredentialId=initial?.credentialId
    private val originalKeyId=initial?.sshKeyId
    var specific by mutableStateOf(initial!=null && initial.credentialId==null)
    var credentialId by mutableStateOf(initial?.credentialId)
    var user by mutableStateOf(if(initial?.credentialId==null) initial?.user.orEmpty() else "")
    var password by mutableStateOf("")
    private val originalPassword=if(initial?.credentialId==null) initial?.pass.orEmpty() else ""
    val keepingPassword get()=originalPassword.isNotEmpty() && password.isEmpty()
    var keyMode by mutableStateOf(initial?.sshKeyId!=null)
    var keyId by mutableStateOf(initial?.sshKeyId)
    var revision by mutableIntStateOf(0)
    fun profile(base:ConnectionProfile,credentials:List<SavedCredential>):ConnectionProfile {
        val stored=if(specific) null else credentials.find {it.id==credentialId && credentialMatchesConnection(it,base)}
        return base.copy(credentialId=stored?.id,
            user=if(specific) (if(base.type==ConnectionType.VNC && base.vncAuthMode==VncAuthMode.VNC_PASSWORD) "" else user) else
                (if(base.type==ConnectionType.SSH) stored?.userShort ?: stored?.user else stored?.user).orEmpty(),
            pass=if(specific) (if(base.type==ConnectionType.SSH && keyMode) "" else password.ifEmpty {originalPassword}) else stored?.pass.orEmpty(),
            sshKeyId=if(specific && base.type==ConnectionType.SSH && keyMode) keyId
                else if(!specific && base.type==ConnectionType.SSH && credentialId==originalCredentialId) originalKeyId else null)
    }
    fun error(base:ConnectionProfile,credentials:List<SavedCredential>):Int? {
        if(base.type==ConnectionType.SSH) return null
        val resolved=profile(base,credentials)
        val credential=if(specific) SavedCredential(name="",user=resolved.user,pass=resolved.pass) else credentials.find {it.id==credentialId && credentialMatchesConnection(it,base)}
        return profileCredentialErrorRes(base.type,base.vncAuthMode,credential,"")

    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SetupStepSignIn(base:ConnectionProfile,state:SetupSignInState,pm:ProfileManager,showErrors:Boolean=false) {
    val credentials=remember(state.revision) {pm.getCredentials()}
    val keys=remember(state.revision) {pm.getSshKeys()}
    var sheet by remember {mutableStateOf<String?>(null)}
    val passwordOnly=base.type==ConnectionType.VNC && base.vncAuthMode==VncAuthMode.VNC_PASSWORD
    val error=state.error(base,credentials)
    Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.setup_sign_in),fontSize=20.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf(false,true).forEach { specific ->
                FilterChip(selected=state.specific==specific,onClick={state.specific=specific},
                    label={Text(stringResource(if(specific) R.string.setup_connection_specific else R.string.setup_saved_sign_ins))},
                    colors=FilterChipDefaults.filterChipColors(selectedContainerColor=SetupColors.accent,selectedLabelColor=SetupColors.background))
            }
        }
        if(!state.specific) {
            credentials.filter {credentialMatchesConnection(it,base)}.forEach { credential ->
                Surface(modifier=Modifier.fillMaxWidth().selectable(selected=state.credentialId==credential.id,role=Role.RadioButton,onClick={state.credentialId=credential.id}),shape=RoundedCornerShape(12.dp),
                    color=if(state.credentialId==credential.id) SetupColors.accent.copy(alpha=.13f) else SetupColors.card,
                    border=BorderStroke(1.dp,if(state.credentialId==credential.id) SetupColors.accent else SetupColors.border)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    VaultCredentialIcon(credential)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(credential.name,style=MaterialTheme.typography.titleMedium)
                        if(!passwordOnly) Text(if(base.type==ConnectionType.SSH) credential.userShort ?: credential.user else credential.user,color=SetupColors.muted)
                        credential.userShort?.let {
                            Text(stringResource(R.string.setup_mac_names_summary,credential.user,it),fontSize=13.sp,color=SetupColors.muted)
                        }
                        if(credential.requirePin) Text(stringResource(R.string.pin_required),fontSize=12.sp,color=SetupColors.accent)
                    }
                    RadioButton(selected=state.credentialId==credential.id,onClick=null)
                    }
                }
            }
            OutlinedButton(onClick={sheet="credential"}) {Text(stringResource(if(passwordOnly) R.string.setup_new_vnc_password else R.string.setup_new_sign_in))}
            state.profile(base,credentials).sshKeyId?.let {id ->
                Text(stringResource(R.string.setup_saved_key_in_use,keys.find {it.id==id}?.name.orEmpty()),fontSize=13.sp,color=SetupColors.muted)
            }
        } else {
            if(!passwordOnly) SetupInput(R.string.setup_username,state.user,{state.user=it})
            if(base.type==ConnectionType.SSH) Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf(false,true).forEach { key -> FilterChip(selected=state.keyMode==key,onClick={state.keyMode=key},
                    label={Text(stringResource(if(key) R.string.setup_ssh_key else R.string.password))}) }
            }
            if(base.type==ConnectionType.SSH && state.keyMode) {
                keys.forEach { key ->
                    val keyType by produceState("",key.id,key.privateKey,key.passphrase) {
                        value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            runCatching {deriveSshPublicKey(key.privateKey,key.passphrase,key.name).line.substringBefore(' ')}.getOrDefault("SSH")
                        }
                    }
                    OutlinedCard(onClick={state.keyId=key.id},modifier=Modifier.fillMaxWidth().selectable(selected=state.keyId==key.id,role=Role.RadioButton,onClick={state.keyId=key.id}),border=BorderStroke(1.dp,if(state.keyId==key.id) SetupColors.accent else SetupColors.border)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        VaultIcon(Icons.Default.Key)
                        Column(Modifier.weight(1f)) {
                            Text(key.name)
                            Text(keyType,fontSize=12.sp,color=SetupColors.muted)
                            if(key.requirePin) Text(stringResource(R.string.pin_required),fontSize=12.sp,color=SetupColors.accent)
                        }
                        RadioButton(selected=state.keyId==key.id,onClick=null)
                        }
                    }
                }
                OutlinedButton(onClick={sheet="key"}) {Text(stringResource(R.string.setup_import_key))}
            } else {
                SetupInput(if(passwordOnly) R.string.setup_vnc_password else R.string.password,state.password,{state.password=it},secret=true)
                if(passwordOnly) Text("${state.password.length}/8",color=if(showErrors && state.password.length>8) SetupColors.danger else SetupColors.muted)
                if(state.keepingPassword) Text(stringResource(R.string.setup_password_keep),fontSize=13.sp,color=SetupColors.muted)
            }
        }
        if(base.type==ConnectionType.SSH) Text(stringResource(R.string.setup_ssh_optional),fontSize=13.sp,color=SetupColors.muted)
        if(base.type==ConnectionType.VNC && !passwordOnly) Text(stringResource(R.string.setup_mac_sign_in_hint),fontSize=13.sp,color=SetupColors.muted)
        error?.takeIf {showErrors}?.let {Text(stringResource(it),color=SetupColors.danger)}
    }
    if(sheet=="credential") CredentialSheet(pm,vncPassword=passwordOnly,allowMac=false,macAccount=base.type==ConnectionType.VNC && !passwordOnly,allowEmptyPassword=base.type!=ConnectionType.VNC,
        onDismiss={sheet=null},onSaved={state.credentialId=it.id;state.specific=false;state.revision++;sheet=null})
    if(sheet=="key") SshKeySheet(pm,onDismiss={sheet=null},onSaved={state.keyId=it.id;state.revision++;sheet=null})
}
