package com.windowslockpin.companion.ui.screens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import com.windowslockpin.companion.core.statemachine.PairedPcRecord
import com.windowslockpin.companion.storage.DeviceDetailsStore
import java.text.DateFormat
import java.util.Date

@Composable fun DeviceDetailsScreen(record:PairedPcRecord,store:DeviceDetailsStore,onBack:()->Unit,onSaved:()->Unit) {
    var nick by remember(record.pcId){mutableStateOf(store.nickname(record))}
    var saved by remember { mutableStateOf(false) }
    val info=store.details(record)
    fun stamp(ms:Long)=if(ms>0)DateFormat.getDateTimeInstance().format(Date(ms)) else "未记录"
    val rows=listOf(
        "原设备名称" to record.pcName,
        "系统" to (info?.optString("os")?.takeIf{it.isNotBlank()}?:"等待电脑同步"),
        "CPU" to (info?.optString("cpu")?.takeIf{it.isNotBlank()}?:"等待电脑同步"),
        "GPU" to (info?.optString("gpu")?.takeIf{it.isNotBlank()}?:"等待电脑同步"),
        "内存（物理总量）" to (info?.optLong("memoryBytes")?.takeIf{it>0}?.let{String.format("%.1f GiB",it/1073741824.0)}?:"等待电脑同步"),
        "蓝牙 MAC 地址" to record.bluetoothMac.value,
        "连接方式" to "蓝牙 RFCOMM · BLE 唤醒",
        "本次启动时间（估算）" to stamp(info?.optLong("bootMs")?:0),
        "开机位置（登录时采集）" to (info?.optString("bootLocation")?.takeIf{it.isNotBlank()}?:"未记录"),
        "位置采集时间" to stamp(info?.optLong("locationCapturedMs")?:0),
        "设备信息更新时间" to stamp(info?.optLong("capturedMs")?:0))
    Dialog(onDismissRequest=onBack,properties=DialogProperties(usePlatformDefaultWidth=false)) {
    Surface(modifier=Modifier.fillMaxWidth(.92f).widthIn(max=560.dp).fillMaxHeight(.84f)
        .border(1.dp,Color(0x5500E5FF),RoundedCornerShape(24.dp)),
        shape=RoundedCornerShape(24.dp),color=Color(0xFF101F35),tonalElevation=8.dp) {
    Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(store.displayName(record),modifier=Modifier.weight(1f),fontSize=22.sp,color=Color.White,maxLines=2)
            IconButton(onClick=onBack) {
                Icon(Icons.Default.Close,contentDescription="关闭详情",tint=Color(0xFF78DFFF))
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
            OutlinedTextField(nick,{if(it.length<=40 && it.none{c->c.isISOControl()}){nick=it;saved=false}},
                placeholder={Text("设备昵称备注")},singleLine=true,modifier=Modifier.weight(1f),
                shape=RoundedCornerShape(12.dp),
                colors=OutlinedTextFieldDefaults.colors(focusedTextColor=Color.White,unfocusedTextColor=Color.White,
                    focusedBorderColor=Color(0xFF78DFFF),unfocusedBorderColor=Color(0x5500E5FF),
                    focusedPlaceholderColor=Color(0xFF83B6D5),unfocusedPlaceholderColor=Color(0xFF83B6D5)))
            Button(onClick={store.saveNickname(record,nick);saved=true;onSaved()},
                modifier=Modifier.height(56.dp),shape=RoundedCornerShape(12.dp),contentPadding=PaddingValues(horizontal=12.dp)) {
                Text(if(saved)"已保存" else "保存备注")
            }
        }
        Text("显示“昵称*”；留空恢复原设备名",color=Color(0xFF83B6D5),fontSize=12.sp)
        HorizontalDivider(color=Color(0x3300E5FF))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        rows.forEach { (name,value)->
            Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFF1B304A))) {
                Column(Modifier.padding(16.dp)){Text(name,color=Color(0xFF83B6D5),fontSize=12.sp);Text(value,color=Color.White)}
            }
        }
        Text("设备信息在配对及后续蓝牙解锁连接时同步。这里展示最近一次快照；MAC 是配对使用的蓝牙适配器地址。",color=Color(0xFF83B6D5),fontSize=12.sp)
        }
    }
    }
    }
}
