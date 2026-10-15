package com.griipbuddy.message.crypto

/** Canonical strings — must match server/src/crypto.ts and server/test/support/griipClient.ts exactly. */
object Canonical {
    fun deviceCert(identityKey: String, signingKey: String, encryptionKey: String) =
        "griip-device-cert-v1\n$identityKey\n$signingKey\n$encryptionKey"

    fun authProof(purpose: String, nonce: String, signingKey: String) = "griip-auth-v1\n$purpose\n$nonce\n$signingKey"

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
}
