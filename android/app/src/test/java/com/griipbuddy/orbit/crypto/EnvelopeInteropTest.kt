package com.griipbuddy.orbit.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Verifies the shared griip-envelope-v1 vectors (`src/test/resources/griip-envelope-v1-vectors.json`),
 * which the Node reference client also verifies (server/test/interop_vectors.test.ts). Deterministic
 * outputs must match the Node values byte for byte; the Node-sealed envelope must open here.
 */
class EnvelopeInteropTest {
    private val v: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("griip-envelope-v1-vectors.json")!!.readText(),
    ).jsonObject
    private fun JsonObject.s(name: String) = this[name]!!.jsonPrimitive.content
    private val sender = v["sender"]!!.jsonObject
    private val recipient = v["recipient"]!!.jsonObject
    private val env = v["envelope"]!!.jsonObject

    private val senderKeys = LocalKeys(sender.s("identitySeed"), sender.s("deviceSignSeed"), sender.s("deviceEncPrivate"), sender.s("deviceEncPublic"))
    /** The recipient only needs its HPKE keys to open; its signing seeds are irrelevant here. */
    private val recipientKeys = LocalKeys(randomBytes(32).b64(), randomBytes(32).b64(), recipient.s("encPrivate"), recipient.s("encPublic"))

    private fun received(e: JsonObject = env, signature: String = e.s("signature")): ReceivedEnvelope {
        val h = e["header"]!!.jsonObject
        return ReceivedEnvelope(
            EnvelopeHeader(h.s("messageId"), h.s("conversationId"), h.s("senderUserId"), h.s("senderDeviceId"), h["sentAt"]!!.jsonPrimitive.long, h.s("cls")),
            e.s("nonce"), e.s("ciphertext"), signature, e.s("enc"), e.s("wrappedKey"),
        )
    }

    @Test
    fun publicKeysCertificateAndAuthProofMatchNodeExactly() {
        assertEquals(sender.s("identityKey"), senderKeys.identityPublic.b64())
        assertEquals(sender.s("deviceSigningKey"), senderKeys.deviceSignPublic.b64())
        assertEquals(sender.s("certificate"), senderKeys.deviceCertificate())
        val proof = v["authProof"]!!.jsonObject
        assertEquals(proof.s("signature"), senderKeys.signWithDevice(proof.s("message")))
    }

    @Test
    fun fingerprintMatchesTheServerDerivation() {
        assertEquals(sender.s("fingerprint"), Fingerprint.of(sender.s("identityKey").unb64()))
    }

    @Test
    fun aesGcmMatchesNodeForTheSameKeyAndNonce() {
        val a = v["aesGcm"]!!.jsonObject
        val ct = AesGcm.encrypt(a.s("key").unb64(), a.s("nonce").unb64(), a.s("plaintext").unb64(), a.s("aad").unb64())
        assertEquals(a.s("ciphertext"), ct.b64())
    }

    @Test
    fun nodeSealedEnvelopeOpensAndVerifies() {
        val plain = EnvelopeV1 { recipientKeys }.open(received(), sender.s("deviceSigningKey").unb64(), recipient.s("deviceId"))
        assertEquals(env.s("plaintext"), String(plain))
    }

    @Test
    fun tamperingOrTheWrongDeviceIsRejected() {
        val crypto = EnvelopeV1 { recipientKeys }
        val signingKey = sender.s("deviceSigningKey").unb64()
        val forged = received(signature = ByteArray(64).b64())
        assertThrows(EnvelopeException::class.java) { crypto.open(forged, signingKey, recipient.s("deviceId")) }
        assertThrows(EnvelopeException::class.java) { crypto.open(received(), randomBytes(32), recipient.s("deviceId")) }
        assertThrows(EnvelopeException::class.java) { crypto.open(received(), signingKey, "d_someone_else") }
    }

    @Test
    fun kotlinSealedEnvelopeRoundTripsToEveryRecipient() {
        val header = EnvelopeHeader("m_kotlin_1", "c_vector", sender.s("userId"), sender.s("deviceId"), 1_760_000_000_000, "message")
        val other = LocalKeys.generate()
        val sealed = EnvelopeV1 { senderKeys }.seal(
            header,
            "Hello from Kotlin ✓".toByteArray(),
            listOf(RecipientDevice(recipient.s("deviceId"), recipient.s("encPublic").unb64()), RecipientDevice("d_other", other.deviceEncPublic.unb64())),
        )
        for ((deviceId, keys) in listOf(recipient.s("deviceId") to recipientKeys, "d_other" to other)) {
            val wrap = sealed.recipients.single { it.deviceId == deviceId }
            val opened = EnvelopeV1 { keys }.open(
                ReceivedEnvelope(header, sealed.nonce, sealed.ciphertext, sealed.signature, wrap.enc, wrap.wrappedKey),
                senderKeys.deviceSignPublic,
                deviceId,
            )
            assertArrayEquals("Hello from Kotlin ✓".toByteArray(), opened)
        }
    }
}
