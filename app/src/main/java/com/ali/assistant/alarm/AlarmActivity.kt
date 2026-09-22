package com.ali.assistant.alarm

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.FaceDetector
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            MaterialTheme {
                AlarmScreen(
                    label = intent.getStringExtra("label") ?: "Wake up",
                    strict = intent.getBooleanExtra("strict", false),
                    challenge = intent.getStringExtra("challenge") ?: "NONE",
                    dismiss = { dismissAlarm() },
                    createPhotoUri = { createChallengePhotoUri() },
                    containsFace = { uri -> containsFace(uri) },
                )
            }
        }
    }

    private fun createChallengePhotoUri(): android.net.Uri {
        val dir = File(cacheDir, "alarm_challenges").apply { mkdirs() }
        val file = File(dir, "challenge-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

    private fun containsFace(uri: android.net.Uri): Boolean {
        return runCatching {
            contentResolver.openInputStream(uri).use { stream ->
                val decoded = BitmapFactory.decodeStream(stream) ?: return@runCatching false
                val width = if (decoded.width % 2 == 0) decoded.width else decoded.width - 1
                val scaled = Bitmap.createScaledBitmap(decoded, width.coerceAtLeast(2), decoded.height, true)
                val rgb565 = scaled.copy(Bitmap.Config.RGB_565, false)
                val detector = FaceDetector(rgb565.width, rgb565.height, 2)
                val faces = arrayOfNulls<FaceDetector.Face>(2)
                detector.findFaces(rgb565, faces) > 0
            }
        }.getOrDefault(false)
    }

    private fun dismissAlarm() {
        stopService(Intent(this, AlarmSoundService::class.java))
        finishAndRemoveTask()
    }
}

@Composable
private fun AlarmScreen(
    label: String,
    strict: Boolean,
    challenge: String,
    dismiss: () -> Unit,
    createPhotoUri: () -> android.net.Uri,
    containsFace: (android.net.Uri) -> Boolean,
) {
    BackHandler(enabled = true) { }
    var photoUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var message by remember { mutableStateOf("") }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = photoUri
        if (success && uri != null && containsFace(uri)) {
            dismiss()
        } else {
            message = if (success) "چهره تشخیص داده نشد؛ یک عکس واضح‌تر بگیر." else "عکس ثبت نشد؛ آلارم ادامه دارد."
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("⏰", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(18.dp))
        Text(label, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        if (!strict) {
            Button(onClick = dismiss) { Text("خاموش کردن") }
        } else {
            Text(if (challenge.equals("SELFIE", true)) "برای خاموش شدن آلارم باید همین الان یک عکس تازه بگیری." else "این آلارم سخت‌گیرانه است و باید Challenge را انجام بدهی.", textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            Button(onClick = {
                val uri = createPhotoUri()
                photoUri = uri
                camera.launch(uri)
            }) { Text("گرفتن عکس تازه") }
            if (message.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(message) }
        }
    }
}
