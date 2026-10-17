package com.griipbuddy.orbit.ui.screens.conversation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import com.griipbuddy.orbit.call.CallPhase
import com.griipbuddy.orbit.data.db.MessageEntity
import com.griipbuddy.orbit.voice.AndroidAudioRecorder
import com.griipbuddy.orbit.voice.VoiceDraftController
import com.griipbuddy.orbit.voice.VoiceNote
import com.griipbuddy.orbit.data.model.AttachmentRef
import com.griipbuddy.orbit.data.model.AttachmentPolicy
import com.griipbuddy.orbit.data.repo.MessageRepository
import com.griipbuddy.orbit.data.model.MentionMember
import com.griipbuddy.orbit.data.model.MentionRules
import com.griipbuddy.orbit.data.model.MentionSet
import com.griipbuddy.orbit.di.AppContainer
import com.griipbuddy.orbit.ui.friendly
import com.griipbuddy.orbit.ui.media.MediaExport
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

    /** Who `@` can mention here (full member list; ids are the targets) and whether @everyone is allowed. */
    val mentionMembers = MutableStateFlow<List<MentionMember>>(emptyList())
    val canMentionAll = c.conversations.mentionAll.map { it[conversationId] == true }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun loadMentionMembers() = viewModelScope.launch {
        val conv = c.db.conversations().get(conversationId)
        if (conv?.type == "DM") return@launch
        mentionMembers.value = c.conversations.mentionMembers(conversationId)
    }

    /** What [text] mentions, resolved against this conversation's members only. */
    fun mentionsIn(text: String): MentionSet = MentionRules.resolve(text, mentionMembers.value, canMentionAll.value, me)

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
        val replyId = replyTo.value?.id
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { clip.file.inputStream() }.use {
                    c.messages.sendAttachment(conversationId, VoiceNote.fileName(System.currentTimeMillis()), VoiceNote.MIME, it, "", voiceDurationMs = clip.durationMs, replyTo = replyId)
                }
                replyTo.value = ReplyTarget.afterSend(replyTo.value, replyId, success = true)
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

    /** Text to put back in the composer after a send that failed before the message was stored (nothing is lost). */
    val restoreText = MutableStateFlow<String?>(null)

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        // Captured at the tap: a swipe to another message while this one is being stored can't change what it replies to.
        val ed = editing.value
        val replyId = replyTo.value?.id
        viewModelScope.launch {
            val ok = runCatching {
                // Resolved from the loaded member list; if it hasn't loaded yet the repository resolves it from what it knows.
                val resolved = if (mentionMembers.value.isNotEmpty()) mentionsIn(t) else null
                if (ed != null) c.messages.edit(ed, t, resolved)
                else {
                    // A preview fetched for a link still in the text goes with it (inside the encrypted message).
                    val preview = linkPreview.value?.takeIf { p -> com.griipbuddy.orbit.data.model.LinkRules.find(t).any { it.url == p.url } }
                    c.messages.sendText(conversationId, t, replyTo = replyId, threadRoot = threadRoot, mentions = resolved, rich = preview?.let { com.griipbuddy.orbit.data.model.RichContent(link = it) })
                }
            }.onFailure { error.value = it.friendly(); restoreText.value = t }.isSuccess
            // The message is stored with its reply (retries resend it from there), so the composer's target can go.
            replyTo.value = ReplyTarget.afterSend(replyTo.value, replyId, ok)
            if (ok) {
                linkPreview.value = null
                previewJob?.cancel()
                if (editing.value?.id == ed?.id) editing.value = null
                c.conversations.setDraft(conversationId, null)
                c.messages.setTyping(conversationId, false)
            }
        }
    }

    /** Keeps the reply target in step with the conversation (dropped if that message is deleted). */
    fun refreshReplyTarget(latest: MessageEntity?) {
        replyTo.value = ReplyTarget.refreshed(replyTo.value, conversationId, latest)
    }

    fun typingChanged(text: String) {
        if (text.isNotBlank()) c.messages.setTyping(conversationId, true)
        viewModelScope.launch { c.conversations.setDraft(conversationId, text.ifBlank { null }) }
        prefetchPreview(text)
    }

    // ─────────── Link previews (sender side only, docs/LINKS.md) ───────────

    /** The preview for the first link in the composer, when Chat settings › Link previews is on. */
    val linkPreview = MutableStateFlow<com.griipbuddy.orbit.data.model.LinkPreview?>(null)
    private var previewJob: kotlinx.coroutines.Job? = null
    private val dismissedPreviews = HashSet<String>()

    private fun prefetchPreview(text: String) {
        val url = com.griipbuddy.orbit.data.model.LinkRules.find(text).firstOrNull()?.url?.takeIf { it.startsWith("https://") }
        if (url == null || !c.settings.current.linkPreviews || url in dismissedPreviews) {
            previewJob?.cancel()
            linkPreview.value = null
            return
        }
        if (linkPreview.value?.url == url || previewJob?.isActive == true) return
        previewJob = viewModelScope.launch {
            kotlinx.coroutines.delay(700)
            linkPreview.value = com.griipbuddy.orbit.data.repo.LinkPreviews.fetch(url)
        }
    }

    /** The person removed the preview: this link is sent without one. */
    fun dismissPreview() {
        linkPreview.value?.url?.let { dismissedPreviews += it }
        linkPreview.value = null
        previewJob?.cancel()
    }

    fun react(m: MessageEntity, emoji: String) = viewModelScope.launch { c.messages.react(m, emoji) }
    fun delete(m: MessageEntity) = viewModelScope.launch { c.messages.delete(m) }
    fun pin(m: MessageEntity) = viewModelScope.launch { c.messages.setPinned(m, !m.pinned) }
    fun retry(m: MessageEntity) = viewModelScope.launch { c.messages.retry(m.id) }

    // ─────────── Photos, videos and files picked to send (2026-10-17 batch attachments, docs/ATTACHMENTS.md) ───────────

    /** A picked file waiting in the send preview, with what the safety checks found ([risk]: why it can run software). */
    data class Candidate(val item: MessageRepository.BatchItem, val risk: String?)

    /** The send preview's files (empty: no preview). Kept here so a rotation doesn't lose the selection. */
    val batch = MutableStateFlow<List<Candidate>>(emptyList())
    /** A note about the last pick ("Up to 10 at a time…", "1 couldn't be read"). */
    val batchNote = MutableStateFlow<String?>(null)

    /** Adds picked files to the preview: at most [AttachmentPolicy.MAX_ITEMS], each inspected (name, size, real type). */
    fun pick(context: Context, uris: List<Uri>) = viewModelScope.launch {
        val current = batch.value
        val fresh = uris.distinct().filter { u -> current.none { it.item.uri == u } }
        val room = (AttachmentPolicy.MAX_ITEMS - current.size).coerceAtLeast(0)
        val accepted = fresh.take(room)
        val read = withContext(Dispatchers.IO) { accepted.mapNotNull { inspect(context, it) } }
        val notes = buildList {
            if (fresh.size > room) add("Up to ${AttachmentPolicy.MAX_ITEMS} at a time: ${fresh.size - room} not added.")
            if (read.size < accepted.size) add(if (accepted.size - read.size == 1) "1 file couldn't be read." else "${accepted.size - read.size} files couldn't be read.")
        }
        batch.value = current + read
        batchNote.value = notes.joinToString(" ").ifEmpty { null }
        if (batch.value.isEmpty() && notes.isNotEmpty()) error.value = batchNote.value
    }

    private fun inspect(context: Context, uri: Uri): Candidate? = runCatching {
        val cr = context.contentResolver
        var name: String? = null
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                name = cur.getString(0)
                if (!cur.isNull(1)) size = cur.getLong(1)
            }
        }
        if (size <= 0) size = runCatching { cr.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it >= 0 } ?: -1L
        val safe = AttachmentPolicy.safeName(name ?: uri.lastPathSegment)
        val mime = cr.getType(uri)
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(safe.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val head = cr.openInputStream(uri)?.use { i -> ByteArray(64).let { b -> b.copyOf(i.read(b).coerceAtLeast(0)) } } ?: return null
        val sniffed = AttachmentPolicy.sniff(head)
        val kind = AttachmentPolicy.kind(mime, sniffed)
        val duration = if (kind == AttachmentPolicy.Kind.VIDEO) runCatching {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                r.release()
            }
        }.getOrNull() else null
        Candidate(MessageRepository.BatchItem(uri, safe, mime, size, kind, sniffed, durationMs = duration), AttachmentPolicy.risk(safe, mime, sniffed))
    }.getOrNull()

    fun unpick(uri: Uri) {
        batch.value = batch.value.filterNot { it.item.uri == uri }
        if (batch.value.isEmpty()) batchNote.value = null
    }

    fun clearBatch() {
        batch.value = emptyList()
        batchNote.value = null
    }

    /**
     * Sends the preview's files that pass the checks as one message at [quality], with [caption] and the reply target
     * chosen now. The preview closes once the message is queued; preparing and uploading carry on in the background.
     */
    fun sendBatch(photoQuality: AttachmentPolicy.Quality, videoQuality: AttachmentPolicy.Quality, caption: String) = viewModelScope.launch {
        val max = maxFile.value
        val items = batch.value.map { it.item }.filter {
            val q = if (it.kind == AttachmentPolicy.Kind.VIDEO) videoQuality else photoQuality
            AttachmentPolicy.check(it.kind, it.sniffed, it.size, q, max) == AttachmentPolicy.Verdict.Ok
        }
        if (items.isEmpty()) return@launch
        val replyId = replyTo.value?.id
        runCatching { c.messages.sendBatch(conversationId, items, photoQuality, caption.trim(), replyId, videoQuality = videoQuality) }
            .onSuccess {
                clearBatch()
                replyTo.value = ReplyTarget.afterSend(replyTo.value, replyId, success = true)
            }
            .onFailure { error.value = it.friendly() }
    }

    /** The largest file this conversation's server accepts (25 MB until the server says otherwise). */
    val maxFile = MutableStateFlow(AttachmentPolicy.MAX_FILE_BYTES)

    /** Stops an upload that hasn't been sent yet; the message disappears from this phone (nothing reached anyone). */
    fun cancelUpload(m: MessageEntity) = viewModelScope.launch {
        if (!c.messages.cancelBatch(m.id)) notice.value = "Already sent: it can no longer be cancelled"
    }

    /** Forwards [m] to [destCid] (chosen and confirmed by the person). */
    fun forward(m: MessageEntity, destCid: String, onDone: (String) -> Unit) = viewModelScope.launch {
        runCatching { c.messages.forward(m, destCid) }
            .onSuccess { onDone(destCid) }
            .onFailure { error.value = it.friendly() }
    }

    /** A contact card, a one-time location or an animated emoji, with the reply target chosen now. */
    fun sendRich(rich: com.griipbuddy.orbit.data.model.RichContent) = viewModelScope.launch {
        val replyId = replyTo.value?.id
        runCatching { c.messages.sendText(conversationId, "", replyTo = replyId, threadRoot = threadRoot, rich = rich) }
            .onSuccess { replyTo.value = ReplyTarget.afterSend(replyTo.value, replyId, success = true) }
            .onFailure { error.value = it.friendly() }
    }

    /**
     * Downloads received photos (up to 10 MB each) among [recent] in the background, when Settings › Chats allows it
     * (always, or only on an unmetered network). One at a time; already-downloaded ones are skipped.
     */
    fun autoDownloadPhotos(recent: List<MessageEntity>, context: Context) = viewModelScope.launch {
        val mode = c.settings.current.autoDownload
        if (!com.griipbuddy.orbit.ui.media.MediaGallery.autoDownloadAllowed(mode, unmetered(context))) return@launch
        for (m in recent) {
            if (m.outgoing || m.deleted) continue
            for (ref in c.messages.attachments(m)) {
                if (com.griipbuddy.orbit.ui.media.MediaGallery.kindOf(ref) != com.griipbuddy.orbit.ui.media.MediaKind.Image || ref.size > 10L * 1024 * 1024) continue
                if (c.messages.localAttachment(ref) != null) continue
                runCatching { c.messages.downloadAttachment(ref) }
            }
        }
    }

    private fun unmetered(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }.getOrDefault(false)

    /** Someone on this server with that exact phone number, if they let people find them by it. */
    fun findByPhone(phone: String, onFound: (String) -> Unit) = viewModelScope.launch {
        runCatching { c.api.get<com.griipbuddy.orbit.data.api.UserRes>("/v1/users/by-phone?phone=${java.net.URLEncoder.encode(phone, "UTF-8")}").user }
            .onSuccess { c.users.remember(it); onFound(it.id) }
            .onFailure { notice.value = "No one on Orbit lets people find them by this number" }
    }

    /** On-screen translations by message id (docs/LANGUAGES.md › Translation); the stored message never changes. */
    data class Translated(val text: String? = null, val working: Boolean = false, val error: String? = null)
    val translations = MutableStateFlow<Map<String, Translated>>(emptyMap())

    fun translate(m: MessageEntity, target: String) = viewModelScope.launch {
        c.translator.lastTarget = target
        translations.value = translations.value + (m.id to Translated(working = true))
        val r = runCatching { c.translator.translate(m.text, target) }
        translations.value = translations.value + (m.id to r.fold({ Translated(text = it) }, { Translated(error = it.message ?: "Couldn't translate this message.") }))
    }

    fun showOriginal(m: MessageEntity) {
        translations.value = translations.value - m.id
    }

    /** A received file that can run software, waiting for the user to confirm opening it: the file and why. */
    val riskyOpen = MutableStateFlow<Pair<AttachmentRef, String>?>(null)

    /** Short confirmations ("Saved to Pictures/Orbit"); failures go to [error]. */
    val notice = MutableStateFlow<String?>(null)

    /** The decrypted file for the media viewer and for saving: downloads, checks and decrypts it first if needed. */
    suspend fun mediaFile(ref: AttachmentRef): File = c.messages.downloadAttachment(ref)

    /** Why a video can't play right now (a call has the speaker and microphone), or null. */
    fun videoBlocked(): String? = if (inCall) "Videos can't play during a call." else null

    fun pauseVoice() = c.voicePlayer.pause()

    fun saveToGallery(ref: AttachmentRef) = viewModelScope.launch {
        runCatching {
            val f = mediaFile(ref)
            // Only real photos and videos go to the gallery, whatever the sender's message says the file is.
            if (AttachmentPolicy.kind(ref.mime, withContext(Dispatchers.IO) { sniffFile(f) }) == AttachmentPolicy.Kind.FILE) {
                throw NotMedia()
            }
            MediaExport.saveToGallery(c.app, f, ref.name, ref.mime)
        }
            .onSuccess { folder -> notice.value = "Saved to $folder" }
            .onFailure { error.value = saveFailure(it) }
    }

    fun saveToDocument(ref: AttachmentRef, target: Uri) = viewModelScope.launch {
        runCatching { MediaExport.saveToDocument(c.app, mediaFile(ref), target) }
            .onSuccess { notice.value = "Saved" }
            .onFailure { error.value = saveFailure(it) }
    }

    fun share(ref: AttachmentRef, start: (Intent) -> Unit) = viewModelScope.launch {
        runCatching { MediaExport.shareIntent(c.app, mediaFile(ref), ref.name, ref.mime) }
            .onSuccess(start)
            .onFailure { error.value = saveFailure(it) }
    }

    private class NotMedia : Exception()

    private fun saveFailure(e: Throwable): String = when (e) {
        is NotMedia -> "This file isn't really a photo or video, so it wasn't added to the gallery. Use Save to files instead."
        is com.griipbuddy.orbit.data.api.ApiException -> e.message
        is java.io.IOException -> "Couldn't save: ${e.message ?: "the phone's storage refused the file"}"
        is SecurityException -> "Couldn't save: storage permission is needed"
        else -> "Couldn't save this file"
    }

    /**
     * Opens a received file with another app. A file that can install or run software (by its name or its real content)
     * asks first: the server only stores encrypted files, so nothing has scanned it ([confirmed]: the user said yes).
     */
    fun openAttachment(context: Context, ref: AttachmentRef, confirmed: Boolean = false) = viewModelScope.launch {
        runCatching { c.messages.downloadAttachment(ref) }
            .onSuccess { f ->
                val risk = AttachmentPolicy.risk(ref.name, ref.mime, withContext(Dispatchers.IO) { sniffFile(f) })
                if (risk != null && !confirmed) riskyOpen.value = ref to risk
                else openFile(context, f, ref.mime)
            }
            .onFailure { error.value = it.friendly() }
    }

    private fun sniffFile(f: File): AttachmentPolicy.Sniffed? =
        runCatching { f.inputStream().use { i -> ByteArray(64).let { b -> AttachmentPolicy.sniff(b.copyOf(i.read(b).coerceAtLeast(0))) } } }.getOrNull()

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

    // Last in the class on purpose: viewModelScope runs on Dispatchers.Main.immediate, so each launch below starts inside
    // the constructor. Properties are initialised in declaration order, and a property declared after this block would
    // still be null there. Until 2026-10-17 `maxFile` was, and opening any chat crashed (NullPointerException on resume).
    // Guarded by InitOrderTest.
    init {
        viewModelScope.launch { runCatching { c.conversations.refreshOne(conversationId) }; loadMentionMembers() }
        viewModelScope.launch { runCatching { memberIds.value = c.directory.conversationMemberIds(conversationId) } }
        viewModelScope.launch { maxFile.value = c.messages.maxAttachmentBytes() }
        // Call history: entries for calls that rang or ended while this phone was offline.
        viewModelScope.launch { runCatching { c.messages.syncCalls(conversationId) } }
        viewModelScope.launch {
            val conv = c.db.conversations().get(conversationId)
            val ids = conv?.let { com.griipbuddy.orbit.data.repo.ConversationRepository.members(it).map { m -> m.id } }.orEmpty()
            runCatching { c.users.refreshPresence(ids.filter { it != me }) }
        }
        // A call takes the microphone and the speaker: stop recording (the clip so far stays as a draft) and pause playback.
        viewModelScope.launch {
            c.calls.state.map { it.phase != CallPhase.Idle }.distinctUntilChanged().collect { inCall ->
                if (inCall) interruptVoice()
            }
        }
    }
}
