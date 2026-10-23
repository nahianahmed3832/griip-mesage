package com.meldiq.android.ui.media

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.meldiq.android.R
import com.meldiq.android.data.model.MediaLinks
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.theme.Griip

/** Hosts the player may show in its own frame; anything else (youtube.com, channel pages, ads) opens outside Meldiq. */
private val PLAYER_HOSTS = listOf("youtube-nocookie.com")

/**
 * A YouTube video inside Meldiq (2026-10-23, the owner's request after the reference's in-chat player). YouTube's
 * privacy-enhanced player loads only when someone taps play, never for a message just scrolling by. The web view is
 * locked down: no file or content access, no JavaScript bridge, and a page other than the player opens in the browser.
 * Under the video: who sent it, when, the message's text and React, like the photo viewer.
 */
@Composable
fun YouTubePlayerDialog(
    videoId: String,
    sender: String?,
    sentAt: Long?,
    caption: String?,
    onReact: ((String) -> Unit)?,
    onOpenOutside: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val embed = MediaLinks.embedUrl(videoId) ?: return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.ux_close), tint = Color.White) }
                Spacer(Modifier.weight(1f))
                IconButton({ onOpenOutside("https://www.youtube.com/watch?v=$videoId") }) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.media_open_in, "YouTube"), tint = Color.White)
                }
            }
            PlayerView(embed, onOpenOutside, Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(16f / 9f))
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f))
                    .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                val who = listOfNotNull(sender, sentAt?.let { TimeFmt.dayHeader(it) + ", " + TimeFmt.clock(it) }).joinToString("  ·  ")
                if (who.isNotEmpty()) Text(who, style = Griip.type.label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                caption?.let { Text(it, style = Griip.type.bodySmall, color = Color.White.copy(alpha = 0.9f), maxLines = 3, overflow = TextOverflow.Ellipsis) }
                if (onReact != null) QuickReact(onReact, Modifier.padding(top = 8.dp))
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PlayerView(embed: String, onOpenOutside: (String) -> Unit, modifier: Modifier) {
    // An iframe in a page of our own (the player refuses to start without an embedding page) on a fixed base address.
    val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000}iframe{border:0;width:100%;height:100%}</style></head>
        <body><iframe src="$embed" allow="autoplay; encrypted-media; picture-in-picture" allowfullscreen
        referrerpolicy="strict-origin-when-cross-origin"></iframe></body></html>"""
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportMultipleWindows(false)
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame) return false
                        val host = request.url.host?.lowercase().orEmpty()
                        if (PLAYER_HOSTS.any { host == it || host.endsWith(".$it") }) return false
                        if (request.url.scheme == "https") onOpenOutside(request.url.toString())
                        return true
                    }
                }
                loadDataWithBaseURL("https://meldiq.app/", html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
        modifier = modifier,
    )
}

/** React with one of the quick reactions, as in the photo viewer. */
@Composable
fun QuickReact(onReact: (String) -> Unit, modifier: Modifier = Modifier) {
    var picking by remember { mutableStateOf(false) }
    var reacted by remember { mutableStateOf<String?>(null) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (!picking) {
            Row(
                Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.14f))
                    .clickable(onClickLabel = stringResource(R.string.ux_react)) { picking = true }.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(reacted ?: "☺", color = Color.White)
                Text("  " + stringResource(R.string.ux_react), style = Griip.type.label, color = Color.White)
            }
        } else com.meldiq.android.data.repo.DOUBLE_TAP_EMOJI.forEach { e ->
            Text(e, style = Griip.type.title, modifier = Modifier.clip(CircleShape).clickable { onReact(e); reacted = e; picking = false }.padding(6.dp))
        }
    }
}
