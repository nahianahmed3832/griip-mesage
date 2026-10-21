package com.meldiq.android.data.model

import com.meldiq.android.data.api.ReportAttachmentDto
import com.meldiq.android.data.api.ReportContentDto
import com.meldiq.android.data.api.ReportContextDto

/**
 * Batch B4: what a report shares and where it goes (pure, `ReportRulesTest`). Messages are end-to-end encrypted, so a
 * report carries only what the reporter's phone shows them on the review step: the reported message, its attachments'
 * names and sizes, and up to [CONTEXT] messages before it in the conversation.
 */
object ReportRules {
    /** The reasons in the order the sheet offers them (the server's REPORT_REASONS). */
    val REASONS = listOf("spam", "harassment", "harmful", "private_info", "illegal", "other")
    const val CONTEXT = 5
    const val NOTE_MAX = 500
    private const val TEXT_MAX = 4000
    private const val CONTEXT_TEXT_MAX = 2000

    /** One message as the report needs it. */
    data class Line(val id: String, val senderUserId: String, val text: String, val sentAt: Long, val attachments: List<AttachmentRef> = emptyList())

    /**
     * The shared content for reporting [target]: its text and attachments, and the up to [CONTEXT] messages just before it
     * in [conversation] (oldest first), text only.
     */
    fun content(target: Line, conversation: List<Line>): ReportContentDto {
        val ordered = conversation.sortedBy { it.sentAt }
        val i = ordered.indexOfFirst { it.id == target.id }
        val before = if (i > 0) ordered.subList(maxOf(0, i - CONTEXT), i) else emptyList()
        return ReportContentDto(
            text = target.text.take(TEXT_MAX).ifEmpty { null },
            sentAt = target.sentAt,
            attachments = target.attachments.take(10).map { ReportAttachmentDto(it.name.take(200), it.size, it.sha256.take(128)) }.ifEmpty { null },
            context = before.filter { it.text.isNotBlank() }.map { ReportContextDto(it.senderUserId, it.text.take(CONTEXT_TEXT_MAX), it.sentAt) }.ifEmpty { null },
        )
    }

    /** Who will read a report, as the review step says it: the community's moderators, or Meldiq's team. */
    fun goesToCommunity(reason: String, conversationType: String?, personInCommunity: Boolean): Boolean = when {
        reason == "illegal" -> false
        conversationType != null -> conversationType == "CHANNEL"
        else -> personInCommunity
    }
}

/** Batch B4: Security actions' durations (1 hour, 6 hours, 24 hours, 7 days; the server allows at most 7 days). */
object SecurityRules {
    private const val HOUR = 3_600_000L
    val DURATIONS = listOf(HOUR, 6 * HOUR, 24 * HOUR, 7 * 24 * HOUR)

    fun running(until: Long?, now: Long = System.currentTimeMillis()): Boolean = until != null && until > now
}
