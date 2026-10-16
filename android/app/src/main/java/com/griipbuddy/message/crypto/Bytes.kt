package com.griipbuddy.message.crypto

import java.security.MessageDigest
import java.security.SecureRandom

internal val secureRandom = SecureRandom()

fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

// java.util.Base64 (API 26+, same as minSdk) rather than android.util.Base64, so the crypto code also runs in
// JVM unit tests. Standard alphabet, no line wrapping; decoding accepts input with or without padding.
fun ByteArray.b64(): String = java.util.Base64.getEncoder().encodeToString(this)
fun String.unb64(): ByteArray = java.util.Base64.getDecoder().decode(this)
fun ByteArray.b64url(): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(this)

fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
    parts.forEach { update(it) }
    digest()
}

/** Overwrite secret material in memory when we're done with it (best effort on the JVM). */
fun ByteArray.wipe() = fill(0)
