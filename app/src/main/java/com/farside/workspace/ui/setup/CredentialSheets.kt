package com.farside.workspace.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
internal fun SetupInput(label:Int,value:String,onChange:(String)->Unit,secret:Boolean=false,multiline:Boolean=false,error:String?=null,compact:Boolean=false) {
    val text=stringResource(label)
    Column(verticalArrangement=Arrangement.spacedBy(if(compact) 4.dp else 6.dp)) {
        Text(text,fontSize=if(compact) 12.sp else 13.sp,color=SetupColors.muted)
        if (label == R.string.private_key) PrivateKeyField(value, onChange, error != null)
        else com.farside.workspace.FormTextMenus { OutlinedTextField(value=value,onValueChange=onChange,singleLine=!multiline,minLines=if(multiline) 3 else 1,
            keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled=false,
                keyboardType=when {
                    label==R.string.enter_new_app_pin || label==R.string.setup_confirm_pin -> androidx.compose.ui.text.input.KeyboardType.NumberPassword
                    label==R.string.bridge_port_hint -> androidx.compose.ui.text.input.KeyboardType.Number
                    secret -> androidx.compose.ui.text.input.KeyboardType.Password
                    else -> androidx.compose.ui.text.input.KeyboardType.Text
                }),
            maxLines=if(multiline) 6 else 1,visualTransformation=if(secret) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle=if(compact) LocalTextStyle.current.copy(fontSize=14.sp) else LocalTextStyle.current,
            isError=error!=null,shape=androidx.compose.foundation.shape.RoundedCornerShape(11.dp),
            colors=OutlinedTextFieldDefaults.colors(focusedContainerColor=SetupColors.field,unfocusedContainerColor=SetupColors.field,
                focusedBorderColor=SetupColors.accent,unfocusedBorderColor=SetupColors.border),
            modifier=Modifier.fillMaxWidth().then(if(compact) Modifier.heightIn(min=48.dp) else Modifier).semantics { contentDescription=text }) }
        error?.let { Text(it,color=SetupColors.danger,fontSize=13.sp) }
    }
}

/** Shared by setup and the vault. Existing secrets are never inserted into editor fields. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CredentialSheet(pm:ProfileManager,vncPassword:Boolean=false,allowMac:Boolean=true,macAccount:Boolean=false,allowPasswordOnly:Boolean=false,allowEmptyPassword:Boolean=true,
    existing:SavedCredential?=null,onDismiss:()->Unit,onSaved:(SavedCredential)->Unit) {
    var passwordOnly by remember {mutableStateOf(vncPassword)}
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var nameEdited by remember { mutableStateOf(existing!=null) }
    var user by remember { mutableStateOf(existing?.user.orEmpty()) }
    var short by remember { mutableStateOf(existing?.userShort.orEmpty()) }
    var shortEdited by remember { mutableStateOf(!existing?.userShort.isNullOrEmpty()) }
    var mac by remember { mutableStateOf(existing?.userShort!=null || macAccount) }
    var pass by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf(existing?.requirePin ?: false) }
    var pinSetup by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    val effectivePass=pass.ifEmpty { existing?.pass.orEmpty() }
    val validPass=(effectivePass.isEmpty() && allowEmptyPassword && !passwordOnly && !mac) || effectivePass.isNotEmpty() && (!passwordOnly || effectivePass.length<=8 && effectivePass.all { it.code in 1..255 })
    val valid=name.isNotBlank() && validPass && (passwordOnly || user.isNotBlank()) && (!mac || MacLoginNames.validShort(short)) && !pinSetup
    val sheetHeight=LocalConfiguration.current.screenHeightDp.dp*.8f
    ModalBottomSheet(onDismissRequest=onDismiss,dragHandle=null,containerColor=SetupColors.background,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().heightIn(max=sheetHeight).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    if(allowPasswordOnly && existing==null) {
                        listOf(false,true).forEach { only ->FilterChip(selected=passwordOnly==only,onClick={passwordOnly=only;if(only) mac=false},
                            label={Text(stringResource(if(only) R.string.setup_vnc_password else R.string.setup_user_password))}) }
                    }
                }
                IconButton(onClick=onDismiss,modifier=Modifier.size(32.dp)) {Icon(Icons.Default.Close,stringResource(R.string.close),Modifier.size(20.dp),tint=SetupColors.muted)}
            }
            Column(Modifier.weight(1f,fill=false).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            SetupInput(R.string.account_display_name,name,{name=it;nameEdited=true},error=if(attempted && name.isBlank()) stringResource(R.string.profile_name_required) else null,compact=true)
            if(!passwordOnly) {
                if(allowMac) Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(stringResource(R.string.setup_macos_account),modifier=Modifier.weight(1f))
                    val macLabel=stringResource(R.string.setup_macos_account)
                    Switch(checked=mac,modifier=Modifier.semantics {contentDescription=macLabel},onCheckedChange={mac=it;if(it && short.isEmpty()) short=MacLoginNames.shortName(user)})
                }
                SetupInput(if(mac) R.string.setup_full_name else R.string.setup_username,user,{
                    user=it;if(!nameEdited) name=it;if(mac && !shortEdited) short=MacLoginNames.shortName(it)
                },error=if(attempted && user.isBlank()) stringResource(R.string.setup_username_required) else null,compact=true)
                if(mac) {
                    SetupInput(R.string.setup_short_name,short,{
                        shortEdited=it.isNotEmpty();short=if(shortEdited) it else MacLoginNames.shortName(user)
                    },error=if(attempted && !MacLoginNames.validShort(short)) stringResource(R.string.setup_short_invalid) else null,compact=true)
                }
            }
            SetupInput(if(passwordOnly) R.string.setup_vnc_password else R.string.password,pass,{pass=it},secret=true,
                error=if(attempted && !validPass) stringResource(if(effectivePass.isEmpty()) R.string.setup_password_required else R.string.vnc_password_latin1) else null,compact=true)
            if(passwordOnly) Text("${pass.length}/8",color=if(attempted && pass.length>8) SetupColors.danger else SetupColors.muted)
            if(existing!=null) Text(stringResource(R.string.setup_password_keep),fontSize=13.sp,color=SetupColors.muted)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.require_pin),modifier=Modifier.weight(1f))
                val pinLabel=stringResource(R.string.require_pin)
                Switch(checked=pin,modifier=Modifier.semantics {contentDescription=pinLabel},onCheckedChange={ wanted ->
                    pinSetup=wanted && pm.getAppPinVerifier()==null && !pm.usesSystemPin()
                    pin=wanted
                })
            }

            }
            Button(enabled=!pinSetup,onClick={
                attempted=true
                if(valid) {
                val saved=SavedCredential(id=existing?.id ?: java.util.UUID.randomUUID().toString(),name=name.trim(),
                    user=if(passwordOnly) "" else user.trim(),pass=effectivePass,requirePin=pin,userShort=short.takeIf { mac })
                if(existing==null) pm.addCredential(saved) else pm.updateCredential(saved)
                onSaved(saved)
                }
            },modifier=Modifier.fillMaxWidth()) { Text(stringResource(if(existing==null) R.string.save else R.string.save_changes)) }
        }
    }
    if(pinSetup) RequirePinSetup(pm,onSaved={pinSetup=false},onCancel={pinSetup=false;pin=false})
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SshKeySheet(pm:ProfileManager,existing:SavedSshKey?=null,onDelete:(()->Unit)?=null,onDismiss:()->Unit,onSaved:(SavedSshKey)->Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var key by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf(existing?.requirePin ?: false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pinSetup by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val sheetHeight=LocalConfiguration.current.screenHeightDp.dp*.8f
    ModalBottomSheet(onDismissRequest={if(!busy) onDismiss()},dragHandle=null,containerColor=SetupColors.background,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().heightIn(max=sheetHeight).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                IconButton(onClick=onDismiss,enabled=!busy,modifier=Modifier.size(32.dp)) {Icon(Icons.Default.Close,stringResource(R.string.close),Modifier.size(20.dp),tint=SetupColors.muted)}
            }
            Column(Modifier.weight(1f,fill=false).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            SetupInput(R.string.key_display_name,name,{name=it},compact=true)
            SetupInput(R.string.private_key,key,{key=it;error=null},multiline=true,compact=true)
            if(existing!=null) Text(stringResource(R.string.setup_key_keep),fontSize=13.sp,color=SetupColors.muted)
            SetupInput(R.string.passphrase_optional,pass,{pass=it},secret=true,compact=true)
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.require_pin),modifier=Modifier.weight(1f))
                val pinLabel=stringResource(R.string.require_pin)
                Switch(checked=pin,modifier=Modifier.semantics {contentDescription=pinLabel},onCheckedChange={ wanted ->
                    pin=wanted
                    pinSetup=wanted && pm.getAppPinVerifier()==null && !pm.usesSystemPin()
                })
            }
            error?.let { Text(stringResource(it),color=SetupColors.danger) }
            }
            Button(enabled=!busy && !pinSetup && name.isNotBlank() && (key.isNotBlank() || existing!=null),onClick={
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
            },modifier=Modifier.fillMaxWidth()) { Text(stringResource(if(existing==null) R.string.save else R.string.save_changes)) }
            onDelete?.let {TextButton(onClick=it) {Text(stringResource(R.string.delete),color=SetupColors.danger)}}
        }
    }
    if(pinSetup) RequirePinSetup(pm,onSaved={pinSetup=false},onCancel={pinSetup=false;pin=false})
}
