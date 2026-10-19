package com.meldiq.android.crypto

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted backup files (2026-10-17, docs/BACKUP.md). Unit-tested in `BackupCryptoTest`.
 *
 * File: "ORBITBK1" ‖ header length (4 bytes) ‖ header (UTF-8 JSON, readable without the password: version, key
 * derivation parameters, account id and username, creation time) ‖ encrypted body.
 * Key: PBKDF2-HMAC-SHA256 of the backup password with the header's random salt ([ITERATIONS] rounds), 32 bytes.
 * Body: AES-256-GCM in chunks (like large attachments): chunk i uses the header's nonce with its last four bytes XOR i,
 * and associated data = SHA-256(header) ‖ i ‖ final flag. So the header can't be swapped onto another backup, chunks
 * can't be reordered or dropped, and a cut-off file is detected. A wrong password fails on the first chunk.
 * The body is a ZIP of the chosen content; it never contains private keys or the recovery key.
 */
object BackupCrypto {
    val MAGIC = "ORBITBK1".toByteArray()
    const val ITERATIONS = 600_000
    const val CHUNK = 256 * 1024
    private const val TAG = 16

    class BadBackup(message: String) : Exception(message)

    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun writeHeader(out: OutputStream, header: ByteArray) {
        out.write(MAGIC)
        out.write(byteArrayOf((header.size ushr 24).toByte(), (header.size ushr 16).toByte(), (header.size ushr 8).toByte(), header.size.toByte()))
        out.write(header)
    }

    /** Reads the readable header (no password needed) and leaves [input] at the start of the encrypted body. */
    fun readHeader(input: InputStream): ByteArray {
        val d = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        runCatching { d.readFully(magic) }.getOrElse { throw BadBackup("This isn't a Meldiq backup file.") }
        if (!magic.contentEquals(MAGIC)) throw BadBackup("This isn't a Meldiq backup file.")
        val len = d.readInt()
        if (len !in 2..64 * 1024) throw BadBackup("This backup file is damaged.")
        return ByteArray(len).also { d.readFully(it) }
    }

    private fun aad(headerHash: ByteArray, i: Int, final: Boolean) =
        headerHash + byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte(), if (final) 1 else 0)

    private fun nonceFor(base: ByteArray, i: Int): ByteArray = base.copyOf().also { n ->
        for (b in 0..3) n[8 + b] = (n[8 + b].toInt() xor ((i ushr (8 * (3 - b))) and 0xFF)).toByte()
    }

    private fun cipher(mode: Int, key: ByteArray, base: ByteArray, headerHash: ByteArray, i: Int, final: Boolean) =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonceFor(base, i)))
            updateAAD(aad(headerHash, i, final))
        }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    /** Encrypts everything written to it into [out] as the backup body; close() writes the final chunk. */
    class EncryptingStream(out: OutputStream, private val key: ByteArray, private val nonce: ByteArray, header: ByteArray, private val chunk: Int = CHUNK) : FilterOutputStream(out) {
        private val hash = sha256(header)
        private val buf = ByteArray(chunk)
        private var n = 0
        private var index = 0
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            while (left > 0) {
                if (n == chunk) emit(false) // more data follows: the buffered chunk isn't the last
                val take = minOf(left, chunk - n)
                System.arraycopy(b, o, buf, n, take)
                n += take
                o += take
                left -= take
            }
        }

        private fun emit(final: Boolean) {
            out.write(cipher(Cipher.ENCRYPT_MODE, key, nonce, hash, index++, final).doFinal(buf, 0, n))
            n = 0
        }

        override fun close() {
            if (closed) return
            closed = true
            emit(true)
            out.flush()
            out.close()
        }
    }

    /** Decrypts a backup body; any tampering, a wrong password or a cut-off file throws [BadBackup]. */
    class DecryptingStream(private val input: InputStream, private val key: ByteArray, private val nonce: ByteArray, header: ByteArray, chunk: Int = CHUNK) : InputStream() {
        private val hash = sha256(header)
        private val block = chunk + TAG
        private var current: ByteArray? = readBlock()
        private var plain = ByteArray(0)
        private var pos = 0
        private var index = 0
        private var done = false

        private fun readBlock(): ByteArray? {
            val out = ByteArrayOutputStream()
            val b = ByteArray(block)
            var total = 0
            while (total < block) {
                val r = input.read(b, total, block - total)
                if (r < 0) break
                total += r
            }
            if (total == 0) return null
            out.write(b, 0, total)
            return out.toByteArray()
        }

        private fun next(): Boolean {
            if (done) return false
            val c = current ?: throw BadBackup("This backup file is cut short or damaged.")
            val following = readBlock()
            val final = following == null
            plain = try {
                cipher(Cipher.DECRYPT_MODE, key, nonce, hash, index, final).doFinal(c)
            } catch (e: AEADBadTagException) {
                throw BadBackup(if (index == 0) "Wrong password, or the backup is damaged." else "This backup file is damaged.")
            } catch (e: javax.crypto.BadPaddingException) {
                throw BadBackup("This backup file is damaged.")
            }
            index++
            pos = 0
            current = following
            if (final) done = true
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (pos >= plain.size) {
                if (!next()) return -1
            }
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() = input.close()
    }
}
