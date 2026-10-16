package com.griipbuddy.message.ui.screens.conversation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import com.griipbuddy.message.call.CallPhase
import com.griipbuddy.message.data.db.MessageEntity
import com.griipbuddy.message.voice.AndroidAudioRecorder
import com.griipbuddy.message.voice.VoiceDraftController
import com.griipbuddy.message.voice.VoiceNote
import com.griipbuddy.message.data.model.AttachmentRef
import com.griipbuddy.message.di.AppContainer
import com.griipbuddy.message.ui.friendly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ConversationViewModel(val c: AppContainer, val conversationId: String, val threadRoot: String?) : ViewModel() {
    val me = c.session.session?.userId
    val conversation = c.conversations.observe(conversationId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val limit = MutableStateFlow(60)

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages = (if (threadRoot != null) c.messages.observeThread(conversationId, threadRoot).map { it.reversed() } else limit.flatMapLatest { c.messages.observeMessages(conversationId, it) })
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val rootMessage = (threadRoot?.let { c.messages.observeMessage(it) } ?: flowOf(null)).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val threadCounts = c.messages.observeThreadCounts(conversationId).map { l -> l.associate { it.root to it.n } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val pinned = c.messages.observePinned(conversationId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val users = c.db.users().observeAll().map { l -> l.associateBy { it.id } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val typing = combine(c.messages.typing, users) { t, u -> t[conversationId]?.keys?.filter { it != me }?.mapNotNull { u[it]?.displayName ?: "Someone" } ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val memberIds = MutableStateFlow<List<String>>(emptyList())

    /** Members whose security key changed: messages to and from them are paused until reviewed (T1.2). */
    val changedMembers = combine(users, memberIds) { u, ids -> ids.mapNotNull { u[it] }.filter { it.identityChanged && it.id != me } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Incoming messages held because their sender's key changed. */
    val heldIncoming = c.db.quarantine().observeCount(conversationId).stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val transfers = c.messages.transferProgress
    val connection = c.realtime.state
    val call = c.calls.state

    val replyTo = MutableStateFlow<MessageEntity?>(null)
    val editing = MutableStateFlow<MessageEntity?>(null)
    val error = MutableStateFlow<String?>(null)

    /** Voice messages: the composer's draft, and the app-wide player that bubbles and the draft share. */
    val voiceDraft = VoiceDraftController(
        File(c.app.cacheDir, "voice"), AndroidAudioRecorder(c.app), SystemClock::elapsedRealtime, System::currentTimeMillis,
    ) { error.value = it }
    val voicePlayer = c.voicePlayer.state
    val draftKey = "draft:$conversationId:$threadRoot"
    private val playedKeys = HashSet<String>()

    init {
        viewModelScope.launch { c.conversations.refreshOne(conversationId) }
        viewModelScope.launch { runCatching { memberIds.value = c.directory.conversationMemberIds(conversationId) } }
        viewModelScope.launch {
            val conv = c.db.conversations().get(conversationId)
            val ids = conv?.let { com.griipbuddy.message.data.repo.ConversationRepository.members(it).map { m -> m.id } }.orEmpty()
            runCatching { c.users.refreshPresence(ids.filter { it != me }) }
        }
        // A call takes the microphone and the speaker: stop recording (the clip so far stays as a draft) and pause playback.
        viewModelScope.launch {
            c.calls.state.map { it.phase != CallPhase.Idle }.distinctUntilChanged().collect { inCall ->
                if (inCall) interruptVoice()
            }
        }
    }

    private val inCall get() = c.calls.state.value.phase != CallPhase.Idle

    fun startVoice() {
        if (inCall) {
            error.value = "You can't record a voice message during a call."
            return
        }
        c.voicePlayer.pause()
        voiceDraft.start()
    }

    fun stopVoice() = voiceDraft.stop()

    fun cancelVoice() {
        c.voicePlayer.stop { it == draftKey }
        voiceDraft.cancel()
    }

    /** The screen left the foreground or a call started: nothing records or plays in the background. */
    fun interruptVoice() {
        if (voiceDraft.recording) voiceDraft.stop()
        c.voicePlayer.pause()
    }

    fun sendVoice() {
        c.voicePlayer.stop { it == draftKey }
        val clip = voiceDraft.take() ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { clip.file.inputStream() }.use {
                    c.messages.sendAttachment(conversationId, VoiceNote.fileName(System.currentTimeMillis()), VoiceNote.MIME, it, "", voiceDurationMs = clip.durationMs)
                }
            } catch (e: Exception) {
                error.value = "Couldn't send the voice message"
            } finally {
                clip.file.delete() // the repository keeps its own copy for upload and retry
            }
        }
    }

    fun toggleDraftPlayback() {
        val clip = voiceDraft.state.value as? VoiceDraftController.Draft.Ready ?: return
        if (!c.voicePlayer.toggle(draftKey, clip.file)) error.value = "Couldn't play the recording"
    }

    fun seekDraft(positionMs: Long) {
        val clip = voiceDraft.state.value as? VoiceDraftController.Draft.Ready ?: return
        c.voicePlayer.seek(draftKey, clip.file, positionMs)
    }

    /** Plays a voice message in message [messageId], downloading and decrypting it first if needed. */
    fun toggleVoice(messageId: String, ref: AttachmentRef) = viewModelScope.launch {
        if (inCall) {
            error.value = "Voice messages can't play during a call."
            return@launch
        }
        if (voiceDraft.recording) return@launch
        val file = runCatching { c.messages.downloadAttachment(ref) }.getOrElse {
            error.value = it.friendly()
            return@launch
        }
        playedKeys += messageId
        if (!c.voicePlayer.toggle(messageId, file)) error.value = "Couldn't play this voice message"
    }

    fun seekVoice(messageId: String, ref: AttachmentRef, positionMs: Long) {
        if (inCall || voiceDraft.recording) return
        playedKeys += messageId
        c.voicePlayer.seek(messageId, c.messages.localAttachment(ref), positionMs)
    }

    override fun onCleared() {
        voiceDraft.close()
        c.voicePlayer.stop { it == draftKey || it in playedKeys }
    }

    fun loadMore() {
        if (messages.value.size >= limit.value) limit.value += 60
    }

    fun onVisible() {
        c.appState.viewingConversation.value = conversationId
        c.notifier.clear(conversationId)
        viewModelScope.launch { c.messages.markConversationRead(conversationId) }
    }

    fun onHidden() {
        if (c.appState.viewingConversation.value == conversationId) c.appState.viewingConversation.value = null
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val ed = editing.value
        viewModelScope.launch {
            runCatching {
                if (ed != null) c.messages.edit(ed, t)
                else c.messages.sendText(conversationId, t, replyTo = replyTo.value?.id, threadRoot = threadRoot)
            }.onFailure { error.value = it.friendly() }
            replyTo.value = null
            editing.value = null
            c.conversations.setDraft(conversationId, null)
            c.messages.setTyping(conversationId, false)
        }
    }

    fun typingChanged(text: String) {
        if (text.isNotBlank()) c.messages.setTyping(conversationId, true)
        viewModelScope.launch { c.conversations.setDraft(conversationId, text.ifBlank { null }) }
    }

    fun react(m: MessageEntity, emoji: String) = viewModelScope.launch { c.messages.react(m, emoji) }
    fun delete(m: MessageEntity) = viewModelScope.launch { c.messages.delete(m) }
    fun pin(m: MessageEntity) = viewModelScope.launch { c.messages.setPinned(m, !m.pinned) }
    fun retry(m: MessageEntity) = viewModelScope.launch { c.messages.retry(m.id) }

    fun attach(context: Context, uri: Uri) = viewModelScope.launch {
        try {
            val (name, size) = withContext(Dispatchers.IO) {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) (cur.getString(0) ?: "file") to cur.getLong(1) else "file" to 0L
                } ?: ("file" to 0L)
            }
            if (size > MAX_ATTACHMENT) {
                error.value = "Files up to 25 MB can be shared in this beta."
                return@launch
            }
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            // Streamed into app storage by the repository; the whole file is never held in memory (T2.6).
            val input = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri) } ?: return@launch
            input.use { c.messages.sendAttachment(conversationId, name, mime, it, "") }
        } catch (e: com.griipbuddy.message.data.repo.MessageRepository.AttachmentTooLarge) {
            error.value = e.message
        } catch (e: Exception) {
            error.value = "Couldn't read that file"
        }
    }

    fun openAttachment(context: Context, ref: AttachmentRef) = viewModelScope.launch {
        runCatching { c.messages.downloadAttachment(ref) }
            .onSuccess { f -> openFile(context, f, ref.mime) }
            .onFailure { error.value = it.friendly() }
    }

    private fun openFile(context: Context, file: File, mime: String) {
        val shared = File(context.cacheDir, "shared").apply { mkdirs() }
        val out = File(shared, file.name.substringAfter('-'))
        file.copyTo(out, overwrite = true)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", out)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(Intent.createChooser(intent, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { error.value = "No app can open this file" }
    }

    fun startCall(video: Boolean) = viewModelScope.launch { c.calls.startCall(conversationId, video) }
    fun joinCall(callId: String, video: Boolean) = viewModelScope.launch { c.calls.join(conversationId, callId, video) }

    companion object {
        const val MAX_ATTACHMENT = 25L * 1024 * 1024
    }
}
