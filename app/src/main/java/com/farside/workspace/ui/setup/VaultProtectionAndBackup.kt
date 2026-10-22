package com.farside.workspace.ui.setup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.farside.workspace.CredentialPin
import com.farside.workspace.R
import com.farside.workspace.data.BackupManager
import com.farside.workspace.data.ProfileManager
import com.farside.workspace.ui.CredentialPinDialog
import com.farside.workspace.ui.SystemCredentialDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VaultAuthentication(pm:ProfileManager,onUnlocked:()->Unit,onCancel:()->Unit) {
    if(pm.usesSystemPin()) SystemCredentialDialog(allowRemembered=false,onUnlocked=onUnlocked,onCancel=onCancel)
    else if(pm.getAppPinVerifier()!=null) CredentialPinDialog(verifier=pm.getAppPinVerifier(),onUnlocked=onUnlocked,onCancel=onCancel)
    else LaunchedEffect(Unit) {onUnlocked()}
}

/** Uses the existing PIN verifier, OS authentication and authenticated backup format unchanged. */
@Composable
internal fun VaultProtectionAndBackup(pm:ProfileManager,onChanged:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var revision by remember {mutableIntStateOf(0)}
    var pinEditor by remember {mutableStateOf(false)}
    var pin by remember {mutableStateOf("")}
    var pinConfirm by remember {mutableStateOf("")}
    var action by remember {mutableStateOf<String?>(null)}
    var systemEnable by remember {mutableStateOf(false)}
    var status by remember {mutableStateOf<Int?>(null)}
    var backupMode by remember {mutableStateOf<String?>(null)}
    var phrase by remember {mutableStateOf("")}
    var phraseConfirm by remember {mutableStateOf("")}
    var pendingUri by remember {mutableStateOf<Uri?>(null)}
    var busy by remember {mutableStateOf(false)}
    fun changed() {revision++;onChanged()}
    fun runBackup(uri:Uri,export:Boolean) {
        val secret=phrase.toCharArray()
        phrase="";phraseConfirm="";backupMode=null;busy=true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if(export) BackupManager.exportToUri(context,pm,uri,secret)
                    else BackupManager.importFromUri(context,pm,uri,secret)
                }
                status=if(export) R.string.backup_export_success else R.string.setup_backup_restored
                changed()
            } catch(_:Exception) {status=if(export) R.string.setup_backup_failed else R.string.backup_invalid_passphrase}
            finally {secret.fill('\u0000');busy=false;pendingUri=null}
        }
    }
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {uri ->
        if(uri!=null) runBackup(uri,true) else {phrase="";phraseConfirm="";backupMode=null}
    }
    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->
        if(uri!=null) {pendingUri=uri;backupMode="restore";phrase="";phraseConfirm=""}
    }
    val verifier=remember(revision) {pm.getAppPinVerifier()}
    val system=remember(revision) {pm.usesSystemPin()}
    Text(stringResource(R.string.setup_vault_protection),style=MaterialTheme.typography.titleMedium)
    Text(stringResource(if(system) R.string.setup_headset_pin_active else if(verifier!=null) R.string.app_pin_active else R.string.setup_no_pin))
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick={if(verifier!=null || system) action="change" else pinEditor=true}) {
            Text(stringResource(if(verifier!=null) R.string.change_app_pin else R.string.setup_set_pin))
        }
        if(verifier!=null || system) OutlinedButton(onClick={action="remove"}) {Text(stringResource(R.string.remove_app_pin))}
    }
    OutlinedButton(onClick={if(system) action="remove" else if(verifier!=null) action="system" else systemEnable=true}) {
        Text(stringResource(if(system) R.string.setup_disable_headset_pin else R.string.setup_use_headset_pin))
    }
    Divider()
    Text(stringResource(R.string.setup_backup_restore),style=MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.setup_backup_covers))
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled=!busy,onClick={action="export"}) {Text(stringResource(R.string.export_backup))}
        OutlinedButton(enabled=!busy,onClick={action="import"}) {Text(stringResource(R.string.restore_backup))}
    }
    status?.let {Text(stringResource(it),color=SetupColors.muted)}
    action?.let {requested ->
        VaultAuthentication(pm,onUnlocked={
            action=null
            when(requested) {
                "change"->pinEditor=true
                "remove"->{pm.setAppPinVerifier(null);context.getSharedPreferences("farside_profiles",0).edit().putBoolean("system_pin_enabled",false).commit();changed()}
                "system"->systemEnable=true
                "export"->{backupMode="export";phrase="";phraseConfirm=""}
                "import"->importer.launch(arrayOf("application/octet-stream","application/json","*/*"))
            }
        },onCancel={action=null})
    }
    if(systemEnable) SystemCredentialDialog(allowRemembered=false,onUnlocked={pm.enableSystemPin();systemEnable=false;changed()},onCancel={systemEnable=false})
    if(pinEditor) AlertDialog(onDismissRequest={pinEditor=false;pin="";pinConfirm=""},title={Text(stringResource(R.string.setup_set_pin))},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            SetupInput(R.string.enter_new_app_pin,pin,{pin=it},secret=true)
            SetupInput(R.string.setup_confirm_pin,pinConfirm,{pinConfirm=it},secret=true)
        }},confirmButton={TextButton(enabled=CredentialPin.valid(pin) && pin==pinConfirm,onClick={
            val verifierValue=CredentialPin.create(pin)
            pm.setAppPinVerifier(verifierValue)
            context.getSharedPreferences("farside_profiles",0).edit().putBoolean("system_pin_enabled",false).commit()
            pin="";pinConfirm="";pinEditor=false;changed()
        }) {Text(stringResource(R.string.save_pin))}},dismissButton={TextButton(onClick={pinEditor=false;pin="";pinConfirm=""}) {Text(stringResource(R.string.cancel))}})
    backupMode?.let {mode ->
        AlertDialog(onDismissRequest={backupMode=null;phrase="";phraseConfirm="";pendingUri=null},title={Text(stringResource(if(mode=="export") R.string.backup_passphrase_title else R.string.restore_passphrase_title))},
            text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if(mode=="export") R.string.backup_passphrase_help else R.string.restore_passphrase_help))
                SetupInput(R.string.backup_passphrase_label,phrase,{phrase=it},secret=true)
                if(mode=="export") SetupInput(R.string.backup_passphrase_confirm_label,phraseConfirm,{phraseConfirm=it},secret=true)
            }},confirmButton={TextButton(enabled=phrase.isNotBlank() && (mode!="export" || phrase==phraseConfirm),onClick={
                if(mode=="export") exporter.launch("farside-workspace.fsbk") else pendingUri?.let {runBackup(it,false)}
            }) {Text(stringResource(R.string.continue_action))}},dismissButton={TextButton(onClick={backupMode=null;phrase="";phraseConfirm="";pendingUri=null}) {Text(stringResource(R.string.cancel))}})
    }
}
