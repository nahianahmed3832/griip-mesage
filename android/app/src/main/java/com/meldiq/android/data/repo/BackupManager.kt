package com.meldiq.android.data.repo

import android.content.Context
import android.net.Uri
import com.meldiq.android.crypto.BackupCrypto
import com.meldiq.android.crypto.KeystoreSealer
import com.meldiq.android.crypto.b64
import com.meldiq.android.crypto.randomBytes
import com.meldiq.android.crypto.unb64
import com.meldiq.android.data.db.ConversationEntity
import com.meldiq.android.data.db.GriipDatabase
import com.meldiq.android.data.db.MessageColumns
import com.meldiq.android.data.db.MessageEntity
import com.meldiq.android.data.db.UserEntity
import com.meldiq.android.data.model.AttachmentRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The readable part of a backup file (no password needed): who it belongs to and what it holds. */
@Serializable
data class BackupHeader(
    val v: Int = 1,
    val kdf: String = "pbkdf2-sha256",
    val iterations: Int,
    val salt: String,
    val nonce: String,
    val chunk: Int,
    val userId: String,
    val username: String,
    val createdAt: Long,
    val media: Boolean,
    val app: String = "Meldiq",
)

@Serializable
data class BackupManifest(val conversations: Int, val messages: Int, val users: Int, val mediaFiles: Int, val mediaBytes: Long)

/**
 * Encrypted backups of one account's chats on this phone (2026-10-17, docs/BACKUP.md). A backup holds chats, messages,
 * the people known for them (with their safety-number pins) and, if chosen, downloaded photos, videos and files. It
 * never holds private keys or the recovery key: restoring onto a new phone means signing in to the same account first
 * (recovery key), then restoring the backup into it. Restoring only adds what this phone doesn't have; nothing on the
 * phone is replaced or deleted.
 */
class BackupManager(
    private val context: Context,
    private val db: GriipDatabase,
    private val session: SessionRepository,
    private val attachmentsDir: File,
    private val profileId: String,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val prefs = context.getSharedPreferences("meldiq_backup_$profileId", Context.MODE_PRIVATE)
    private val sealer by lazy { KeystoreSealer("meldiq_backup_$profileId") }

    data class Progress(val done: Int, val total: Int)

    // ─────────── Automatic backups (a file the person chose, e.g. in Google Drive, through Android's picker) ───────────

    data class AutoConfig(val uri: Uri?, val frequencyDays: Int, val media: Boolean, val lastSuccessAt: Long?, val lastError: String?)

    val auto: AutoConfig
        get() = AutoConfig(
            prefs.getString("uri", null)?.let(Uri::parse), prefs.getInt("days", 0), prefs.getBoolean("media", false),
            prefs.getLong("lastSuccess", 0).takeIf { it > 0 }, prefs.getString("lastError", null),
        )

    /** Turns on automatic backups to [uri] every [days] days with [password]; the derived key is sealed in the Keystore. */
    fun enableAuto(uri: Uri, days: Int, media: Boolean, password: CharArray) {
        val salt = randomBytes(16)
        val key = BackupCrypto.deriveKey(password, salt)
        prefs.edit()
            .putString("uri", uri.toString()).putInt("days", days).putBoolean("media", media)
            .putString("salt", salt.b64()).putInt("iterations", BackupCrypto.ITERATIONS)
            .putString("sealedKey", sealer.seal(key).b64())
            .remove("lastError").apply()
        key.fill(0)
    }

    fun disableAuto() {
        prefs.edit().clear().apply()
        runCatching { sealer.destroy() }
    }

    /** One automatic backup (from the worker). Records the outcome for Settings › Data & Storage. */
    suspend fun runAuto(): Boolean {
        val uri = auto.uri ?: return false
        val salt = prefs.getString("salt", null)?.unb64() ?: return false
        val iterations = prefs.getInt("iterations", BackupCrypto.ITERATIONS)
        val key = runCatching { sealer.open(prefs.getString("sealedKey", null)!!.unb64()) }.getOrNull()
            ?: return fail("The backup key is no longer on this phone. Turn automatic backup on again.")
        return try {
            // Never overwrite a newer backup written by another phone (or restored elsewhere) at the same place.
            val existing = runCatching { context.contentResolver.openInputStream(uri)?.use { readHeader(it) } }.getOrNull()
            val mine = prefs.getLong("lastCreatedAt", 0)
            if (existing != null && existing.createdAt > mine) return fail("A newer backup is already in that file. Choose another file for automatic backups.")
            val tmp = File(context.cacheDir, "backup-$profileId.part")
            val header = withContext(Dispatchers.IO) { tmp.outputStream().use { write(it, key, salt, iterations, auto.media) {} } }
            withContext(Dispatchers.IO) {
                (context.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("can't write")).use { o -> tmp.inputStream().use { it.copyTo(o) } }
            }
            tmp.delete()
            prefs.edit().putLong("lastSuccess", System.currentTimeMillis()).putLong("lastCreatedAt", header.createdAt).remove("lastError").apply()
            true
        } catch (e: Exception) {
            fail("The last backup didn't finish: ${e.message ?: "the file couldn't be written"}.")
        } finally {
            key.fill(0)
        }
    }

    private fun fail(why: String): Boolean {
        prefs.edit().putString("lastError", why).apply()
        return false
    }

    // ─────────── Writing ───────────

    /** Writes a backup protected by [password] to [out] (a file the person chose). */
    suspend fun export(out: OutputStream, password: CharArray, media: Boolean, onProgress: (Progress) -> Unit = {}): BackupHeader {
        val salt = randomBytes(16)
        val key = BackupCrypto.deriveKey(password, salt)
        try {
            return withContext(Dispatchers.IO) { write(out, key, salt, BackupCrypto.ITERATIONS, media, onProgress) }
        } finally {
            key.fill(0)
        }
    }

    private suspend fun write(out: OutputStream, key: ByteArray, salt: ByteArray, iterations: Int, media: Boolean, onProgress: (Progress) -> Unit): BackupHeader {
        val s = session.session ?: throw IllegalStateException("Not signed in")
        val header = BackupHeader(
            iterations = iterations, salt = salt.b64(), nonce = randomBytes(12).b64(), chunk = BackupCrypto.CHUNK,
            userId = s.userId, username = s.username, createdAt = System.currentTimeMillis(), media = media,
        )
        val headerBytes = json.encodeToString(BackupHeader.serializer(), header).toByteArray()
        BackupCrypto.writeHeader(out, headerBytes)
        val convs = db.conversations().all()
        val users = db.users().all()
        val total = db.messages().count()
        var mediaFiles = 0
        var mediaBytes = 0L
        ZipOutputStream(BackupCrypto.EncryptingStream(out, key, header.nonce.unb64(), headerBytes)).use { zip ->
            zip.putNextEntry(ZipEntry("conversations.json"))
            zip.write(json.encodeToString(ListSerializer(ConversationEntity.serializer()), convs).toByteArray())
            zip.putNextEntry(ZipEntry("users.json"))
            zip.write(json.encodeToString(ListSerializer(UserEntity.serializer()), users).toByteArray())
            zip.putNextEntry(ZipEntry("messages.jsonl"))
            val refs = ArrayList<AttachmentRef>()
            var offset = 0
            while (true) {
                val page = db.messages().page(500, offset)
                if (page.isEmpty()) break
                for (m in page) {
                    zip.write(json.encodeToString(MessageEntity.serializer(), m).toByteArray())
                    zip.write('\n'.code)
                    if (media) refs += MessageColumns.attachments(m)
                }
                offset += page.size
                onProgress(Progress(offset, total))
            }
            if (media) {
                val files = AttachmentFiles(attachmentsDir)
                for (r in refs.filter { it.id != AttachmentFiles.PENDING }.distinctBy { it.id }) {
                    val f = files.fileFor(r.id, r.name).takeIf { it.isFile } ?: continue
                    zip.putNextEntry(ZipEntry("media/" + f.name))
                    f.inputStream().use { it.copyTo(zip) }
                    mediaFiles++
                    mediaBytes += f.length()
                }
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(json.encodeToString(BackupManifest.serializer(), BackupManifest(convs.size, total, users.size, mediaFiles, mediaBytes)).toByteArray())
        }
        return header
    }

    // ─────────── Reading ───────────

    fun readHeader(input: InputStream): BackupHeader =
        runCatching { json.decodeFromString(BackupHeader.serializer(), String(BackupCrypto.readHeader(input))) }
            .getOrElse { if (it is BackupCrypto.BadBackup) throw it else throw BackupCrypto.BadBackup("This backup file can't be read.") }

    data class Restored(val conversations: Int, val messages: Int, val media: Int, val skippedExisting: Int)

    /**
     * Checks [input] with [password] and, unless [dryRun], adds its chats and messages to this account. Refuses a backup
     * of another account, a newer format, a wrong password and any damaged or cut-off file before changing anything:
     * the whole body is decrypted and checked first into a temporary file.
     */
    suspend fun restore(input: InputStream, password: CharArray, dryRun: Boolean = false, onProgress: (Progress) -> Unit = {}): Restored = withContext(Dispatchers.IO) {
        val s = session.session ?: throw IllegalStateException("Not signed in")
        val headerBytes = BackupCrypto.readHeader(input)
        val header = runCatching { json.decodeFromString(BackupHeader.serializer(), String(headerBytes)) }.getOrElse { throw BackupCrypto.BadBackup("This backup file can't be read.") }
        if (header.v > 1) throw BackupCrypto.BadBackup("This backup was made by a newer version of Meldiq. Update the app first.")
        if (header.userId != s.userId) throw BackupCrypto.BadBackup("This backup belongs to @${header.username}. Sign in to that account to restore it.")
        val key = BackupCrypto.deriveKey(password, header.salt.unb64(), header.iterations)
        // Decrypt and authenticate everything before touching the database.
        val plain = File(context.cacheDir, "restore-$profileId.zip")
        try {
            BackupCrypto.DecryptingStream(input, key, header.nonce.unb64(), headerBytes, header.chunk).use { d -> plain.outputStream().use { d.copyTo(it) } }
            if (dryRun) return@withContext count(plain)
            apply(plain, onProgress)
        } finally {
            key.fill(0)
            plain.delete()
        }
    }

    private fun count(plain: File): Restored {
        ZipInputStream(plain.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "manifest.json") {
                    val m = json.decodeFromString(BackupManifest.serializer(), String(z.readBytes()))
                    return Restored(m.conversations, m.messages, m.mediaFiles, 0)
                }
            }
        }
        throw BackupCrypto.BadBackup("This backup file is incomplete.")
    }

    private suspend fun apply(plain: File, onProgress: (Progress) -> Unit): Restored {
        var convs = 0
        var msgs = 0
        var mediaN = 0
        var skipped = 0
        val files = AttachmentFiles(attachmentsDir)
        ZipInputStream(plain.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                when {
                    e.name == "conversations.json" -> for (c in json.decodeFromString(ListSerializer(ConversationEntity.serializer()), String(z.readBytes()))) {
                        if (db.conversations().insertIgnore(c) == -1L) skipped++ else convs++
                    }
                    e.name == "users.json" -> for (u in json.decodeFromString(ListSerializer(UserEntity.serializer()), String(z.readBytes()))) db.users().insertIgnore(u)
                    // Streamed line by line: the entry ends where ZipInputStream returns -1, so the reader stays within it
                    // (and isn't closed, which would close the ZIP).
                    e.name == "messages.jsonl" -> for (line in z.bufferedReader().lineSequence()) {
                        if (line.isBlank()) continue
                        // A message that was still being sent when the backup was made can't resume from here.
                        val m = json.decodeFromString(MessageEntity.serializer(), line).let {
                            if (it.status == com.meldiq.android.data.model.MessageStatus.SENDING || it.status == com.meldiq.android.data.model.MessageStatus.HELD) {
                                it.copy(status = com.meldiq.android.data.model.MessageStatus.FAILED, errorText = "Not sent before the backup was made")
                            } else it
                        }
                        if (db.messages().insertIgnore(m) == -1L) skipped++ else msgs++
                        if ((msgs + skipped) % 500 == 0) onProgress(Progress(msgs + skipped, -1))
                    }
                    e.name.startsWith("media/") -> {
                        val name = e.name.removePrefix("media/")
                        val target = files.child(name) ?: continue // a name that could escape the folder is ignored
                        if (!target.exists()) {
                            val part = File(target.parentFile, "$name.part")
                            part.outputStream().use { z.copyTo(it) }
                            if (part.renameTo(target)) mediaN++ else part.delete()
                        }
                    }
                }
            }
        }
        return Restored(convs, msgs, mediaN, skipped)
    }
}
