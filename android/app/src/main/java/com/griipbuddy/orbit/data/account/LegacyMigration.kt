package com.griipbuddy.orbit.data.account

import com.griipbuddy.orbit.crypto.Canonical
import com.griipbuddy.orbit.crypto.Ed25519
import com.griipbuddy.orbit.crypto.RecoveryCode
import com.griipbuddy.orbit.crypto.b64
import com.griipbuddy.orbit.data.api.ApiException

/**
 * Proof that the person holds a legacy account: a single-use ticket from the server, and the identity seed derived from
 * their recovery key, kept in memory only for setting up a new phone afterwards. Never stored, never logged.
 */
class LegacyTicket(val ticket: String, val expiresAt: Long, val username: String, val displayName: String, internal val identitySeed: ByteArray) {
    override fun toString(): String = "LegacyTicket(@$username)"
}

/**
 * Moving a legacy (device-keyed) Orbit account to an email-and-password account (docs/ORBIT_2_ARCHITECTURE §5).
 *
 * The recovery key never leaves the phone: it is turned into the identity key here, which signs a fresh challenge, and
 * the server checks that signature against the account's identity key. On a recognised installation (this phone is
 * signed in to the legacy account) the key must also match the one this phone holds, so a typo is caught before any
 * request. Unit-tested in `LegacyMigrationTest`.
 */
class LegacyMigration(
    private val api: AccountApi,
    private val vault: AccountVault,
    private val session: MessagingSession,
    private val accounts: AccountRepository,
) {
    /**
     * A legacy installation the app recognises: signed in the old way, holding the account's keys, with no Orbit 2.0
     * account yet. The username alone would prove nothing; the recovery key typed in is checked against these keys.
     */
    val recognizedInstall: Boolean
        get() = session.current != null && vault.keys() != null && vault.account() == null

    /** The legacy username of a recognised installation (shown, not typed). */
    val recognizedUsername: String? get() = session.current?.username?.takeIf { recognizedInstall }

    suspend fun verify(serverUrl: String, username: String, recoveryKey: String): LegacyTicket {
        val seed = RecoveryCode.decode(recoveryKey) ?: throw ApiException(400, "bad_recovery", "That recovery key isn't valid. Check for typos.")
        if (recognizedInstall) {
            val mine = vault.keys()?.identityPublic?.b64()
            if (mine != Ed25519.publicFromSeed(seed).b64()) {
                throw ApiException(400, "recovery_mismatch", "That recovery key doesn't belong to the account on this phone.")
            }
        }
        val name = username.trim().removePrefix("@").lowercase()
        val nonce = api.challenge(serverUrl)
        val signature = Ed25519.sign(seed, Canonical.legacyMigration(name, nonce).toByteArray()).b64()
        val res = api.legacyVerify(serverUrl, LegacyVerifyReq(name, nonce, signature))
        return LegacyTicket(res.ticket, res.expiresAt, res.legacyUser.username, res.legacyUser.displayName, seed)
    }

    /** Creates the new account linked to the legacy one; the person then enters the emailed code. Safe to repeat. */
    suspend fun complete(serverUrl: String, ticket: LegacyTicket, name: String, email: String, password: String) {
        val address = AccountRules.normalizeEmail(email)
        api.legacyComplete(serverUrl, LegacyCompleteReq(ticket.ticket, name.trim(), address, password))
        // On a recognised installation this phone's device signs in again as itself, keeping its keys and messages.
        val recognized = recognizedInstall
        accounts.awaitVerification(serverUrl, address, deviceId = session.current?.deviceId?.takeIf { recognized }, legacyOwner = recognized)
    }

    /**
     * After the code: signs in. A recognised installation is bound with its own device key; a new phone sets itself up
     * with the identity seed from the recovery key, then restores the account's encrypted history like any new device.
     */
    suspend fun finish(serverUrl: String, ticket: LegacyTicket, email: String, password: String) {
        accounts.login(serverUrl, email, password)
        if (accounts.phase.value is AccountPhase.NeedsDeviceSetup) accounts.devices.withIdentitySeed(ticket.identitySeed)
    }
}
