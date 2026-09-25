package com.ali.assistant

import android.Manifest
import android.app.AlarmManager
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ali.assistant.ui.AssistantViewModel
import com.ali.assistant.voice.SpeechInputManager
import com.ali.assistant.wake.HotwordService

class MainActivity : ComponentActivity() {
    companion object {
        private const val MIC_REQUEST_CODE = 2001
        private const val MIC_WAKE_REQUEST_CODE = 2002
    }

    private val vm: AssistantViewModel by viewModels()
    private lateinit var speech: SpeechInputManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speech = SpeechInputManager(this)
        setContent { MaterialTheme { AssistantScreen(vm) } }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        intent.getStringExtra("hotword_command")?.takeIf { it.isNotBlank() }?.let { vm.submit(it) }
        if (intent.getBooleanExtra("auto_listen", false)) window.decorView.postDelayed({ beginListening() }, 450)
        intent.removeExtra("hotword_command")
        intent.removeExtra("auto_listen")
    }

    private fun beginListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            vm.setListeningStatus("برای صحبت، اجازه میکروفن لازم است")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST_CODE)
            return
        }
        speech.listen(onState = vm::setListeningStatus, onResult = vm::submit, onError = vm::setListeningStatus)
    }

    private fun startWakeWord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            vm.setListeningStatus("برای بیوک، اجازه میکروفن لازم است")
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), MIC_WAKE_REQUEST_CODE)
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, HotwordService::class.java))
        vm.setListeningStatus("بیوک روشن شد؛ می‌تونی اپ رو ببندی")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            MIC_REQUEST_CODE -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginListening()
                else vm.setListeningStatus("اجازه میکروفن داده نشده؛ از Settings > Apps > Assistant > Permissions فعالش کن")
            }
            MIC_WAKE_REQUEST_CODE -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startWakeWord()
                else vm.setListeningStatus("بدون اجازه میکروفن، بیوک نمی‌تونه در پس‌زمینه گوش بده")
            }
        }
    }

    @Composable
    private fun AssistantScreen(vm: AssistantViewModel) {
        var command by remember { mutableStateOf("") }
        var url by remember { mutableStateOf(vm.serverUrl) }
        var appToken by remember { mutableStateOf(vm.appToken) }
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

        vm.pendingConfirmation?.let { pending ->
            AlertDialog(
                onDismissRequest = { },
                title = { Text(pending.title) },
                text = { Text(pending.detail) },
                confirmButton = { Button(onClick = { vm.resolveConfirmation(true) }) { Text("تأیید و اجرا") } },
                dismissButton = { TextButton(onClick = { vm.resolveConfirmation(false) }) { Text("لغو") } }
            )
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Assistant", style = MaterialTheme.typography.headlineLarge)
            Text("v0.2.3 • ${vm.status}", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value = command, onValueChange = { command = it }, label = { Text("دستور") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { beginListening() }, enabled = !vm.busy) { Icon(Icons.Default.Mic, contentDescription = null); Text(" صحبت") }
                Button(onClick = { vm.submit(command); command = "" }, enabled = !vm.busy && command.isNotBlank()) { Icon(Icons.Default.Send, contentDescription = null); Text(" اجرا") }
            }
            if (vm.lastUserText.isNotBlank()) Text("تو: ${vm.lastUserText}")
            if (vm.lastAssistantText.isNotBlank()) Text("دستیار: ${vm.lastAssistantText}")
            Text("صدای پاسخ با voice ابری تولید می‌شود؛ اگر در دسترس نباشد، اپ از صدای محلی گوشی استفاده می‌کند.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text("تنظیمات", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Server URL (HTTPS برای استفاده واقعی)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { vm.saveServerUrl(url) }) { Text("ذخیره آدرس سرور") }
            OutlinedTextField(value = appToken, onValueChange = { appToken = it }, label = { Text("App connection token") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { vm.saveAppToken(appToken) }) { Text("ذخیره امن توکن اتصال") }
            Button(onClick = { permissionLauncher.launch(runtimePermissions()) }) { Text("دادن Permissionهای پایه") }
            Button(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("فعال‌کردن Notification Access") }
            Button(onClick = { requestExactAlarmAccess() }) { Text("اجازه Exact Alarm") }

            Text("بیوک", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { startWakeWord() }) { Text("روشن‌کردن بیوک") }
                Button(onClick = {
                    stopService(Intent(this@MainActivity, HotwordService::class.java))
                    vm.setListeningStatus("بیوک خاموش شد")
                }) { Text("خاموش‌کردن") }
            }
            Text("بعد از روشن‌کردن، می‌تونی اپ رو ببندی یا گوشی رو قفل کنی و بگی «بیوک». علامت میکروفن Android هنگام گوش‌دادن دائمی طبیعی و اجباری است. بعد از ری‌استارت گوشی، بیوک را یک بار دوباره روشن کن.", style = MaterialTheme.typography.bodySmall)
            Text("تماس و SMS قبل از اجرا روی خود گوشی تأیید می‌خواهند.")
        }
    }

    private fun runtimePermissions(): Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.SEND_SMS)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_AUDIO)
        } else add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }.toTypedArray()

    private fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(AlarmManager::class.java)
            if (!am.canScheduleExactAlarms()) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = android.net.Uri.parse("package:$packageName")
            })
        }
    }

    override fun onDestroy() {
        speech.stop()
        super.onDestroy()
    }
}
