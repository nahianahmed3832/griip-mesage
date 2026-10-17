package com.griipbuddy.orbit.call

import android.content.Context
import android.content.Intent
import android.util.Log
import com.griipbuddy.orbit.crypto.b64
import com.griipbuddy.orbit.crypto.randomBytes
import com.griipbuddy.orbit.data.api.ApiClient
import com.griipbuddy.orbit.data.api.ApiException
import com.griipbuddy.orbit.data.api.CallDto
import com.griipbuddy.orbit.data.api.CallRes
import com.griipbuddy.orbit.data.api.CallsRes
import com.griipbuddy.orbit.data.api.DeclineCallReq
import com.griipbuddy.orbit.data.api.MediaGrant
import com.griipbuddy.orbit.data.api.StartCallReq
import com.griipbuddy.orbit.data.db.CallLogEntity
import com.griipbuddy.orbit.data.db.GriipDatabase
import com.griipbuddy.orbit.data.repo.ConversationRepository
import com.griipbuddy.orbit.data.repo.MessageRepository
import com.griipbuddy.orbit.data.repo.SessionRepository
import com.griipbuddy.orbit.data.repo.SettingsStore
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.LiveKit
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKitOverrides
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.track.VideoPreset169
import livekit.org.webrtc.RtpParameters
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import io.livekit.android.RoomOptions
import io.livekit.android.e2ee.BaseKeyProvider
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.e2ee.E2EEState
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.screencapture.ScreenCaptureParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class CallPhase { Idle, Incoming, Outgoing, Connecting, Connected, Reconnecting, Ended }

enum class MediaSecurity { Unknown, E2eeActive, E2eeProblem, TransportOnly }

data class ParticipantUi(
    val identity: String,
    val userId: String,
    val name: String,
    val isLocal: Boolean,
    val speaking: Boolean,
    val micOn: Boolean,
    val camera: VideoTrack?,
    val screen: VideoTrack?,
    /** The camera is published and on, even if its video hasn't arrived yet ("connecting video"). */
    val cameraOn: Boolean = camera != null,
    /** LiveKit rates this person's connection poor or lost. */
    val connectionWeak: Boolean = false,
    /** This person's media reports an encryption failure (never hidden to make video show). */
    val e2eeProblem: Boolean = false,
)

data class CallUiState(
    val phase: CallPhase = CallPhase.Idle,
    val callId: String? = null,
    val conversationId: String? = null,
    val title: String = "",
    val subtitle: String? = null,
    val colorIndex: Int = 0,
    val video: Boolean = false,
    val isGroup: Boolean = false,
    val micOn: Boolean = true,
    val cameraOn: Boolean = false,
    val screenSharing: Boolean = false,
    val speakerOn: Boolean = false,
    val participants: List<ParticipantUi> = emptyList(),
    val connectedAt: Long? = null,
    val security: MediaSecurity = MediaSecurity.Unknown,
    val error: String? = null,
    val endReason: String? = null,
    val waitingForKey: Boolean = false,
    /** Outgoing: a recipient device confirmed it is ringing ("Ringing…" instead of "Calling…"). */
    val alerted: Boolean = false,
    /** Whether remote media actually arrives (inbound bytes), separate from the room being connected. */
    val media: MediaFlow = MediaFlow.None,
    /** Redacted network path of the media, e.g. "udp · direct" or "tcp · direct"; null until known. */
    val path: String? = null,
    /** Measured frame sizes, frame rates and bitrates, sent and received (the call's "Connection details"). */
    val stats: CallMediaStats? = null,
    /** LiveKit rates this phone's or the other person's connection poor or lost: video quality is reduced. */
    val connectionWeak: Boolean = false,
    /** The call's verification code, from its end-to-end encryption key (null: the call isn't end-to-end encrypted). */
    val verification: CallVerification.Code? = null,
    /** 1:1: the other person's connection dropped; the call waits [CallRecovery.PEER_WINDOW_MS] for them. */
    val peerReconnecting: Boolean = false,
)

/**
 * Owns the single active LiveKit room. Media E2EE keys are generated by the caller and distributed
 * to member devices through the end-to-end encrypted message channel (MessageRepository.sendCallKey),
 * never through the server's call API. If E2EE is on and no key is available, we refuse to join
 * rather than silently downgrade (docs/SECURITY.md §7).
 */
class CallManager(
    private val context: Context,
    private val api: ApiClient,
    private val db: GriipDatabase,
    private val session: SessionRepository,
    private val messages: MessageRepository,
    private val conversations: ConversationRepository,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state
    private val _room = MutableStateFlow<Room?>(null)
    /** Observed by the call UI; set to null first on teardown so video sinks detach before native release. */
    val roomFlow: StateFlow<Room?> = _room
    var room: Room?
        get() = _room.value
        private set(value) { _room.value = value }
    private var eventsJob: Job? = null
    private var cryptorGuard: FrameCryptorGuard? = null
    /** This call's badge evidence (null without E2EE). Fed from room events and, through [cryptorGuard], WebRTC's signaling thread. */
    @Volatile private var securityTracker: MediaSecurityTracker? = null
    /** Publishing the badge and swapping [securityTracker] happen under this lock, so an ended call's callbacks can't touch the next call. */
    private val securityLock = Any()
    private val diag = CallDiagnostics(scope)
    /** Calls this device already rang, answered, declined or ended: ring recovery never re-rings them. Bounded. */
    private val handled = LinkedHashSet<String>()
    private var mediaJob: Job? = null
    /** The previous call's native teardown; a new call waits for it, so two rooms never hold the microphone and speaker at once. */
    private var releaseJob: Job? = null
    private var audioListener: ((List<AudioDevice>, AudioDevice?) -> Unit)? = null
    private val sampler = VideoStatsSampler()
    /** When each participant identity was first seen in this call: tiles keep their places as people come and go. */
    private val firstSeen = HashMap<String, Long>()
    private var seenCounter = 0L

    /**
     * WebRTC's native library must be loaded before any LiveKit native object exists — including the
     * E2EE key provider, which is created before the Room. LiveKit.init does that (once).
     */
    private val webrtcReady by lazy { LiveKit.init(context.applicationContext) }

    fun start() {
        scope.launch {
            messages.callFrames.collect { f ->
                val call = f.call ?: return@collect
                // Call history: the conversation's entry for this call follows the server's record of it.
                if (f.event == "ring" || f.event == "started" || f.event == "ended") scope.launch { runCatching { messages.recordCall(call) } }
                when (f.event) {
                    "ring" -> onRing(call)
                    "ended" -> if (call.id == _state.value.callId) finish(remoteReason = call.endReason ?: "ended", notifyServer = false)
                    "dismissed" -> if (call.id == _state.value.callId && _state.value.phase == CallPhase.Incoming) finish("answered elsewhere", false)
                    "declined" -> if (call.id == _state.value.callId && !_state.value.isGroup) finish("declined", false)
                    "updated" -> if (call.id == _state.value.callId && call.state == "ACTIVE" && _state.value.phase == CallPhase.Incoming && call.conversationType != "DM") {
                        // Group call already running: keep the banner but stop ringing UI.
                        IncomingCallNotifier.cancel(context)
                    }
                    "alerted" -> if (call.id == _state.value.callId && _state.value.phase == CallPhase.Outgoing) _state.update { it.copy(alerted = true) }
                }
            }
        }
        scope.launch { messages.callInvites.collect { /* key arrival is read from DB when joining */ } }
        // Ring recovery: the server rings only live sockets, so after every reconnect (once the backlog,
        // and with it any call key, has drained) look for calls that started ringing while we were away.
        scope.launch { messages.backlogDrained.collect { drained -> if (drained) recoverRinging() } }
    }

    private suspend fun recoverRinging() {
        val calls = runCatching { api.get<CallsRes>("/v1/calls/active").calls }.getOrNull() ?: return
        val me = session.session?.userId
        val now = System.currentTimeMillis()
        for (c in calls) {
            if (RingRecovery.shouldRing(c, me, now, synchronized(handled) { handled.toSet() }, idle = _state.value.phase == CallPhase.Idle)) {
                Log.i(TAG, "ring recovered after reconnect")
                onRing(c)
            }
        }
    }

    private fun markHandled(id: String?) {
        id ?: return
        synchronized(handled) {
            handled += id
            if (handled.size > 64) handled.remove(handled.first())
        }
    }

    private suspend fun onRing(call: CallDto) {
        if (synchronized(handled) { call.id in handled }) return // a repeated or recovered ring for a call already dealt with
        if (_state.value.phase != CallPhase.Idle) {
            // Busy: tell a 1:1 caller at once instead of leaving them ringing; group calls can be joined later.
            if (call.id != _state.value.callId && call.conversationType == "DM") {
                markHandled(call.id)
                scope.launch { runCatching { api.post<DeclineCallReq, CallRes>("/v1/calls/${call.id}/decline", DeclineCallReq("busy")) } }
            }
            return
        }
        markHandled(call.id)
        val conv = db.conversations().get(call.conversationId) ?: conversations.refreshOne(call.conversationId)
        val me = session.session?.userId
        val title = conv?.let { ConversationRepository.title(it, me) } ?: "Incoming call"
        val caller = db.users().get(call.createdBy)
        _state.value = CallUiState(
            phase = CallPhase.Incoming, callId = call.id, conversationId = call.conversationId,
            title = if (conv?.type == "DM") caller?.displayName ?: title else title,
            subtitle = if (conv?.type == "DM") null else "${caller?.displayName ?: "Someone"} is calling",
            colorIndex = caller?.avatarColor ?: 0, video = call.video, isGroup = conv?.type != "DM",
        )
        db.calls().upsertLog(CallLogEntity(call.id, call.conversationId, call.video, false, call.createdAt, peerName = _state.value.title))
        IncomingCallNotifier.show(context, call.id, _state.value.title, call.video)
        // Tell the caller this device is actually ringing ("Ringing…" rather than "Calling…").
        scope.launch { runCatching { api.post<CallRes>("/v1/calls/${call.id}/alerted") } }
    }

    suspend fun startCall(conversationId: String, video: Boolean) {
        if (_state.value.phase != CallPhase.Idle) return
        val conv = db.conversations().get(conversationId)
        val me = session.session?.userId
        _state.value = CallUiState(
            phase = CallPhase.Outgoing, conversationId = conversationId, title = conv?.let { ConversationRepository.title(it, me) } ?: "Call",
            colorIndex = conv?.let { ConversationRepository.peer(it, me)?.avatarColor } ?: 0, video = video, cameraOn = video,
            isGroup = conv?.type != "DM", speakerOn = video,
        )
        try {
            val res = try {
                api.post<StartCallReq, CallRes>("/v1/calls", StartCallReq(conversationId, video))
            } catch (e: ApiException) {
                scope.launch { runCatching { messages.recordFailedCall(conversationId, video) } }
                throw e
            }
            markHandled(res.call.id)
            _state.update { it.copy(callId = res.call.id, alerted = res.call.alertedAt != null) }
            val key = if (res.joinedExisting) waitForKey(res.call.id) else newKey().also { messages.sendCallKey(conversationId, res.call.id, video, it) }
            db.calls().upsertLog(CallLogEntity(res.call.id, conversationId, video, !res.joinedExisting, System.currentTimeMillis(), peerName = _state.value.title))
            connect(res.call.id, res.media!!, key, video)
        } catch (e: ApiException) {
            fail(e.message)
        }
    }

    suspend fun accept() {
        val s = _state.value
        val callId = s.callId ?: return
        IncomingCallNotifier.cancel(context)
        _state.update { it.copy(phase = CallPhase.Connecting, cameraOn = it.video, speakerOn = it.video) }
        try {
            val res = api.post<CallRes>("/v1/calls/$callId/join")
            val key = waitForKey(callId)
            connect(callId, res.media!!, key, s.video)
        } catch (e: ApiException) {
            fail(CallFailure.forApi(e))
        }
    }

    /** Join an already running group call from a conversation banner. */
    suspend fun join(conversationId: String, callId: String, video: Boolean) {
        if (_state.value.phase != CallPhase.Idle) return
        val conv = db.conversations().get(conversationId)
        _state.value = CallUiState(phase = CallPhase.Connecting, callId = callId, conversationId = conversationId, title = conv?.let { ConversationRepository.title(it, session.session?.userId) } ?: "Call", video = video, cameraOn = video, isGroup = conv?.type != "DM", speakerOn = true)
        try {
            val res = api.post<CallRes>("/v1/calls/$callId/join")
            connect(callId, res.media!!, waitForKey(callId), video)
        } catch (e: ApiException) {
            fail(e.message)
        }
    }

    fun decline() {
        val id = _state.value.callId ?: return
        markHandled(id)
        IncomingCallNotifier.cancel(context)
        scope.launch {
            db.calls().getLog(id)?.let { db.calls().upsertLog(it.copy(endedAt = System.currentTimeMillis(), endReason = "declined")) }
            runCatching { api.post<CallRes>("/v1/calls/$id/decline") }.onSuccess { runCatching { messages.recordCall(it.call) } }
        }
        reset()
    }

    private fun newKey(): String = randomBytes(32).b64()

    /** The key arrives as an E2EE control message; it may land a moment after the ring event. */
    private suspend fun waitForKey(callId: String): String? {
        if (!settings.current.mediaE2ee) return null
        _state.update { it.copy(waitingForKey = true) }
        repeat(40) {
            messages.callKey(callId)?.let { k -> _state.update { it.copy(waitingForKey = false) }; return k }
            delay(250)
        }
        _state.update { it.copy(waitingForKey = false) }
        throw ApiException(0, "no_key", "Couldn't get this call's encryption key, so the call wasn't joined. Ask the caller to try again.")
    }

    private suspend fun connect(callId: String, media: MediaGrant, key: String?, video: Boolean) {
        _state.update { it.copy(phase = if (it.phase == CallPhase.Outgoing) CallPhase.Outgoing else CallPhase.Connecting, callId = callId) }
        // A call right after another: let the previous room finish releasing its audio device first.
        releaseJob?.let { if (it.isActive) diag.log("waiting for the previous call's media to release") ; it.join() }
        val r = try {
            webrtcReady
            _state.update { it.copy(verification = key?.let { k -> CallVerification.code(k, callId) }) }
            val e2ee = key?.let { k ->
                val provider = BaseKeyProvider()
                provider.setSharedKey(k, null)
                E2EEOptions(keyProvider = provider)
            }
            val hwAec = CallMediaPolicy.useHardwareEchoCanceller(settings.current.hardwareEchoCanceller)
            diag.log(CallMediaPolicy.describeAudio(android.os.Build.MODEL, android.os.Build.VERSION.SDK_INT, JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported(), hwAec))
            _state.update { it.copy(stats = CallMediaStats(echoCanceller = if (hwAec && JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()) "phone (hardware)" else "Orbit (WebRTC AEC3)")) }
            LiveKit.create(
                context.applicationContext,
                roomOptions(e2ee),
                LiveKitOverrides(audioOptions = AudioOptions(javaAudioDeviceModuleCustomizer = { b -> b.setUseHardwareAcousticEchoCanceler(hwAec) })),
            )
        } catch (t: Throwable) {
            // UnsatisfiedLinkError etc. are Errors, not Exceptions: never let media setup crash the app.
            Log.e(TAG, "media engine init failed: ${t.javaClass.simpleName}")
            fail("Couldn't start the call engine on this device.")
            return
        }
        room = r
        diag.start(r)
        val tracker = if (key != null) MediaSecurityTracker() else null
        synchronized(securityLock) { securityTracker = tracker }
        cryptorGuard = tracker?.let { t -> FrameCryptorGuard(onState = { k, st -> if (room === r) onE2eeState(t, k, st) }, log = diag::log) }
        _state.update { it.copy(security = if (key != null) MediaSecurity.Unknown else MediaSecurity.TransportOnly) }
        eventsJob = scope.launch { r.events.events.collect { ev -> onRoomEvent(ev) } }
        CallService.start(context, _state.value.title, video)
        val t0 = System.currentTimeMillis()
        try {
            r.connect(media.url, media.token)
        } catch (e: Throwable) {
            Log.w(TAG, "connect failed after ${System.currentTimeMillis() - t0} ms: ${e.javaClass.simpleName}")
            fail(CallFailure.forConnect(e))
            return
        }
        diag.log("media room joined in ${System.currentTimeMillis() - t0} ms")
        startMediaMonitor(r)
        try {
            r.localParticipant.setMicrophoneEnabled(true)
        } catch (e: Throwable) {
            _state.update { it.copy(error = CallFailure.MICROPHONE) }
        }
        if (video) try {
            r.localParticipant.setCameraEnabled(true)
        } catch (e: Throwable) {
            _state.update { it.copy(error = CallFailure.CAMERA) }
        }
        watchAudioRoute(r)
        setSpeaker(_state.value.speakerOn)
        runCatching { api.post<CallRes>("/v1/calls/$callId/connected") }
        _state.update {
            it.copy(
                phase = if (r.remoteParticipants.isEmpty() && !it.isGroup) CallPhase.Outgoing else CallPhase.Connected,
                connectedAt = if (r.remoteParticipants.isNotEmpty() || it.isGroup) System.currentTimeMillis() else null,
            )
        }
        refreshParticipants()
    }

    /**
     * Samples WebRTC inbound byte counters every 2 s (all builds): the UI says media is flowing only when
     * bytes actually arrive, and the redacted network path (udp/tcp/relay) is recorded for diagnosis.
     */
    private fun startMediaMonitor(r: Room) {
        mediaJob?.cancel()
        val monitor = MediaFlowMonitor()
        mediaJob = scope.launch {
            while (isActive && room === r) {
                delay(2000)
                var total: Long? = null
                var path: String? = null
                for (rp in r.remoteParticipants.values) {
                    for ((_, t) in rp.audioTrackPublications + rp.videoTrackPublications) {
                        val report = runCatching { t?.getRTCStats() }.getOrNull() ?: continue
                        val stats = report.statsMap.values
                        val bytes = stats.filter { it.type == "inbound-rtp" }.sumOf { (it.members["bytesReceived"] as? Number)?.toLong() ?: 0L }
                        total = (total ?: 0L) + bytes
                        if (path == null) path = MediaPath.describe(stats)
                    }
                }
                if (room !== r) break
                val before = _state.value
                val flow = monitor.sample(total)
                if (flow != before.media || (path != null && path != before.path)) diag.log("media $flow via ${path ?: before.path ?: "unknown path"}")
                _state.update { if (it.media == flow && (path == null || it.path == path)) it else it.copy(media = flow, path = path ?: it.path) }
                val stats = runCatching { sampleStats(r) }.getOrNull()
                if (room !== r) break
                if (stats != null) {
                    if (++ticks % 3 == 0) diag.log("stats send cam=${stats.sendingCamera?.describe()} screen=${stats.sendingScreen?.describe()} | recv video=${stats.receivingVideo?.describe()} screen=${stats.receivingScreen?.describe()} | route=${stats.audioRoute}")
                    _state.update { it.copy(stats = stats.copy(echoCanceller = it.stats?.echoCanceller)) }
                }
            }
        }
    }

    private var ticks = 0

    /** Sender and receiver video numbers for the largest camera and screen streams, plus the current audio route. */
    private suspend fun sampleStats(r: Room): CallMediaStats {
        val now = System.currentTimeMillis()
        val keys = HashSet<String>()
        suspend fun video(t: Track?, key: String, outbound: Boolean): VideoStats? {
            val report = t?.let { runCatching { it.getRTCStats() }.getOrNull() } ?: return null
            keys += key
            return sampler.sample(key, report.statsMap.values.map { it.type to it.members }, outbound, now)
        }
        var sendCam: VideoStats? = null
        var sendScreen: VideoStats? = null
        for ((pub, t) in r.localParticipant.videoTrackPublications) {
            if (pub.muted) continue
            when (pub.source) {
                Track.Source.CAMERA -> sendCam = video(t, "out:" + pub.sid, true)
                Track.Source.SCREEN_SHARE -> sendScreen = video(t, "out:" + pub.sid, true)
                else -> {}
            }
        }
        var recvVideo: VideoStats? = null
        var recvScreen: VideoStats? = null
        for (rp in r.remoteParticipants.values) for ((pub, t) in rp.videoTrackPublications) {
            if (pub.muted || t == null) continue
            val v = video(t, "in:" + pub.sid, false) ?: continue
            if (pub.source == Track.Source.SCREEN_SHARE) { if (recvScreen == null) recvScreen = v } else if (recvVideo == null) recvVideo = v
        }
        sampler.forget(keys)
        val route = r.audioSwitchHandler?.selectedAudioDevice?.let { audioRouteName(it) }
        return CallMediaStats(sendCam, sendScreen, recvVideo, recvScreen, route)
    }

    private fun audioRouteName(d: AudioDevice): String = when (d) {
        is AudioDevice.Speakerphone -> "speaker"
        is AudioDevice.Earpiece -> "earpiece"
        is AudioDevice.WiredHeadset -> "wired headset"
        is AudioDevice.BluetoothHeadset -> "bluetooth"
        else -> d.javaClass.simpleName
    }

    /**
     * Keeps the speaker button true to the actual route, applies the wanted speaker setting once the device list is known
     * (it can be empty right after joining), and logs route changes (device types only).
     */
    private fun watchAudioRoute(r: Room) {
        val handler = r.audioSwitchHandler ?: return
        audioListener?.let { runCatching { handler.unregisterAudioDeviceChangeListener(it) } }
        var applied = handler.availableAudioDevices.isNotEmpty()
        val listener: (List<AudioDevice>, AudioDevice?) -> Unit = { devices, selected ->
            diag.log("audio route ${selected?.let(::audioRouteName)} of ${devices.joinToString { audioRouteName(it) }}")
            if (room === r) {
                if (!applied && devices.isNotEmpty()) {
                    applied = true
                    setSpeaker(_state.value.speakerOn)
                } else if (selected != null) {
                    _state.update { it.copy(speakerOn = selected is AudioDevice.Speakerphone) }
                }
            }
        }
        audioListener = listener
        handler.registerAudioDeviceChangeListener(listener)
    }

    /** Room options per [CallMediaPolicy]: explicit camera settings, and screen share tuned for readable text. */
    private fun roomOptions(e2ee: E2EEOptions?): RoomOptions {
        val wm = context.getSystemService(android.view.WindowManager::class.java)
        val (w, h) = if (android.os.Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else android.util.DisplayMetrics().also { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
        val (long, short) = CallMediaPolicy.screenCaptureSize(w, h)
        return RoomOptions(
            adaptiveStream = true,
            dynacast = true,
            e2eeOptions = e2ee,
            videoTrackCaptureDefaults = LocalVideoTrackOptions(captureParams = VideoCaptureParameter(CallMediaPolicy.CAMERA_WIDTH, CallMediaPolicy.CAMERA_HEIGHT, CallMediaPolicy.CAMERA_FPS)),
            videoTrackPublishDefaults = VideoTrackPublishDefaults(videoEncoding = VideoPreset169.H720.encoding, simulcast = true),
            // The SDK orients the capture to the screen: (long, short) on a landscape display, (short, long) on a portrait one.
            screenShareTrackCaptureDefaults = LocalVideoTrackOptions(isScreencast = true, captureParams = VideoCaptureParameter(long, short, CallMediaPolicy.SCREEN_FPS)),
            screenShareTrackPublishDefaults = VideoTrackPublishDefaults(
                videoEncoding = VideoEncoding(CallMediaPolicy.SCREEN_MAX_BITRATE, CallMediaPolicy.SCREEN_FPS),
                simulcast = false,
                degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION,
            ),
        )
    }

    private fun onRoomEvent(ev: RoomEvent) {
        diag.event(ev)
        when (ev) {
            is RoomEvent.ParticipantConnected -> {
                peerWait?.cancel()
                peerWait = null
                _state.update { if (it.phase == CallPhase.Outgoing) it.copy(phase = CallPhase.Connected, connectedAt = System.currentTimeMillis(), peerReconnecting = false) else it.copy(peerReconnecting = false) }
                scope.launch { db.calls().getLog(_state.value.callId ?: "")?.let { db.calls().upsertLog(it.copy(answeredAt = System.currentTimeMillis())) } }
            }
            is RoomEvent.ParticipantDisconnected -> {
                securityTracker?.let { t -> ev.participant.identity?.value?.let(t::participantLeft); publishSecurity(t) }
                val s = _state.value
                // 1:1: the other person may only have lost their connection (a hang-up arrives as a call "ended" event).
                // Wait for them to come back instead of ending the call at once.
                if (!s.isGroup && room?.remoteParticipants?.isEmpty() == true && peerWait == null) {
                    _state.update { it.copy(peerReconnecting = true) }
                    peerWait = scope.launch {
                        delay(CallRecovery.PEER_WINDOW_MS)
                        peerWait = null
                        if (room?.remoteParticipants?.isEmpty() == true) finish("lost", notifyServer = true)
                    }
                }
            }
            is RoomEvent.Reconnecting -> _state.update { it.copy(phase = CallPhase.Reconnecting) }
            is RoomEvent.Reconnected -> _state.update { it.copy(phase = CallPhase.Connected) }
            is RoomEvent.Disconnected -> if (_state.value.phase != CallPhase.Idle && _state.value.phase != CallPhase.Ended) {
                val reason = ev.reason?.name
                Log.i(TAG, "room disconnected: $reason")
                // A lost connection (not a hang-up or removal): rejoin the same call with the same key for a while.
                if (CallRecovery.retryable(reason) && rejoinJob?.isActive != true) rejoin() else if (rejoinJob?.isActive != true) finish("disconnected", notifyServer = true)
            }
            // The SDK has set up the track's frame cryptor by now: register the track for the badge, then
            // swap in the crash-safe observer.
            is RoomEvent.TrackSubscribed -> {
                trackChanged { it.trackAdded(trackKey(ev.participant, ev.publication), remote = true, muted = ev.publication.muted) }
                cryptorGuard?.guard(ev.room, ev.publication, ev.participant)
            }
            is RoomEvent.TrackPublished -> {
                // Remote publications get a cryptor only once subscribed (TrackSubscribed); local ones now.
                if (ev.participant === ev.room.localParticipant) {
                    trackChanged { it.trackAdded(trackKey(ev.participant, ev.publication), remote = false, muted = ev.publication.muted) }
                }
                cryptorGuard?.guard(ev.room, ev.publication, ev.participant)
            }
            is RoomEvent.TrackUnsubscribed -> trackChanged { it.trackRemoved(trackKey(ev.participant, ev.publications)) }
            is RoomEvent.TrackUnpublished -> trackChanged { it.trackRemoved(trackKey(ev.participant, ev.publication)) }
            is RoomEvent.TrackMuted -> trackChanged { it.trackMuted(trackKey(ev.participant, ev.publication), true) }
            is RoomEvent.TrackUnmuted -> trackChanged { it.trackMuted(trackKey(ev.participant, ev.publication), false) }
            // Only until the guard has replaced a cryptor's observer; afterwards states arrive through the guard.
            is RoomEvent.TrackE2EEStateEvent -> securityTracker?.let { onE2eeState(it, trackKey(ev.participant, ev.publication), ev.state) }
            is RoomEvent.ConnectionQualityChanged -> {
                val r = room
                val qualities = listOfNotNull(r?.localParticipant?.connectionQuality) + r?.remoteParticipants?.values?.map { it.connectionQuality }.orEmpty()
                val weak = qualities.any { it == ConnectionQuality.POOR || it == ConnectionQuality.LOST }
                if (weak != _state.value.connectionWeak) diag.log("connection quality ${ev.quality} (weak=$weak)")
                _state.update { it.copy(connectionWeak = weak) }
            }
            else -> {}
        }
        refreshParticipants()
    }

    private fun trackKey(p: Participant, pub: TrackPublication) = FrameCryptorGuard.key(p.identity?.value, pub.sid)

    private inline fun trackChanged(change: (MediaSecurityTracker) -> Unit) {
        val t = securityTracker ?: return
        change(t)
        publishSecurity(t)
    }

    private fun onE2eeState(tracker: MediaSecurityTracker, key: String, st: E2EEState) {
        if (!tracker.state(key, st)) return
        publishSecurity(tracker)
        // Tiles show who has an encryption problem: refresh them from the call's own scope (this may be WebRTC's thread).
        scope.launch { if (securityTracker === tracker) refreshParticipants() }
    }

    /**
     * Shows what [tracker] can prove, unless its call has ended or been replaced meanwhile. Computed inside the lock, so
     * two threads can't publish out of order and leave a stale badge (lock order: [securityLock], then the tracker).
     */
    private fun publishSecurity(tracker: MediaSecurityTracker) {
        synchronized(securityLock) {
            if (securityTracker !== tracker) return
            val sec = tracker.security()
            if (_state.value.security != sec) diag.log("media security: $sec")
            _state.update { if (it.security == sec) it else it.copy(security = sec) }
        }
    }

    private fun refreshParticipants() {
        val r = room ?: return
        val problems = securityTracker?.problemIdentities().orEmpty()
        fun ui(p: Participant, local: Boolean): ParticipantUi {
            val meta = p.metadata
            val uid = meta?.let { Regex("\"userId\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) } ?: p.identity?.value?.substringBefore('.') ?: ""
            val camPub = p.videoTrackPublications.firstOrNull { it.first.source == Track.Source.CAMERA && !it.first.muted }
            val cam = camPub?.second as? VideoTrack
            val scr = p.videoTrackPublications.firstOrNull { it.first.source == Track.Source.SCREEN_SHARE && !it.first.muted }?.second as? VideoTrack
            val id = p.identity?.value ?: ""
            synchronized(firstSeen) { if (id.isNotEmpty() && id !in firstSeen) firstSeen[id] = seenCounter++ }
            return ParticipantUi(
                id, uid, p.name?.takeIf { it.isNotBlank() } ?: (if (local) "You" else "Participant"), local, p.isSpeaking, p.isMicrophoneEnabled, cam, scr,
                cameraOn = camPub != null,
                connectionWeak = p.connectionQuality == ConnectionQuality.POOR || p.connectionQuality == ConnectionQuality.LOST,
                e2eeProblem = id in problems,
            )
        }
        // One tile per identity in a stable order (others by arrival, then you), never yourself twice.
        val list = com.griipbuddy.orbit.ui.screens.calls.CallTiles.order(
            listOf(ui(r.localParticipant, true)) + r.remoteParticipants.values.map { ui(it, false) },
            identity = { it.identity }, isLocal = { it.isLocal }, firstSeen = { synchronized(firstSeen) { firstSeen[it] ?: Long.MAX_VALUE } },
        )
        _state.update {
            it.copy(
                participants = list,
                micOn = r.localParticipant.isMicrophoneEnabled,
                cameraOn = r.localParticipant.isCameraEnabled,
                screenSharing = r.localParticipant.isScreenShareEnabled,
            )
        }
    }

    fun toggleMic() = scope.launch {
        val r = room ?: return@launch
        runCatching { r.localParticipant.setMicrophoneEnabled(!r.localParticipant.isMicrophoneEnabled) }
        refreshParticipants()
    }

    fun toggleCamera() = scope.launch {
        val r = room ?: return@launch
        runCatching { r.localParticipant.setCameraEnabled(!r.localParticipant.isCameraEnabled) }
            .onFailure { _state.update { s -> s.copy(error = "Camera unavailable") } }
        refreshParticipants()
    }

    fun flipCamera() {
        val track = room?.localParticipant?.videoTrackPublications?.firstOrNull { it.first.source == Track.Source.CAMERA }?.second as? LocalVideoTrack
        val pos = track?.options?.position
        track?.switchCamera(position = if (pos == io.livekit.android.room.track.CameraPosition.FRONT) io.livekit.android.room.track.CameraPosition.BACK else io.livekit.android.room.track.CameraPosition.FRONT)
    }

    fun setSpeaker(on: Boolean) {
        val handler = room?.audioSwitchHandler ?: return
        val target = handler.availableAudioDevices.firstOrNull { if (on) it is AudioDevice.Speakerphone else it !is AudioDevice.Speakerphone }
        if (target != null) handler.selectDevice(target)
        _state.update { it.copy(speakerOn = on) }
    }

    /** Called with the MediaProjection consent result. Never invoked without the system dialog. */
    fun startScreenShare(resultData: Intent) = scope.launch {
        val r = room ?: return@launch
        try {
            r.localParticipant.setScreenShareEnabled(true, ScreenCaptureParams(resultData, onStop = {
                Log.i(TAG, "screen capture stopped by the system")
                scope.launch { refreshParticipants() }
            }))
        } catch (e: Exception) {
            Log.w(TAG, "screen share start failed: ${e.javaClass.simpleName}")
            _state.update { it.copy(error = "Screen sharing failed to start") }
        }
        refreshParticipants()
    }

    fun stopScreenShare() = scope.launch {
        runCatching { room?.localParticipant?.setScreenShareEnabled(false, null) }
        refreshParticipants()
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun hangup() {
        Log.i(TAG, "user tapped end")
        finish("hangup", notifyServer = true)
    }

    private fun finish(remoteReason: String, notifyServer: Boolean) {
        val s = _state.value
        if (s.phase == CallPhase.Idle) return
        markHandled(s.callId)
        Log.i(TAG, "call ending: reason=$remoteReason notifyServer=$notifyServer phase=${s.phase} participants=${s.participants.size}")
        val id = s.callId
        if (notifyServer && id != null) {
            scope.launch {
                runCatching { if (!s.isGroup || s.participants.size <= 1) api.post<CallRes>("/v1/calls/$id/end") else api.post<CallRes>("/v1/calls/$id/leave") }
                    .onFailure { runCatching { api.post<CallRes>("/v1/calls/$id/leave") } }
            }
        }
        scope.launch {
            id?.let { cid ->
                db.calls().getLog(cid)?.let { db.calls().upsertLog(it.copy(endedAt = System.currentTimeMillis(), endReason = remoteReason)) }
                db.calls().dropKey(cid, System.currentTimeMillis() - 24 * 3600_000L)
            }
        }
        teardown()
        _state.value = s.copy(phase = CallPhase.Ended, endReason = remoteReason, participants = emptyList())
        scope.launch {
            delay(1500)
            if (_state.value.phase == CallPhase.Ended) reset()
        }
    }

    private fun fail(msg: String) {
        teardown()
        _state.update { it.copy(phase = CallPhase.Ended, error = msg, endReason = "error") }
        scope.launch {
            delay(3500)
            if (_state.value.phase == CallPhase.Ended) reset()
        }
    }

    /**
     * Ordered shutdown. Freeing WebRTC's native objects while its audio/render threads or the call
     * UI's video sinks still reference them crashed the process (SIGSEGV in libjingle_peerconnection_so,
     * seen on a physical phone). So: drop the room reference (the UI detaches its sinks), disconnect,
     * and only release native resources after those threads have wound down.
     * Disconnecting clears the remote tracks but leaves their frame cryptors running, so every cryptor
     * gets the crash-safe observer first, including ones whose TrackSubscribed event was still queued.
     */
    private var peerWait: kotlinx.coroutines.Job? = null
    private var rejoinJob: kotlinx.coroutines.Job? = null

    /**
     * After this phone's media connection dropped (the SDK's own reconnect gave up): new media credentials from the
     * server and a new room with the same call key, retried with backoff for [CallRecovery.REJOIN_WINDOW_MS]. The call
     * screen says "Reconnecting…" meanwhile; the call ends only if the window runs out or the call has ended.
     */
    private fun rejoin() {
        val s = _state.value
        val callId = s.callId ?: return finish("disconnected", notifyServer = true)
        _state.update { it.copy(phase = CallPhase.Reconnecting) }
        rejoinJob = scope.launch {
            val started = System.currentTimeMillis()
            var attempt = 0
            teardownRoomOnly()
            while (System.currentTimeMillis() - started < CallRecovery.REJOIN_WINDOW_MS) {
                delay(CallRecovery.backoff(attempt++))
                if (_state.value.callId != callId || _state.value.phase == CallPhase.Ended || _state.value.phase == CallPhase.Idle) return@launch
                val res = try {
                    api.post<CallRes>("/v1/calls/$callId/join")
                } catch (e: ApiException) {
                    if (e.status == 410 || e.status == 404) { finish("ended", notifyServer = false); return@launch }
                    continue
                }
                val key = if (settings.current.mediaE2ee) messages.callKey(callId) else null
                if (settings.current.mediaE2ee && key == null) continue
                runCatching { connect(callId, res.media ?: continue, key, s.video) }.onSuccess {
                    diag.log("rejoined after ${System.currentTimeMillis() - started} ms ($attempt attempts)")
                    return@launch
                }
            }
            finish("lost", notifyServer = true)
        }
    }

    /** Drops the current room without ending the call (before a rejoin). */
    private fun teardownRoomOnly() {
        mediaJob?.cancel()
        mediaJob = null
        eventsJob?.cancel()
        eventsJob = null
        val r = room
        room = null
        synchronized(securityLock) { securityTracker = null }
        if (r != null) scope.launch(Dispatchers.Main) { runCatching { r.disconnect() }; delay(1500); runCatching { r.release() } }
    }

    private fun teardown() {
        peerWait?.cancel()
        peerWait = null
        rejoinJob?.cancel()
        rejoinJob = null
        IncomingCallNotifier.cancel(context)
        synchronized(firstSeen) { firstSeen.clear() }
        mediaJob?.cancel()
        mediaJob = null
        eventsJob?.cancel()
        eventsJob = null
        diag.stop()
        val r = room
        room = null
        val guard = cryptorGuard
        cryptorGuard = null
        synchronized(securityLock) { securityTracker = null }
        val listener = audioListener
        audioListener = null
        CallService.stop(context)
        if (r != null) {
            listener?.let { l -> runCatching { r.audioSwitchHandler?.unregisterAudioDeviceChangeListener(l) } }
            releaseJob = scope.launch(Dispatchers.Main) {
                delay(600)
                runCatching { guard?.sweep(r) }
                runCatching { r.disconnect() }
                delay(2000)
                runCatching { r.release() }
                guard?.let { diag.log("frame-cryptor guard after release: ${it.lateCallbacks} late callbacks ignored, ${it.failedCallbacks} failed") }
            }
        }
    }

    private fun reset() {
        _state.value = CallUiState()
    }

    companion object {
        private const val TAG = "CallManager"
    }
}
