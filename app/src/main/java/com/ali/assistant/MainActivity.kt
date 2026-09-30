package com.ali.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ali.assistant.core.ReminderItem
import com.ali.assistant.wake.BiyokWakeService
import com.ali.assistant.wake.WakeEnrollmentRecorder
import com.ali.assistant.wake.WakeTemplateStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val vm: BiyokViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BiyokTheme { BiyokScreen(vm) } }
    }

    override fun onResume() { super.onResume(); vm.refresh() }

    @Composable
    private fun BiyokScreen(vm: BiyokViewModel) {
        var text by remember { mutableStateOf("") }
        val templateStore = remember { WakeTemplateStore(this@MainActivity) }
        val trainer = remember { WakeEnrollmentRecorder(this@MainActivity) }
        var samples by remember { mutableIntStateOf(templateStore.count()) }
        var training by remember { mutableStateOf(false) }
        var sensitivity by remember { mutableFloatStateOf(templateStore.sensitivity()) }

        fun recordTrainingSample() {
            if (training) return
            training = true
            trainer.recordOnce(
                onState = { vm.updateStatus(it) },
                onDone = { _, message ->
                    training = false
                    samples = templateStore.count()
                    vm.updateStatus(message)
                }
            )
        }

        val trainMicPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) recordTrainingSample() else vm.updateStatus("اجازه میکروفن برای آموزش لازم است")
        }
        val wakeMicPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startWake(samples) else vm.updateStatus("اجازه میکروفن برای بیوک لازم است")
        }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            vm.updateStatus(if (granted) "نوتیفیکیشن فعال شد" else "اجازه نوتیفیکیشن داده نشد")
        }

        val bg = Brush.verticalGradient(listOf(Color(0xFF050A10), Color(0xFF09131E), Color(0xFF0D1721)))
        Box(Modifier.fillMaxSize().background(bg)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(18.dp, 24.dp, 18.dp, 46.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(
                            Brush.linearGradient(listOf(Color(0xFF12395B), Color(0xFF11263B), Color(0xFF0E1C2A)))
                        ).padding(22.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text("بیوک", fontSize = 44.sp, fontWeight = FontWeight.Black, color = Color.White)
                                    Text("یادت بمونه، بدون اینکه گوشی رو برداری.", color = Color(0xFFB9D0E3), fontSize = 15.sp)
                                }
                                Box(Modifier.size(58.dp).clip(CircleShape).background(Color(0xFF2D91E8)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(29.dp))
                                }
                            }
                            Surface(shape = RoundedCornerShape(16.dp), color = Color(0x331A9FFF)) {
                                Text(vm.status, color = Color(0xFFC9E8FF), modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp), fontSize = 13.sp)
                            }
                        }
                    }
                }

                item {
                    Surface(shape = RoundedCornerShape(26.dp), color = Color(0xFF101D28)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text("۱. صدای «بیوک» رو یاد بده", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Text("فقط بار اول • سه نمونه کوتاه", color = Color(0xFF8296A7), fontSize = 12.sp)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    repeat(3) { i -> Box(Modifier.size(9.dp).clip(CircleShape).background(if (i < samples) Color(0xFF62D19B) else Color(0xFF334552))) }
                                }
                            }
                            Button(
                                onClick = {
                                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) recordTrainingSample()
                                    else trainMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                enabled = !training,
                                modifier = Modifier.fillMaxWidth().height(50.dp),
                                shape = RoundedCornerShape(17.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = if (samples >= 3) Color(0xFF244D43) else Color(0xFF196FC3))
                            ) {
                                Icon(Icons.Rounded.Mic, null); Spacer(Modifier.width(8.dp))
                                Text(if (training) "دارم گوش می‌دم…" else if (samples >= 3) "آموزش کامل • برای آموزش دوباره بزن" else "نمونه ${samples + 1} از ۳")
                            }
                            if (samples > 0) TextButton(onClick = {
                                stopService(Intent(this@MainActivity, BiyokWakeService::class.java))
                                templateStore.clear(); samples = 0; vm.updateStatus("آموزش بیوک پاک شد")
                            }) { Icon(Icons.Rounded.Refresh, null); Spacer(Modifier.width(6.dp)); Text("آموزش از اول") }
                        }
                    }
                }

                item {
                    Surface(shape = RoundedCornerShape(26.dp), color = Color(0xFF101D28)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("۲. همیشه آماده‌اش کن", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("بعدش می‌تونی اپ رو ببندی و گوشی رو بذاری روی میز. بگو «بیوک»، beep رو که شنیدی یادآوری‌ات رو بگو.", color = Color(0xFF97A9B8), lineHeight = 20.sp, fontSize = 13.sp)
                            Text("حساسیت بیوک", color = Color(0xFFBFD1DF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Slider(
                                value = sensitivity,
                                onValueChange = { sensitivity = it; templateStore.setSensitivity(it) },
                                valueRange = 0.15f..0.95f
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("کمتر false positive", color = Color(0xFF647786), fontSize = 10.sp)
                                Text("راحت‌تر بیدار می‌شه", color = Color(0xFF647786), fontSize = 10.sp)
                            }
                            Button(
                                onClick = {
                                    if (samples < 3) vm.updateStatus("اول آموزش سه‌مرحله‌ای بیوک رو کامل کن")
                                    else if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startWake(samples)
                                    else wakeMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                enabled = samples >= 3,
                                modifier = Modifier.fillMaxWidth().height(54.dp),
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A8C64))
                            ) { Icon(Icons.Rounded.PowerSettingsNew, null); Spacer(Modifier.width(8.dp)); Text("فعال‌کردن بیوک", fontWeight = FontWeight.Bold) }
                            OutlinedButton(
                                onClick = { stopService(Intent(this@MainActivity, BiyokWakeService::class.java)); vm.updateStatus("بیوک در پس‌زمینه خاموش شد") },
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
                            ) { Text("خاموش‌کردن شنود بیوک") }
                        }
                    }
                }

                item { Text("ثبت سریع", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold) }
                item {
                    Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF0F1A24)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("مثلاً: فردا ساعت ده پارچه سفارش بدم") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), minLines = 2)
                            Button(onClick = { vm.add(text); text = "" }, enabled = text.isNotBlank(), modifier = Modifier.align(Alignment.End), shape = RoundedCornerShape(16.dp)) { Text("ذخیره") }
                        }
                    }
                }

                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("کارهایی که گفتی", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF182936)) {
                            Text("${vm.items.size}", color = Color(0xFF79C8FF), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (vm.items.isEmpty()) item {
                    Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF0F1A24)) {
                        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.NotificationsActive, null, tint = Color(0xFF607789), modifier = Modifier.size(38.dp)); Spacer(Modifier.height(10.dp)); Text("فعلاً چیزی ثبت نکردی", color = Color(0xFF9EADBA))
                        }
                    }
                } else items(vm.items, key = { it.id }) { ReminderCard(it, onDone = { vm.done(it.id) }, onDelete = { vm.delete(it.id) }) }

                item {
                    HorizontalDivider(color = Color(0xFF23313C)); Spacer(Modifier.height(4.dp))
                    Text("اجازه‌های سیستم", color = Color(0xFF91A3B2), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("فعال‌کردن نوتیفیکیشن") }
                    if (Build.VERSION.SDK_INT >= 31) TextButton(onClick = { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply { data = android.net.Uri.parse("package:$packageName") }) }) { Text("اجازه یادآوری دقیق") }
                    TextButton(onClick = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("تنظیم باتری برای پایداری پس‌زمینه") }
                    Text("بیوک هیچ API Key، سرور یا مدل زبانی ندارد. متن یادآوری‌ها فقط روی همین گوشی ذخیره می‌شود.", color = Color(0xFF647786), fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }
    }

    @Composable
    private fun ReminderCard(item: ReminderItem, onDone: () -> Unit, onDelete: () -> Unit) {
        Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF13212D)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(item.remindAt?.let(::formatTime) ?: "بدون زمان • در لیست می‌مونه", color = if (item.remindAt == null) Color(0xFF718495) else Color(0xFF69B7F5), fontSize = 12.sp)
                }
                IconButton(onClick = onDone) { Icon(Icons.Rounded.Check, "انجام شد", tint = Color(0xFF6DD39E)) }
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "حذف", tint = Color(0xFFEB7C7C)) }
            }
        }
    }

    private fun startWake(samples: Int) {
        if (samples < 3) { vm.updateStatus("اول بیوک رو سه بار آموزش بده"); return }
        ContextCompat.startForegroundService(this, Intent(this, BiyokWakeService::class.java))
        vm.updateStatus("بیوک فعال شد • حالا می‌تونی اپ رو ببندی")
    }

    private fun formatTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM • HH:mm", Locale("fa", "IR")))
}

@Composable
private fun BiyokTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF3B9CFF), secondary = Color(0xFF6DD39E), background = Color(0xFF071018), surface = Color(0xFF111D28)), content = content)
}
