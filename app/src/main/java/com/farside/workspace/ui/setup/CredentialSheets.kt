package com.farside.workspace.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farside.workspace.R
import com.farside.workspace.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SetupInput(label:Int,value:String,onChange:(String)->Unit,secret:Boolean=false,multiline:Boolean=false,error:String?=null) {
    val text=stringResource(label)
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(text,fontSize=13.sp,color=SetupColors.muted)
        OutlinedTextField(value=value,onValueChange=onChange,singleLine=!multiline,minLines=if(multiline) 3 else 1,
            maxLines=if(multiline) 6 else 1,visualTransformation=if(secret) PasswordVisualTransformation() else VisualTransformation.None,
            isError=error!=null,shape=androidx.compose.foundation.shape.RoundedCornerShape(11.dp),
            colors=OutlinedTextFieldDefaults.colors(focusedContainerColor=SetupColors.field,unfocusedContainerColor=SetupColors.field,
                focusedBorderColor=SetupColors.accent,unfocusedBorderColor=SetupColors.border),
            modifier=Modifier.fillMaxWidth().semantics { contentDescription=text })
        error?.let { Text(it,color=SetupColors.danger,fontSize=13.sp) }
    }
}

/** Shared by setup and the vault. Existing secrets are never inserted into editor fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CredentialSheet(pm:ProfileManager,vncPassword:Boolean=false,allowMac:Boolean=true,
    existing:SavedCredential?=null,onDismiss:()->Unit,onSaved:(SavedCredential)->Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var nameEdited by remember { mutableStateOf(existing!=null) }
    var user by remember { mutableStateOf(existing?.user.orEmpty()) }
    var short by remember { mutableStateOf(existing?.userShort.orEmpty()) }
    var shortEdited by remember { mutableStateOf(!existing?.userShort.isNullOrEmpty()) }
    var mac by remember { mutableStateOf(existing?.userShort!=null) }
    var pass by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf(existing?.requirePin ?: false) }
    var pinError by remember { mutableStateOf(false) }
    val effectivePass=pass.ifEmpty { existing?.pass.orEmpty() }
    val validPass=effectivePass.isNotEmpty() && (!vncPassword || effectivePass.length<=8 && effectivePass.all { it.code in 1..255 })
    val valid=name.isNotBlank() && validPass && (vncPassword || user.isNotBlank()) && (!mac || MacLoginNames.validShort(short)) && !pinError
    val sheetHeight=LocalConfiguration.current.screenHeightDp.dp*.8f
    ModalBottomSheet(onDismissRequest=onDismiss,containerColor=SetupColors.background,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().heightIn(max=sheetHeight).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if(existing!=null) R.string.edit_credential else if(mac) R.string.setup_new_mac_login else if(vncPassword) R.string.setup_new_vnc_password else R.string.setup_new_sign_in),style=MaterialTheme.typography.titleLarge)
            SetupInput(R.string.account_display_name,name,{name=it;nameEdited=true})
            if(!vncPassword) {
                if(allowMac) Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.setup_macos_account),modifier=Modifier.weight(1f))
                    val macLabel=stringResource(R.string.setup_macos_account)
                    Switch(checked=mac,modifier=Modifier.semantics {contentDescription=macLabel},onCheckedChange={mac=it;if(it && short.isEmpty()) short=MacLoginNames.shortName(user)})
                }
                SetupInput(if(mac) R.string.setup_full_name else R.string.setup_username,user,{
                    user=it;if(!nameEdited) name=it;if(mac && !shortEdited) short=MacLoginNames.shortName(it)
                })
                if(mac) {
                    Text(stringResource(R.string.setup_mac_names_help),fontSize=13.sp,color=SetupColors.muted)
                    SetupInput(R.string.setup_short_name,short,{
                        shortEdited=it.isNotEmpty();short=if(shortEdited) it else MacLoginNames.shortName(user)
                    },error=if(!MacLoginNames.validShort(short)) stringResource(R.string.setup_short_invalid) else null)
                }
            }
            SetupInput(if(vncPassword) R.string.setup_vnc_password else R.string.password,pass,{pass=it},secret=true,
                error=if(pass.isNotEmpty() && !validPass) stringResource(R.string.vnc_password_latin1) else null)
            if(vncPassword) Text("${pass.length}/8",color=if(pass.length>8) SetupColors.danger else SetupColors.muted)
            if(existing!=null) Text(stringResource(R.string.setup_password_keep),fontSize=13.sp,color=SetupColors.muted)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.require_pin),modifier=Modifier.weight(1f))
                val pinLabel=stringResource(R.string.require_pin)
                Switch(checked=pin,modifier=Modifier.semantics {contentDescription=pinLabel},onCheckedChange={ wanted ->
                    pinError=wanted && pm.getAppPinVerifier()==null && !pm.usesSystemPin()
                    pin=wanted
                })
            }
            if(pinError) Text(stringResource(R.string.setup_pin_first),color=SetupColors.danger)
            Button(enabled=valid,onClick={
                val saved=SavedCredential(id=existing?.id ?: java.util.UUID.randomUUID().toString(),name=name.trim(),
                    user=if(vncPassword) "" else user.trim(),pass=effectivePass,requirePin=pin,userShort=short.takeIf { mac })
                if(existing==null) pm.addCredential(saved) else pm.updateCredential(saved)
                onSaved(saved)
            },modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_changes)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SshKeySheet(pm:ProfileManager,existing:SavedSshKey?=null,onDismiss:()->Unit,onSaved:(SavedSshKey)->Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var key by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf(existing?.requirePin ?: false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    val sheetHeight=LocalConfiguration.current.screenHeightDp.dp*.8f
    ModalBottomSheet(onDismissRequest={if(!busy) onDismiss()},containerColor=SetupColors.background,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().heightIn(max=sheetHeight).verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if(existing==null) R.string.setup_import_key else R.string.edit_ssh_key),style=MaterialTheme.typography.titleLarge)
            SetupInput(R.string.key_display_name,name,{name=it})
            SetupInput(R.string.private_key,key,{key=it;error=null},multiline=true)
            if(existing!=null) Text(stringResource(R.string.setup_key_keep),fontSize=13.sp,color=SetupColors.muted)
            SetupInput(R.string.passphrase_optional,pass,{pass=it},secret=true)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.require_pin),modifier=Modifier.weight(1f))
                val pinLabel=stringResource(R.string.require_pin)
                Switch(checked=pin,modifier=Modifier.semantics {contentDescription=pinLabel},onCheckedChange={ wanted ->
                    pin=wanted
                    error=if(wanted && pm.getAppPinVerifier()==null && !pm.usesSystemPin()) R.string.setup_pin_first else null
                })
            }
            error?.let { Text(stringResource(it),color=SetupColors.danger) }
            Button(enabled=!busy && error!=R.string.setup_pin_first && name.isNotBlank() && (key.isNotBlank() || existing!=null),onClick={
                busy=true
                scope.launch {
                    try {
                        val saved=SavedSshKey(id=existing?.id ?: java.util.UUID.randomUUID().toString(),name=name.trim(),
                            privateKey=key.ifBlank { existing?.privateKey.orEmpty() },passphrase=pass.ifEmpty {existing?.passphrase.orEmpty()},requirePin=pin)
                        withContext(Dispatchers.Default) { deriveSshPublicKey(saved.privateKey,saved.passphrase,saved.name) }
                        if(existing==null) pm.addSshKey(saved) else pm.updateSshKey(saved)
                        onSaved(saved)
                    } catch(_:Exception) { error=R.string.ssh_public_key_invalid }
                    finally {busy=false}
                }
            },modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_changes)) }
        }
    }
}
