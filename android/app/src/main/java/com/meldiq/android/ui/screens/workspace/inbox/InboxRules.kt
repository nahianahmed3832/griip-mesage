package com.meldiq.android.ui.screens.workspace.inbox

import com.meldiq.android.data.api.InboxConversationDto
import com.meldiq.android.data.api.InboxDetailRes

/** What the inbox screens decide, without Compose (docs/UNIFIED_INBOX.md, round 8). */
object InboxRules {
    val VIEWS = listOf("all", "unassigned", "mine", "waiting_agent", "waiting_customer", "ai", "resolved")
    val PROVIDERS = listOf("messenger", "instagram", "whatsapp", "tiktok")
    val PRIORITIES = listOf("low", "normal", "high", "urgent")
    val AI_MODES = listOf("off", "draft", "auto")
    val AUTO_INTENTS = listOf("greeting", "opening_hours", "product_question", "order_status_question", "shipping_question", "general_question")

    /** The short badge shown on a conversation. */
    fun providerBadge(p: String): String = when (p) {
        "messenger" -> "Messenger"
        "instagram" -> "Instagram"
        "whatsapp" -> "WhatsApp"
        "tiktok" -> "TikTok"
        else -> p
    }

    /** A customer's label: only what the provider supplied (a name, a WhatsApp number), else a neutral word. */
    fun customerLabel(c: InboxConversationDto, fallback: String): String = c.customer.name?.takeIf { it.isNotBlank() } ?: c.customer.handle ?: fallback

    /** What the composer can do now. */
    sealed interface Composer {
        data object Text : Composer
        data object TemplateOnly : Composer
        data class Closed(val reason: String) : Composer
        data object NoPermission : Composer
    }

    fun composer(d: InboxDetailRes): Composer = when {
        !d.reply.canReply -> Composer.NoPermission
        d.reply.rule.allowed -> Composer.Text
        d.reply.rule.reason == "template_required" && d.reply.templates -> Composer.TemplateOnly
        else -> Composer.Closed(d.reply.rule.reason ?: "unsupported")
    }

    /** Whole hours left in the free reply window, or null when it's closed or unknown. */
    fun hoursLeft(windowEndsAt: Long?, now: Long): Int? {
        val end = windowEndsAt ?: return null
        val left = end - now
        return if (left <= 0) null else ((left + 3_599_999) / 3_600_000).toInt()
    }

    /** Fits the provider's limit (Instagram counts UTF-8 bytes). */
    fun fits(provider: String, text: String, limit: Int): Boolean =
        limit <= 0 || if (provider == "instagram") text.toByteArray(Charsets.UTF_8).size <= limit else text.codePointCount(0, text.length) <= limit

    /** Tags typed as "a, b, c": trimmed, lowercased, unique, at most 10 of up to 30 characters. */
    fun parseTags(input: String): List<String> =
        input.split(",").map { it.trim().lowercase().take(30) }.filter { it.isNotEmpty() }.distinct().take(10)

    fun minutesLabel(ms: Long?): String? = ms?.let { m ->
        val minutes = m / 60_000
        if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
    }
}
