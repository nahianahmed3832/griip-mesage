package com.meldiq.android.crypto

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.hpke.HPKE
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Thin wrappers over vetted library primitives. No algorithm here is implemented by us:
 *  - Ed25519 signatures: BouncyCastle Ed25519Signer (RFC 8032)
 *  - HPKE base mode, DHKEM(X25519, HKDF-SHA256) / HKDF-SHA256 / AES-256-GCM: BouncyCastle HPKE (RFC 9180)
 *  - AES-256-GCM content encryption: platform JCA (Conscrypt)
 */
object Ed25519 {
    fun publicFromSeed(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        val s = Ed25519Signer()
        s.init(true, Ed25519PrivateKeyParameters(seed, 0))
        s.update(message, 0, message.size)
        return s.generateSignature()
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        if (publicKey.size != 32 || signature.size != 64) false
        else Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(publicKey, 0))
            update(message, 0, message.size)
            verifySignature(signature)
        }
    } catch (_: Exception) {
        false
    }
}

object Hpke {
    private fun suite() = HPKE(HPKE.mode_base, HPKE.kem_X25519_SHA256, HPKE.kdf_HKDF_SHA256, HPKE.aead_AES_GCM256)

    /** Returns (privateKeyRaw32, publicKeyRaw32). */
    fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val s = suite()
        val kp: AsymmetricCipherKeyPair = s.generatePrivateKey()
        return s.serializePrivateKey(kp.private) to s.serializePublicKey(kp.public)
    }

    /** Returns (enc, ciphertext). */
    fun seal(recipientPublic: ByteArray, info: ByteArray, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val s = suite()
        val out = s.seal(s.deserializePublicKey(recipientPublic), info, aad, plaintext, null, null, null)
        return out[1] to out[0]
    }

    fun open(privateKey: ByteArray, publicKey: ByteArray, enc: ByteArray, info: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val s = suite()
        val kp = s.deserializePrivateKey(privateKey, publicKey)
        return s.open(enc, kp, info, aad, ciphertext, null, null, null)
    }
}

object AesGcm {
    fun encrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray? = null): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(ciphertext)
    }
}

/** Safety-number style fingerprint; identical derivation to server/src/crypto.ts keyFingerprint. */
object Fingerprint {
    fun of(identityKey: ByteArray): String {
        val h = sha256(identityKey)
        return (0 until 6).joinToString(" ") { i ->
            var n = 0L
            for (j in 0 until 5) n = (n shl 8) or (h[i * 5 + j].toLong() and 0xff)
            (n % 100000).toString().padStart(5, '0')
        }
    }
}
