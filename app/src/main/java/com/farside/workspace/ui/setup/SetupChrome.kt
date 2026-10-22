package com.farside.workspace.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farside.workspace.R
import com.farside.workspace.data.ConnectionType

internal object SetupColors {
    val background=Color(0xFF1C1B1F)
    val card=Color(0xFF1F1F27)
    val field=Color(0xFF101014)
    val border=Color(0xFF2D2D38)
    val accent=Color(0xFFD0BCFF)
    val muted=Color(0xFFA7A7B3)
    val faint=Color(0xFF6F6F7D)
    val danger=Color(0xFFFF6B6B)
}

@Composable
internal fun SetupStepper(step:Int,canVisit:(Int)->Boolean,onStep:(Int)->Unit) {
    val labels=listOf(R.string.setup_protocol,R.string.setup_server,R.string.setup_sign_in,R.string.setup_review)
    Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.Top) {
        labels.forEachIndexed { index,label ->
            val description="${index+1} ${stringResource(label)}"
            Box(Modifier.weight(1f)) {
                if(index>0) Box(Modifier.fillMaxWidth(.5f).padding(top=17.dp).height(2.dp).background(if(index<=step) SetupColors.accent else SetupColors.border))
                if(index<3) Box(Modifier.fillMaxWidth(.5f).align(Alignment.TopEnd).padding(top=17.dp).height(2.dp).background(if(index<step) SetupColors.accent else SetupColors.border))
                Column(Modifier.fillMaxWidth().semantics {contentDescription=description}
                    .clickable(enabled=canVisit(index),role=Role.Button) {onStep(index)},
                    horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(34.dp).background(if(index==step) SetupColors.accent else SetupColors.background,CircleShape)
                        .border(2.dp,if(index<=step) SetupColors.accent else SetupColors.border,CircleShape),contentAlignment=Alignment.Center) {
                        Text(if(index<step) "\u2713" else (index+1).toString(),fontSize=14.sp,fontWeight=FontWeight.Bold,
                            color=if(index==step) SetupColors.background else if(index<step) SetupColors.accent else SetupColors.muted)
                    }
                    Text(stringResource(label),fontSize=12.sp,fontWeight=FontWeight.SemiBold,maxLines=1,softWrap=false,
                        textAlign=androidx.compose.ui.text.style.TextAlign.Center,
                        color=if(index==step) Color.White else if(index<step) SetupColors.muted else SetupColors.faint)
                }
            }
        }
    }
}

@Composable
internal fun SetupProtocolCards(selected:ConnectionType,onChoose:(ConnectionType)->Unit) {
    BoxWithConstraints {
        if(maxWidth<600.dp) Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            ConnectionType.entries.forEach { SetupProtocolCard(it,it==selected,{onChoose(it)},Modifier.fillMaxWidth()) }
        } else Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            ConnectionType.entries.forEach { SetupProtocolCard(it,it==selected,{onChoose(it)},Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun SetupProtocolCard(type:ConnectionType,selected:Boolean,onClick:()->Unit,modifier:Modifier) {
    Surface(onClick=onClick,modifier=modifier.heightIn(min=188.dp),shape=RoundedCornerShape(14.dp),
        border=BorderStroke(if(selected) 2.dp else 1.dp,if(selected) SetupColors.accent else SetupColors.border),
        color=if(selected) SetupColors.accent.copy(alpha=.13f) else SetupColors.card) {
        Column(Modifier.padding(horizontal=16.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(42.dp).background(SetupColors.field,RoundedCornerShape(11.dp))
                .border(1.dp,if(selected) SetupColors.accent else SetupColors.border,RoundedCornerShape(11.dp)),contentAlignment=Alignment.Center) {
                Icon(when(type) { ConnectionType.RDP->Icons.Default.Computer;ConnectionType.SSH->Icons.Default.Code;ConnectionType.VNC->Icons.Default.ScreenShare },null,tint=SetupColors.accent,modifier=Modifier.size(22.dp))
            }
            Spacer(Modifier.height(3.dp))
            Text(type.name,fontSize=17.sp,fontWeight=FontWeight.Bold,color=Color.White)
            Text(stringResource(when(type) { ConnectionType.RDP->R.string.setup_rdp_description;ConnectionType.SSH->R.string.setup_ssh_description;ConnectionType.VNC->R.string.setup_vnc_description }),
                fontSize=13.sp,color=SetupColors.muted,minLines=2)
            Text(stringResource(R.string.setup_default_port,when(type) {ConnectionType.RDP->3389;ConnectionType.SSH->22;ConnectionType.VNC->5900}),fontSize=12.sp,color=SetupColors.faint)
        }
    }
}

@Composable
internal fun SetupServerField(value:String,onChange:(String)->Unit,label:Int,error:Int?,modifier:Modifier=Modifier) {
    val text=stringResource(label)
    Column(modifier,verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Text(text,fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=SetupColors.muted)
        OutlinedTextField(value=value,onValueChange=onChange,singleLine=true,isError=error!=null,
            keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled=false,
                keyboardType=if(label==R.string.setup_port) androidx.compose.ui.text.input.KeyboardType.Number else androidx.compose.ui.text.input.KeyboardType.Text),
            shape=RoundedCornerShape(11.dp),textStyle=LocalTextStyle.current.copy(fontSize=15.sp),
            colors=OutlinedTextFieldDefaults.colors(focusedContainerColor=SetupColors.field,unfocusedContainerColor=SetupColors.field,errorContainerColor=SetupColors.field,
                focusedBorderColor=SetupColors.accent,unfocusedBorderColor=SetupColors.border,errorBorderColor=SetupColors.danger),
            modifier=Modifier.fillMaxWidth().semantics { contentDescription=text })
        if(error!=null) Text(stringResource(error),fontSize=13.sp,color=SetupColors.danger)
    }
}
