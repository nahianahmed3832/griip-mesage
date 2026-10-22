package com.meldiq.android.data.model

/**
 * Batch B14: what a DM's, group's or (B14b) channel's details screen shows (docs/product/MELDIQ_BACKLOG_B14_B16_B15.md §B14). Every tab is
 * backed by data already on the phone: the conversation's members, its photos and videos, its pinned messages, the
 * links in its messages and its other files. Pure, unit-tested in `DetailsRulesTest`.
 */
object DetailsRules {
    enum class Tab { MEMBERS, MEDIA, PINS, THREADS, LINKS, FILES }

    /**
     * A DM opens on Media (its two members are already in the header); a group and a channel on Members. A channel also
     * lists its threads (batch B14b), except a forum, whose posts are already its threads.
     */
    fun tabs(type: String, forum: Boolean = false): List<Tab> = when (type) {
        "GROUP" -> listOf(Tab.MEMBERS, Tab.MEDIA, Tab.PINS, Tab.LINKS, Tab.FILES)
        "CHANNEL" -> if (forum) listOf(Tab.MEMBERS, Tab.MEDIA, Tab.PINS, Tab.LINKS, Tab.FILES)
            else listOf(Tab.MEMBERS, Tab.MEDIA, Tab.PINS, Tab.THREADS, Tab.LINKS, Tab.FILES)
        else -> listOf(Tab.MEDIA, Tab.PINS, Tab.LINKS, Tab.FILES)
    }

    /** One shared item, with the message it came from (tapping it opens that message in the chat). */
    data class Item<T>(val messageId: String, val sortTs: Long, val senderId: String, val value: T)

    /** A message as these rules need it. */
    data class Msg(val id: String, val sortTs: Long, val senderId: String, val text: String, val attachments: List<AttachmentRef>, val deleted: Boolean = false)

    /** Photos and videos, newest first. */
    fun media(messages: List<Msg>, isVisual: (AttachmentRef) -> Boolean): List<Item<AttachmentRef>> =
        attachments(messages) { isVisual(it) }

    /** Everything else people shared as a file: not photos or videos, not voice notes, not still sending. */
    fun files(messages: List<Msg>, isVisual: (AttachmentRef) -> Boolean, isVoice: (AttachmentRef) -> Boolean): List<Item<AttachmentRef>> =
        attachments(messages) { !isVisual(it) && !isVoice(it) }

    private fun attachments(messages: List<Msg>, keep: (AttachmentRef) -> Boolean): List<Item<AttachmentRef>> =
        messages.asSequence().filter { !it.deleted }.sortedByDescending { it.sortTs }
            .flatMap { m -> m.attachments.asSequence().filter { it.id != "pending" && keep(it) }.map { Item(m.id, m.sortTs, m.senderId, it) } }
            .toList()

    /** Links found in message text, newest first, each address once (its most recent sharing). */
    fun links(messages: List<Msg>): List<Item<String>> {
        val seen = HashSet<String>()
        return messages.asSequence().filter { !it.deleted && it.text.isNotBlank() }.sortedByDescending { it.sortTs }
            .flatMap { m -> LinkRules.find(m.text).asSequence().map { Item(m.id, m.sortTs, m.senderId, it.url) } }
            .filter { seen.add(it.value) }
            .toList()
    }

    /** The host to show for a link ("example.com"), or the address itself if it has none. */
    fun host(url: String): String = runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url
}
