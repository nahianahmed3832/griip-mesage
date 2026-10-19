package com.meldiq.android.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCodeTest {
    @Test
    fun roundTripsRandomSeeds() {
        repeat(200) {
            val seed = randomBytes(32)
            assertArrayEquals(seed, RecoveryCode.decode(RecoveryCode.encode(seed)))
        }
    }

    @Test
    fun isGroupedForReadingAndToleratesHowPeopleTypeIt() {
        val seed = ByteArray(32) { it.toByte() }
        val code = RecoveryCode.encode(seed)
        assertTrue(code, Regex("[A-Z2-9]{4}(-[A-Z2-9]{1,4})+").matches(code))
        assertArrayEquals(seed, RecoveryCode.decode(code.lowercase()))
        assertArrayEquals(seed, RecoveryCode.decode(code.replace("-", " ")))
        assertArrayEquals(seed, RecoveryCode.decode(code.replace("-", "")))
    }

    @Test
    fun aTypoOrTruncationIsRejectedRatherThanRestoringTheWrongIdentity() {
        val code = RecoveryCode.encode(ByteArray(32) { (it * 7).toByte() })
        val i = code.indexOfFirst { it != '-' }
        val typo = code.substring(0, i) + (if (code[i] == 'A') 'B' else 'A') + code.substring(i + 1)
        assertNull(RecoveryCode.decode(typo))
        assertNull(RecoveryCode.decode(code.dropLast(6)))
        assertNull(RecoveryCode.decode(""))
    }
}

class AttachmentCryptoTest {
    @Test
    fun encryptsWithAFreshKeyAndDecryptsBack() {
        val plain = randomBytes(100_000)
        val a = AttachmentCrypto.encrypt(plain)
        val b = AttachmentCrypto.encrypt(plain)
        assertNotEquals(a.key, b.key)
        assertArrayEquals(plain, AttachmentCrypto.decrypt(a.bytes, a.key, a.nonce, a.sha256))
    }

    @Test
    fun aModifiedCiphertextFailsTheIntegrityCheck() {
        val a = AttachmentCrypto.encrypt("payroll.pdf".toByteArray())
        val tampered = a.bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val e = assertThrows(EnvelopeException::class.java) { AttachmentCrypto.decrypt(tampered, a.key, a.nonce, a.sha256) }
        assertEquals("attachment integrity check failed", e.message)
    }
}
