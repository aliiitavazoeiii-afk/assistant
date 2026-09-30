package com.ali.assistant

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ali.assistant.biyok.BiyokNotifications
import com.ali.assistant.biyok.BiyokWakeService
import com.ali.assistant.biyok.PersianReminderParser
import com.ali.assistant.biyok.ReminderItem
import com.ali.assistant.biyok.ReminderScheduler
import com.ali.assistant.biyok.ReminderStore
import com.ali.assistant.biyok.VoiceCommandKind
import com.ali.assistant.biyok.WakePhase
import com.ali.assistant.biyok.WakePreferences
import com.ali.assistant.biyok.WakeStateBus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var store: ReminderStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = ReminderStore.get(this)
        BiyokNotifications.ensureChannels(this)
        setContent {
            BiyokTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    BiyokScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) store.refresh()
    }

    @Composable
    private fun BiyokScreen() {
        val context = LocalContext.current
        val reminders by store.items.collectAsState()
        val wake by WakeStateBus.state.collectAsState()
        var filter by remember { mutableStateOf(ReminderFilter.OPEN) }
        var manualText by remember { mutableStateOf("") }
        var wakeWanted by remember { mutableStateOf(WakePreferences.isEnabled(context)) }

        val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startWakeService() else WakePreferences.setEnabled(context, false)
        }
        val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val open = reminders.count { !it.completed }
        val inbox = reminders.count { !it.completed && it.dueAt == null }
        val scheduled = reminders.count { !it.completed && it.dueAt != null }
        val done = reminders.count { it.completed }
        val visible = reminders.filter {
            when (filter) {
                ReminderFilter.OPEN -> !it.completed
                ReminderFilter.INBOX -> !it.completed && it.dueAt == null
                ReminderFilter.SCHEDULED -> !it.completed && it.dueAt != null
                ReminderFilter.DONE -> it.completed
            }
        }

        val background = Brush.verticalGradient(
            listOf(Color(0xFF07111F), Color(0xFF0B1020), Color(0xFF130D21))
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(background)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(18.dp))
            Header()

            WakeCard(
                stateText = when {
                    wake.enabled -> wake.message
                    wakeWanted -> "بعد از روشن‌شدن گوشی، یک بار دوباره فعالش کن"
                    else -> "خاموش • برای شنیدن «بیوک» روشنش کن"
                },
                phase = wake.phase,
                enabled = wake.enabled,
                onToggle = { enable ->
                    wakeWanted = enable
                    if (enable) {
                        WakePreferences.setEnabled(context, true)
                        if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) startWakeService() else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        WakePreferences.setEnabled(context, false)
                        stopService(Intent(this@MainActivity, BiyokWakeService::class.java))
                    }
                }
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("باز", open, Icons.Default.RadioButtonChecked, Modifier.weight(1f))
                StatCard("Inbox", inbox, Icons.Default.Inbox, Modifier.weight(1f))
                StatCard("زمان‌دار", scheduled, Icons.Default.Schedule, Modifier.weight(1f))
            }

            QuickAdd(
                value = manualText,
                onValueChange = { manualText = it },
                onAdd = {
                    val clean = manualText.trim()
                    if (clean.isNotBlank()) {
                        val parsed = PersianReminderParser.parse("یادم بنداز $clean")
                        if (parsed.kind == VoiceCommandKind.ADD) {
                            val item = store.add(parsed.text, clean, parsed.dueAtMillis, false)
                            ReminderScheduler.schedule(context, item)
                        }
                        manualText = ""
                    }
                }
            )

            FilterRow(filter = filter, onChange = { filter = it }, doneCount = done)

            if (visible.isEmpty()) {
                EmptyState(filter, Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    items(visible, key = { it.id }) { item ->
                        ReminderCard(
                            item = item,
                            onCompleted = { checked ->
                                store.setCompleted(item.id, checked)
                                if (checked) ReminderScheduler.cancel(context, item.id)
                                else ReminderScheduler.schedule(context, store.get(item.id) ?: item)
                            },
                            onDelete = {
                                ReminderScheduler.cancel(context, item.id)
                                store.delete(item.id)
                            }
                        )
                    }
                }
            }

            SetupRow(
                exactReady = ReminderScheduler.hasExactAlarmAccess(context),
                notificationReady = BiyokNotifications.canNotify(context),
                onExact = { requestExactAlarmAccess() },
                onNotifications = {
                    if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                onBattery = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            )
        }
    }

    private fun startWakeService() {
        WakePreferences.setEnabled(this, true)
        ContextCompat.startForegroundService(this, Intent(this, BiyokWakeService::class.java))
    }

    private fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarm = getSystemService(AlarmManager::class.java)
            if (!alarm.canScheduleExactAlarms()) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }
    }
}

private enum class ReminderFilter { OPEN, INBOX, SCHEDULED, DONE }

@Composable
private fun Header() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("بیوک", fontSize = 38.sp, fontWeight = FontWeight.Black, color = Color.White)
            Text(
                "هر چیزی یادت افتاد، همون لحظه بسپار به بیوک.",
                color = Color(0xFFAAB7CC),
                fontSize = 14.sp
            )
        }
        Surface(shape = CircleShape, color = Color(0xFF18243A)) {
            Icon(
                Icons.Default.Mic,
                contentDescription = null,
                tint = Color(0xFF73E6C4),
                modifier = Modifier.padding(13.dp).size(26.dp)
            )
        }
    }
}

@Composable
private fun WakeCard(
    stateText: String,
    phase: WakePhase,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val accent = when (phase) {
        WakePhase.COMMAND_LISTENING -> Color(0xFFFFC857)
        WakePhase.SAVED -> Color(0xFF73E6C4)
        WakePhase.ERROR -> Color(0xFFFF7B87)
        WakePhase.LOADING -> Color(0xFF8EBBFF)
        WakePhase.WAKE_LISTENING -> Color(0xFF73E6C4)
        WakePhase.OFF -> Color(0xFF7D8AA2)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xCC111B2E)
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).background(accent.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.RadioButtonChecked, null, tint = accent, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text("Wake Word • «بیوک»", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(stateText, color = Color(0xFF9FB0C7), fontSize = 13.sp, maxLines = 2)
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun StatCard(title: String, count: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(18.dp), color = Color(0xAA121B2C)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, null, tint = Color(0xFF8EBBFF), modifier = Modifier.size(18.dp))
            Text(count.toPersianDigits(), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(title, color = Color(0xFF8F9DB1), fontSize = 11.sp)
        }
    }
}

@Composable
private fun QuickAdd(value: String, onValueChange: (String) -> Unit, onAdd: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color(0xAA101929)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("مثلاً: فردا ساعت ۳ فاکتور رو بفرستم") },
                singleLine = true,
                shape = RoundedCornerShape(15.dp)
            )
            Button(
                onClick = onAdd,
                enabled = value.isNotBlank(),
                modifier = Modifier.padding(start = 6.dp).height(54.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF276C5C))
            ) {
                Icon(Icons.Default.Add, null)
            }
        }
    }
}

@Composable
private fun FilterRow(filter: ReminderFilter, onChange: (ReminderFilter) -> Unit, doneCount: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(
                modifier = Modifier.weight(1f),
                selected = filter == ReminderFilter.OPEN,
                onClick = { onChange(ReminderFilter.OPEN) },
                label = { Text("باز") }
            )
            FilterChip(
                modifier = Modifier.weight(1f),
                selected = filter == ReminderFilter.INBOX,
                onClick = { onChange(ReminderFilter.INBOX) },
                label = { Text("Inbox") }
            )
            FilterChip(
                modifier = Modifier.weight(1f),
                selected = filter == ReminderFilter.SCHEDULED,
                onClick = { onChange(ReminderFilter.SCHEDULED) },
                label = { Text("زمان‌دار") }
            )
        }
        if (doneCount > 0 || filter == ReminderFilter.DONE) {
            FilterChip(
                selected = filter == ReminderFilter.DONE,
                onClick = { onChange(ReminderFilter.DONE) },
                label = { Text("انجام‌شده ${doneCount.toPersianDigits()}") }
            )
        }
    }
}

@Composable
private fun ReminderCard(item: ReminderItem, onCompleted: (Boolean) -> Unit, onDelete: () -> Unit) {
    val overdue = item.dueAt?.let { it < System.currentTimeMillis() && !item.completed } == true
    Surface(shape = RoundedCornerShape(19.dp), color = Color(0xD9131C2D)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = item.completed, onCheckedChange = onCompleted)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    item.text,
                    color = if (item.completed) Color(0xFF738096) else Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(
                        if (item.dueAt == null) Icons.Default.Inbox else Icons.Default.Schedule,
                        null,
                        modifier = Modifier.size(14.dp),
                        tint = if (overdue) Color(0xFFFF7B87) else Color(0xFF8EBBFF)
                    )
                    Text(
                        item.dueAt?.let(::formatDue) ?: "بدون زمان • Inbox",
                        color = if (overdue) Color(0xFFFF8D98) else Color(0xFF8494AA),
                        fontSize = 11.sp
                    )
                    if (item.createdByVoice) Text("• صدا", color = Color(0xFF6D7A8E), fontSize = 11.sp)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null, tint = Color(0xFF6F7C91), modifier = Modifier.size(19.dp))
            }
        }
    }
}

@Composable
private fun EmptyState(filter: ReminderFilter, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF47635F), modifier = Modifier.size(42.dp))
            Text(
                when (filter) {
                    ReminderFilter.DONE -> "هنوز چیزی را انجام‌شده علامت نزدی."
                    ReminderFilter.SCHEDULED -> "یادآوری زمان‌داری نداری."
                    ReminderFilter.INBOX -> "Inbox خالیه."
                    ReminderFilter.OPEN -> "همه‌چی جمعه؛ کار بازی نداری."
                },
                color = Color(0xFF76859B)
            )
        }
    }
}

@Composable
private fun SetupRow(
    exactReady: Boolean,
    notificationReady: Boolean,
    onExact: () -> Unit,
    onNotifications: () -> Unit,
    onBattery: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(18.dp), color = Color(0x80121A29)) {
        Column(Modifier.fillMaxWidth().padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, null, tint = Color(0xFF77869C), modifier = Modifier.size(17.dp))
                Text("  تنظیم یک‌باره", color = Color(0xFF97A5B9), fontSize = 12.sp)
            }
            if (!notificationReady) OutlinedButton(onClick = onNotifications, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Notifications, null, modifier = Modifier.size(16.dp)); Text(" اجازه نوتیفیکیشن")
            }
            if (!exactReady) OutlinedButton(onClick = onExact, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Schedule, null, modifier = Modifier.size(16.dp)); Text(" اجازه زمان‌بندی دقیق")
            }
            OutlinedButton(onClick = onBattery, modifier = Modifier.fillMaxWidth()) {
                Text("برای پایداری Wake Word: باتری روی Unrestricted")
            }
        }
    }
}

private fun formatDue(value: Long): String = SimpleDateFormat("EEE d MMM • HH:mm", Locale("fa", "IR")).format(Date(value))

private fun Int.toPersianDigits(): String = toString().map { c ->
    if (c in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[c - '0'] else c
}.joinToString("")

private val BiyokColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF73E6C4),
    onPrimary = Color(0xFF06231C),
    secondary = Color(0xFF8EBBFF),
    background = Color(0xFF07111F),
    surface = Color(0xFF111B2E),
    onSurface = Color.White,
)

@Composable
private fun BiyokTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BiyokColors, content = content)
}
