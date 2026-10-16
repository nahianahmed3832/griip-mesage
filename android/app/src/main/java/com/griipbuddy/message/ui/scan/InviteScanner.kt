package com.griipbuddy.message.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.griipbuddy.message.ui.theme.Griip
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen camera scanner for invite QR codes (T4.2). Frames are decoded on the phone with zxing;
 * nothing leaves the device. Only Griip invite codes are accepted ([InviteCodes]); other QR codes,
 * web links included, are reported and ignored. The camera permission is asked through Android's own
 * dialog, and typing the code stays available if it's refused.
 */
@Composable
fun InviteScanner(onCode: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var notInvite by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; denied = !ok }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            val executor = remember { Executors.newSingleThreadExecutor() }
            val found = remember { AtomicBoolean(false) }
            val providerFuture = remember { ProcessCameraProvider.getInstance(context) }
            DisposableEffect(Unit) {
                onDispose {
                    runCatching { if (providerFuture.isDone) providerFuture.get().unbindAll() }
                    executor.shutdown()
                }
            }
            AndroidView(
                factory = { ctx ->
                    val view = PreviewView(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        analysis.setAnalyzer(executor) { image ->
                            val text = image.use {
                                val plane = it.planes[0]
                                val buf = plane.buffer
                                val bytes = ByteArray(buf.remaining()).also { b -> buf.get(b) }
                                QrDecoder.decode(bytes, plane.rowStride, it.width, it.height)
                            } ?: return@setAnalyzer
                            val code = InviteCodes.fromScan(text)
                            val main = ContextCompat.getMainExecutor(ctx)
                            if (code == null) {
                                main.execute { notInvite = true }
                            } else if (found.compareAndSet(false, true)) {
                                main.execute { onCode(code) }
                            }
                        }
                        try {
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        } catch (e: Exception) {
                            Log.w("InviteScanner", "camera bind failed: ${e.javaClass.simpleName}")
                            denied = true
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    view
                },
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.align(Alignment.Center).size(240.dp).border(3.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(24.dp)))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val message = when {
                denied -> "The camera isn't available. You can type the invite code instead."
                notInvite -> "That QR code isn't a Griip invite. Point the camera at the invite's code."
                else -> "Point the camera at the invite QR code."
            }
            Text(message, style = Griip.type.body, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
        }
        IconButton(onClose, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Close scanner", tint = Color.White)
        }
    }
}
