package com.meldiq.android.data.db

import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.model.AttachmentRef
import com.meldiq.android.data.model.CallInfo
import com.meldiq.android.data.model.RichContent
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The one decoder and encoder for a message row's JSON columns (`attachmentsJson`, `callJson`, `richJson`, the id lists
 * and the reactions), whichever way the row arrived: the live queue, history sync or a backup restore. A column that
 * doesn't decode (written by a newer or broken app) reads as empty, never as an error.
 */
object MessageColumns {
    private val attachmentsSer = ListSerializer(AttachmentRef.serializer())
    private val idsSer = ListSerializer(String.serializer())
    private val reactionsSer = MapSerializer(String.serializer(), idsSer)

    fun attachments(m: MessageEntity): List<AttachmentRef> = attachments(m.attachmentsJson)

    fun attachments(json: String): List<AttachmentRef> =
        runCatching { GriipJson.decodeFromString(attachmentsSer, json) }.getOrDefault(emptyList())

    fun call(m: MessageEntity): CallInfo? = m.callJson?.let { runCatching { GriipJson.decodeFromString(CallInfo.serializer(), it) }.getOrNull() }

    fun rich(m: MessageEntity): RichContent? = m.richJson?.let { runCatching { GriipJson.decodeFromString(RichContent.serializer(), it) }.getOrNull() }

    fun encodeAttachments(refs: List<AttachmentRef>): String = GriipJson.encodeToString(attachmentsSer, refs)

    fun encodeCall(info: CallInfo): String = GriipJson.encodeToString(CallInfo.serializer(), info)

    fun encodeRich(rich: RichContent?): String? = rich?.let { GriipJson.encodeToString(RichContent.serializer(), it) }

    /** Emoji to the user ids that reacted with it. */
    fun reactions(m: MessageEntity): Map<String, List<String>> = runCatching { GriipJson.decodeFromString(reactionsSer, m.reactionsJson) }.getOrDefault(emptyMap())

    fun readBy(m: MessageEntity): List<String> = ids(m.readByJson)

    fun deliveredTo(m: MessageEntity): List<String> = ids(m.deliveredToJson)

    fun mentions(m: MessageEntity): List<String> = ids(m.mentionsJson)

    private fun ids(json: String): List<String> = runCatching { GriipJson.decodeFromString(idsSer, json) }.getOrDefault(emptyList())

    fun encodeIds(ids: List<String>): String = GriipJson.encodeToString(idsSer, ids)

    fun encodeReactions(reactions: Map<String, List<String>>): String = GriipJson.encodeToString(reactionsSer, reactions)
}
