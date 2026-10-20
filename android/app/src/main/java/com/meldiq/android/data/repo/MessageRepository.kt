package com.meldiq.android.data.repo

import android.content.Context
import android.util.Log
import com.meldiq.android.crypto.AttachmentCrypto
import com.meldiq.android.crypto.ChunkedAttachmentCrypto
import com.meldiq.android.crypto.EnvelopeException
import com.meldiq.android.crypto.EnvelopeHeader
import com.meldiq.android.crypto.MessageCrypto
import com.meldiq.android.crypto.ReceivedEnvelope
import com.meldiq.android.data.api.AckReq
import com.meldiq.android.data.api.ApiClient
import com.meldiq.android.data.api.ApiException
import com.meldiq.android.data.api.CallRes
import com.meldiq.android.data.api.ConnState
import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.api.OkRes
import com.meldiq.android.data.api.QueueItem
import com.meldiq.android.data.api.QueuePayload
import com.meldiq.android.data.api.QueueRes
import com.meldiq.android.data.api.RealtimeClient
import com.meldiq.android.data.api.RealtimeFrame
import com.meldiq.android.data.api.SendMessageReq
import com.meldiq.android.data.api.SendMessageRes
import com.meldiq.android.data.api.WireKeyWrap
import com.meldiq.android.data.db.CallKeyEntity
import com.meldiq.android.data.db.ConversationEntity
import com.meldiq.android.data.db.GriipDatabase
import com.meldiq.android.data.db.MessageEntity
import com.meldiq.android.data.db.OutboxEntity
import com.meldiq.android.data.db.QuarantineEntity
import com.meldiq.android.data.model.AttachmentRef
import com.meldiq.android.voice.VoiceNote
import com.meldiq.android.data.model.CallInfo
import com.meldiq.android.data.model.AttachmentPolicy
import com.meldiq.android.data.model.RichContent
import com.meldiq.android.data.model.PollRules
import com.meldiq.android.data.model.RichRules
import com.meldiq.android.data.model.CallHistory
import com.meldiq.android.data.api.CallDto
import com.meldiq.android.data.api.CallsRes
import com.meldiq.android.data.model.MessageStatus
import com.meldiq.android.data.model.MentionNotify
import com.meldiq.android.data.model.MentionRules
import com.meldiq.android.data.model.MentionSet
import com.meldiq.android.data.model.Payload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.UUID

data class Notice(val conversationId: String, val title: String, val sender: String, val text: String, val mention: Boolean, val isGroup: Boolean, val canReply: Boolean = true)
data class IncomingCallKey(val callId: String, val conversationId: String, val fromUserId: String, val video: Boolean)

/** Live UI context used to decide notifications / read receipts. */
class AppState {
    val foreground = MutableStateFlow(false)
    val viewingConversation = MutableStateFlow<String?>(null)
    /** Push wake-up fetches in progress ([WakeFetch]): the relay connection stays open while above zero. */
    val wakeHolds = MutableStateFlow(0)
}

/**
 * Message pipeline: local-first history in Room, an outbox that encrypts and submits with retry,
 * and an ordered consumer for relay deliveries that verifies, decrypts, applies and acknowledges.
 */
class MessageRepository(
    private val context: Context,
    private val db: GriipDatabase,
    private val api: ApiClient,
    private val realtime: RealtimeClient,
    private val session: SessionRepository,
    private val crypto: MessageCrypto,
    private val directory: KeyDirectory,
    private val conversations: ConversationRepository,
    private val users: UserRepository,
    private val settings: SettingsStore,
    private val appState: AppState,
    private val scope: CoroutineScope,
    attachmentsDir: File = File(context.filesDir, "attachments"),
    /** Direct uploads to the server's file store when it offers them; the original upload otherwise. */
    private val uploader: AttachmentUploader = AttachmentUploader(api, null),
    private val notify: (Notice) -> Unit,
) {
    /** Round 3: the person's notification level for a conversation (set by the container from NavPreferences). */
    var notifyLevelOf: (ConversationEntity) -> String = { com.meldiq.android.data.model.NotifyLevel.ALL }

    private val json = GriipJson
    private val files = AttachmentFiles(attachmentsDir)
    private val pending = PendingControls(RoomPendingControlStore(db))
    private val keyRequests = CallKeyRequests()
    private val callKeys = CallKeyVerifier { id ->
        try {
            api.get<CallRes>("/v1/calls/$id").call
        } catch (e: ApiException) {
            if (e.status == 404) null else throw e // other failures: redelivered and checked again
        }
    }
    private val listOfStrings = ListSerializer(String.serializer())
    private val reactionsSer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    private val attachmentsSer = ListSerializer(AttachmentRef.serializer())
    private val kickCh = Channel<Unit>(Channel.CONFLATED)
    private val processLock = Mutex()
    private val lastReadSent = HashMap<String, Long>()

    private val _typing = MutableStateFlow<Map<String, Map<String, Long>>>(emptyMap())
    val typing: StateFlow<Map<String, Map<String, Long>>> = _typing
    private val _callInvites = MutableSharedFlow<IncomingCallKey>(extraBufferCapacity = 16)
    val callInvites: SharedFlow<IncomingCallKey> = _callInvites
    private val _callFrames = MutableSharedFlow<RealtimeFrame>(extraBufferCapacity = 32)
    val callFrames: SharedFlow<RealtimeFrame> = _callFrames
    private val _orgEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** A channel got an app notification (the conversation id); its screen fetches them (docs/INTEGRATIONS.md). */
    private val _appEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val appEvents: SharedFlow<String> = _appEvents
    /** Something about apps changed for this person in a workspace (the workspace id): app screens reload. */
    private val _appUpdates = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val appUpdates: SharedFlow<String> = _appUpdates
    /** Round 7: a meeting starting in ten minutes (its id), and a workspace whose meetings changed (its id). */
    private val _meetingSoon = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val meetingSoon: SharedFlow<String> = _meetingSoon
    private val _meetingUpdates = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val meetingUpdates: SharedFlow<String> = _meetingUpdates
    val orgEvents: SharedFlow<String> = _orgEvents
    /** Round 8: something changed in a workspace's customer inbox (ids only, docs/UNIFIED_INBOX.md). */
    data class InboxEvent(val kind: String, val orgId: String, val conversationId: String?)
    private val _inboxEvents = MutableSharedFlow<InboxEvent>(extraBufferCapacity = 32)
    val inboxEvents: SharedFlow<InboxEvent> = _inboxEvents
    private val _orgDeleted = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val orgDeleted: SharedFlow<String> = _orgDeleted
    private val _reminderEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Reminders that went off or changed on another device ("reminder.due", "reminders.changed"): their ids. */
    val reminderEvents: SharedFlow<String> = _reminderEvents
    private val _prefsUpdated = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Workspaces whose personal preferences another device of this person changed ("prefs.updated"). */
    val prefsUpdated: SharedFlow<String> = _prefsUpdated
    private val _transfer = MutableStateFlow<Map<String, Float>>(emptyMap())
    val transferProgress: StateFlow<Map<String, Float>> = _transfer
    val backlogDrained = MutableStateFlow(false)

    private val myUserId get() = session.session?.userId
    private val myDeviceId get() = session.session?.deviceId

    fun start() {
        scope.launch { realtime.frames.collect { f -> runCatching { handleFrame(f) }.onFailure { Log.w(TAG, "frame ${f.t} failed: ${it.javaClass.simpleName}") } } }
        scope.launch { outboxLoop() }
        scope.launch { runCatching { retrySetAside() } }
        scope.launch {
            realtime.state.collect { st ->
                if (st == ConnState.Connected) {
                    kick()
                    runCatching { conversations.refresh() }
                } else backlogDrained.value = false
            }
        }
        scope.launch {
            while (true) {
                delay(3000)
                val now = System.currentTimeMillis()
                _typing.update { m -> m.mapValues { (_, u) -> u.filterValues { it > now } }.filterValues { it.isNotEmpty() } }
            }
        }
    }

    fun kick() {
        kickCh.trySend(Unit)
    }

    fun observeMessages(cid: String, limit: Int): Flow<List<MessageEntity>> = db.messages().observeLatest(cid, limit)
    fun observeThread(cid: String, root: String) = db.messages().observeThread(cid, root)
    fun observeThreadCounts(cid: String) = db.messages().observeThreadCounts(cid)
    fun observePinned(cid: String) = db.messages().observePinned(cid)
    fun observeWithFiles(cid: String, limit: Int) = db.messages().observeWithFiles(cid, limit)
    fun observeThreadReplies(me: String, since: Long, limit: Int) = db.messages().observeThreadReplies(me, since, limit)
    fun observeMessage(id: String) = db.messages().observe(id)
    suspend fun search(q: String, cid: String? = null): List<MessageEntity> {
        val esc = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return if (cid == null) db.messages().search(esc) else db.messages().searchIn(cid, esc)
    }

    /** Files on this phone whose name contains [q], newest first, with the message each belongs to (round 7 search). */
    suspend fun searchFiles(q: String): List<Pair<MessageEntity, AttachmentRef>> {
        val esc = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return db.messages().searchFiles(esc).flatMap { m -> attachments(m).filter { it.name.contains(q, ignoreCase = true) }.map { m to it } }.take(100)
    }

    /** Pinned messages on this phone whose text contains [q] (round 7 search). */
    suspend fun searchPinned(q: String): List<MessageEntity> =
        db.messages().searchPinned(q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"))

    fun reactions(m: MessageEntity): Map<String, List<String>> = runCatching { json.decodeFromString(reactionsSer, m.reactionsJson) }.getOrDefault(emptyMap())
    fun attachments(m: MessageEntity): List<AttachmentRef> = runCatching { json.decodeFromString(attachmentsSer, m.attachmentsJson) }.getOrDefault(emptyList())
    fun readBy(m: MessageEntity): List<String> = runCatching { json.decodeFromString(listOfStrings, m.readByJson) }.getOrDefault(emptyList())
    fun deliveredTo(m: MessageEntity): List<String> = runCatching { json.decodeFromString(listOfStrings, m.deliveredToJson) }.getOrDefault(emptyList())
    fun mentions(m: MessageEntity): List<String> = runCatching { json.decodeFromString(listOfStrings, m.mentionsJson) }.getOrDefault(emptyList())
    fun callInfo(m: MessageEntity): CallInfo? = m.callJson?.let { runCatching { json.decodeFromString(CallInfo.serializer(), it) }.getOrNull() }
    fun rich(m: MessageEntity): RichContent? = m.richJson?.let { runCatching { json.decodeFromString(RichContent.serializer(), it) }.getOrNull() }

    // ─────────────────────────── Sending ───────────────────────────

    /**
     * [mentions]: what the composer resolved against the conversation's members (docs/MENTIONS.md). Without it (a reply
     * from a notification) the text is resolved here against the members known for the conversation.
     */
    suspend fun sendText(
        cid: String, text: String, replyTo: String? = null, threadRoot: String? = null, attachments: List<AttachmentRef> = emptyList(),
        presetId: String? = null, mentions: MentionSet? = null, rich: RichContent? = null, forwarded: Boolean = false,
    ): String {
        val s = session.session ?: error("not signed in")
        val id = presetId ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        // A contact, location or animated emoji travels with a readable text, which older apps show instead.
        val body = if (rich != null && rich.structured && text.isBlank()) RichRules.fallbackText(rich) else text
        val m = mentions ?: if (rich?.structured == true) MentionSet.NONE else resolveMentions(cid, body)
        val payload = Payload(
            type = "text", text = body, replyTo = replyTo, threadRoot = threadRoot, mentions = m.ids.ifEmpty { null },
            mentionAll = if (m.all) true else null, attachments = attachments.ifEmpty { null },
            contact = rich?.contact, location = rich?.location, animoji = rich?.animoji, forwarded = if (forwarded) true else null,
            link = rich?.link, poll = rich?.poll, sticker = rich?.sticker,
        )
        val existing = db.messages().get(id)
        val msg = MessageEntity(
            id = id, conversationId = cid, senderUserId = s.userId, senderDeviceId = s.deviceId, outgoing = true, kind = "text", text = body,
            replyToId = replyTo, threadRootId = threadRoot, mentionsJson = json.encodeToString(listOfStrings, MentionRules.stored(m)),
            attachmentsJson = json.encodeToString(attachmentsSer, attachments), sentAt = existing?.sentAt ?: now, sortTs = existing?.sortTs ?: now, status = MessageStatus.SENDING,
            richJson = rich?.let { json.encodeToString(RichContent.serializer(), it) }, forwarded = forwarded,
        )
        db.messages().upsert(msg)
        db.outbox().put(OutboxEntity(id, cid, "message", json.encodeToString(Payload.serializer(), payload), now))
        if (threadRoot == null) db.conversations().bumpPreview(cid, id, preview(payload), now, "You", 0, 0)
        kick()
        return id
    }

    /**
     * Resolves `@username` tokens against this conversation's members only. It used to look usernames up among every
     * user the phone knew, so a token could point at someone outside the conversation, or `@ann:other.server` at a
     * local "ann" (docs/MENTIONS.md).
     */
    private suspend fun resolveMentions(cid: String, text: String): MentionSet {
        if (!text.contains('@')) return MentionSet.NONE
        val conv = db.conversations().get(cid) ?: return MentionSet.NONE
        if (conv.type == "DM") return MentionSet.NONE
        // What the phone already knows (no network wait while the composer still shows the message being sent).
        val members = conversations.knownMentionMembers(cid)
        return MentionRules.resolve(text, members, conversations.mentionAll.value[cid] == true, myUserId)
    }

    private suspend fun sendControl(cid: String, payload: Payload) {
        val id = UUID.randomUUID().toString()
        db.outbox().put(OutboxEntity(id, cid, "control", json.encodeToString(Payload.serializer(), payload), System.currentTimeMillis(), visible = false))
        kick()
    }

    /**
     * Where the user can't post (announcement channels for non-managers, archived scopes), the server
     * refuses every envelope, controls included. Changing the message locally would show a reaction,
     * pin, edit or deletion that nobody else sees.
     */
    private suspend fun canSendIn(cid: String) = db.conversations().get(cid)?.canPost != false

    suspend fun react(m: MessageEntity, emoji: String) {
        val me = myUserId ?: return
        if (!canSendIn(m.conversationId)) return
        val current = reactions(m)
        val add = current[emoji]?.contains(me) != true
        applyReaction(db.messages().get(m.id) ?: return, me, emoji, add) // fresh row: `m` may be stale
        sendControl(m.conversationId, Payload(type = "reaction", target = m.id, emoji = emoji, add = add))
    }

    /** Edits keep mention targets in step with the text (members only); @everyone stays as it was sent. */
    suspend fun edit(m: MessageEntity, newText: String, resolved: MentionSet? = null) {
        if (!m.outgoing || !canSendIn(m.conversationId)) return
        val ids = (resolved ?: resolveMentions(m.conversationId, newText)).ids
        val stored = MentionRules.stored(MentionSet(ids, MentionRules.fromStored(mentions(m)).all))
        db.messages().upsert(m.copy(text = newText, editedAt = System.currentTimeMillis(), mentionsJson = json.encodeToString(listOfStrings, stored)))
        sendControl(m.conversationId, Payload(type = "edit", target = m.id, text = newText, mentions = ids))
    }

    suspend fun delete(m: MessageEntity) {
        if (!m.outgoing || !canSendIn(m.conversationId)) return
        files.deleteFor(m.id, attachments(m)) // the decrypted copies go too (T1.10)
        db.messages().upsert(m.copy(deleted = true, text = "", attachmentsJson = "[]"))
        sendControl(m.conversationId, Payload(type = "delete", target = m.id))
        refreshPreview(m.conversationId)
    }

    /**
     * Your vote on the poll in [m] (docs/RICH_MESSAGES.md › Polls): shown here at once and sent to every member as an
     * encrypted "vote" control. An empty [choices] takes the vote back.
     */
    suspend fun vote(m: MessageEntity, choices: List<String>) {
        val me = myUserId ?: return
        if (!canSendIn(m.conversationId)) return
        val fresh = db.messages().get(m.id) ?: return
        val rich = rich(fresh)?.takeIf { it.poll != null && it.pollClosed != true } ?: return
        val updated = PollRules.vote(rich, me, choices)
        db.messages().upsert(fresh.copy(richJson = json.encodeToString(RichContent.serializer(), updated)))
        sendControl(m.conversationId, Payload(type = "vote", target = m.id, choices = updated.votes.orEmpty()[me].orEmpty()))
    }

    /** Closes your own poll for everyone: no more votes. */
    suspend fun closePoll(m: MessageEntity) {
        val me = myUserId ?: return
        if (!m.outgoing || !canSendIn(m.conversationId)) return
        val fresh = db.messages().get(m.id) ?: return
        val rich = rich(fresh)?.takeIf { it.poll != null } ?: return
        db.messages().upsert(fresh.copy(richJson = json.encodeToString(RichContent.serializer(), PollRules.close(rich, fresh.senderUserId, me))))
        sendControl(m.conversationId, Payload(type = "poll_close", target = m.id))
    }

    suspend fun setPinned(m: MessageEntity, pinned: Boolean) {
        if (!canSendIn(m.conversationId)) return
        db.messages().upsert(m.copy(pinned = pinned))
        sendControl(m.conversationId, Payload(type = "pin", target = m.id, pinned = pinned))
    }

    suspend fun retry(id: String) {
        val m = db.messages().get(id) ?: return
        if (m.status != MessageStatus.FAILED) return
        // Files that never finished uploading are prepared and sent again from their local copies (T2.6); the ones
        // already uploaded aren't sent twice.
        if (attachments(m).any { it.id == AttachmentFiles.PENDING }) {
            db.messages().setStatus(id, MessageStatus.SENDING, null, null)
            startBatch(id)
            return
        }
        sendText(m.conversationId, m.text, m.replyToId, m.threadRootId, attachments(m), presetId = id, rich = rich(m), forwarded = m.forwarded)
    }

    /** Distribute a call's media E2EE key to all member devices through the encrypted channel. */
    suspend fun sendCallKey(cid: String, callId: String, video: Boolean, key: String) {
        val s = session.session ?: return
        db.calls().putKey(CallKeyEntity(callId, cid, key, s.userId, System.currentTimeMillis()))
        sendControl(cid, Payload(type = "call_invite", callId = callId, video = video, e2eeKey = key))
    }

    /**
     * This call's media key, if it has arrived. While it's missing, the call's creator is asked to
     * re-send it (T5.3): a device that joins after the call started never received the original.
     */
    suspend fun callKey(callId: String): String? {
        db.calls().key(callId)?.let { return it.key }
        if (keyRequests.shouldRequest(callId)) scope.launch { runCatching { requestCallKey(callId) } }
        return null
    }

    private suspend fun requestCallKey(callId: String) {
        val call = api.get<CallRes>("/v1/calls/$callId").call
        if (call.state == "ENDED") return
        sendControl(call.conversationId, Payload(type = "call_key_request", callId = callId))
    }

    /** Clear unread and (DMs/groups) send an encrypted read receipt. */
    suspend fun markConversationRead(cid: String) {
        db.conversations().markRead(cid)
        val conv = db.conversations().get(cid) ?: return
        if (!settings.current.readReceipts || conv.type == "CHANNEL" || conv.memberCount > 20 || !conv.canPost) return
        val latest = db.messages().latestIncoming(cid) ?: return
        val upTo = latest.serverTs ?: latest.sortTs
        if ((lastReadSent[cid] ?: 0) >= upTo) return
        lastReadSent[cid] = upTo
        sendControl(cid, Payload(type = "read", upTo = upTo))
    }

    private var lastTypingSent = 0L
    fun setTyping(cid: String, on: Boolean) {
        if (!settings.current.typingIndicators) return
        val now = System.currentTimeMillis()
        if (on && now - lastTypingSent < 2500) return
        lastTypingSent = if (on) now else 0
        realtime.send("""{"t":"typing","c":"$cid","on":$on}""")
    }

    // ─────────── Attachments (encrypted before upload) ───────────

    /** The picked file is larger than attachments may be (25 MB). */
    class AttachmentTooLarge : Exception("This file is larger than this server accepts.")
    class NotEnoughSpace : Exception("Not enough free space on this phone to send this file. Free up some space and try again.")

    /**
     * Copies the picked file into app storage (streamed, never whole in memory), then encrypts and uploads
     * it (T2.6). The local copy is the retry source until the upload succeeds. A voice message passes
     * [voiceDurationMs] and travels as an ordinary encrypted attachment marked as voice.
     */
    suspend fun sendAttachment(cid: String, name: String, mime: String, input: java.io.InputStream, caption: String, voiceDurationMs: Long? = null, replyTo: String? = null) {
        val s = session.session ?: return
        val id = UUID.randomUUID().toString()
        val local = files.fileFor(id, name)
        withContext(Dispatchers.IO) {
            try {
                local.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ATTACHMENT_BYTES) throw AttachmentTooLarge()
                        out.write(buf, 0, n)
                    }
                }
            } catch (e: Exception) {
                local.delete()
                throw e
            }
        }
        val now = System.currentTimeMillis()
        val voice = voiceDurationMs?.let { true }
        val placeholder = AttachmentRef(AttachmentFiles.PENDING, name, mime, local.length(), "", "", local.name, voice, voiceDurationMs)
        db.messages().upsert(
            MessageEntity(id, cid, s.userId, s.deviceId, true, "text", caption, replyToId = replyTo, attachmentsJson = json.encodeToString(attachmentsSer, listOf(placeholder)), sentAt = now, sortTs = now, status = MessageStatus.SENDING),
        )
        db.conversations().bumpPreview(cid, id, VoiceNote.label(listOf(placeholder)) ?: "📎 $name", now, "You", 0, 0)
        uploadAttachment(id, cid, placeholder, local, caption)
    }

    /**
     * Encrypts [local] to a temporary file and uploads it, retrying transient failures. Any failure
     * leaves the message FAILED with a reason (never stuck in SENDING); the local copy stays for a retry.
     */
    private suspend fun uploadAttachment(id: String, cid: String, pending: AttachmentRef, local: File, caption: String) {
        val name = pending.name
        val enc = File(context.cacheDir, "$id.enc")
        _transfer.update { it + (id to 0f) }
        try {
            val meta = withContext(Dispatchers.IO) { AttachmentCrypto.encryptFile(local, enc) }
            val up = uploader.upload(cid, enc, meta.sha256) { p -> _transfer.update { it + (id to p) } }
            val ref = AttachmentRef(up.id, name, pending.mime, local.length(), meta.key, meta.nonce, meta.sha256, pending.voice, pending.durationMs)
            local.renameTo(files.fileFor(up.id, name))
            sendText(cid, caption, replyTo = db.messages().get(id)?.replyToId, attachments = listOf(ref), presetId = id)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e // left SENDING; marked interrupted at the next start (failInterruptedUploads)
        } catch (e: ApiException) {
            db.messages().setStatus(id, MessageStatus.FAILED, null, e.message)
        } catch (e: Exception) {
            Log.w(TAG, "attachment send failed: ${e.javaClass.simpleName}")
            db.messages().setStatus(id, MessageStatus.FAILED, null, "Couldn't send this file")
        } finally {
            enc.delete()
            _transfer.update { it - id }
        }
    }

    // ─────────── Several photos, videos or files in one message (2026-10-17, docs/ATTACHMENTS.md) ───────────

    /** One picked file for [sendBatch]: where to read it, and what [AttachmentPolicy] found when it was picked. */
    data class BatchItem(
        val uri: android.net.Uri,
        val name: String,
        val mime: String,
        val size: Long,
        val kind: AttachmentPolicy.Kind,
        val sniffed: AttachmentPolicy.Sniffed?,
        /** A voice message being forwarded: it stays a voice message. */
        val voiceDurationMs: Long? = null,
        /** Video length, when the picker could read it (for size estimates). */
        val durationMs: Long? = null,
    )

    /** An album being prepared and sent: [done] of [total] files are uploaded; [stage] describes the current one. */
    data class BatchProgress(val done: Int, val total: Int, val stage: String)

    private val _batches = MutableStateFlow<Map<String, BatchProgress>>(emptyMap())
    val batchProgress: StateFlow<Map<String, BatchProgress>> = _batches

    /** Where each not-yet-prepared file comes from, by its placeholder's local file name (lost if the app is closed). */
    private data class BatchSource(val uri: android.net.Uri, val quality: AttachmentPolicy.Quality, val kind: AttachmentPolicy.Kind, val sniffed: AttachmentPolicy.Sniffed?)
    private val batchSources = java.util.concurrent.ConcurrentHashMap<String, BatchSource>()
    private val runningBatches = java.util.Collections.synchronizedSet(HashSet<String>())

    /** At most two uploads at a time, the server's per-person limit. */
    private val uploadSlots = kotlinx.coroutines.sync.Semaphore(2)

    class MissingOriginal : Exception("Some files are no longer on this phone. Delete this message and pick them again.")

    /**
     * Sends up to [AttachmentPolicy.MAX_ITEMS] photos, videos or files as one message (an album), with one [caption].
     * The message appears at once; each file is then prepared at [quality], encrypted and uploaded in the background (it
     * carries on when you leave the conversation), and the message is sent once every file is uploaded. A failure leaves
     * the message FAILED with a reason; retrying resumes with the files that aren't uploaded yet. Returns the message id.
     */
    suspend fun sendBatch(
        cid: String, items: List<BatchItem>, photoQuality: AttachmentPolicy.Quality, caption: String, replyTo: String? = null,
        videoQuality: AttachmentPolicy.Quality = AttachmentPolicy.Quality.ORIGINAL, forwarded: Boolean = false,
    ): String {
        val s = session.session ?: throw IllegalStateException("Not signed in")
        require(items.size in 1..AttachmentPolicy.MAX_ITEMS) { "1 to ${AttachmentPolicy.MAX_ITEMS} files" }
        val id = UUID.randomUUID().toString()
        val placeholders = items.mapIndexed { i, it ->
            val local = files.fileFor("$id-$i", it.name)
            val q = if (it.kind == AttachmentPolicy.Kind.VIDEO) videoQuality else photoQuality
            batchSources[local.name] = BatchSource(it.uri, q, it.kind, it.sniffed)
            AttachmentRef(
                AttachmentFiles.PENDING, it.name, AttachmentPolicy.sendMime(it.mime, it.kind, it.sniffed), it.size, "", "", local.name,
                voice = it.voiceDurationMs?.let { true }, durationMs = it.voiceDurationMs,
            )
        }
        val now = System.currentTimeMillis()
        db.messages().upsert(
            MessageEntity(
                id, cid, s.userId, s.deviceId, true, "text", caption, replyToId = replyTo, attachmentsJson = json.encodeToString(attachmentsSer, placeholders),
                sentAt = now, sortTs = now, status = MessageStatus.SENDING, forwarded = forwarded,
            ),
        )
        db.conversations().bumpPreview(cid, id, caption.ifBlank { AttachmentPolicy.preview(items.map { it.kind }) }, now, "You", 0, 0)
        startBatch(id)
        return id
    }

    private val batchJobs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    private fun startBatch(id: String) {
        batchJobs[id] = scope.launch { try { processBatch(id) } finally { batchJobs.remove(id) } }
    }

    /**
     * Cancels a message whose files are still being prepared or uploaded (it hasn't been sent): the upload stops and the
     * message and its local copies are removed. Nothing reaches anyone. Returns false once the message has been sent.
     */
    suspend fun cancelBatch(id: String): Boolean {
        val m = db.messages().get(id) ?: return false
        if (m.status != MessageStatus.SENDING && m.status != MessageStatus.FAILED) return false
        if (db.outbox().get(id) != null) return false // already handed to the outbox: it's being sent
        batchJobs.remove(id)?.cancel()
        attachments(m).forEach { ref -> if (ref.id == AttachmentFiles.PENDING) batchSources.remove(ref.sha256) }
        files.deleteFor(id, attachments(m))
        db.messages().deleteLocal(id)
        refreshPreview(m.conversationId)
        return true
    }

    /**
     * The largest file this server accepts (its `/v1/server-info`), capped at the app's own limit. Cached for
     * [SERVER_LIMIT_TTL_MS], so a limit the server's operator raises (production ran 25 MB) applies without restarting Meldiq.
     */
    @Volatile private var serverMaxBytes: Pair<Long, Long>? = null // limit, when it was read
    suspend fun maxAttachmentBytes(): Long {
        serverMaxBytes?.let { (limit, at) -> if (System.currentTimeMillis() - at < SERVER_LIMIT_TTL_MS) return limit }
        val info = runCatching { api.get<com.meldiq.android.data.api.ServerInfo>("/v1/server-info") }.getOrNull()
        return AttachmentPolicy.effectiveMax(info?.attachmentMaxBytes, info?.fileMaxBytes)
            .also { if (info != null) serverMaxBytes = it to System.currentTimeMillis() }
    }

    /**
     * Forwards [m] to [destCid], which the person chose and confirmed. Text, contact cards, locations and animated
     * emoji go as a new message; files are decrypted copies on this phone (downloaded first if needed), encrypted again
     * with new keys and uploaded for the destination, because each conversation's files can only be fetched by its
     * members. Mentions aren't carried over. Returns the new message's id.
     */
    suspend fun forward(m: MessageEntity, destCid: String): String {
        val refs = attachments(m).filter { it.id != AttachmentFiles.PENDING }
        if (refs.isEmpty()) return sendText(destCid, m.text, mentions = MentionSet.NONE, rich = rich(m), forwarded = true)
        val items = refs.map { ref ->
            val f = downloadAttachment(ref)
            val head = withContext(Dispatchers.IO) { f.inputStream().use { i -> ByteArray(64).let { b -> b.copyOf(i.read(b).coerceAtLeast(0)) } } }
            val sniffed = AttachmentPolicy.sniff(head)
            BatchItem(
                android.net.Uri.fromFile(f), ref.name, ref.mime, f.length(), AttachmentPolicy.kind(ref.mime, sniffed), sniffed,
                voiceDurationMs = if (ref.voice == true) ref.durationMs ?: 0 else null,
            )
        }
        val caption = if (m.text.isBlank()) "" else m.text
        return sendBatch(destCid, items, AttachmentPolicy.Quality.ORIGINAL, caption, forwarded = true)
    }

    private suspend fun processBatch(id: String) {
        if (!runningBatches.add(id)) return
        val m0 = db.messages().get(id) ?: run { runningBatches.remove(id); return }
        val cid = m0.conversationId
        val refs = attachments(m0).toMutableList()
        val total = refs.size
        suspend fun save() {
            db.messages().get(id)?.let { db.messages().upsert(it.copy(attachmentsJson = json.encodeToString(attachmentsSer, refs))) }
        }
        fun progress(i: Int, stage: String, fraction: Float) {
            _batches.update { it + (id to BatchProgress(refs.count { r -> r.id != AttachmentFiles.PENDING }, total, stage)) }
            _transfer.update { it + (id to ((i + fraction.coerceIn(0f, 1f)) / total)) }
        }
        try {
            for (i in refs.indices) {
                val pending = refs[i]
                if (pending.id != AttachmentFiles.PENDING) continue
                val local = files.child(pending.sha256) ?: throw MissingOriginal()
                val what = if (total > 1) " ${i + 1} of $total" else ""
                var ref = pending
                if (!local.exists()) {
                    val src = batchSources[pending.sha256] ?: throw MissingOriginal()
                    progress(i, "Preparing$what", 0f)
                    ref = prepare(pending, local, src) { f -> progress(i, (if (src.kind == AttachmentPolicy.Kind.VIDEO) "Compressing video" else "Preparing") + what, f * 0.5f) }
                    batchSources.remove(pending.sha256)
                    refs[i] = ref
                    save()
                }
                progress(i, "Uploading$what", 0f)
                val uploaded = uploadSlots.withPermit {
                    val enc = File(context.cacheDir, "${local.name}.enc")
                    try {
                        // Over 25 MB: the chunked format, so neither side ever holds the whole file in memory.
                        val chunked = local.length() > AttachmentPolicy.LEGACY_MAX_FILE_BYTES
                        val meta = withContext(Dispatchers.IO) {
                            if (chunked) ChunkedAttachmentCrypto.encryptFile(local, enc) else AttachmentCrypto.encryptFile(local, enc)
                        }
                        val up = uploader.upload(cid, enc, meta.sha256) { p -> progress(i, "Uploading$what", 0.5f + p * 0.5f) }
                        AttachmentRef(
                            up.id, ref.name, ref.mime, local.length(), meta.key, meta.nonce, meta.sha256, ref.voice, ref.durationMs,
                            chunk = if (chunked) ChunkedAttachmentCrypto.DEFAULT_CHUNK else null,
                        )
                    } finally {
                        enc.delete()
                    }
                }
                local.renameTo(files.fileFor(uploaded.id, uploaded.name))
                refs[i] = uploaded
                save()
            }
            val m = db.messages().get(id) ?: return
            sendText(cid, m.text, replyTo = m.replyToId, attachments = refs, presetId = id, forwarded = m.forwarded, mentions = if (m.forwarded) MentionSet.NONE else null)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e // left SENDING; marked interrupted at the next start (failInterruptedUploads)
        } catch (e: ApiException) {
            db.messages().setStatus(id, MessageStatus.FAILED, null, e.message)
        } catch (e: AttachmentTooLarge) {
            db.messages().setStatus(id, MessageStatus.FAILED, null, e.message)
        } catch (e: NotEnoughSpace) {
            db.messages().setStatus(id, MessageStatus.FAILED, null, e.message)
        } catch (e: MissingOriginal) {
            db.messages().setStatus(id, MessageStatus.FAILED, null, e.message)
        } catch (e: com.meldiq.android.media.MediaPrep.TooLargeAfterCompression) {
            db.messages().setStatus(id, MessageStatus.FAILED, null, e.message)
        } catch (e: com.meldiq.android.media.MediaPrep.CompressionFailed) {
            Log.w(TAG, "video compression failed: ${generateSequence(e.cause) { it.cause }.take(3).joinToString(" < ") { it.javaClass.simpleName }}")
            db.messages().setStatus(id, MessageStatus.FAILED, null, e.message)
        } catch (e: Exception) {
            Log.w(TAG, "attachment batch failed: ${e.javaClass.simpleName}")
            db.messages().setStatus(id, MessageStatus.FAILED, null, if (total > 1) "Couldn't send these files" else "Couldn't send this file")
        } finally {
            runningBatches.remove(id)
            _batches.update { it - id }
            _transfer.update { it - id }
        }
    }

    /**
     * Writes the file to send into [local] (which appears only when complete): the picked file as it is, or a Standard/HD
     * version of a photo or video. Returns the placeholder with its final name, type and size.
     */
    private suspend fun prepare(pending: AttachmentRef, local: File, src: BatchSource, onProgress: (Float) -> Unit): AttachmentRef {
        val compress = src.quality != AttachmentPolicy.Quality.ORIGINAL && AttachmentPolicy.compressible(src.kind, src.sniffed)
        // Large files (up to 1 GiB) need room for their copy and its encrypted copy: refuse early rather than fill the phone.
        if ((local.parentFile ?: context.filesDir).usableSpace < AttachmentPolicy.spaceNeeded(pending.size)) throw NotEnoughSpace()
        val part = File(local.parentFile, local.name + ".part")
        val raw = File(context.cacheDir, "prep-${local.name}")
        try {
            val copyTo = if (compress) raw else part
            val max = maxAttachmentBytes()
            copyFromUri(src.uri, copyTo, if (compress) AttachmentPolicy.MAX_SOURCE_BYTES else max)
            // The bytes read now must still be what was checked when it was picked (a file can change after picking).
            val head = copyTo.inputStream().use { i -> ByteArray(64).let { b -> b.copyOf(i.read(b).coerceAtLeast(0)) } }
            val kindNow = AttachmentPolicy.kind(pending.mime, AttachmentPolicy.sniff(head))
            var name = pending.name
            var mime = if (kindNow == src.kind) pending.mime else AttachmentPolicy.sendMime(pending.mime, kindNow, AttachmentPolicy.sniff(head))
            if (compress && kindNow == src.kind) {
                val done = when (src.kind) {
                    AttachmentPolicy.Kind.PHOTO -> com.meldiq.android.media.MediaPrep.photo(raw, part, name, src.quality)
                    else -> com.meldiq.android.media.MediaPrep.video(context, raw, part, name, src.quality, max, onProgress)
                }
                if (done == null) {
                    // Already within this quality: sent as it is, if it fits.
                    if (raw.length() > max) throw AttachmentTooLarge()
                    if (!raw.renameTo(part)) raw.copyTo(part, overwrite = true)
                } else {
                    name = done.name
                    mime = done.mime
                }
            } else if (compress) {
                if (raw.length() > max) throw AttachmentTooLarge()
                if (!raw.renameTo(part)) raw.copyTo(part, overwrite = true)
            }
            if (part.length() > max) throw AttachmentTooLarge()
            if (!part.renameTo(local)) throw java.io.IOException("couldn't store the prepared file")
            return pending.copy(name = name, mime = mime, size = local.length())
        } finally {
            part.delete()
            raw.delete()
        }
    }

    private suspend fun copyFromUri(uri: android.net.Uri, out: File, max: Long) = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw MissingOriginal()
        input.use { i ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > max) throw AttachmentTooLarge()
                    o.write(buf, 0, n)
                }
                if (total == 0L) throw java.io.IOException("empty file")
            }
        }
    }

    fun localAttachment(ref: AttachmentRef): File? =
        files.fileFor(ref.id, ref.name).takeIf { it.exists() } ?: files.child(ref.sha256)?.takeIf { ref.id == AttachmentFiles.PENDING && it.exists() }

    /**
     * Downloads the ciphertext to a partial file (resumed after an interruption), checks its hash, and
     * decrypts it next to the other attachments (T2.6). A corrupt partial file is discarded.
     */
    suspend fun downloadAttachment(ref: AttachmentRef): File {
        localAttachment(ref)?.let { return it }
        val part = File(context.cacheDir, "dl-${ref.id.replace(Regex("[^A-Za-z0-9_-]"), "_")}.part")
        _transfer.update { it + (ref.id to 0f) }
        try {
            TransferRetry.run { api.downloadTo("/v1/attachments/${ref.id}", part) { p -> _transfer.update { it + (ref.id to p) } } }
            val out = files.fileFor(ref.id, ref.name)
            try {
                withContext(Dispatchers.IO) {
                    val chunk = ref.chunk
                    if (chunk != null) ChunkedAttachmentCrypto.decryptFile(part, ref.key, ref.nonce, ref.sha256, chunk, out)
                    else AttachmentCrypto.decryptFile(part, ref.key, ref.nonce, ref.sha256, out)
                }
            } catch (e: EnvelopeException) {
                part.delete() // start over next time rather than resume corrupt bytes
                throw e
            }
            part.delete()
            return out
        } catch (e: ApiException) {
            if (e.status == 404) part.delete()
            throw e
        } finally {
            _transfer.update { it - ref.id }
        }
    }


    // ─────────────────────────── Outbox ───────────────────────────

    private suspend fun outboxLoop() {
        while (true) {
            if (session.session != null && realtime.state.value != ConnState.Offline) {
                runCatching { processOutbox() }.onFailure { Log.w(TAG, "outbox: ${it.javaClass.simpleName}") }
            }
            val next = db.outbox().nextDue()
            val wait = if (next == null) 60_000L else (next - System.currentTimeMillis()).coerceIn(300L, 60_000L)
            withTimeoutOrNull(wait) { kickCh.receive() }
        }
    }

    private suspend fun processOutbox() {
        for (item in db.outbox().due(System.currentTimeMillis())) sendOne(item)
    }

    private suspend fun sendOne(item: OutboxEntity) {
        val s = session.session ?: return
        var force = item.attempts > 0
        for (pass in 0..1) {
            try {
                val recips = directory.conversationRecipients(item.conversationId, s.deviceId, force)
                val now = System.currentTimeMillis()
                val sentAt = if (now - item.createdAt > 20 * 3600_000L) now else item.createdAt
                val sealed = crypto.seal(EnvelopeHeader(item.messageId, item.conversationId, s.userId, s.deviceId, sentAt, item.cls), item.payloadJson.toByteArray(), recips)
                // @everyone asks the server for its attestation, on every attempt (a retry of an accepted message is a duplicate there).
                val mentionAll = item.cls == "message" && runCatching { json.decodeFromString(Payload.serializer(), item.payloadJson).mentionAll == true }.getOrDefault(false)
                val res = api.post<SendMessageReq, SendMessageRes>(
                    "/v1/messages",
                    SendMessageReq(
                        item.conversationId, item.messageId, item.cls, sentAt, sealed.nonce, sealed.ciphertext, sealed.signature,
                        sealed.recipients.map { WireKeyWrap(it.deviceId, it.enc, it.wrappedKey) }, mentionAll = if (mentionAll) true else null,
                    ),
                )
                db.outbox().remove(item.messageId)
                if (item.visible) db.messages().markSent(item.messageId, res.serverTs)
                return
            } catch (e: ApiException) {
                if (e.status == 409 && e.code == "device_mismatch" && pass == 0) {
                    directory.invalidate(item.conversationId)
                    force = true
                    continue
                }
                val transient = e.isNetwork || e.status >= 500 || e.status == 429 || e.status == 401 || e.code == "decode"
                if (transient && item.attempts < MAX_ATTEMPTS) {
                    val backoff = BACKOFF.getOrElse(item.attempts) { BACKOFF.last() }
                    db.outbox().put(item.copy(attempts = item.attempts + 1, nextAttemptAt = System.currentTimeMillis() + backoff, lastError = e.message))
                } else {
                    db.outbox().remove(item.messageId)
                    if (item.visible) db.messages().setStatus(item.messageId, MessageStatus.FAILED, null, e.message)
                }
                return
            } catch (e: IdentityChangedException) {
                // Paused, not failed: resumes when the user accepts the new key (acceptIdentity), and
                // is re-checked now and then in case the server goes back to the pinned key.
                db.outbox().put(item.copy(nextAttemptAt = System.currentTimeMillis() + HELD_RECHECK_MS, lastError = HELD_REASON))
                if (item.visible) db.messages().setStatus(item.messageId, MessageStatus.HELD, null, "Paused: a security key changed")
                return
            } catch (e: Exception) {
                Log.w(TAG, "send failed: ${e.javaClass.simpleName}")
                db.outbox().remove(item.messageId)
                if (item.visible) db.messages().setStatus(item.messageId, MessageStatus.FAILED, null, "Couldn't encrypt this message")
                return
            }
        }
    }

    // ─────────────────────────── Receiving ───────────────────────────

    private suspend fun handleFrame(f: RealtimeFrame) {
        when (f.t) {
            "queue" -> processQueue(f.items.orEmpty(), f.backlog == true)
            "queue.drained" -> backlogDrained.value = true
            "typing" -> {
                val c = f.c ?: return
                val u = f.u ?: return
                _typing.update { m ->
                    val cur = m[c].orEmpty().toMutableMap()
                    if (f.on == true) cur[u] = System.currentTimeMillis() + 6000 else cur.remove(u)
                    m + (c to cur)
                }
            }
            "presence" -> f.userId?.let { db.users().setPresence(it, f.online == true, f.lastSeenAt) }
            "conv.updated" -> f.conversationId?.let { conversations.refreshOne(it) }
            "org.updated" -> {
                f.orgId?.let { _orgEvents.tryEmit(it) }
                runCatching { conversations.refresh() }
            }
            // 2026-10-17: an owner deleted the organization (servers before then only send org.updated).
            "org.deleted" -> f.orgId?.let { id ->
                _orgDeleted.tryEmit(id)
                _orgEvents.tryEmit(id)
            }
            "devices.changed" -> f.userId?.let { directory.invalidateUser(it) }
            "prefs.updated" -> f.workspaceId?.let { _prefsUpdated.tryEmit(it) }
            "reminder.due", "reminders.changed" -> f.id?.let { _reminderEvents.tryEmit(it) }
            "integration.event" -> f.conversationId?.let { _appEvents.tryEmit(it) }
            "integration.updated" -> f.orgId?.let { _appUpdates.tryEmit(it) }
            // Round 7 meetings (docs/WORKSPACE_C5.md).
            "meeting.soon" -> f.meetingId?.let { _meetingSoon.tryEmit(it) }
            "meeting.updated" -> f.orgId?.let { _meetingUpdates.tryEmit(it) }
            "inbox.updated", "inbox.escalated", "inbox.overdue", "inbox.delivery_failed", "inbox.account_failed" ->
                f.orgId?.let { _inboxEvents.tryEmit(InboxEvent(f.t, it, f.conversationId)) }
            "call" -> _callFrames.tryEmit(f)
        }
    }

    /** Acknowledges an item only once its local state is committed or it's safely kept (T1.4, [QueueProcessor]). */
    private val processor = QueueProcessor(
        apply = { handleItem(it) },
        undecryptable = { item, reason -> recordUndecryptable(item, reason) },
        quarantine = { quarantine(it) },
        setAside = { item, e -> setAside(item, e) },
    )

    private suspend fun processQueue(items: List<QueueItem>, backlog: Boolean) = processLock.withLock {
        val acks = processor.process(items)
        if (acks.size < items.size) Log.w(TAG, "deferring ${items.size - acks.size} queue item(s) for redelivery")
        if (acks.isNotEmpty() || backlog) {
            val sent = realtime.send("""{"t":"ack","ids":[${acks.joinToString(",")}],"backlog":$backlog}""")
            if (!sent && acks.isNotEmpty()) runCatching { api.post<AckReq, OkRes>("/v1/queue/ack", AckReq(acks)) }
        }
    }

    /** REST fallback used by the background service when the socket is unavailable. */
    suspend fun pollQueueOnce() {
        val r = api.get<QueueRes>("/v1/queue?limit=100")
        if (r.items.isEmpty()) return
        val acks = processLock.withLock { processor.process(r.items) }
        if (acks.isNotEmpty()) api.post<AckReq, OkRes>("/v1/queue/ack", AckReq(acks))
    }

    /**
     * Retries items set aside after repeated failures (for example after an app update fixed the
     * cause). Called at start-up; whatever still fails stays set aside.
     */
    private suspend fun retrySetAside() = processLock.withLock {
        pending.purgeExpired()
        // Uploads cut off by the app being killed: offer a retry instead of "sending" forever (T2.6).
        db.messages().failInterruptedUploads("Upload interrupted · tap to retry")
        context.cacheDir.listFiles { f -> f.name.endsWith(".enc") }?.forEach { it.delete() }
        for (q in db.quarantine().setAside()) {
            val item = runCatching { json.decodeFromString(QueueItem.serializer(), q.itemJson) }.getOrNull() ?: continue
            if (runCatching { handleItem(item) }.isSuccess) db.quarantine().remove(q.queueId)
        }
    }

    private suspend fun handleItem(item: QueueItem) {
        // Never "succeed" without a session: that would acknowledge (and lose) the item unprocessed.
        val me = myUserId ?: throw ApiException(0, "not_signed_in", "Not signed in yet")
        val myDevice = myDeviceId ?: throw ApiException(0, "not_signed_in", "Not signed in yet")
        if (item.kind == "receipt") {
            if (item.payload.status == "delivered") {
                val m = db.messages().get(item.messageId) ?: return
                if (!m.outgoing) return
                val d = (deliveredTo(m) + item.senderUserId).distinct()
                db.messages().upsert(m.copy(deliveredToJson = json.encodeToString(listOfStrings, d)))
                db.messages().markDelivered(m.id)
            }
            return
        }
        val p = item.payload
        val existing = db.messages().get(item.messageId)
        // A duplicate delivery is ignored, but a placeholder left by an earlier failure may be replaced.
        val placeholder = existing?.kind == "undecryptable" && existing.senderUserId == item.senderUserId
        if (existing != null && p.cls == "message" && !placeholder) return
        val signingKey = when (val k = directory.senderKey(item.senderUserId, item.senderDeviceId, p.serverTs ?: item.createdAt)) {
            is SenderKey.Trusted -> k.signingKey
            SenderKey.IdentityChanged -> throw SenderIdentityChanged()
            SenderKey.Unknown -> throw EnvelopeException("unknown or uncertified sender device")
        }
        val header = EnvelopeHeader(item.messageId, item.conversationId, item.senderUserId, item.senderDeviceId, p.sentAt ?: 0, p.cls ?: "message")
        val env = ReceivedEnvelope(
            header,
            p.nonce ?: throw EnvelopeException("malformed envelope"), p.ciphertext ?: throw EnvelopeException("malformed envelope"),
            p.signature ?: throw EnvelopeException("malformed envelope"), p.enc ?: throw EnvelopeException("malformed envelope"),
            p.wrappedKey ?: throw EnvelopeException("malformed envelope"),
        )
        val plain = crypto.open(env, signingKey, myDevice)
        val payload = json.decodeFromString(Payload.serializer(), String(plain))
        val conv = db.conversations().get(item.conversationId) ?: conversations.refreshOne(item.conversationId)
        if (db.users().get(item.senderUserId)?.displayName.isNullOrEmpty()) runCatching { users.fetch(item.senderUserId) }
        apply(payload, item, conv, item.senderUserId == me, p.serverTs ?: item.createdAt)
    }

    private suspend fun apply(pl: Payload, item: QueueItem, conv: ConversationEntity?, mine: Boolean, serverTs: Long) {
        val me = myUserId!!
        when (pl.type) {
            "text" -> {
                // Names are cleaned for display (no hidden or direction-changing characters that could disguise a type).
                val atts = pl.attachments.orEmpty().take(MAX_RECEIVED_ATTACHMENTS).map { it.copy(name = AttachmentPolicy.safeName(it.name)) }
                // @everyone counts only with the server's mark on this copy, in a group or channel; a claim inside the
                // payload alone (or from another server) is plain text (docs/MENTIONS.md).
                val received = MentionRules.received(pl.mentions, pl.mentionAll, item.payload.mentionAll, conv?.type)
                val m = MessageEntity(
                    id = item.messageId, conversationId = item.conversationId, senderUserId = item.senderUserId, senderDeviceId = item.senderDeviceId,
                    outgoing = mine, kind = "text", text = pl.text.orEmpty(), replyToId = pl.replyTo, threadRootId = pl.threadRoot,
                    mentionsJson = json.encodeToString(listOfStrings, MentionRules.stored(received)), attachmentsJson = json.encodeToString(attachmentsSer, atts),
                    sentAt = item.payload.sentAt ?: serverTs, serverTs = serverTs, sortTs = serverTs,
                    status = if (mine) MessageStatus.SENT else MessageStatus.RECEIVED,
                    richJson = RichRules.fromPayload(pl)?.let { json.encodeToString(RichContent.serializer(), it) },
                    forwarded = pl.forwarded == true,
                )
                if (db.messages().insertIgnore(m) == -1L) return
                replayPending(item.conversationId, item.messageId, conv, me)
                val viewing = appState.foreground.value && appState.viewingConversation.value == item.conversationId
                val mention = !mine && (me in received.ids || received.all)
                // A muted conversation still notifies when someone mentions you by name; @everyone respects the mute.
                val personalMention = !mine && me in received.ids
                val unread = if (!mine && !viewing) 1 else 0
                val senderName = if (mine) "You" else db.users().get(item.senderUserId)?.displayName ?: "Someone"
                if (pl.threadRoot == null) {
                    db.conversations().bumpPreview(item.conversationId, item.messageId, preview(pl), serverTs, senderName, unread, if (mention && !viewing) 1 else 0)
                } else if (unread > 0) db.conversations().bumpUnread(item.conversationId, 0, if (mention) 1 else 0)
                if (viewing && !mine) scope.launch { markConversationRead(item.conversationId) }
                val muted = conv?.muted == true
                val level = conv?.let { notifyLevelOf(it) } ?: com.meldiq.android.data.model.NotifyLevel.ALL
                if (!mine && !viewing && MentionNotify.shouldNotify(settings.current.notifications, level, muted, mention, personalMention)) {
                    val title = conv?.let { ConversationRepository.title(it, me) } ?: senderName
                    notify(Notice(item.conversationId, if (conv?.type == "CHANNEL") "#$title" else title, senderName, preview(pl), mention, conv?.type != "DM", canReply = conv?.canPost != false))
                }
            }
            "edit" -> {
                val t = target(pl, item, serverTs) ?: return
                if (t.senderUserId == item.senderUserId && !t.deleted) {
                    // New text, new mention targets (no new notification); @everyone stays as it was when sent.
                    val mentionsJson = pl.mentions?.let { ids ->
                        json.encodeToString(listOfStrings, MentionRules.stored(MentionSet(ids.distinct(), MentionRules.fromStored(mentions(t)).all)))
                    } ?: t.mentionsJson
                    db.messages().upsert(t.copy(text = pl.text.orEmpty(), editedAt = serverTs, mentionsJson = mentionsJson))
                    if (db.conversations().get(t.conversationId)?.lastMessageId == t.id) refreshPreview(t.conversationId)
                }
            }
            "delete" -> {
                val t = target(pl, item, serverTs) ?: return
                if (t.senderUserId == item.senderUserId) {
                    files.deleteFor(t.id, attachments(t))
                    db.messages().upsert(t.copy(deleted = true, text = "", attachmentsJson = "[]"))
                    refreshPreview(t.conversationId)
                }
            }
            "reaction" -> {
                val emoji = pl.emoji ?: return
                applyReaction(target(pl, item, serverTs) ?: return, item.senderUserId, emoji, pl.add != false)
            }
            "pin" -> {
                val t = target(pl, item, serverTs) ?: return
                db.messages().upsert(t.copy(pinned = pl.pinned == true))
            }
            "vote" -> {
                val t = target(pl, item, serverTs) ?: return
                val rich = rich(t)?.takeIf { it.poll != null } ?: return
                val updated = PollRules.vote(rich, item.senderUserId, pl.choices.orEmpty().take(PollRules.MAX_OPTIONS))
                if (updated != rich) db.messages().upsert(t.copy(richJson = json.encodeToString(RichContent.serializer(), updated)))
            }
            "poll_close" -> {
                val t = target(pl, item, serverTs) ?: return
                val rich = rich(t)?.takeIf { it.poll != null } ?: return
                val updated = PollRules.close(rich, t.senderUserId, item.senderUserId)
                if (updated != rich) db.messages().upsert(t.copy(richJson = json.encodeToString(RichContent.serializer(), updated)))
            }
            "read" -> {
                val upTo = pl.upTo ?: return
                if (mine) {
                    db.conversations().markRead(item.conversationId)
                } else {
                    for (m in db.messages().outgoingUpTo(item.conversationId, upTo)) {
                        val r = (readBy(m) + item.senderUserId).distinct()
                        db.messages().upsert(m.copy(readByJson = json.encodeToString(listOfStrings, r), status = MessageStatus.READ))
                    }
                }
            }
            "call_key_request" -> {
                // Only the call's creator answers, in the call's own conversation, while the call is live (T5.3).
                val callId = pl.callId ?: return
                val k = db.calls().key(callId) ?: return
                if (k.conversationId != item.conversationId || !keyRequests.shouldAnswer(callId)) return
                val call = runCatching { api.get<CallRes>("/v1/calls/$callId").call }.getOrNull() ?: return // the joiner asks again
                if (call.state == "ENDED") return
                // The creator answers; in a voice room, so does anyone connected who holds the key (docs/VOICE_ROOMS.md).
                val mayAnswer = (k.fromUserId == me && call.createdBy == me) || (call.voiceRoom && call.participants.any { it.userId == me })
                if (!mayAnswer) return
                sendControl(item.conversationId, Payload(type = "call_invite", callId = callId, video = call.video, e2eeKey = k.key))
            }
            "call_invite" -> {
                val callId = pl.callId ?: return
                val key = pl.e2eeKey ?: return
                if (db.calls().key(callId) != null) {
                    Log.w(TAG, "ignoring second media key for an existing call")
                    return
                }
                val verdict = callKeys.check(callId, item.conversationId, item.senderUserId)
                if (verdict != CallKeyVerifier.Verdict.ACCEPT) {
                    Log.w(TAG, "ignoring call key: $verdict") // T5.2: only the call's creator sets its key
                    return
                }
                if (db.calls().putKeyIfAbsent(CallKeyEntity(callId, item.conversationId, key, item.senderUserId, System.currentTimeMillis())) == -1L) {
                    Log.w(TAG, "ignoring second media key for an existing call")
                    return
                }
                val info = CallInfo(callId, pl.video == true)
                // A voice room's key: joining the room isn't a call to record in its conversation.
                val voiceRoom = db.conversations().get(item.conversationId)?.voice == true
                if (!voiceRoom) db.messages().insertIgnore(
                    MessageEntity(
                        id = "call-$callId", conversationId = item.conversationId, senderUserId = item.senderUserId, senderDeviceId = item.senderDeviceId,
                        outgoing = mine, kind = "call", text = if (pl.video == true) "Video call" else "Voice call", sentAt = serverTs, serverTs = serverTs,
                        sortTs = serverTs, status = if (mine) MessageStatus.SENT else MessageStatus.RECEIVED, callJson = json.encodeToString(CallInfo.serializer(), info),
                    ),
                )
                if (!mine) _callInvites.tryEmit(IncomingCallKey(callId, item.conversationId, item.senderUserId, pl.video == true))
            }
        }
    }

    /**
     * The message a control refers to, only if it's in the control's own conversation (T3.4). A target
     * that hasn't arrived yet holds the control until it does (T2.1); one in another conversation is ignored.
     */
    private suspend fun target(pl: Payload, item: QueueItem, serverTs: Long): MessageEntity? {
        val id = pl.target ?: return null
        val t = db.messages().get(id)
        if (t == null) {
            pending.hold(item.messageId, item.conversationId, id, item.senderUserId, json.encodeToString(Payload.serializer(), pl), serverTs)
            return null
        }
        return t.takeIf { it.conversationId == item.conversationId }
    }

    /** Applies, in their original order, the controls that arrived before this message. */
    private suspend fun replayPending(conversationId: String, messageId: String, conv: ConversationEntity?, me: String) {
        for (c in pending.takeFor(conversationId, messageId)) {
            val pl = runCatching { json.decodeFromString(Payload.serializer(), c.payloadJson) }.getOrNull() ?: continue
            val item = QueueItem(0, "envelope", c.conversationId, c.id, c.senderUserId, "", c.serverTs, c.expiresAt, QueuePayload(cls = "control", serverTs = c.serverTs))
            apply(pl, item, conv, c.senderUserId == me, c.serverTs)
        }
    }

    private suspend fun applyReaction(t: MessageEntity, userId: String, emoji: String, add: Boolean) {
        val map = reactions(t).toMutableMap()
        val set = map[emoji].orEmpty().toMutableList()
        if (add) { if (userId !in set) set += userId } else set.remove(userId)
        if (set.isEmpty()) map.remove(emoji) else map[emoji] = set
        db.messages().upsert(t.copy(reactionsJson = json.encodeToString(reactionsSer, map)))
    }

    // ─────────────────────────── Call history ───────────────────────────

    /**
     * Writes or updates the conversation's entry for [call] from the server's record (docs/CALL_HISTORY.md). Idempotent:
     * one entry per call id, updated in place, so repeated events and syncs never duplicate it.
     */
    suspend fun recordCall(call: CallDto) {
        val me = session.session?.userId ?: return
        if (db.conversations().get(call.conversationId) == null) return
        // A voice room's comings and goings aren't calls in its history (docs/VOICE_ROOMS.md); an entry an earlier
        // build made is removed (phone test: opening the room added "Group voice call in progress").
        if (call.voiceRoom) {
            db.messages().deleteLocal("call-${call.id}")
            refreshPreview(call.conversationId)
            return
        }
        val declinedHere = db.calls().getLog(call.id)?.endReason == "declined"
        val info = CallHistory.summarize(call, me, declinedHere)
        val id = "call-${call.id}"
        val encoded = json.encodeToString(CallInfo.serializer(), info)
        val existing = db.messages().get(id)
        if (existing == null) {
            val mine = call.createdBy == me
            db.messages().insertIgnore(
                MessageEntity(
                    id = id, conversationId = call.conversationId, senderUserId = call.createdBy, senderDeviceId = "", outgoing = mine,
                    kind = "call", text = if (call.video) "Video call" else "Voice call", sentAt = call.createdAt, serverTs = call.createdAt,
                    sortTs = call.createdAt, status = if (mine) MessageStatus.SENT else MessageStatus.RECEIVED, callJson = encoded,
                ),
            )
        } else if (existing.kind == "call" && existing.conversationId == call.conversationId && existing.callJson != encoded) {
            db.messages().upsert(existing.copy(callJson = encoded))
        } else return
        refreshPreview(call.conversationId)
    }

    /** Brings the conversation's call entries up to date (on open). Servers before 2026-10-17 have no call list: skipped. */
    suspend fun syncCalls(cid: String) {
        val calls = runCatching { api.get<CallsRes>("/v1/conversations/$cid/calls?limit=50").calls }.getOrNull() ?: return
        for (c in calls.asReversed()) if (c.conversationId == cid) recordCall(c)
    }

    /** A call that never started because the request to start it failed: a local entry, so the attempt isn't lost. */
    suspend fun recordFailedCall(cid: String, video: Boolean) {
        val conv = db.conversations().get(cid) ?: return
        val me = session.session?.userId ?: return
        val now = System.currentTimeMillis()
        val id = "local-${UUID.randomUUID()}"
        val info = CallHistory.failedAttempt(id, video, me, conv.type != "DM")
        db.messages().insertIgnore(
            MessageEntity(
                id = "call-$id", conversationId = cid, senderUserId = me, senderDeviceId = "", outgoing = true, kind = "call",
                text = if (video) "Video call" else "Voice call", sentAt = now, sortTs = now, status = MessageStatus.SENT,
                callJson = json.encodeToString(CallInfo.serializer(), info),
            ),
        )
        refreshPreview(cid)
    }

    private suspend fun refreshPreview(cid: String) {
        val conv = db.conversations().get(cid) ?: return
        val last = db.messages().latestVisible(cid)
        db.conversations().upsert(
            conv.copy(
                lastMessageId = last?.id,
                lastMessagePreview = last?.let { if (it.kind == "call") CallHistory.preview(callInfo(it), it.outgoing, it.text) else it.text.ifEmpty { attachments(it).let { a -> if (a.size > 1) AttachmentPolicy.preview(a.map { r -> AttachmentPolicy.kindOfMime(r.mime) }) else VoiceNote.label(a) ?: "📎 Attachment" } } } ?: "Message deleted",
                lastMessageAt = last?.sortTs ?: conv.lastMessageAt,
            ),
        )
    }

    /** Kept locally, then acknowledged: opened later if the user accepts the sender's new key (T1.2). */
    private suspend fun quarantine(item: QueueItem) {
        db.quarantine().put(QuarantineEntity(item.id, item.conversationId, item.senderUserId, json.encodeToString(QueueItem.serializer(), item), System.currentTimeMillis()))
    }

    /**
     * An item that kept failing for an unexpected reason (a local database error or a bug): kept on
     * the phone with the reason, shown in the conversation, and retried at start-up (T1.4).
     */
    private suspend fun setAside(item: QueueItem, e: Throwable) {
        Log.w(TAG, "setting aside queue item after repeated failures: ${e.javaClass.simpleName}")
        db.quarantine().put(
            QuarantineEntity(item.id, item.conversationId, item.senderUserId, json.encodeToString(QueueItem.serializer(), item), System.currentTimeMillis(), reason = "failed: ${e.javaClass.simpleName}"),
        )
        if (item.payload.cls == "message") {
            db.messages().insertIgnore(
                MessageEntity(
                    id = item.messageId, conversationId = item.conversationId, senderUserId = item.senderUserId, senderDeviceId = item.senderDeviceId,
                    outgoing = false, kind = "undecryptable", text = "This message couldn't be processed. It's kept on this phone and will be tried again when the app restarts.",
                    sentAt = item.createdAt, serverTs = item.createdAt, sortTs = item.createdAt, status = MessageStatus.RECEIVED, errorText = e.javaClass.simpleName,
                ),
            )
        }
    }

    /**
     * The user reviewed [identityKey] (the safety number they compared) and accepts it for [userId]:
     * pin it, resume paused sends and open the messages held while the key was untrusted.
     */
    suspend fun acceptIdentity(userId: String, identityKey: String) {
        directory.acceptIdentity(userId, identityKey)
        db.messages().resumeHeld()
        db.outbox().resume(HELD_REASON, System.currentTimeMillis())
        kick()
        processLock.withLock {
            for (q in db.quarantine().heldForIdentity(userId)) {
                val item = runCatching { json.decodeFromString(QueueItem.serializer(), q.itemJson) }.getOrNull()
                if (item == null) {
                    db.quarantine().remove(q.queueId)
                    continue
                }
                try {
                    handleItem(item)
                    db.quarantine().remove(q.queueId)
                } catch (_: SenderIdentityChanged) {
                    // Still untrusted (the server presents yet another key): keep holding it.
                } catch (_: ApiException) {
                    // Keys unreachable right now: try again on the next acceptance or app start.
                } catch (e: EnvelopeException) {
                    recordUndecryptable(item, e.message ?: "")
                    db.quarantine().remove(q.queueId)
                }
            }
        }
    }

    private suspend fun recordUndecryptable(item: QueueItem, reason: String) {
        if (item.payload.cls != "message") return
        db.messages().insertIgnore(
            MessageEntity(
                id = item.messageId, conversationId = item.conversationId, senderUserId = item.senderUserId, senderDeviceId = item.senderDeviceId,
                outgoing = false, kind = "undecryptable", text = "This message couldn't be verified or decrypted and was discarded.",
                sentAt = item.createdAt, serverTs = item.createdAt, sortTs = item.createdAt, status = MessageStatus.RECEIVED, errorText = reason,
            ),
        )
    }

    private fun preview(p: Payload): String = when {
        p.contact != null -> "👤 ${p.contact.name}"
        p.location != null -> "📍 " + (p.location.label ?: "Location")
        !p.attachments.isNullOrEmpty() && p.text.isNullOrBlank() && p.attachments.size > 1 -> AttachmentPolicy.preview(p.attachments.map { AttachmentPolicy.kindOfMime(it.mime) })
        !p.attachments.isNullOrEmpty() && p.text.isNullOrBlank() -> VoiceNote.label(p.attachments) ?: "📎 ${p.attachments.first().name}"
        !p.attachments.isNullOrEmpty() -> "📎 ${p.text}"
        else -> p.text.orEmpty()
    }

    companion object {
        const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024
        private const val SERVER_LIMIT_TTL_MS = 10 * 60_000L
        /** More than a sender can attach (10), so an older or newer app's message still shows, but bounded. */
        const val MAX_RECEIVED_ATTACHMENTS = 20
        /** Outbox `lastError` marking a send paused by an identity change. */
        private const val HELD_REASON = "identity_changed"
        private const val HELD_RECHECK_MS = 6 * 3600_000L
        private const val TAG = "Messages"
        private const val MAX_ATTEMPTS = 6
        private val BACKOFF = listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L, 120_000L)
    }
}
