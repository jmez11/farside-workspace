package com.farside.workspace

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.size

/** User confirms the exact remote password field; the app never guesses a lock-screen target. */
@Composable
internal fun MacUnlockControl(enabled:Boolean,onSubmit:()->Boolean,onInteraction:()->Unit,
    buttonSize:Dp,iconSize:Dp) {
    var confirming by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    IconButton(enabled=enabled && !submitted,onClick={ onInteraction();confirming=true },modifier=Modifier.size(buttonSize)) {
        Icon(Icons.Default.LockOpen,stringResource(R.string.mac_unlock_saved),modifier=Modifier.size(iconSize),
            tint=if(enabled && !submitted) Color.White else Color.Gray)
    }
    if(confirming) AlertDialog(onDismissRequest={ confirming=false },
        title={ Text(stringResource(R.string.mac_unlock_saved)) },
        text={ Text(stringResource(R.string.mac_unlock_confirm)) },
        confirmButton={ TextButton(enabled=enabled && !submitted,onClick={
            confirming=false;submitted=true;onSubmit();onInteraction()
        }) { Text(stringResource(R.string.mac_unlock_send)) } },
        dismissButton={ TextButton(onClick={ confirming=false }) { Text(stringResource(R.string.cancel)) } })
}
