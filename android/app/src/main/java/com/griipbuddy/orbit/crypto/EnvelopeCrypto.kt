package com.griipbuddy.orbit.crypto

import java.io.File
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Canonical strings — must match server/src/crypto.ts and server/test/support/griipClient.ts exactly. */
object Canonical {
    fun deviceCert(identityKey: String, signingKey: String, encryptionKey: String) =
        "griip-device-cert-v1\n$identityKey\n$signingKey\n$encryptionKey"

    fun authProof(purpose: String, nonce: String, signingKey: String) = "griip-auth-v1\n$purpose\n$nonce\n$signingKey"

    /** Orbit 2.0 legacy migration: signed by the account IDENTITY key, derived from the recovery key on this phone. */
    fun legacyMigration(username: String, nonce: String) = "griip-legacy-migration-v1\n$username\n$nonce"

    fun header(h: EnvelopeHeader) =
        "griip-envelope-v1\n${h.messageId}\n${h.conversationId}\n${h.senderUserId}\n${h.senderDeviceId}\n${h.sentAt}\n${h.cls}"
}

data class EnvelopeHeader(
    val messageId: String,
    val conversationId: String,
    val senderUserId: String,
    val senderDeviceId: String,
    val sentAt: Long,
    val cls: String, // "message" | "control"
)

data class RecipientDevice(val deviceId: String, val encryptionKey: ByteArray)

data class KeyWrap(val deviceId: String, val enc: String, val wrappedKey: String)

data class SealedEnvelope(
    val header: EnvelopeHeader,
    val nonce: String,
    val ciphertext: String,
    val signature: String,
    val recipients: List<KeyWrap>,
)

data class ReceivedEnvelope(
    val header: EnvelopeHeader,
    val nonce: String,
    val ciphertext: String,
    val signature: String,
    val enc: String,
    val wrappedKey: String,
)

class EnvelopeException(message: String) : Exception(message)

/**
 * Replaceable message-protection interface (docs/DECISIONS.md D-04). Screens and repositories only
 * see this; swapping in Signal/MLS later means a new implementation, not a rewrite.
 */
interface MessageCrypto {
    val protocolId: String
    fun seal(header: EnvelopeHeader, plaintext: ByteArray, recipients: List<RecipientDevice>): SealedEnvelope
    fun open(env: ReceivedEnvelope, senderSigningKey: ByteArray, myDeviceId: String): ByteArray
}

/**
 * griip-envelope-v1: per-message random AES-256-GCM content key; content key wrapped to every
 * recipient device with HPKE (RFC 9180, base mode); ciphertext signed with the sender device's
 * Ed25519 key. NO forward secrecy / post-compromise security — see docs/SECURITY.md §4.
 */
class EnvelopeV1(private val keys: () -> LocalKeys) : MessageCrypto {
    override val protocolId = "griip-envelope-v1"
    private val info = "griip-envelope-v1/key".toByteArray()

    override fun seal(header: EnvelopeHeader, plaintext: ByteArray, recipients: List<RecipientDevice>): SealedEnvelope {
        val k = keys()
        val headerStr = Canonical.header(header)
        val contentKey = randomBytes(32)
        val nonce = randomBytes(12)
        try {
            val ct = AesGcm.encrypt(contentKey, nonce, plaintext, headerStr.toByteArray())
            val nonceB64 = nonce.b64()
            val ctB64 = ct.b64()
            val signature = k.signWithDevice("$headerStr\n$nonceB64\n$ctB64")
            val wraps = recipients.map { r ->
                val (enc, wrapped) = Hpke.seal(r.encryptionKey, info, "${header.messageId}\n${r.deviceId}".toByteArray(), contentKey)
                KeyWrap(r.deviceId, enc.b64(), wrapped.b64())
            }
            return SealedEnvelope(header, nonceB64, ctB64, signature, wraps)
        } finally {
            contentKey.wipe()
        }
    }

    override fun open(env: ReceivedEnvelope, senderSigningKey: ByteArray, myDeviceId: String): ByteArray {
        val k = keys()
        val headerStr = Canonical.header(env.header)
        if (!Ed25519.verify(senderSigningKey, "$headerStr\n${env.nonce}\n${env.ciphertext}".toByteArray(), env.signature.unb64())) {
            throw EnvelopeException("signature verification failed")
        }
        val contentKey = try {
            Hpke.open(k.deviceEncPrivate.unb64(), k.deviceEncPublic.unb64(), env.enc.unb64(), info, "${env.header.messageId}\n$myDeviceId".toByteArray(), env.wrappedKey.unb64())
        } catch (e: Exception) {
            throw EnvelopeException("key unwrap failed")
        }
        return try {
            AesGcm.decrypt(contentKey, env.nonce.unb64(), env.ciphertext.unb64(), headerStr.toByteArray())
        } catch (e: Exception) {
            throw EnvelopeException("content decryption failed")
        } finally {
            contentKey.wipe()
        }
    }
}

/** Attachment encryption: random key + nonce per file, key travels inside the E2EE message. */
object AttachmentCrypto {
    data class Encrypted(val bytes: ByteArray, val key: String, val nonce: String, val sha256: String)

    fun encrypt(plain: ByteArray): Encrypted {
        val key = randomBytes(32)
        val nonce = randomBytes(12)
        val ct = AesGcm.encrypt(key, nonce, plain)
        return Encrypted(ct, key.b64(), nonce.b64(), sha256(ct).b64())
    }

    fun decrypt(ct: ByteArray, key: String, nonce: String, expectedSha256: String): ByteArray {
        if (!sha256(ct).b64().equals(expectedSha256)) throw EnvelopeException("attachment integrity check failed")
        return AesGcm.decrypt(key.unb64(), nonce.unb64(), ct)
    }

    /** Key, nonce and ciphertext hash of an encrypted file: what travels inside the E2EE message. */
    data class Meta(val key: String, val nonce: String, val sha256: String)

    /**
     * Encrypts [plain] into [out] in 64 KiB steps, hashing the ciphertext as it is written (T2.6). The
     * output is byte-identical to [encrypt] (one AES-256-GCM message), so every client reads it the same.
     */
    fun encryptFile(plain: File, out: File): Meta {
        val key = randomBytes(32)
        val nonce = randomBytes(12)
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
            val md = MessageDigest.getInstance("SHA-256")
            plain.inputStream().use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        c.update(buf, 0, n)?.let { o.write(it); md.update(it) }
                    }
                    c.doFinal().let { o.write(it); md.update(it) }
                }
            }
            return Meta(key.b64(), nonce.b64(), md.digest().b64())
        } finally {
            key.wipe()
        }
    }

    /**
     * Verifies the ciphertext hash of [ct] (streamed), then decrypts it into [out]. The plaintext goes
     * to a temporary file that becomes [out] only once the GCM tag has verified, so a corrupt or forged
     * file never appears as a decrypted attachment. GCM decryption still buffers up to one file's
     * ciphertext inside the cipher (≤ 25 MB); constant memory would need a chunked format version.
     */
    fun decryptFile(ct: File, key: String, nonce: String, expectedSha256: String, out: File) {
        val md = MessageDigest.getInstance("SHA-256")
        ct.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        if (md.digest().b64() != expectedSha256) throw EnvelopeException("attachment integrity check failed")
        val tmp = File(out.parentFile, out.name + ".dec")
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key.unb64(), "AES"), GCMParameterSpec(128, nonce.unb64())) }
            ct.inputStream().use { input ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        c.update(buf, 0, n)?.let { o.write(it) }
                    }
                    o.write(c.doFinal())
                }
            }
            if (!tmp.renameTo(out)) throw java.io.IOException("couldn't store the decrypted file")
        } catch (e: javax.crypto.AEADBadTagException) {
            throw EnvelopeException("attachment decryption failed")
        } finally {
            tmp.delete()
        }
    }
}

/**
 * Chunked attachment encryption for large files (2026-10-17, docs/ATTACHMENTS.md › "Large files"). Used only for files
 * over 25 MB, which older apps couldn't receive anyway; smaller files keep the original single-message format.
 *
 * The plaintext is split into chunks of [chunk] bytes (the last may be shorter, and an empty file is one empty chunk).
 * Chunk i is AES-256-GCM with the file key, nonce = base nonce with its last four bytes XOR i (big-endian), and
 * associated data "orbit-att-chunk" ‖ i (4 bytes) ‖ final (1 byte, 1 on the last chunk). So chunks can't be reordered,
 * dropped or cut off without failing authentication, and each chunk is checked as it's read: memory stays at one
 * chunk, whatever the file's size. The SHA-256 of the whole ciphertext travels in the message, as for other files.
 */
object ChunkedAttachmentCrypto {
    const val DEFAULT_CHUNK = 1024 * 1024
    private const val TAG = 16
    private val AAD_PREFIX = "orbit-att-chunk".toByteArray()

    fun validChunk(c: Int) = c in 1024..(16 * 1024 * 1024)

    private fun nonceFor(base: ByteArray, i: Int): ByteArray = base.copyOf().also { n ->
        for (b in 0..3) n[8 + b] = (n[8 + b].toInt() xor ((i ushr (8 * (3 - b))) and 0xFF)).toByte()
    }

    private fun aad(i: Int, final: Boolean): ByteArray =
        AAD_PREFIX + byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte(), if (final) 1 else 0)

    private fun cipher(mode: Int, key: ByteArray, base: ByteArray, i: Int, final: Boolean): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonceFor(base, i)))
            updateAAD(aad(i, final))
        }

    /** Encrypts [plain] into [out]; returns the key, base nonce and ciphertext hash for the message. */
    fun encryptFile(plain: File, out: File, chunk: Int = DEFAULT_CHUNK): AttachmentCrypto.Meta {
        require(validChunk(chunk))
        val key = randomBytes(32)
        val base = randomBytes(12)
        try {
            val md = MessageDigest.getInstance("SHA-256")
            val total = plain.length()
            val count = if (total == 0L) 1L else (total + chunk - 1) / chunk
            require(count <= Int.MAX_VALUE) { "file too large" }
            plain.inputStream().buffered(chunk).use { input ->
                out.outputStream().buffered(256 * 1024).use { o ->
                    val buf = ByteArray(chunk)
                    for (i in 0 until count.toInt()) {
                        var n = 0
                        while (n < chunk) {
                            val r = input.read(buf, n, chunk - n)
                            if (r < 0) break
                            n += r
                        }
                        val ct = cipher(Cipher.ENCRYPT_MODE, key, base, i, i == count.toInt() - 1).doFinal(buf, 0, n)
                        o.write(ct)
                        md.update(ct)
                    }
                }
            }
            return AttachmentCrypto.Meta(key.b64(), base.b64(), md.digest().b64())
        } finally {
            key.wipe()
        }
    }

    /**
     * Checks the ciphertext hash of [ct], then decrypts it chunk by chunk into [out], which appears only once every chunk
     * (including the final one) has authenticated.
     */
    fun decryptFile(ct: File, key: String, nonce: String, expectedSha256: String, chunk: Int, out: File) {
        if (!validChunk(chunk)) throw EnvelopeException("attachment chunk size not supported")
        val md = MessageDigest.getInstance("SHA-256")
        ct.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        if (md.digest().b64() != expectedSha256) throw EnvelopeException("attachment integrity check failed")
        val k = key.unb64()
        val base = nonce.unb64()
        if (base.size != 12) throw EnvelopeException("attachment nonce invalid")
        val block = chunk + TAG
        val total = ct.length()
        val count = if (total == 0L) 0L else (total + block - 1) / block
        if (count == 0L || count > Int.MAX_VALUE || (total - (count - 1) * block) < TAG) throw EnvelopeException("attachment decryption failed")
        val tmp = File(out.parentFile, out.name + ".dec")
        try {
            ct.inputStream().buffered(block).use { input ->
                tmp.outputStream().buffered(256 * 1024).use { o ->
                    val buf = ByteArray(block)
                    for (i in 0 until count.toInt()) {
                        val want = if (i == count.toInt() - 1) (total - i.toLong() * block).toInt() else block
                        var n = 0
                        while (n < want) {
                            val r = input.read(buf, n, want - n)
                            if (r < 0) throw EnvelopeException("attachment decryption failed")
                            n += r
                        }
                        o.write(cipher(Cipher.DECRYPT_MODE, k, base, i, i == count.toInt() - 1).doFinal(buf, 0, n))
                    }
                }
            }
            if (!tmp.renameTo(out)) throw java.io.IOException("couldn't store the decrypted file")
        } catch (e: javax.crypto.AEADBadTagException) {
            throw EnvelopeException("attachment decryption failed")
        } finally {
            tmp.delete()
            k.wipe()
        }
    }
}
