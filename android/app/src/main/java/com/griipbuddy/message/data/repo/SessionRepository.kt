package com.griipbuddy.message.data.repo

import android.os.Build
import com.griipbuddy.message.crypto.Canonical
import com.griipbuddy.message.crypto.Ed25519
import com.griipbuddy.message.crypto.Hpke
import com.griipbuddy.message.crypto.IdentityStore
import com.griipbuddy.message.crypto.LocalKeys
import com.griipbuddy.message.crypto.RecoveryCode
import com.griipbuddy.message.crypto.SessionInfo
import com.griipbuddy.message.crypto.b64
import com.griipbuddy.message.crypto.unb64
import com.griipbuddy.message.data.api.AddDeviceReq
import com.griipbuddy.message.data.api.ApiClient
import com.griipbuddy.message.data.api.ApiException
import com.griipbuddy.message.data.api.ChallengeRes
import com.griipbuddy.message.data.api.DeviceReg
import com.griipbuddy.message.data.api.GriipJson
import com.griipbuddy.message.data.api.LoginReq
import com.griipbuddy.message.data.api.PairingApproveReq
import com.griipbuddy.message.data.api.PairingPollRes
import com.griipbuddy.message.data.api.PairingRequestInfo
import com.griipbuddy.message.data.api.PairingStartReq
import com.griipbuddy.message.data.api.PairingStartRes
import com.griipbuddy.message.data.api.RegisterReq
import com.griipbuddy.message.data.api.ServerInfo
import com.griipbuddy.message.data.api.SessionRes
import com.griipbuddy.message.data.api.UsernameAvailableRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.serializer

sealed interface SessionState {
    data object SignedOut : SessionState
    data class SignedIn(val session: SessionInfo) : SessionState
    data class Revoked(val reason: String) : SessionState
}

/** Pending link-a-device request held by the NEW device while it waits for approval. */
data class PendingPairing(
    val serverUrl: String,
    val pairingId: String,
    val code: String,
    val expiresAt: Long,
    val keys: LocalKeys,            // device keys generated for this phone (identity seed filled in later)
    val transferPrivate: String,
    val transferPublic: String,
)

class SessionRepository(
    private val store: IdentityStore,
    private val settings: SettingsStore,
) {
    private val _state = MutableStateFlow<SessionState>(store.session()?.let { SessionState.SignedIn(it) } ?: SessionState.SignedOut)
    val state: StateFlow<SessionState> = _state

    val session: SessionInfo? get() = (state.value as? SessionState.SignedIn)?.session
    val keys: LocalKeys? get() = store.keys()
    val serverUrl: String get() = session?.serverUrl ?: settings.current.serverUrl
    val token: String? get() = session?.token

    /** Unauthenticated client used for setup flows (server URL may not be saved yet). */
    private fun setupClient(url: String) = ApiClient({ url }, { null }, { false })

    lateinit var api: ApiClient

    suspend fun serverInfo(url: String): ServerInfo = setupClient(url).request("GET", "/v1/server-info", null, serializer(), auth = false)

    suspend fun usernameAvailable(url: String, username: String): UsernameAvailableRes =
        setupClient(url).request("GET", "/v1/auth/username-available?username=${java.net.URLEncoder.encode(username, "UTF-8")}", null, serializer(), auth = false)

    private suspend fun challenge(c: ApiClient): String = c.request("POST", "/v1/auth/challenge", "{}", ChallengeRes.serializer(), auth = false).nonce

    private fun deviceName(): String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".take(60)

    /** Create a brand-new identity on this device and register the username. */
    suspend fun register(url: String, username: String, displayName: String, avatarColor: Int) {
        val c = setupClient(url)
        val keys = store.keys() ?: LocalKeys.generate().also { store.saveKeys(it) }
        val nonce = challenge(c)
        val signing = keys.deviceSignPublic.b64()
        val req = RegisterReq(
            username = username.trim().lowercase(),
            displayName = displayName.trim(),
            identityKey = keys.identityPublic.b64(),
            avatarColor = avatarColor,
            device = DeviceReg(deviceName(), signing, keys.deviceEncPublic, keys.deviceCertificate()),
            nonce = nonce,
            signature = keys.signWithDevice(Canonical.authProof("register", nonce, signing)),
        )
        val res: SessionRes = c.request("POST", "/v1/auth/register", GriipJson.encodeToString(RegisterReq.serializer(), req), SessionRes.serializer(), auth = false)
        settings.update { it.copy(serverUrl = url) }
        signedIn(SessionInfo(url, res.userId, res.deviceId, req.username, req.displayName, res.sessionToken, res.expiresAt))
    }

    /** Recover an account on this phone with the recovery key (identity seed). Old messages are not recoverable. */
    suspend fun restore(url: String, username: String, recoveryCode: String) {
        val seed = RecoveryCode.decode(recoveryCode) ?: throw ApiException(400, "bad_recovery", "That recovery key isn't valid. Check for typos.")
        val keys = LocalKeys.generate(seed)
        addDevice(url, username.trim().lowercase(), keys, keys.deviceCertificate())
    }

    private suspend fun addDevice(url: String, username: String, keys: LocalKeys, certificate: String) {
        val c = setupClient(url)
        val nonce = challenge(c)
        val signing = keys.deviceSignPublic.b64()
        val req = AddDeviceReq(username, DeviceReg(deviceName(), signing, keys.deviceEncPublic, certificate), nonce, keys.signWithDevice(Canonical.authProof("add-device", nonce, signing)))
        val res = c.request("POST", "/v1/auth/add-device", GriipJson.encodeToString(AddDeviceReq.serializer(), req), SessionRes.serializer(), auth = false)
        store.saveKeys(keys)
        settings.update { it.copy(serverUrl = url) }
        signedIn(SessionInfo(url, res.userId, res.deviceId, username, username, res.sessionToken, res.expiresAt))
    }

    // ───── Pairing: new device side ─────
    suspend fun startPairing(url: String): PendingPairing {
        val keys = LocalKeys.generate()
        val (tPriv, tPub) = Hpke.generateKeyPair()
        val res: PairingStartRes = setupClient(url).request(
            "POST", "/v1/pairing",
            GriipJson.encodeToString(PairingStartReq.serializer(), PairingStartReq(deviceName(), keys.deviceSignPublic.b64(), keys.deviceEncPublic, tPub.b64())),
            PairingStartRes.serializer(), auth = false,
        )
        return PendingPairing(url, res.pairingId, res.code, res.expiresAt, keys, tPriv.b64(), tPub.b64())
    }

    /** Returns true once linked, false while still pending; throws when expired. */
    suspend fun pollPairing(p: PendingPairing): Boolean {
        val r: PairingPollRes = setupClient(p.serverUrl).request("GET", "/v1/pairing/${p.pairingId}", null, PairingPollRes.serializer(), auth = false)
        when (r.status) {
            "pending" -> return false
            "expired" -> throw ApiException(410, "expired", "The link code expired. Start again.")
        }
        val seed = Hpke.open(p.transferPrivate.unb64(), p.transferPublic.unb64(), r.sealedEnc!!.unb64(), PAIRING_INFO, p.keys.deviceSignPublic, r.sealedIdentity!!.unb64())
        if (!Ed25519.publicFromSeed(seed).b64().equals(r.identityKey)) throw ApiException(400, "mismatch", "Identity key mismatch — pairing aborted")
        val keys = p.keys.copy(identitySeed = seed.b64())
        addDevice(p.serverUrl, r.username!!, keys, r.certificate!!)
        return true
    }

    // ───── Pairing: existing device side ─────
    suspend fun lookupPairing(code: String): PairingRequestInfo = api.get("/v1/pairing/code/${code.filter { it.isLetterOrDigit() }}")

    suspend fun approvePairing(code: String, info: PairingRequestInfo) {
        val k = keys ?: error("no keys")
        val cert = Ed25519.sign(k.identitySeed.unb64(), Canonical.deviceCert(k.identityPublic.b64(), info.signingKey, info.encryptionKey).toByteArray()).b64()
        val (enc, sealed) = Hpke.seal(info.transferKey.unb64(), PAIRING_INFO, info.signingKey.unb64(), k.identitySeed.unb64())
        api.post<PairingApproveReq, com.griipbuddy.message.data.api.OkRes>("/v1/pairing/code/${code.filter { it.isLetterOrDigit() }}/approve", PairingApproveReq(cert, sealed.b64(), enc.b64()))
    }

    /** Re-authenticate by signing a fresh challenge with the device key. */
    suspend fun reauth(): Boolean {
        val s = session ?: return false
        val k = keys ?: return false
        return try {
            val c = setupClient(s.serverUrl)
            val nonce = challenge(c)
            val sig = k.signWithDevice(Canonical.authProof("login", nonce, k.deviceSignPublic.b64()))
            val res = c.request("POST", "/v1/auth/login", GriipJson.encodeToString(LoginReq.serializer(), LoginReq(s.deviceId, nonce, sig)), SessionRes.serializer(), auth = false)
            signedIn(s.copy(token = res.sessionToken, tokenExpiresAt = res.expiresAt))
            true
        } catch (e: ApiException) {
            if (e.code == "device_revoked" || e.code == "account_disabled") {
                _state.value = SessionState.Revoked(e.message)
            }
            false
        }
    }

    fun updateDisplayName(name: String) {
        session?.let { signedIn(it.copy(displayName = name)) }
    }

    private fun signedIn(s: SessionInfo) {
        store.saveSession(s)
        _state.value = SessionState.SignedIn(s)
    }

    fun recoveryKey(): String? = store.recoveryKey()

    /** Erase all local identity material. Caller wipes the database. */
    fun wipe() {
        store.clearAll()
        _state.value = SessionState.SignedOut
    }

    companion object {
        val PAIRING_INFO = "griip-pairing-v1".toByteArray()
    }
}
