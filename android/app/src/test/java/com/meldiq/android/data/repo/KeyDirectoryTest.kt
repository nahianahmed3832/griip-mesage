package com.meldiq.android.data.repo

import com.meldiq.android.crypto.LocalKeys
import com.meldiq.android.crypto.b64
import com.meldiq.android.data.api.DeviceKeyDto
import com.meldiq.android.data.api.UserKeysDto
import com.meldiq.android.data.db.UserEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** T1.2: a relay that substitutes a contact's identity key must not receive or forge anything. */
class KeyDirectoryTest {
    /** A user's keys as a relay would serve them: one device certified by the user's identity key. */
    private class Account(val userId: String, val deviceId: String, val keys: LocalKeys = LocalKeys.generate()) {
        fun served(identity: LocalKeys = keys) = UserKeysDto(
            userId,
            identity.identityPublic.b64(),
            listOf(DeviceKeyDto(deviceId, identity.deviceSignPublic.b64(), identity.deviceEncPublic, identity.deviceCertificate())),
        )
    }

    private class FakeServer : KeySource {
        val conversations = HashMap<String, List<UserKeysDto>>()
        val users = HashMap<String, UserKeysDto>()
        fun serve(conversationId: String, vararg u: UserKeysDto) {
            conversations[conversationId] = u.toList()
            u.forEach { users[it.userId] = it }
        }
        override suspend fun conversationKeys(conversationId: String) = conversations.getValue(conversationId)
        override suspend fun userKeys(userId: String) = users[userId]
    }

    private class FakePins : PinStore {
        val rows = HashMap<String, UserEntity>()
        override suspend fun get(userId: String) = rows[userId]
        override suspend fun upsert(u: UserEntity) {
            rows[u.id] = u
        }
    }

    private val me = Account("u_me", "d_me")
    private val bob = Account("u_bob", "d_bob")
    private val carol = Account("u_carol", "d_carol")
    private val mallory = LocalKeys.generate() // what a malicious relay substitutes for Bob's identity
    private val server = FakeServer()
    private val pins = FakePins()
    private val dir = KeyDirectory(server, pins)

    @Test
    fun firstContactPinsAndEncryptsToCertifiedDevices() = runTest {
        server.serve("c1", me.served(), bob.served())
        val recips = dir.conversationRecipients("c1", me.deviceId, force = true)
        assertEquals(listOf(bob.deviceId), recips.map { it.deviceId })
        assertEquals(bob.keys.identityPublic.b64(), pins.rows.getValue(bob.userId).pinnedIdentityKey)
        assertFalse(pins.rows.getValue(bob.userId).identityChanged)
    }

    @Test
    fun aSubstitutedIdentityPausesSendingToTheWholeConversation() = runTest {
        server.serve("c1", me.served(), bob.served(), carol.served())
        dir.conversationRecipients("c1", me.deviceId, force = true) // pins Bob and Carol
        server.serve("c1", me.served(), bob.served(identity = mallory), carol.served())

        val e = assertThrows(IdentityChangedException::class.java) { kotlinx.coroutines.runBlocking { dir.conversationRecipients("c1", me.deviceId, force = true) } }
        assertEquals(setOf(bob.userId), e.userIds)
        assertTrue(pins.rows.getValue(bob.userId).identityChanged)
        assertEquals("the pinned key is kept", bob.keys.identityPublic.b64(), pins.rows.getValue(bob.userId).pinnedIdentityKey)
    }

    @Test
    fun messagesFromASubstitutedIdentityAreHeldNotTrusted() = runTest {
        server.serve("c1", bob.served())
        assertTrue(dir.senderKey(bob.userId, bob.deviceId) is SenderKey.Trusted) // pins Bob
        dir.invalidateUser(bob.userId)
        server.serve("c1", bob.served(identity = mallory))
        assertEquals(SenderKey.IdentityChanged, dir.senderKey(bob.userId, bob.deviceId))
    }

    @Test
    fun aDeviceNotCertifiedByThePinnedIdentityIsUnknown() = runTest {
        val forged = bob.served().let { it.copy(devices = it.devices + DeviceKeyDto("d_rogue", mallory.deviceSignPublic.b64(), mallory.deviceEncPublic, mallory.deviceCertificate())) }
        server.serve("c1", forged)
        assertEquals(SenderKey.Unknown, dir.senderKey(bob.userId, "d_rogue"))
        val trusted = dir.senderKey(bob.userId, bob.deviceId) as SenderKey.Trusted
        assertArrayEquals(bob.keys.deviceSignPublic, trusted.signingKey)
    }

    @Test
    fun acceptingTheReviewedKeyResumesMessaging() = runTest {
        server.serve("c1", me.served(), bob.served())
        dir.conversationRecipients("c1", me.deviceId, force = true)
        val bobNewPhone = LocalKeys.generate() // a legitimate re-registration
        server.serve("c1", me.served(), Account(bob.userId, "d_bob2", bobNewPhone).served())
        assertThrows(IdentityChangedException::class.java) { kotlinx.coroutines.runBlocking { dir.conversationRecipients("c1", me.deviceId, force = true) } }

        dir.acceptIdentity(bob.userId, bobNewPhone.identityPublic.b64())
        assertEquals(listOf("d_bob2"), dir.conversationRecipients("c1", me.deviceId, force = true).map { it.deviceId })
        assertFalse(pins.rows.getValue(bob.userId).identityChanged)
    }

    @Test
    fun acceptingOneKeyDoesNotTrustASecondSwap() = runTest {
        server.serve("c1", me.served(), bob.served())
        dir.conversationRecipients("c1", me.deviceId, force = true)
        val reviewed = LocalKeys.generate()
        dir.acceptIdentity(bob.userId, reviewed.identityPublic.b64())
        // The relay now serves a different key than the one the user compared.
        server.serve("c1", me.served(), bob.served(identity = mallory))
        assertThrows(IdentityChangedException::class.java) { kotlinx.coroutines.runBlocking { dir.conversationRecipients("c1", me.deviceId, force = true) } }
    }

    @Test
    fun theServerReturningToThePinnedKeyClearsTheWarning() = runTest {
        server.serve("c1", me.served(), bob.served())
        dir.conversationRecipients("c1", me.deviceId, force = true)
        server.serve("c1", me.served(), bob.served(identity = mallory))
        runCatching { dir.conversationRecipients("c1", me.deviceId, force = true) }
        assertTrue(pins.rows.getValue(bob.userId).identityChanged)
        server.serve("c1", me.served(), bob.served())
        assertEquals(listOf(bob.deviceId), dir.conversationRecipients("c1", me.deviceId, force = true).map { it.deviceId })
        assertFalse(pins.rows.getValue(bob.userId).identityChanged)
    }
}
