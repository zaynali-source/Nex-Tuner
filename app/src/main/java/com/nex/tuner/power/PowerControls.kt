package com.nex.tuner.power
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private suspend fun readNode(path:String):String?=withContext(Dispatchers.IO){runCatching{val p=ProcessBuilder("su","-c","test -r '$path' && cat '$path'").redirectErrorStream(true).start();val s=p.inputStream.bufferedReader().use{it.readText().trim()};if(p.waitFor()==0)s.takeIf{it.isNotBlank()}else null}.getOrNull()}
private suspend fun writeNode(path:String,value:Int):Boolean=withContext(Dispatchers.IO){runCatching{val p=ProcessBuilder("su","-c","test -w '$path' && echo '$value' > '$path'").redirectErrorStream(true).start();p.inputStream.bufferedReader().use{it.readText()};p.waitFor()==0}.getOrDefault(false)}

@Composable fun ThermalConfigScreen(){
 val ctx=LocalContext.current;val prefs=remember{ctx.getSharedPreferences("nex_thermal",Context.MODE_PRIVATE)};val scope=rememberCoroutineScope()
 var level by remember{mutableFloatStateOf(0f)};var detected by remember{mutableStateOf(false)};var info by remember{mutableStateOf("Scanning thermal ABI…")};var status by remember{mutableStateOf("")}
 LaunchedEffect(Unit){val raw=readNode("/sys/class/thermal/thermal_message/sconfig");detected=raw?.toIntOrNull()!=null;raw?.toIntOrNull()?.let{level=it.coerceIn(0,20).toFloat()};info=raw?.let{"Ulquiorra sconfig: $it / 20"}?:"Ulquiorra sconfig node not detected"}
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
  Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF151515))){Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Text("THERMAL CONFIGS",color=Color.White,fontSize=13.sp);Text(info,color=Color.LightGray,fontSize=12.sp);Text("Thermal sconfig · "+level.toInt()+" / 20",color=Color.White,fontSize=18.sp)
   Slider(value=level,onValueChange={level=it},valueRange=0f..20f,steps=19,enabled=detected)
   Button(enabled=detected,modifier=Modifier.fillMaxWidth(),onClick={scope.launch{val ok=writeNode("/sys/class/thermal/thermal_message/sconfig",level.toInt());status=if(ok){prefs.edit().putInt("sconfig",level.toInt()).apply();info="Ulquiorra sconfig: "+(readNode("/sys/class/thermal/thermal_message/sconfig")?:level.toInt().toString());"Applied"}else "Write failed — root/kernel permission unavailable"}}){Text("APPLY THERMAL CONFIG")}
   Text(status,color=Color.LightGray,fontSize=12.sp)
  }}
  Text("Uses the verified Ulquiorra thermal_message/sconfig ABI (0–20). Generic devices remain read-only; vendor thermal limits are not overwritten.",color=Color.Gray,fontSize=11.sp,lineHeight=16.sp)
 }
}

@Composable fun ChargingControllerScreen(){
 val ctx=LocalContext.current;val prefs=remember{ctx.getSharedPreferences("nex_charging",Context.MODE_PRIVATE)};val scope=rememberCoroutineScope()
 var path by remember{mutableStateOf<String?>(null)};var limit by remember{mutableFloatStateOf(100f)};var info by remember{mutableStateOf("Detecting charging interface…")};var status by remember{mutableStateOf("")}
 LaunchedEffect(Unit){val options=listOf("/sys/class/power_supply/battery/charge_control_end_threshold","/sys/class/qcom-battery/charge_control_end_threshold");for(candidate in options){val raw=readNode(candidate)?.toIntOrNull();if(raw!=null){path=candidate;limit=raw.coerceIn(50,100).toFloat();info="Detected "+candidate+" · current "+raw+"%";break}};if(path==null){val candidates=listOf("/sys/class/qcom-battery/charge_control_en","/sys/kernel/ulquiorra/charge_bypass","/sys/kernel/ulquiorra/charge_control");val found=candidates.mapNotNull{q->readNode(q)?.let{q+" = "+it}};info=if(found.isEmpty())"No supported charging control node found" else "Read-only controls detected:\n"+found.joinToString("\n")}}
 Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
  Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF151515))){Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Text("CHARGING CONTROLLER",color=Color.White,fontSize=13.sp);Text(info,color=Color.LightGray,fontSize=11.sp,lineHeight=16.sp);Text("Charge stop threshold · "+limit.toInt()+"%",color=Color.White,fontSize=18.sp)
   Slider(value=limit,onValueChange={limit=it},valueRange=50f..100f,steps=49,enabled=path!=null)
   Button(enabled=path!=null,modifier=Modifier.fillMaxWidth(),onClick={scope.launch{val q=path?:return@launch;val old=readNode(q)?.toIntOrNull();if(old!=null)prefs.edit().putInt("previous",old).apply();status=if(writeNode(q,limit.toInt()))"Applied charge limit "+limit.toInt()+"%" else "Write failed";info="Detected "+q+" · current "+(readNode(q)?:"unknown")}}){Text("APPLY CHARGE LIMIT")}
   Button(enabled=path!=null&&prefs.contains("previous"),modifier=Modifier.fillMaxWidth(),onClick={scope.launch{val q=path?:return@launch;val old=prefs.getInt("previous",100);status=if(writeNode(q,old))"Restored previous value "+old+"%" else "Restore failed"}}){Text("RESTORE PREVIOUS LIMIT")}
   Text(status,color=Color.LightGray,fontSize=12.sp)
  }}
  Text("Only standard charge_control_end_threshold is writable. Ulquiorra charge_bypass/charge_control and qcom enable nodes are read-only until exact value semantics are verified.",color=Color.Gray,fontSize=11.sp,lineHeight=16.sp)
 }
}
