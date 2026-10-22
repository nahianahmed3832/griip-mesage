package com.meldiq.android.data.db

import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.model.AttachmentRef
import com.meldiq.android.data.model.CallInfo
import com.meldiq.android.data.model.RichContent
import kotlinx.serialization.builtins.ListSerializer

/**
 * The one decoder and encoder for a message row's JSON columns (`attachmentsJson`, `callJson`, `richJson`), whichever
 * way the row arrived: the live queue, history sync or a backup restore. A column that doesn't decode (written by a
 * newer or broken app) reads as empty, never as an error.
 */
object MessageColumns {
    private val attachmentsSer = ListSerializer(AttachmentRef.serializer())

    fun attachments(m: MessageEntity): List<AttachmentRef> = attachments(m.attachmentsJson)

    fun attachments(json: String): List<AttachmentRef> =
        runCatching { GriipJson.decodeFromString(attachmentsSer, json) }.getOrDefault(emptyList())

    fun call(m: MessageEntity): CallInfo? = m.callJson?.let { runCatching { GriipJson.decodeFromString(CallInfo.serializer(), it) }.getOrNull() }

    fun rich(m: MessageEntity): RichContent? = m.richJson?.let { runCatching { GriipJson.decodeFromString(RichContent.serializer(), it) }.getOrNull() }

    fun encodeAttachments(refs: List<AttachmentRef>): String = GriipJson.encodeToString(attachmentsSer, refs)

    fun encodeCall(info: CallInfo): String = GriipJson.encodeToString(CallInfo.serializer(), info)

    fun encodeRich(rich: RichContent?): String? = rich?.let { GriipJson.encodeToString(RichContent.serializer(), it) }
}
