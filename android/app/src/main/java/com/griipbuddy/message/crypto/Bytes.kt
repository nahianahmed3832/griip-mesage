package com.griipbuddy.message.crypto

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

internal val secureRandom = SecureRandom()

fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
fun String.unb64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
fun ByteArray.b64url(): String = Base64.encodeToString(this, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
    parts.forEach { update(it) }
    digest()
}

/** Overwrite secret material in memory when we're done with it (best effort on the JVM). */
fun ByteArray.wipe() = fill(0)
