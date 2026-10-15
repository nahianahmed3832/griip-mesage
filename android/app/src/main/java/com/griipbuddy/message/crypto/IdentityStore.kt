package com.griipbuddy.message.crypto

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Private key material for this installation. Generated on-device; never transmitted except
 * (a) the identity seed sealed with HPKE to a new device during explicit pairing, or
 * (b) shown to the user as a recovery key on request.
 */
@Serializable
data class LocalKeys(
    val identitySeed: String,     // Ed25519 seed (account identity) — b64
    val deviceSignSeed: String,   // Ed25519 seed (this device)
    val deviceEncPrivate: String, // X25519 private (HPKE)
    val deviceEncPublic: String,  // X25519 public
) {
    val identityPublic: ByteArray get() = Ed25519.publicFromSeed(identitySeed.unb64())
    val deviceSignPublic: ByteArray get() = Ed25519.publicFromSeed(deviceSignSeed.unb64())

    /** Certificate binding this device's keys to the account identity (docs/SECURITY.md §3.1). */
    fun deviceCertificate(): String {
        val msg = Canonical.deviceCert(identityPublic.b64(), deviceSignPublic.b64(), deviceEncPublic)
        return Ed25519.sign(identitySeed.unb64(), msg.toByteArray()).b64()
    }

    fun signWithDevice(message: String): String = Ed25519.sign(deviceSignSeed.unb64(), message.toByteArray()).b64()

    companion object {
        fun generate(identitySeed: ByteArray = randomBytes(32)): LocalKeys {
            val (encPriv, encPub) = Hpke.generateKeyPair()
            return LocalKeys(identitySeed.b64(), randomBytes(32).b64(), encPriv.b64(), encPub.b64())
        }
    }
}

@Serializable
data class SessionInfo(
    val serverUrl: String,
    val userId: String,
    val deviceId: String,
    val username: String,
    val displayName: String,
    val token: String? = null,
    val tokenExpiresAt: Long = 0,
)

/** Persists keys and the session sealed under the Keystore key (SharedPreferences holds ciphertext only). */
class IdentityStore(context: Context) {
    private val prefs = context.getSharedPreferences("griip_identity", Context.MODE_PRIVATE)
    private val sealer = KeystoreSealer()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cachedKeys: LocalKeys? = null
    @Volatile private var cachedSession: SessionInfo? = null

    private fun put(name: String, value: String?) {
        if (value == null) prefs.edit().remove(name).commit()
        else prefs.edit().putString(name, sealer.seal(value.toByteArray()).b64()).commit()
    }

    private fun get(name: String): String? = prefs.getString(name, null)?.let { String(sealer.open(it.unb64())) }

    fun keys(): LocalKeys? = cachedKeys ?: get("keys")?.let { json.decodeFromString<LocalKeys>(it) }?.also { cachedKeys = it }

    fun saveKeys(k: LocalKeys) {
        put("keys", json.encodeToString(LocalKeys.serializer(), k))
        cachedKeys = k
    }

    fun session(): SessionInfo? = cachedSession ?: get("session")?.let { json.decodeFromString<SessionInfo>(it) }?.also { cachedSession = it }

    fun saveSession(s: SessionInfo?) {
        put("session", s?.let { json.encodeToString(SessionInfo.serializer(), it) })
        cachedSession = s
    }

    /** Random 32-byte passphrase for SQLCipher, created once and sealed by the Keystore. */
    fun databasePassphrase(): ByteArray {
        get("dbkey")?.let { return it.unb64() }
        val k = randomBytes(32)
        put("dbkey", k.b64())
        return k
    }

    fun clearAll() {
        prefs.edit().clear().commit()
        cachedKeys = null
        cachedSession = null
        sealer.destroy()
    }

    /** Recovery key = account identity seed rendered as grouped base32 (shown only on explicit request). */
    fun recoveryKey(): String? = keys()?.let { RecoveryCode.encode(it.identitySeed.unb64()) }
}

object RecoveryCode {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun encode(seed: ByteArray): String {
        val checksum = sha256(seed).copyOf(2)
        val data = seed + checksum
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.chunked(4).joinToString("-")
    }

    fun decode(code: String): ByteArray? {
        val clean = code.uppercase().filter { it in ALPHABET }
        var buffer = 0
        var bits = 0
        val out = ArrayList<Byte>()
        for (ch in clean) {
            buffer = (buffer shl 5) or ALPHABET.indexOf(ch)
            bits += 5
            if (bits >= 8) {
                out.add(((buffer shr (bits - 8)) and 0xff).toByte())
                bits -= 8
            }
        }
        if (out.size < 34) return null
        val data = out.toByteArray()
        val seed = data.copyOfRange(0, 32)
        val check = data.copyOfRange(32, 34)
        return if (sha256(seed).copyOf(2).contentEquals(check)) seed else null
    }
}
