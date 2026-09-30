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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ali.assistant.core.ReminderItem
import com.ali.assistant.wake.BiyokWakeService
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
        val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startWake() else vm.updateStatus("اجازه میکروفن برای بیوک لازم است")
        }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

        val bg = Brush.verticalGradient(listOf(Color(0xFF071018), Color(0xFF0C1823), Color(0xFF111827)))
        Box(Modifier.fillMaxSize().background(bg)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(18.dp, 26.dp, 18.dp, 42.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text("بیوک", fontSize = 42.sp, fontWeight = FontWeight.Black, color = Color.White)
                    Text("هر چیزی یادت افتاد، فقط بگو.", color = Color(0xFF96A7B7), fontSize = 16.sp)
                }
                item {
                    Surface(shape = RoundedCornerShape(28.dp), color = Color(0xFF122536), tonalElevation = 4.dp) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF1F6FEB)) {
                                    Icon(Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.padding(11.dp))
                                }
                                Column {
                                    Text("Wake word: بیوک", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Text(vm.status, color = Color(0xFF8FB8D8), fontSize = 13.sp)
                                }
                            }
                            Button(
                                onClick = {
                                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startWake()
                                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(18.dp)
                            ) { Text("فعال‌کردن بیوک") }
                            Text("بعد از فعال‌سازی می‌تونی صفحه را ببندی و گوشی را روی میز بگذاری. با شنیدن «بیوک» یک صدای کوتاه می‌شنوی و بعد یادآوری‌ات را می‌گویی.", color = Color(0xFF9EADBA), fontSize = 13.sp)
                        }
                    }
                }
                item {
                    Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF101C27)) {
                        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                placeholder = { Text("مثلاً: فردا ساعت ۱۰ پارچه سفارش بدم") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                minLines = 2
                            )
                            Button(
                                onClick = { vm.add(text); text = "" },
                                enabled = text.isNotBlank(),
                                modifier = Modifier.align(Alignment.End),
                                shape = RoundedCornerShape(16.dp)
                            ) { Text("ذخیره") }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("کارهایی که گفتی", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF1A2A38)) {
                            Text("${vm.items.size}", color = Color(0xFF72C3FF), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (vm.items.isEmpty()) item {
                    Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF101C27)) {
                        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.NotificationsActive, null, tint = Color(0xFF607789), modifier = Modifier.size(38.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("فعلاً چیزی ثبت نکردی", color = Color(0xFF9EADBA))
                        }
                    }
                } else items(vm.items, key = { it.id }) { ReminderCard(it, onDone = { vm.done(it.id) }, onDelete = { vm.delete(it.id) }) }
                item {
                    if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("اجازه نوتیفیکیشن") }
                    if (Build.VERSION.SDK_INT >= 31) TextButton(onClick = { startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply { data = android.net.Uri.parse("package:$packageName") }) }) { Text("اجازه یادآوری دقیق") }
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
                    Text(item.remindAt?.let(::formatTime) ?: "بدون زمان • در لیست نگه داشته می‌شود", color = if (item.remindAt == null) Color(0xFF718495) else Color(0xFF69B7F5), fontSize = 12.sp)
                }
                IconButton(onClick = onDone) { Icon(Icons.Rounded.Check, "انجام شد", tint = Color(0xFF6DD39E)) }
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "حذف", tint = Color(0xFFEB7C7C)) }
            }
        }
    }

    private fun startWake() {
        ContextCompat.startForegroundService(this, Intent(this, BiyokWakeService::class.java))
        vm.updateStatus("بیوک در پس‌زمینه فعال شد")
    }

    private fun formatTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM • HH:mm", Locale("fa", "IR")))
}

@Composable
private fun BiyokTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF3B9CFF), secondary = Color(0xFF6DD39E), surface = Color(0xFF111D28)), content = content)
}
