package com.meldiq.android.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HistoryCryptoTest {
    private val seed = ByteArray(32) { it.toByte() }
    private val keys = HistoryKeys(seed, "u_alice")

    @Test
    fun anotherDeviceOfTheSameAccountOpensItems() {
        val tag = keys.messageTag("m-1")
        val sealed = keys.seal(tag, "hello".toByteArray())
        // Same identity seed (a linked phone, or a restore with the recovery key): same keys.
        assertArrayEquals("hello".toByteArray(), HistoryKeys(seed.copyOf(), "u_alice").open(tag, sealed))
        assertEquals(tag, HistoryKeys(seed.copyOf(), "u_alice").messageTag("m-1"))
    }

    @Test
    fun itemsAreRandomisedAndDontContainThePlaintext() {
        val tag = keys.messageTag("m-1")
        val a = keys.seal(tag, "the same text".toByteArray())
        val b = keys.seal(tag, "the same text".toByteArray())
        assertNotEquals(a.b64(), b.b64())
        assertTrue(!String(a, Charsets.ISO_8859_1).contains("the same text"))
    }

    @Test
    fun tagsHideIdsAndDifferPerKindAndAccount() {
        val m = keys.messageTag("c_1")
        val c = keys.conversationTag("c_1")
        assertNotEquals(m, c)
        assertTrue(!m.contains("c_1") && m.length == 43)
        assertNotEquals(m, HistoryKeys(seed, "u_bob").messageTag("c_1"))
        assertNotEquals(m, HistoryKeys(ByteArray(32) { 7 }, "u_alice").messageTag("c_1"))
    }

    @Test
    fun aChangedMovedOrForeignItemIsRefused() {
        val tag = keys.messageTag("m-1")
        val sealed = keys.seal(tag, "secret".toByteArray())
        val tampered = sealed.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        expectFailure { keys.open(tag, tampered) }
        expectFailure { keys.open(keys.messageTag("m-2"), sealed) } // moved to another tag by the server
        expectFailure { HistoryKeys(seed, "u_bob").open(tag, sealed) } // another account
        expectFailure { HistoryKeys(ByteArray(32) { 9 }, "u_alice").open(tag, sealed) } // a different identity key
        expectFailure { keys.open(tag, byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        val okm = Hkdf.derive(ikm, salt, info, 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.joinToString("") { "%02x".format(it) })
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("expected a failure")
        } catch (_: Exception) {
        }
    }
}
