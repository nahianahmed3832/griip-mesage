package com.griipbuddy.orbit.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** T2.6: streaming file encryption is the same format as before, and a bad file never yields plaintext. */
class AttachmentFileCryptoTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun streamedEncryptionIsTheSameFormatAsInMemoryEncryption() {
        val plain = randomBytes(5 * 1024 * 1024 + 123) // several chunks, not a multiple of the chunk size
        val src = tmp.newFile("photo.jpg").apply { writeBytes(plain) }
        val enc = tmp.newFile("photo.enc")
        val meta = AttachmentCrypto.encryptFile(src, enc)
        // Readable by the existing one-shot decrypt (and so by every other client and the Node reference).
        assertArrayEquals(plain, AttachmentCrypto.decrypt(enc.readBytes(), meta.key, meta.nonce, meta.sha256))
        assertEquals(sha256(enc.readBytes()).b64(), meta.sha256)
    }

    @Test
    fun streamedDecryptionReadsTheExistingFormat() {
        val plain = randomBytes(300_000)
        val e = AttachmentCrypto.encrypt(plain)
        val ct = tmp.newFile("in.bin").apply { writeBytes(e.bytes) }
        val out = tmp.root.resolve("out.bin")
        AttachmentCrypto.decryptFile(ct, e.key, e.nonce, e.sha256, out)
        assertArrayEquals(plain, out.readBytes())
    }

    @Test
    fun aModifiedFileFailsTheHashAndLeavesNoOutput() {
        val e = AttachmentCrypto.encrypt(randomBytes(70_000))
        val ct = tmp.newFile("in.bin").apply { writeBytes(e.bytes.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }) }
        val out = tmp.root.resolve("out.bin")
        assertThrows(EnvelopeException::class.java) { AttachmentCrypto.decryptFile(ct, e.key, e.nonce, e.sha256, out) }
        assertFalse(out.exists())
    }

    @Test
    fun aWrongKeyFailsTheTagAndLeavesNoOutputOrTemporaryFile() {
        val e = AttachmentCrypto.encrypt(randomBytes(70_000))
        val ct = tmp.newFile("in.bin").apply { writeBytes(e.bytes) }
        val out = tmp.root.resolve("out.bin")
        val err = assertThrows(EnvelopeException::class.java) { AttachmentCrypto.decryptFile(ct, randomBytes(32).b64(), e.nonce, e.sha256, out) }
        assertEquals("attachment decryption failed", err.message)
        assertFalse(out.exists())
        assertEquals(listOf("in.bin"), tmp.root.list()!!.sorted())
    }
}
