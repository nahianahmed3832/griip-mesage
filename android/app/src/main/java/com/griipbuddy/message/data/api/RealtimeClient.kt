package com.griipbuddy.message.data.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.math.min
import kotlin.random.Random

enum class ConnState { Disconnected, Connecting, Connected, Offline }

/**
 * Single authenticated WebSocket to the relay with automatic reconnect (exponential backoff with
 * jitter, immediate retry when the network returns). Frames are surfaced as a hot flow.
 */
class RealtimeClient(
    context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val baseUrl: () -> String,
    private val token: () -> String?,
    private val reauth: suspend () -> Boolean,
) {
    private val _state = MutableStateFlow(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state
    private val _frames = MutableSharedFlow<RealtimeFrame>(extraBufferCapacity = 256)
    val frames: SharedFlow<RealtimeFrame> = _frames

    private var ws: WebSocket? = null
    private var wanted = false
    private var attempt = 0
    private var reconnectJob: Job? = null
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (wanted && _state.value != ConnState.Connected) reconnectNow()
            }
            override fun onLost(network: Network) {
                if (!hasNetwork()) _state.value = ConnState.Offline
            }
        })
    }

    private fun hasNetwork(): Boolean =
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    @Synchronized
    fun start() {
        wanted = true
        if (ws == null) open()
    }

    @Synchronized
    fun stop() {
        wanted = false
        reconnectJob?.cancel()
        ws?.close(1000, "bye")
        ws = null
        _state.value = ConnState.Disconnected
    }

    @Synchronized
    fun reconnectNow() {
        reconnectJob?.cancel()
        attempt = 0
        ws?.cancel()
        ws = null
        if (wanted) open()
    }

    fun send(text: String): Boolean = ws?.send(text) ?: false

    @Synchronized
    private fun open() {
        val t = token()
        if (t == null) {
            scheduleReconnect(needsAuth = true)
            return
        }
        _state.value = if (hasNetwork()) ConnState.Connecting else ConnState.Offline
        val url = baseUrl().trimEnd('/').replaceFirst(Regex("^http"), "ws") + "/v1/ws"
        val req = Request.Builder().url(url).header("Authorization", "Bearer $t").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = runCatching { GriipJson.decodeFromString(RealtimeFrame.serializer(), text) }.getOrNull() ?: return
                if (frame.t == "hello") {
                    attempt = 0
                    _state.value = ConnState.Connected
                }
                _frames.tryEmit(frame)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onDrop(webSocket, code == 4401 || code == 4001)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("Realtime", "socket failure: ${t.javaClass.simpleName}")
                onDrop(webSocket, response?.code == 401)
            }
        })
    }

    @Synchronized
    private fun onDrop(socket: WebSocket, authProblem: Boolean) {
        if (ws !== socket) return
        ws = null
        if (!wanted) {
            _state.value = ConnState.Disconnected
            return
        }
        _state.value = if (hasNetwork()) ConnState.Connecting else ConnState.Offline
        scheduleReconnect(authProblem)
    }

    private fun scheduleReconnect(needsAuth: Boolean) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val base = min(30_000L, 1000L shl min(attempt, 5))
            attempt++
            delay(base / 2 + Random.nextLong(base / 2 + 1))
            if (needsAuth) reauth()
            synchronized(this@RealtimeClient) { if (wanted && ws == null) open() }
        }
    }
}
