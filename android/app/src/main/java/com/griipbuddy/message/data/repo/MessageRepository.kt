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
        applyReaction(m.id, me, emoji, add)
        sendControl(m.conversationId, Payload(type = "reaction", target = m.id, emoji = emoji, add = add))
    }

    suspend fun edit(m: MessageEntity, newText: String) {
        if (!m.outgoing) return
        db.messages().upsert(m.copy(text = newText, editedAt = System.currentTimeMillis()))
        sendControl(m.conversationId, Payload(type = "edit", target = m.id, text = newText))
    }

    suspend fun delete(m: MessageEntity) {
        if (!m.outgoing) return
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
        val local = File(attachmentDir(), "$id-${safeName(name)}").apply { writeBytes(bytes) }
        val placeholder = AttachmentRef("pending", name, mime, bytes.size.toLong(), "", "", local.name)
        db.messages().upsert(
            MessageEntity(id, cid, s.userId, s.deviceId, true, "text", caption, attachmentsJson = json.encodeToString(attachmentsSer, listOf(placeholder)), sentAt = now, sortTs = now, status = MessageStatus.SENDING),
        )
        db.conversations().bumpPreview(cid, id, "📎 $name", now, "You", 0, 0)
        try {
            val enc = AttachmentCrypto.encrypt(bytes)
            _transfer.update { it + (id to 0f) }
            val up = api.upload("/v1/attachments?conversationId=$cid", enc.bytes) { p -> _transfer.update { it + (id to p) } }
            val ref = AttachmentRef(up.id, name, mime, bytes.size.toLong(), enc.key, enc.nonce, enc.sha256)
            File(attachmentDir(), "$id-${safeName(name)}").renameTo(File(attachmentDir(), "${up.id}-${safeName(name)}"))
            sendText(cid, caption, attachments = listOf(ref), presetId = id)
        } catch (e: ApiException) {
            db.messages().setStatus(id, MessageStatus.FAILED, null, e.message)
        } finally {
            _transfer.update { it - id }
        }
    }

    fun localAttachment(ref: AttachmentRef): File? =
        File(attachmentDir(), "${ref.id}-${safeName(ref.name)}").takeIf { it.exists() } ?: File(attachmentDir(), ref.sha256).takeIf { ref.id == "pending" && it.exists() }

    suspend fun downloadAttachment(ref: AttachmentRef): File {
        localAttachment(ref)?.let { return it }
        _transfer.update { it + (ref.id to 0f) }
        try {
            val ct = api.download("/v1/attachments/${ref.id}") { p -> _transfer.update { it + (ref.id to p) } }
            val plain = AttachmentCrypto.decrypt(ct, ref.key, ref.nonce, ref.sha256)
            return File(attachmentDir(), "${ref.id}-${safeName(ref.name)}").apply { writeBytes(plain) }
        } finally {
            _transfer.update { it - ref.id }
        }
    }

    private fun attachmentDir() = File(context.filesDir, "attachments").apply { mkdirs() }
    private fun safeName(n: String) = n.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

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

    private suspend fun processQueue(items: List<QueueItem>, backlog: Boolean) = processLock.withLock {
        val acks = ArrayList<Long>()
        for (item in items) {
            try {
                handleItem(item)
                acks += item.id
            } catch (e: ApiException) {
                // Couldn't fetch keys etc. (transient) — leave unacknowledged; it will be redelivered.
                Log.w(TAG, "deferring queue item: ${e.code}")
            } catch (e: EnvelopeException) {
                recordUndecryptable(item, e.message ?: "")
                acks += item.id
            } catch (e: Exception) {
                Log.w(TAG, "dropping malformed item: ${e.javaClass.simpleName}")
                recordUndecryptable(item, "malformed")
                acks += item.id
            }
        }
        if (acks.isNotEmpty() || backlog) {
            val sent = realtime.send("""{"t":"ack","ids":[${acks.joinToString(",")}],"backlog":$backlog}""")
            if (!sent && acks.isNotEmpty()) runCatching { api.post<AckReq, OkRes>("/v1/queue/ack", AckReq(acks)) }
        }
    }

    /** REST fallback used by the background service when the socket is unavailable. */
    suspend fun pollQueueOnce() {
        val r = api.get<QueueRes>("/v1/queue?limit=100")
        if (r.items.isEmpty()) return
        val acks = processLock.withLock {
            val ids = ArrayList<Long>()
            for (i in r.items) {
                try {
                    handleItem(i); ids += i.id
                } catch (e: EnvelopeException) {
                    recordUndecryptable(i, e.message ?: ""); ids += i.id
                } catch (_: ApiException) {
                }
            }
            ids
        }
        if (acks.isNotEmpty()) api.post<AckReq, OkRes>("/v1/queue/ack", AckReq(acks))
    }

    private suspend fun handleItem(item: QueueItem) {
        val me = myUserId ?: return
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
        if (existing != null && p.cls == "message") return // duplicate delivery
        val signingKey = directory.senderSigningKey(item.senderUserId, item.senderDeviceId)
            ?: throw EnvelopeException("unknown or uncertified sender device")
        val header = EnvelopeHeader(item.messageId, item.conversationId, item.senderUserId, item.senderDeviceId, p.sentAt ?: 0, p.cls ?: "message")
        val plain = crypto.open(ReceivedEnvelope(header, p.nonce!!, p.ciphertext!!, p.signature!!, p.enc!!, p.wrappedKey!!), signingKey, myDeviceId!!)
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
                val t = db.messages().get(pl.target ?: return) ?: return
                if (t.senderUserId == item.senderUserId && !t.deleted) {
                    db.messages().upsert(t.copy(text = pl.text.orEmpty(), editedAt = serverTs))
                    if (db.conversations().get(t.conversationId)?.lastMessageId == t.id) refreshPreview(t.conversationId)
                }
            }
            "delete" -> {
                val t = db.messages().get(pl.target ?: return) ?: return
                if (t.senderUserId == item.senderUserId) {
                    db.messages().upsert(t.copy(deleted = true, text = "", attachmentsJson = "[]"))
                    refreshPreview(t.conversationId)
                }
            }
            "reaction" -> applyReaction(pl.target ?: return, item.senderUserId, pl.emoji ?: return, pl.add != false)
            "pin" -> {
                val t = db.messages().get(pl.target ?: return) ?: return
                if (t.conversationId == item.conversationId) db.messages().upsert(t.copy(pinned = pl.pinned == true))
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

    private suspend fun applyReaction(target: String, userId: String, emoji: String, add: Boolean) {
        val t = db.messages().get(target) ?: return
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
        private const val TAG = "Messages"
        private const val MAX_ATTEMPTS = 6
        private val BACKOFF = listOf(2_000L, 5_000L, 15_000L, 30_000L, 60_000L, 120_000L)
    }
}
