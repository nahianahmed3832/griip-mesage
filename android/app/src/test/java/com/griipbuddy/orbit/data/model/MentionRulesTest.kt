package com.griipbuddy.orbit.data.model

import com.griipbuddy.orbit.data.api.GriipJson
import com.griipbuddy.orbit.data.api.QueuePayload
import com.griipbuddy.orbit.data.api.SendMessageReq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Group mentions (2026-10-17, docs/MENTIONS.md). */
class MentionRulesTest {
    private val ann = MentionMember("u_ann", "ann", "Ann Lee")
    private val ann2 = MentionMember("u_ann2", "ann.k", "Ann Lee") // same display name, different person
    private val bob = MentionMember("u_bob", "bob_r", "Bob Rahman")
    private val remote = MentionMember("u_rem", "ann:b.griip.test", "Ann (Community B)")
    private val me = MentionMember("u_me", "nahian", "Nahian")
    private val members = listOf(ann, ann2, bob, remote, me)

    @Test
    fun theQueryIsTheAtWordUnderTheCursor() {
        assertEquals(MentionQuery(0, ""), MentionRules.activeQuery("@", 1))
        assertEquals(MentionQuery(6, "an"), MentionRules.activeQuery("hello @an", 9))
        assertEquals(MentionQuery(4, "bo"), MentionRules.activeQuery("hi (@bo", 7))
        assertEquals(MentionQuery(0, "an"), MentionRules.activeQuery("@an rest", 3)) // cursor mid-text
        assertNull(MentionRules.activeQuery("mail me at ann@example.com", 26)) // an e-mail address
        assertNull(MentionRules.activeQuery("@ann done", 9)) // the cursor moved past a space
        assertNull(MentionRules.activeQuery("no mention", 10))
        assertNull(MentionRules.activeQuery("", 0))
    }

    @Test
    fun suggestionsMatchNamesAndUsernamesAndNeverMe() {
        val s = MentionRules.suggest(members, "ann", "u_me", canMentionAll = false)
        assertEquals(setOf("u_ann", "u_ann2", "u_rem"), s.map { it.member!!.id }.toSet()) // both "Ann Lee"s appear
        assertTrue(MentionRules.suggest(members, "rah", "u_me", false).any { it.member?.id == "u_bob" }) // last name
        assertTrue(MentionRules.suggest(members, "nah", "u_me", false).isEmpty()) // never yourself
        val all = MentionRules.suggest(members, "", "u_me", canMentionAll = true)
        assertTrue(all.first().everyone) // @everyone first where allowed
        assertFalse(MentionRules.suggest(members, "", "u_me", canMentionAll = false).any { it.everyone })
        assertFalse(MentionRules.suggest(members, "bo", "u_me", canMentionAll = true).any { it.everyone }) // doesn't match "bo"
        assertTrue(MentionRules.suggest(members, "ev", "u_me", canMentionAll = true).first().everyone)
        val many = (1..30).map { MentionMember("u$it", "user$it", "User $it") }
        assertEquals(8, MentionRules.suggest(many, "user", null, false).size)
    }

    @Test
    fun choosingInsertsTheUsernameTokenAndMovesTheCursor() {
        val q = MentionRules.activeQuery("hey @an how are you", 7)!!
        val (t, cursor) = MentionRules.insert("hey @an how are you", q, MentionSuggestion(ann2))
        assertEquals("hey @ann.k how are you", t)
        assertEquals(11, cursor)
        val (t2, _) = MentionRules.insert("@ev", MentionRules.activeQuery("@ev", 3)!!, MentionSuggestion(null, everyone = true))
        assertEquals("@everyone ", t2)
    }

    @Test
    fun resolutionUsesConversationMembersOnlyByIdNotDisplayName() {
        val set = MentionRules.resolve("@ann and @ANN.K, also @bob_r. and @bob_r again", members, canMentionAll = false)
        assertEquals(listOf("u_ann", "u_ann2", "u_bob"), set.ids) // case-insensitive, trailing dot dropped, no duplicates
        assertFalse(set.all)
        // Someone who isn't a member, a display name, and yourself resolve to nothing.
        assertEquals(MentionSet.NONE, MentionRules.resolve("@stranger and @Ann Lee", listOf(bob), false))
        assertTrue(MentionRules.resolve("@nahian", members, false, myId = "u_me").ids.isEmpty())
        // A remote member's token is their full address, never the local "ann".
        assertEquals(listOf("u_rem"), MentionRules.resolve("ping @ann:b.griip.test", members, false).ids)
        assertTrue(MentionRules.resolve("write to ann@example.com", members, false).ids.isEmpty())
    }

    @Test
    fun everyoneIsTheOnlyMentionAllAndNeedsPermission() {
        assertTrue(MentionRules.resolve("@everyone standup", members, canMentionAll = true).all)
        assertFalse(MentionRules.resolve("@everyone standup", members, canMentionAll = false).all) // stays plain text
        assertFalse(MentionRules.resolve("@all standup", members, canMentionAll = true).all) // no competing forms
        assertFalse(MentionRules.resolve("@here standup", members, canMentionAll = true).all)
    }

    @Test
    fun spansComeFromMentionedIdsSoRenamesAndStrayTokensAreHandled() {
        val usernames = mapOf("u_ann" to "ann", "u_bob" to "bob_r")
        val text = "@ann and @bob_r, not @stranger, @everyone"
        val spans = MentionRules.spans(text, listOf("u_ann", "u_bob"), everyone = true) { usernames[it] }
        assertEquals(listOf("u_ann", "u_bob", null), spans.map { it.userId })
        assertEquals("@ann", text.substring(spans[0].start, spans[0].end))
        assertEquals("@bob_r", text.substring(spans[1].start, spans[1].end)) // the comma isn't part of it
        // A token whose user isn't among the message's mentioned ids stays plain (a typed "@bob_r" never resolved).
        assertEquals(listOf("u_ann"), MentionRules.spans(text, listOf("u_ann"), everyone = false) { usernames[it] }.map { it.userId })
        // A departed or unknown member (no local username) isn't drawn; the text itself is untouched.
        assertTrue(MentionRules.spans("@gone", listOf("u_gone"), false) { null }.isEmpty())
        // Malformed ids are ignored.
        assertTrue(MentionRules.spans("@ann", listOf("", "x".repeat(100)), false) { usernames[it] }.isEmpty())
    }

    @Test
    fun receivedEveryoneNeedsTheServersMarkAndAGroup() {
        assertTrue(MentionRules.received(null, true, true, "GROUP").all)
        assertTrue(MentionRules.received(null, true, true, "CHANNEL").all)
        assertFalse(MentionRules.received(null, true, null, "GROUP").all) // payload claim without the server's mark
        assertFalse(MentionRules.received(null, true, false, "GROUP").all)
        assertFalse(MentionRules.received(null, null, true, "GROUP").all) // a mark without the claim
        assertFalse(MentionRules.received(null, true, true, "DM").all)
        assertFalse(MentionRules.received(null, true, true, null).all)
        assertEquals(listOf("u_a", "u_b"), MentionRules.received(listOf("u_a", "u_a", "", "u_b", "x".repeat(65)), null, null, "GROUP").ids)
    }

    @Test
    fun storedListRoundTripsAndKeepsEveryoneLocal() {
        val set = MentionSet(listOf("u_a", "u_b"), all = true)
        assertEquals(listOf("u_a", "u_b", MentionRules.EVERYONE_MARK), MentionRules.stored(set))
        assertEquals(set, MentionRules.fromStored(MentionRules.stored(set)))
        assertEquals(MentionSet(listOf("u_a"), false), MentionRules.fromStored(listOf("u_a")))
    }

    @Test
    fun notificationsFollowTheSwitchMuteAndMentions() {
        assertTrue(MentionNotify.shouldNotify(notifications = true, muted = false, mention = false, personal = false))
        assertFalse(MentionNotify.shouldNotify(notifications = false, muted = false, mention = true, personal = true))
        assertTrue(MentionNotify.shouldNotify(notifications = true, muted = true, mention = true, personal = true)) // by name: through mute
        assertFalse(MentionNotify.shouldNotify(notifications = true, muted = true, mention = true, personal = false)) // @everyone: mute wins
        assertFalse(MentionNotify.shouldNotify(notifications = true, muted = true, mention = false, personal = false))
    }

    @Test
    fun wireFormatsStayCompatible() {
        // Ordinary messages: no mentionAll key anywhere (older apps and servers see exactly what they saw before).
        val plain = GriipJson.encodeToString(Payload.serializer(), Payload(type = "text", text = "hi", mentions = listOf("u_a")))
        assertFalse(plain.contains("mentionAll"))
        val everyone = GriipJson.encodeToString(Payload.serializer(), Payload(type = "text", text = "@everyone hi", mentionAll = true))
        assertTrue(everyone.contains("\"mentionAll\":true"))
        val req = SendMessageReq("c", "m", "message", 1, "n", "ct", "sig", emptyList())
        assertFalse(GriipJson.encodeToString(SendMessageReq.serializer(), req).contains("mentionAll"))
        assertTrue(GriipJson.encodeToString(SendMessageReq.serializer(), req.copy(mentionAll = true)).contains("\"mentionAll\":true"))
        // A queue payload from an older server (no mark) and from a new one.
        assertNull(GriipJson.decodeFromString(QueuePayload.serializer(), """{"v":1,"cls":"message"}""").mentionAll)
        assertEquals(true, GriipJson.decodeFromString(QueuePayload.serializer(), """{"v":1,"cls":"message","mentionAll":true}""").mentionAll)
        // An older app's edit (no mentions) leaves the stored targets alone; a new one can clear them.
        assertNull(GriipJson.decodeFromString(Payload.serializer(), """{"type":"edit","target":"m","text":"x"}""").mentions)
        assertEquals(emptyList<String>(), GriipJson.decodeFromString(Payload.serializer(), """{"type":"edit","target":"m","text":"x","mentions":[]}""").mentions)
    }
}
