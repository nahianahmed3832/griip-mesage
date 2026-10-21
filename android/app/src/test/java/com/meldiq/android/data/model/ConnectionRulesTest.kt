package com.meldiq.android.data.model

import com.meldiq.android.data.api.ConnectionEntryDto
import com.meldiq.android.data.api.ConnectionsRes
import com.meldiq.android.data.api.ConversationDto
import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.api.UserDto
import com.meldiq.android.data.db.ConversationEntity
import com.meldiq.android.data.repo.ConnectionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Batch B6: the profile's connection button, Add by username, where message requests are listed, and the API shapes. */
class ConnectionRulesTest {
    private fun u(id: String) = UserDto(id, id, id.uppercase())

    @Test
    fun `the button follows your own lists`() {
        val lists = ConnectionsRes(connected = listOf(u("a")), incoming = listOf(ConnectionEntryDto(u("b"))), outgoing = listOf(ConnectionEntryDto(u("c"))))
        assertEquals(ConnectionRules.State.CONNECTED, ConnectionRules.state(lists, "a"))
        assertEquals(ConnectionRules.State.RECEIVED, ConnectionRules.state(lists, "b"))
        assertEquals(ConnectionRules.State.SENT, ConnectionRules.state(lists, "c"))
        assertEquals(ConnectionRules.State.NONE, ConnectionRules.state(lists, "d"))
        assertEquals(ConnectionRules.State.NONE, ConnectionRules.state(null, "a"))
    }

    @Test
    fun `a username loses its at sign and spaces, and blank or overlong names send nothing`() {
        assertEquals("ada", ConnectionRules.username("  @ada "))
        assertNull(ConnectionRules.username(" @ "))
        assertNull(ConnectionRules.username("x".repeat(65)))
    }

    private fun conv(id: String, request: String?, at: Long = 0, removed: Boolean = false) = ConversationEntity(
        id = id, type = "DM", orgId = null, scope = null, scopeId = null, scopeName = null, kind = null, name = id, topic = null,
        archived = false, memberCount = 2, membersJson = "[]", joined = true, myRole = null, canPost = true, canManage = false,
        activeCallId = null, activeCallVideo = false, serverUpdatedAt = 1, lastMessageAt = at, removed = removed, request = request,
    )

    @Test
    fun `incoming requests are listed apart, newest first, and outgoing ones stay in your DMs`() {
        val convs = listOf(conv("in1", RequestRules.INCOMING, at = 1), conv("in2", RequestRules.INCOMING, at = 5), conv("out", RequestRules.OUTGOING), conv("dm", null), conv("gone", RequestRules.INCOMING, removed = true))
        assertEquals(listOf("in2", "in1"), RequestRules.incoming(convs).map { it.id })
        assertEquals(listOf("out", "dm"), RequestRules.notRequests(convs).map { it.id })
        assertEquals(1, RequestRules.remaining(2, 3))
        assertEquals(0, RequestRules.remaining(5, 3))
    }

    @Test
    fun `the API shapes read from the server and default without the feature`() {
        val d = GriipJson.decodeFromString(ConversationDto.serializer(), """{"id":"c1","type":"DM","request":{"state":"pending","incoming":false,"sent":2,"limit":3}}""")
        assertEquals(2, d.request?.sent)
        assertNull(GriipJson.decodeFromString(ConversationDto.serializer(), """{"id":"c1","type":"DM"}""").request)
        val l = GriipJson.decodeFromString(ConnectionsRes.serializer(), """{"connected":[{"id":"u1","username":"a","displayName":"A"}],"incoming":[{"user":{"id":"u2","username":"b","displayName":"B"},"at":5}],"outgoing":[]}""")
        assertEquals("u2", l.incoming.single().user.id)
        // Only the field being changed goes out.
        assertEquals("""{"dmPolicy":"connections"}""", GriipJson.encodeToString(ConnectionRepository.MessagingSettingsPatch.serializer(), ConnectionRepository.MessagingSettingsPatch(dmPolicy = "connections")))
        assertEquals(listOf("everyone", "communities", "connections"), RequestRules.DM_POLICIES)
        assertEquals(listOf("everyone", "communities", "nobody"), RequestRules.REQUEST_POLICIES)
    }
}
