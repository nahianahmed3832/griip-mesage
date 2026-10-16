package com.griipbuddy.message.data.repo

import android.content.Context
import android.util.Log
import com.griipbuddy.message.crypto.AttachmentCrypto
import com.griipbuddy.message.crypto.EnvelopeException
import com.griipbuddy.message.crypto.EnvelopeHeader
import com.griipbuddy.message.crypto.MessageCrypto
import com.griipbuddy.message.crypto.ReceivedEnvelope
import com.griipbuddy.message.data.api.AckReq
import com.griipbuddy.message.data.api.ApiClient
import com.griipbuddy.message.data.api.ApiException
import com.griipbuddy.message.data.api.ConnState
import com.griipbuddy.message.data.api.GriipJson
import com.griipbuddy.message.data.api.OkRes
import com.griipbuddy.message.data.api.QueueItem
import com.griipbuddy.message.data.api.QueuePayload
import com.griipbuddy.message.data.api.QueueRes
import com.griipbuddy.message.data.api.RealtimeClient
import com.griipbuddy.message.data.api.RealtimeFrame
import com.griipbuddy.message.data.api.SendMessageReq
import com.griipbuddy.message.data.api.SendMessageRes
import com.griipbuddy.message.data.api.WireKeyWrap
import com.griipbuddy.message.data.db.CallKeyEntity
import com.griipbuddy.message.data.db.ConversationEntity
import com.griipbuddy.message.data.db.GriipDatabase
import com.griipbuddy.message.data.db.MessageEntity
import com.griipbuddy.message.data.db.OutboxEntity
import com.griipbuddy.message.data.db.QuarantineEntity
import com.griipbuddy.message.data.model.AttachmentRef
import com.griipbuddy.message.data.model.CallInfo
import com.griipbuddy.message.data.model.MessageStatus
import com.griipbuddy.message.data.model.Payload
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.UUID

data class Notice(val conversationId: String, val title: String, val sender: String, val text: String, val mention: Boolean, val isGroup: Boolean)
data class IncomingCallKey(val callId: String, val conversationId: String, val fromUserId: String, val video: Boolean)

/** Live UI context used to decide notifications / read receipts. */
class AppState {
    val foreground = MutableStateFlow(false)
    val viewingConversation = MutableStateFlow<String?>(null)
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
    private val notify: (Notice) -> Unit,
) {
    private val json = GriipJson
    private val files = AttachmentFiles(File(context.filesDir, "attachments"))
    private val pending = PendingControls(RoomPendingControlStore(db))
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
    val orgEvents: SharedFlow<String> = _orgEvents
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
    fun observeThread(root: String) = db.messages().observeThread(root)
    fun observeThreadCounts(cid: String) = db.messages().observeThreadCounts(cid)
    fun observePinned(cid: String) = db.messages().observePinned(cid)
    fun observeMessage(id: String) = db.messages().observe(id)
    suspend fun search(q: String, cid: String? = null): List<MessageEntity> {
        val esc = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return if (cid == null) db.messages().search(esc) else db.messages().searchIn(cid, esc)
    }

    fun reactions(m: MessageEntity): Map<String, List<String>> = runCatching { json.decodeFromString(reactionsSer, m.reactionsJson) }.getOrDefault(emptyMap())
    fun attachments(m: MessageEntity): List<AttachmentRef> = runCatching { json.decodeFromString(attachmentsSer, m.attachmentsJson) }.getOrDefault(emptyList())
    fun readBy(m: MessageEntity): List<String> = runCatching { json.decodeFromString(listOfStrings, m.readByJson) }.getOrDefault(emptyList())
    fun deliveredTo(m: MessageEntity): List<String> = runCatching { json.decodeFromString(listOfStrings, m.deliveredToJson) }.getOrDefault(emptyList())
    fun mentions(m: MessageEntity): List<String> = runCatching { json.decodeFromString(listOfStrings, m.mentionsJson) }.getOrDefault(emptyList())
    fun callInfo(m: MessageEntity): CallInfo? = m.callJson?.let { runCatching { json.decodeFromString(CallInfo.serializer(), it) }.getOrNull() }

    // ─────────────────────────── Sending ───────────────────────────

    suspend fun sendText(cid: String, text: String, replyTo: String? = null, threadRoot: String? = null, attachments: List<AttachmentRef> = emptyList(), presetId: String? = null): String {
        val s = session.session ?: error("not signed in")
        val id = presetId ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val mentionIds = resolveMentions(text)
        val payload = Payload(type = "text", text = text, replyTo = replyTo, threadRoot = threadRoot, mentions = mentionIds.ifEmpty { null }, attachments = attachments.ifEmpty { null })
        val existing = db.messages().get(id)
        val msg = MessageEntity(
            id = id, conversationId = cid, senderUserId = s.userId, senderDeviceId = s.deviceId, outgoing = true, kind = "text", text = text,
            replyToId = replyTo, threadRootId = threadRoot, mentionsJson = json.encodeToString(listOfStrings, mentionIds),
            attachmentsJson = json.encodeToString(attachmentsSer, attachments), sentAt = existing?.sentAt ?: now, sortTs = existing?.sortTs ?: now, status = MessageStatus.SENDING,
        )
        db.messages().upsert(msg)
        db.outbox().put(OutboxEntity(id, cid, "message", json.encodeToString(Payload.serializer(), payload), now))
        if (threadRoot == null) db.conversations().bumpPreview(cid, id, preview(payload), now, "You", 0, 0)
        kick()
        return id
    }

    private suspend fun resolveMentions(text: String): List<String> {
        val names = Regex("@([a-z0-9._]{3,24})").findAll(text.lowercase()).map { it.groupValues[1] }.distinct().toList()
        val ids = ArrayList<String>()
        for (n in names) db.users().byUsername(n)?.id?.let { ids += it }
        return ids
    }

    private suspend fun sendControl(cid: String, payload: Payload) {
        val id = UUID.randomUUID().toString()
        db.outbox().put(OutboxEntity(id, cid, "control", json.encodeToString(Payload.serializer(), payload), System.currentTimeMillis(), visible = false))
        kick()
    }

    suspend fun react(m: MessageEntity, emoji: String) {
        val me = myUserId ?: return
        val current = reactions(m)
        val add = current[emoji]?.contains(me) != true
        applyReaction(db.messages().get(m.id) ?: return, me, emoji, add) // fresh row: `m` may be stale
        sendControl(m.conversationId, Payload(type = "reaction", target = m.id, emoji = emoji, add = add))
    }

    suspend fun edit(m: MessageEntity, newText: String) {
        if (!m.outgoing) return
        db.messages().upsert(m.copy(text = newText, editedAt = System.currentTimeMillis()))
        sendControl(m.conversationId, Payload(type = "edit", target = m.id, text = newText))
    }

    suspend fun delete(m: MessageEntity) {
        if (!m.outgoing) return
        files.deleteFor(m.id, attachments(m)) // the decrypted copies go too (T1.10)
        db.messages().upsert(m.copy(deleted = true, text = "", attachmentsJson = "[]"))
        sendControl(m.conversationId, Payload(type = "delete", target = m.id))
        refreshPreview(m.conversationId)
    }

    suspend fun setPinned(m: MessageEntity, pinned: Boolean) {
        db.messages().upsert(m.copy(pinned = pinned))
        sendControl(m.conversationId, Payload(type = "pin", target = m.id, pinned = pinned))
    }

    suspend fun retry(id: String) {
        val m = db.messages().get(id) ?: return
        if (m.status != MessageStatus.FAILED) return
        sendText(m.conversationId, m.text, m.replyToId, m.threadRootId, attachments(m), presetId = id)
    }

    /** Distribute a call's media E2EE key to all member devices through the encrypted channel. */
    suspend fun sendCallKey(cid: String, callId: String, video: Boolean, key: String) {
        val s = session.session ?: return
        db.calls().putKey(CallKeyEntity(callId, cid, key, s.userId, System.currentTimeMillis()))
        sendControl(cid, Payload(type = "call_invite", callId = callId, video = video, e2eeKey = key))
    }

    suspend fun callKey(callId: String): String? = db.calls().key(callId)?.key

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

    suspend fun sendAttachment(cid: String, name: String, mime: String, bytes: ByteArray, caption: String) {
        val s = session.session ?: return
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val local = files.fileFor(id, name).apply { writeBytes(bytes) }
        val placeholder = AttachmentRef(AttachmentFiles.PENDING, name, mime, bytes.size.toLong(), "", "", local.name)
        db.messages().upsert(
            MessageEntity(id, cid, s.userId, s.deviceId, true, "text", caption, attachmentsJson = json.encodeToString(attachmentsSer, listOf(placeholder)), sentAt = now, sortTs = now, status = MessageStatus.SENDING),
        )
        db.conversations().bumpPreview(cid, id, "📎 $name", now, "You", 0, 0)
        try {
            val enc = AttachmentCrypto.encrypt(bytes)
            _transfer.update { it + (id to 0f) }
            val up = api.upload("/v1/attachments?conversationId=$cid", enc.bytes) { p -> _transfer.update { it + (id to p) } }
            val ref = AttachmentRef(up.id, name, mime, bytes.size.toLong(), enc.key, enc.nonce, enc.sha256)
            files.fileFor(id, name).renameTo(files.fileFor(up.id, name))
            sendText(cid, caption, attachments = listOf(ref), presetId = id)
        } catch (e: ApiException) {
            db.messages().setStatus(id, MessageStatus.FAILED, null, e.message)
        } finally {
            _transfer.update { it - id }
        }
    }

    fun localAttachment(ref: AttachmentRef): File? =
        files.fileFor(ref.id, ref.name).takeIf { it.exists() } ?: files.child(ref.sha256)?.takeIf { ref.id == AttachmentFiles.PENDING && it.exists() }

    suspend fun downloadAttachment(ref: AttachmentRef): File {
        localAttachment(ref)?.let { return it }
        _transfer.update { it + (ref.id to 0f) }
        try {
            val ct = api.download("/v1/attachments/${ref.id}") { p -> _transfer.update { it + (ref.id to p) } }
            val plain = AttachmentCrypto.decrypt(ct, ref.key, ref.nonce, ref.sha256)
            return files.fileFor(ref.id, ref.name).apply { writeBytes(plain) }
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
                val res = api.post<SendMessageReq, SendMessageRes>(
                    "/v1/messages",
                    SendMessageReq(item.conversationId, item.messageId, item.cls, sentAt, sealed.nonce, sealed.ciphertext, sealed.signature, sealed.recipients.map { WireKeyWrap(it.deviceId, it.enc, it.wrappedKey) }),
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
            "devices.changed" -> f.userId?.let { directory.invalidateUser(it) }
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
        val signingKey = when (val k = directory.senderKey(item.senderUserId, item.senderDeviceId)) {
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
                val atts = pl.attachments.orEmpty()
                val m = MessageEntity(
                    id = item.messageId, conversationId = item.conversationId, senderUserId = item.senderUserId, senderDeviceId = item.senderDeviceId,
                    outgoing = mine, kind = "text", text = pl.text.orEmpty(), replyToId = pl.replyTo, threadRootId = pl.threadRoot,
                    mentionsJson = json.encodeToString(listOfStrings, pl.mentions.orEmpty()), attachmentsJson = json.encodeToString(attachmentsSer, atts),
                    sentAt = item.payload.sentAt ?: serverTs, serverTs = serverTs, sortTs = serverTs,
                    status = if (mine) MessageStatus.SENT else MessageStatus.RECEIVED,
                )
                if (db.messages().insertIgnore(m) == -1L) return
                replayPending(item.conversationId, item.messageId, conv, me)
                val viewing = appState.foreground.value && appState.viewingConversation.value == item.conversationId
                val mention = !mine && (me in pl.mentions.orEmpty())
                val unread = if (!mine && !viewing) 1 else 0
                val senderName = if (mine) "You" else db.users().get(item.senderUserId)?.displayName ?: "Someone"
                if (pl.threadRoot == null) {
                    db.conversations().bumpPreview(item.conversationId, item.messageId, preview(pl), serverTs, senderName, unread, if (mention && !viewing) 1 else 0)
                } else if (unread > 0) db.conversations().bumpUnread(item.conversationId, 0, if (mention) 1 else 0)
                if (viewing && !mine) scope.launch { markConversationRead(item.conversationId) }
                val muted = conv?.muted == true
                if (!mine && !viewing && settings.current.notifications && (!muted || mention)) {
                    val title = conv?.let { ConversationRepository.title(it, me) } ?: senderName
                    notify(Notice(item.conversationId, if (conv?.type == "CHANNEL") "#$title" else title, senderName, preview(pl), mention, conv?.type != "DM"))
                }
            }
            "edit" -> {
                val t = target(pl, item, serverTs) ?: return
                if (t.senderUserId == item.senderUserId && !t.deleted) {
                    db.messages().upsert(t.copy(text = pl.text.orEmpty(), editedAt = serverTs))
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
            "call_invite" -> {
                val callId = pl.callId ?: return
                if (db.calls().putKeyIfAbsent(CallKeyEntity(callId, item.conversationId, pl.e2eeKey ?: return, item.senderUserId, System.currentTimeMillis())) == -1L) {
                    Log.w(TAG, "ignoring second media key for an existing call")
                    return
                }
                val info = CallInfo(callId, pl.video == true)
                db.messages().insertIgnore(
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

    private suspend fun refreshPreview(cid: String) {
        val conv = db.conversations().get(cid) ?: return
        val last = db.messages().latestVisible(cid)
        db.conversations().upsert(
            conv.copy(
                lastMessageId = last?.id,
                lastMessagePreview = last?.let { if (it.kind == "call") "📞 ${it.text}" else it.text.ifEmpty { "📎 Attachment" } } ?: "Message deleted",
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
        !p.attachments.isNullOrEmpty() && p.text.isNullOrBlank() -> "📎 ${p.attachments.first().name}"
        !p.attachments.isNullOrEmpty() -> "📎 ${p.text}"
        else -> p.text.orEmpty()
    }

    companion object {
        /** Outbox `lastError` marking a send paused by an identity change. */
        private const val HELD_REASON = "identity_changed"
        private const val HELD_RECHECK_MS = 6 * 3600_000L
        private const val TAG = "Messages"
        private const val MAX_ATTEMPTS = 6
        private val BACKOFF = listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L, 120_000L)
    }
}
