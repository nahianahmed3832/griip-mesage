package com.griipbuddy.message.ui.screens.calls

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.util.Log
import android.widget.Toast
import com.griipbuddy.message.BuildConfig
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PhoneInTalk
import androidx.compose.material.icons.rounded.ScreenShare
import androidx.compose.material.icons.rounded.StopScreenShare
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.griipbuddy.message.call.CallPhase
import com.griipbuddy.message.call.CallUiState
import com.griipbuddy.message.call.MediaSecurity
import com.griipbuddy.message.call.ParticipantUi
import com.griipbuddy.message.ui.LocalContainer
import com.griipbuddy.message.ui.TimeFmt
import com.griipbuddy.message.ui.components.Avatar
import com.griipbuddy.message.ui.components.CallControlButton
import com.griipbuddy.message.ui.components.ConfirmDialog
import com.griipbuddy.message.ui.components.pressable
import com.griipbuddy.message.ui.theme.Griip
import com.griipbuddy.message.ui.theme.Shapes
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Asks for microphone (and camera for video) before any call action. */
@Composable
fun rememberCallPermission(): (video: Boolean, onGranted: () -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.RECORD_AUDIO] == true) pending?.invoke()
        else Toast.makeText(ctx, "Microphone permission is required for calls", Toast.LENGTH_LONG).show()
        pending = null
    }
    return { video, onGranted ->
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
            if (android.os.Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        }.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) onGranted() else {
            pending = onGranted
            launcher.launch(needed.toTypedArray())
        }
    }
}

private val StageTop = Color(0xFF123A3B)
private val StageBottom = Color(0xFF061314)

@Composable
fun CallOverlay() {
    val container = LocalContainer.current
    val calls = container.calls
    val s by calls.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val permission = rememberCallPermission()
    var minimized by remember { mutableStateOf(false) }
    var explainShare by remember { mutableStateOf(false) }
    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) calls.startScreenShare(r.data!!)
        else Toast.makeText(ctx, "Screen sharing cancelled", Toast.LENGTH_SHORT).show()
    }
    BackHandler(enabled = s.phase != CallPhase.Idle && !minimized && s.phase != CallPhase.Incoming) { minimized = true }
    LaunchedEffect(s.phase) { if (s.phase == CallPhase.Incoming) minimized = false }

    if (minimized && s.phase != CallPhase.Incoming) {
        Box(Modifier.fillMaxSize()) { MiniCallBar(s, onExpand = { minimized = false }, onEnd = { calls.hangup() }) }
        return
    }

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(StageTop, StageBottom)))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {},
    ) {
        val remote = s.participants.filter { !it.isLocal }
        val local = s.participants.firstOrNull { it.isLocal }
        val screen = s.participants.firstOrNull { it.screen != null && !it.isLocal }
        val hasVideo = s.phase == CallPhase.Connected && (screen != null || remote.any { it.camera != null })
        when {
            hasVideo && (screen != null || remote.size == 1) -> {
                val main = screen ?: remote.first()
                VideoTile(main.screen ?: main.camera, Modifier.fillMaxSize(), fit = screen != null)
                if (screen != null) {
                    Text(
                        "${screen.name} is sharing their screen", style = Griip.type.label, color = Color.White,
                        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 70.dp).clip(Shapes.pill).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            s.phase == CallPhase.Connected && remote.size > 1 -> {
                LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().statusBarsPadding().padding(top = 96.dp, bottom = 190.dp, start = 10.dp, end = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(s.participants, key = { it.identity }) { p -> ParticipantTile(p, Modifier.aspectRatio(0.8f)) }
                }
            }
            else -> CallerHero(s, Modifier.align(Alignment.Center))
        }
        val gridLayout = s.phase == CallPhase.Connected && remote.size > 1 && !(hasVideo && screen != null)
        // Floating self-preview only in 1:1 / screen-share layouts; the grid already has a "You" tile.
        if (local?.camera != null && !gridLayout) {
            VideoTile(local.camera, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 80.dp, end = 16.dp).size(110.dp, 150.dp).clip(Shapes.tile).border(2.dp, Color.White.copy(alpha = 0.7f), Shapes.tile), mirror = true)
        }

        // Top status
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.phase != CallPhase.Incoming) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).pressable({ minimized = true }, onClickLabel = "Minimize call"), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.CloseFullscreen, "Minimize call", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            SecurityPill(s)
        }

        // Bottom: share indicator + controls
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            s.error?.let {
                Text(it, style = Griip.type.label, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 10.dp).clip(Shapes.tile).background(Griip.colors.danger.copy(alpha = 0.85f)).pressable({ calls.clearError() }).padding(horizontal = 14.dp, vertical = 8.dp))
            }
            if (s.screenSharing) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(Shapes.tile).background(Griip.colors.danger.copy(alpha = 0.92f)).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White))
                    Spacer(Modifier.width(10.dp))
                    Text("You're sharing your screen", style = Griip.type.bodyMedium, color = Color.White, modifier = Modifier.weight(1f))
                    Text("Stop", style = Griip.type.button, color = Color.White, modifier = Modifier.clip(Shapes.pill).background(Color.White.copy(alpha = 0.22f)).pressable({ calls.stopScreenShare() }).padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
            if (s.phase == CallPhase.Incoming) {
                Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CallControlButton(Icons.Rounded.CallEnd, "Decline", { calls.decline() }, danger = true)
                    CallControlButton(if (s.video) Icons.Rounded.Videocam else Icons.Rounded.Call, "Accept", { permission(s.video) { scope.launch { calls.accept() } } }, active = true)
                }
            } else if (s.phase != CallPhase.Ended) {
                Row(
                    Modifier.fillMaxWidth().clip(Shapes.hero).background(Color.White.copy(alpha = 0.10f)).border(1.dp, Color.White.copy(alpha = 0.14f), Shapes.hero).padding(vertical = 14.dp, horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CallControlButton(if (s.micOn) Icons.Rounded.Mic else Icons.Rounded.MicOff, if (s.micOn) "Mute" else "Unmute", { calls.toggleMic() }, active = !s.micOn)
                    CallControlButton(if (s.cameraOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, "Camera", {
                        if (s.cameraOn) calls.toggleCamera() else permission(true) { calls.toggleCamera() }
                    }, active = s.cameraOn)
                    if (s.cameraOn) CallControlButton(Icons.Rounded.Cameraswitch, "Flip", { calls.flipCamera() })
                    else CallControlButton(Icons.AutoMirrored.Rounded.VolumeUp, "Speaker", { calls.setSpeaker(!s.speakerOn) }, active = s.speakerOn)
                    CallControlButton(if (s.screenSharing) Icons.Rounded.StopScreenShare else Icons.Rounded.ScreenShare, if (s.screenSharing) "Stop share" else "Share", {
                        if (s.screenSharing) calls.stopScreenShare() else explainShare = true
                    }, active = s.screenSharing, enabled = s.phase == CallPhase.Connected || s.phase == CallPhase.Outgoing)
                    CallControlButton(Icons.Rounded.CallEnd, "End", { calls.hangup() }, danger = true)
                }
            }
        }
    }
    if (explainShare) ConfirmDialog(
        "Share your screen?",
        "Everyone in this call will see everything on your screen — including notifications and any app you open — until you tap Stop. Android will ask for your permission next.",
        "Continue",
        onConfirm = {
            explainShare = false
            val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
            projection.launch(mpm.createScreenCaptureIntent())
        },
        onDismiss = { explainShare = false },
    )
}

@Composable
private fun SecurityPill(s: CallUiState) {
    val c = Griip.colors
    val (icon, text, tint) = when (s.security) {
        MediaSecurity.E2eeActive -> Triple(Icons.Rounded.GppGood, "End-to-end encrypted", c.primaryBright)
        MediaSecurity.E2eeProblem -> Triple(Icons.Rounded.GppMaybe, "Encryption problem", c.warning)
        MediaSecurity.TransportOnly -> Triple(Icons.Rounded.GppMaybe, "Not end-to-end encrypted", c.warning)
        // Not verified: no decrypted media proves the key yet. A peer with the wrong key stays here, because
        // WebRTC doesn't report those failures (MediaSecurityTracker), so this must not look like success.
        MediaSecurity.Unknown -> Triple(Icons.Rounded.Shield, if (s.waitingForKey) "Getting encryption key…" else "Verifying encryption…", Color.White.copy(alpha = 0.8f))
    }
    Row(Modifier.clip(Shapes.pill).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = Griip.type.caption, color = Color.White)
    }
}

@Composable
private fun CallerHero(s: CallUiState, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "pulse")
    val pulse by t.animateFloat(1f, 1.18f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "p")
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.connectedAt) { while (s.connectedAt != null) { now = System.currentTimeMillis(); delay(1000) } }
    Column(modifier.padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            val ringing = s.phase == CallPhase.Incoming || s.phase == CallPhase.Outgoing || s.phase == CallPhase.Connecting
            Box(Modifier.size(170.dp).scale(if (ringing) pulse else 1f).clip(CircleShape).background(Griip.colors.primaryBright.copy(alpha = 0.16f)))
            Box(Modifier.size(140.dp).clip(CircleShape).background(Griip.colors.primaryBright.copy(alpha = 0.18f)))
            Avatar(s.title, s.colorIndex, size = 112.dp)
        }
        Spacer(Modifier.height(24.dp))
        Text(s.title, style = Griip.type.display, color = Color.White, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        val status = when (s.phase) {
            CallPhase.Incoming -> s.subtitle ?: if (s.video) "Incoming video call" else "Incoming voice call"
            CallPhase.Outgoing -> if (s.isGroup) "Waiting for others to join…" else "Calling…"
            CallPhase.Connecting -> "Connecting…"
            CallPhase.Reconnecting -> "Reconnecting…"
            CallPhase.Connected -> s.connectedAt?.let { TimeFmt.duration((now - it) / 1000) } ?: "Connected"
            CallPhase.Ended -> when (s.endReason) { "declined" -> "Call declined"; "missed" -> "No answer"; "error" -> "Call failed"; else -> "Call ended" }
            else -> ""
        }
        Text(status, style = Griip.type.body, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
        if (s.phase == CallPhase.Connected && s.participants.size > 1) {
            Spacer(Modifier.height(6.dp))
            Text("${s.participants.size} in call", style = Griip.type.caption, color = Color.White.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun ParticipantTile(p: ParticipantUi, modifier: Modifier) {
    val c = Griip.colors
    Box(modifier.clip(Shapes.card).background(Color.White.copy(alpha = 0.08f)).border(if (p.speaking) 2.dp else 1.dp, if (p.speaking) c.primaryBright else Color.White.copy(alpha = 0.12f), Shapes.card)) {
        // Your own tile shows your camera: drawing your own screen share into the screen being shared recurses.
        val screen = p.screen.takeIf { !p.isLocal }
        val track = screen ?: p.camera
        if (track != null) VideoTile(track, Modifier.fillMaxSize(), mirror = p.isLocal, fit = screen != null)
        else Avatar(p.name, p.userId.hashCode(), Modifier.align(Alignment.Center), size = 64.dp)
        Row(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(Shapes.pill).background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!p.micOn) { Icon(Icons.Rounded.MicOff, "Muted", tint = Color.White, modifier = Modifier.size(12.dp)); Spacer(Modifier.width(4.dp)) }
            Text(if (p.isLocal) "You" else p.name, style = Griip.type.caption, color = Color.White, maxLines = 1)
        }
    }
}

/**
 * LiveKit video sink rendered through a TextureView so it composes with rounded clipping.
 * [VideoSinkBinding] owns the renderer: the sink is attached only while the screen is started (frames pushed into a
 * destroyed surface, e.g. under the screen-share consent dialog, crashed WebRTC natively), and the renderer is
 * released only when the view itself is removed.
 */
@Composable
fun VideoTile(track: VideoTrack?, modifier: Modifier, mirror: Boolean = false, fit: Boolean = false) {
    val container = LocalContainer.current
    val room by container.calls.roomFlow.collectAsStateWithLifecycle()
    val r0 = room
    if (track == null || r0 == null) {
        Box(modifier.background(Color.Black.copy(alpha = 0.3f)))
        return
    }
    key(r0) {
        val binding = remember {
            VideoSinkBinding<VideoTrack, TextureViewRenderer>(
                addSink = { t, r ->
                    runCatching { t.addRenderer(r) }
                    if (BuildConfig.DEBUG) Log.i("CallDiag", "renderer attach ${t.javaClass.simpleName} sid=${t.sid?.takeLast(6)} view=${r.width}x${r.height} shown=${r.isShown}")
                },
                removeSink = { t, r ->
                    runCatching { t.removeRenderer(r) }
                    if (BuildConfig.DEBUG) Log.i("CallDiag", "renderer detach sid=${t.sid?.takeLast(6)}")
                },
                releaseRenderer = { r ->
                    runCatching { r.release() }
                    if (BuildConfig.DEBUG) Log.i("CallDiag", "renderer released")
                },
            )
        }
        // The renderer letterboxes (FIT) only when measured with loose constraints, so a fitted tile is centred in a
        // Box instead of being forced to the tile's exact size, which crops the frame.
        Box(modifier, contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx -> TextureViewRenderer(ctx).also { r -> r0.initVideoRenderer(r); binding.setRenderer(r) } },
                update = { r ->
                    r.setMirror(mirror)
                    r.setScalingType(if (fit) livekit.org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT else livekit.org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                },
                onRelease = { binding.release() },
                modifier = if (fit) Modifier else Modifier.fillMaxSize(),
            )
        }
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        DisposableEffect(track, lifecycle) {
            binding.bind(track)
            val observer = androidx.lifecycle.LifecycleEventObserver { _, e ->
                binding.setStarted(e.targetState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                binding.bind(null)
            }
        }
    }
}

@Composable
private fun BoxScope.MiniCallBar(s: CallUiState, onExpand: () -> Unit, onEnd: () -> Unit) {
    val c = Griip.colors
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.connectedAt) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    Row(
        Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(Shapes.pill).background(Brush.horizontalGradient(listOf(c.heroStart, c.heroEnd))).pressable(onExpand, onClickLabel = "Return to call")
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.PhoneInTalk, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "${s.title} · ${s.connectedAt?.let { TimeFmt.duration((now - it) / 1000) } ?: "connecting"}" + if (s.screenSharing) " · sharing" else "",
            style = Griip.type.label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        Box(Modifier.size(34.dp).clip(CircleShape).background(c.danger).pressable(onEnd, onClickLabel = "End call"), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.CallEnd, "End call", tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}
