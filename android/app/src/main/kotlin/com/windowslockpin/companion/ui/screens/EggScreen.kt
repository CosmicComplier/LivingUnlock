package com.windowslockpin.companion.ui.screens
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.windowslockpin.companion.core.egg.EggCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun EggScreen(raw:String,onBack:()->Unit) {
    val scope=rememberCoroutineScope()
    var password by remember(raw){mutableStateOf("")}
    var message by remember(raw){mutableStateOf<String?>(null)}
    var error by remember(raw){mutableStateOf<String?>(null)}
    var busy by remember(raw){mutableStateOf(false)}
    val envelope=remember(raw){runCatching { EggCodec.parse(raw) }}
    val needsPassword=envelope.getOrNull()?.mode=="P9"
    fun decode() {
        if(busy)return
        busy=true;error=null
        val chars=password.toCharArray();password=""
        scope.launch {
            try { message=withContext(Dispatchers.Default){EggCodec.decrypt(raw,chars)} }
            catch(_:Exception){error="无法解读这段讯息：口令不正确，或二维码已损坏。"}
            finally { chars.fill('\u0000');busy=false }
        }
    }
    LaunchedEffect(raw){if(envelope.isSuccess && !needsPassword)decode()}
    Column(Modifier.fillMaxSize().background(Color(0xFF101F35)).statusBarsPadding()
        .navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        TextButton(onClick=onBack){Text("← 返回",color=Color(0xFF78DFFF))}
        Text("藏在光里的留言",fontSize=26.sp,color=Color.White)
        Text("LivingUnlock · 秘密讯息",color=Color(0xFF83B6D5))
        if(envelope.isFailure)Text("无法识别此彩蛋。",color=Color(0xFFFF91AC))
        if(needsPassword && message==null) {
            OutlinedTextField(value=password,onValueChange={if(it.length<=256)password=it},
                label={Text("彩蛋口令")},visualTransformation=PasswordVisualTransformation(),singleLine=true,
                modifier=Modifier.fillMaxWidth(),enabled=!busy)
            Button(onClick={decode()},enabled=!busy && password.isNotEmpty()){Text("解读留言")}
        }
        if(busy)CircularProgressIndicator(color=Color(0xFF75D9FF))
        error?.let { Text(it,color=Color(0xFFFF91AC)) }
        message?.let { Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color(0xFF1E3652))) {
            Text(it,Modifier.padding(24.dp),color=Color(0xFFE1F8FF),fontSize=19.sp,lineHeight=31.sp)
        } }
    }
}
