package com.meldiq.android.ui.screens.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meldiq.android.R
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Batch B10 (Discord's mic test and video preview): check the microphone (a live level) and the camera (front or back
 * preview, mirrored only for the front camera, as calls show it) before a call. Nothing is recorded or sent.
 */
@Composable
fun PreCallCheckSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val c = Griip.colors
    fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var micOk by remember { mutableStateOf(has(Manifest.permission.RECORD_AUDIO)) }
    var camOk by remember { mutableStateOf(has(Manifest.permission.CAMERA)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        micOk = r[Manifest.permission.RECORD_AUDIO] ?: micOk
        camOk = r[Manifest.permission.CAMERA] ?: camOk
    }
    LaunchedEffect(Unit) { if (!micOk || !camOk) ask.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)) }
    var front by remember { mutableStateOf(true) }
    GlassSheet(onDismiss, title = stringResource(R.string.b10_check)) {
        Column {
            Text(stringResource(R.string.b10_mic_level), style = Griip.type.bodyMedium, color = c.textPrimary)
            if (micOk) MicLevel() else Text(stringResource(R.string.b10_need_mic), style = Griip.type.caption, color = c.textSecondary)
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.b10_camera_preview), style = Griip.type.bodyMedium, color = c.textPrimary)
            if (camOk) {
                CameraPreview(front, Modifier.fillMaxWidth().aspectRatio(3f / 4f).padding(top = 6.dp).clip(Shapes.card))
                PillButton(
                    stringResource(if (front) R.string.b10_use_back else R.string.b10_use_front), { front = !front },
                    Modifier.padding(top = 8.dp), tone = ButtonTone.Glass, height = 40.dp, showKnob = false,
                )
            } else Text(stringResource(R.string.b10_need_camera), style = Griip.type.caption, color = c.textSecondary)
            Text(stringResource(R.string.b10_check_private), style = Griip.type.caption, color = c.textTertiary, modifier = Modifier.padding(vertical = 10.dp))
        }
    }
}

/** A bar that follows the microphone's loudness (RMS of 16-bit samples, eased). */
@SuppressLint("MissingPermission") // granted before this is shown
@Composable
private fun MicLevel() {
    val c = Griip.colors
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val rate = 16_000
            val size = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048)
            val rec = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size) }.getOrNull()
                ?: return@withContext
            val buf = ShortArray(size / 2)
            try {
                rec.startRecording()
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) level = MicMeter.level(buf, n)
                }
            } catch (e: Exception) {
                Log.w("PreCallCheck", "mic level stopped: ${e.javaClass.simpleName}")
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }
    }
    val shown by animateFloatAsState(level, label = "mic")
    Box(Modifier.fillMaxWidth().height(12.dp).padding(top = 6.dp).clip(Shapes.pill).background(c.surfaceVariant).semantics { contentDescription = "Microphone level" }) {
        Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).fillMaxHeight().background(if (shown > 0.05f) c.success else c.textTertiary))
    }
}

@Composable
private fun CameraPreview(front: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
    DisposableEffect(Unit) { onDispose { runCatching { if (providerFuture.isDone) providerFuture.get().unbindAll() } } }
    AndroidView(
        factory = { ctx -> PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } },
        update = { view ->
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, preview)
                }.onFailure { Log.w("PreCallCheck", "camera bind failed: ${it.javaClass.simpleName}") }
            }, ContextCompat.getMainExecutor(context))
        },
        // CameraX mirrors the front camera's preview and not the back one's, as calls do (CallMediaPolicy.mirrorPreview).
        modifier = modifier,
    )
}

/** The microphone meter's arithmetic (unit-tested): loudness 0–1 from 16-bit samples, on a square-root curve. */
object MicMeter {
    fun level(samples: ShortArray, n: Int): Float {
        if (n <= 0) return 0f
        var sum = 0.0
        for (i in 0 until n) sum += samples[i].toDouble() * samples[i]
        val rms = sqrt(sum / n) / Short.MAX_VALUE
        return sqrt(rms).toFloat().coerceIn(0f, 1f)
    }
}
