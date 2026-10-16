package com.griipbuddy.message.ui.screens.chats

import com.griipbuddy.message.data.db.ConversationEntity
import com.griipbuddy.message.ui.screens.chats.ChatPreview.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The chat list's second line: typing beats draft beats last message; nothing is invented. */
class ChatPreviewTest {
    private fun conv(type: String = "DM", preview: String? = null, sender: String? = null, draft: String? = null, scope: String? = null, topic: String? = null) =
        ConversationEntity(
            id = "c1", type = type, orgId = null, scope = null, scopeId = null, scopeName = scope, kind = null, name = "n", topic = topic,
            archived = false, memberCount = 2, membersJson = "[]", joined = true, myRole = null, canPost = true, canManage = false,
            activeCallId = null, activeCallVideo = false, serverUpdatedAt = 0, lastMessagePreview = preview, lastSenderName = sender, draft = draft,
        )

    @Test
    fun typingWinsAndNamesTheTypistInGroups() {
        assertEquals(ChatPreview(null, "typing…", Kind.Typing), ChatPreview.of(conv(preview = "hi", draft = "x"), listOf("Ana")))
        assertEquals(ChatPreview(null, "Ana is typing…", Kind.Typing), ChatPreview.of(conv(type = "GROUP"), listOf("Ana", "Lee")))
    }

    @Test
    fun aSavedDraftIsShownBeforeTheLastMessage() {
        assertEquals(ChatPreview("Draft", "see you at 5", Kind.Draft), ChatPreview.of(conv(preview = "ok", draft = "  see you\nat 5 "), emptyList()))
        assertEquals("a blank draft is ignored", Kind.Message, ChatPreview.of(conv(preview = "ok", draft = "   "), emptyList()).kind)
    }

    @Test
    fun theSenderIsNamedInGroupsAndYouEverywhere() {
        assertEquals(ChatPreview("Ana", "lunch?", Kind.Message), ChatPreview.of(conv(type = "GROUP", preview = "lunch?", sender = "Ana"), emptyList()))
        assertEquals(ChatPreview(null, "lunch?", Kind.Message), ChatPreview.of(conv(preview = "lunch?", sender = "Ana"), emptyList()))
        assertEquals(ChatPreview("You", "sure", Kind.Message), ChatPreview.of(conv(preview = "sure", sender = "You"), emptyList()))
    }

    @Test
    fun emptyConversationsSayNoMessagesOrDescribeTheChannel() {
        assertEquals(ChatPreview(null, "No messages yet", Kind.Empty), ChatPreview.of(conv(), emptyList()))
        assertEquals(ChatPreview(null, "Dhaka · Daily ops", Kind.Empty), ChatPreview.of(conv(type = "CHANNEL", scope = "Dhaka", topic = "Daily ops"), emptyList()))
    }
}
