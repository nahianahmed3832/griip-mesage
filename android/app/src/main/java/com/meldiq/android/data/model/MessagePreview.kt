package com.meldiq.android.data.model

import com.meldiq.android.data.db.MessageColumns
import com.meldiq.android.data.db.MessageEntity
import com.meldiq.android.voice.VoiceNote

/**
 * The one-line text for a message wherever it's summarised: the chat list (sent, received, restored from history or
 * after a deletion), notifications and the reply quote. It replaces four versions that disagreed (a received photo
 * read "📎 IMG_1.jpg" in the list but "📷 Photo" for its sender; a restored voice message lost its length).
 */
object MessagePreview {
    const val DELETED = "Message deleted"

    fun of(m: MessageEntity): String = of(
        text = m.text, attachments = MessageColumns.attachments(m), rich = MessageColumns.rich(m),
        deleted = m.deleted, call = if (m.kind == "call") CallPreview(MessageColumns.call(m), m.outgoing) else null,
    )

    fun of(p: Payload): String = of(text = p.text, attachments = p.attachments.orEmpty(), rich = RichRules.fromPayload(p))

    /** A call entry: its record (null when unknown) and whether this person placed it. */
    data class CallPreview(val info: CallInfo?, val outgoing: Boolean)

    fun of(
        text: String?,
        attachments: List<AttachmentRef>,
        rich: RichContent?,
        deleted: Boolean = false,
        call: CallPreview? = null,
    ): String {
        val body = text.orEmpty()
        return when {
            deleted -> DELETED
            call != null -> CallHistory.preview(call.info, call.outgoing, body.ifBlank { "Call" })
            rich?.contact != null -> "👤 ${rich.contact.name}"
            rich?.location != null -> "📍 " + (rich.location.label ?: "Location")
            rich?.animoji != null -> rich.animoji
            attachments.isEmpty() -> body
            body.isNotBlank() -> "📎 $body"
            else -> VoiceNote.label(attachments) ?: attachmentsOnly(attachments)
        }
    }

    private fun attachmentsOnly(refs: List<AttachmentRef>): String {
        val kinds = refs.map { AttachmentPolicy.kindOfMime(it.mime) }
        val single = refs.singleOrNull()
        return if (single != null && kinds.single() == AttachmentPolicy.Kind.FILE && single.name.isNotBlank()) "📎 ${single.name}" else AttachmentPolicy.preview(kinds)
    }
}
