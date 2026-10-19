package com.meldiq.android.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** Encrypted backup files (2026-10-17, docs/BACKUP.md). */
class BackupCryptoTest {
    private val header = """{"v":1,"userId":"u_1"}""".toByteArray()
    private val nonce = ByteArray(12) { it.toByte() }

    private fun backup(data: ByteArray, key: ByteArray, chunk: Int, hdr: ByteArray = header): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.writeHeader(out, hdr)
        BackupCrypto.EncryptingStream(out, key, nonce, hdr, chunk).use { it.write(data) }
        return out.toByteArray()
    }

    private fun open(file: ByteArray, key: ByteArray, chunk: Int): ByteArray {
        val input = ByteArrayInputStream(file)
        val h = BackupCrypto.readHeader(input)
        return BackupCrypto.DecryptingStream(input, key, nonce, h, chunk).use { it.readBytes() }
    }

    private fun rejects(file: ByteArray, key: ByteArray, chunk: Int, expect: String) {
        try {
            open(file, key, chunk)
            fail("accepted a bad backup")
        } catch (e: BackupCrypto.BadBackup) {
            assertTrue(e.message, e.message!!.contains(expect))
        }
    }

    @Test
    fun roundTripsWithAPasswordDerivedKey() {
        val key = BackupCrypto.deriveKey("correct horse battery".toCharArray(), ByteArray(16) { 1 }, iterations = 1000)
        assertEquals(32, key.size)
        for (size in listOf(0, 1, 1023, 1024, 1025, 5000)) {
            val data = Random(size).nextBytes(size)
            assertArrayEquals(data, open(backup(data, key, 1024), key, 1024))
        }
        // Same password and salt: same key; another password: another key.
        assertArrayEquals(key, BackupCrypto.deriveKey("correct horse battery".toCharArray(), ByteArray(16) { 1 }, iterations = 1000))
        assertFalse(key.contentEquals(BackupCrypto.deriveKey("wrong".toCharArray(), ByteArray(16) { 1 }, iterations = 1000)))
    }

    @Test
    fun wrongPasswordTamperingTruncationAndHeaderSwapAreRefused() {
        val key = Random(1).nextBytes(32)
        val data = Random(2).nextBytes(4000)
        val file = backup(data, key, 1024)
        rejects(file, Random(3).nextBytes(32), 1024, "Wrong password")
        rejects(file.copyOf().also { it[it.size - 20] = (it[it.size - 20].toInt() xor 1).toByte() }, key, 1024, "damaged")
        rejects(file.copyOf(file.size - (1024 + 16)), key, 1024, "damaged") // the final chunk is missing
        // The readable header can't be edited (e.g. to claim another account): it's bound to every chunk.
        val other = """{"v":1,"userId":"u_2"}""".toByteArray()
        val body = file.copyOfRange(8 + 4 + header.size, file.size)
        val swapped = ByteArrayOutputStream().also { BackupCrypto.writeHeader(it, other); it.write(body) }.toByteArray()
        rejects(swapped, key, 1024, "Wrong password")
        try {
            BackupCrypto.readHeader(ByteArrayInputStream("PK\u0003\u0004 not a backup".toByteArray()))
            fail()
        } catch (e: BackupCrypto.BadBackup) {
            assertTrue(e.message!!.contains("isn't a Meldiq backup"))
        }
    }

    @Test
    fun theDefaultsAreStrong() {
        assertTrue(BackupCrypto.ITERATIONS >= 600_000)
        assertEquals("ORBITBK1", String(BackupCrypto.MAGIC))
    }
}
