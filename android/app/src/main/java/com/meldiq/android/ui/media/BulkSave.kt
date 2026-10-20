package com.meldiq.android.ui.media

import com.meldiq.android.data.model.AttachmentRef

/**
 * Saving many files at once (round 7, docs/ATTACHMENTS.md › Saving several files): photos, videos and files picked across
 * several messages and albums, saved with one action. Pure rules (limits, file names, progress), so they're
 * testable on the JVM; the conversation's view model runs the batch.
 *
 * Files are end-to-end encrypted, so the server can't build an archive of them. Each file is downloaded through the
 * same membership-checked endpoint as a single tap, decrypted and checked on the phone, then copied to the folder
 * the person picked. One file at a time: memory stays flat whatever the batch size.
 */
object BulkSave {
    /** Most files in one batch. A larger selection is refused with the limit, so the person can save it in parts. */
    const val MAX_ITEMS = 200

    /** Most bytes in one batch (the files are downloaded to the phone's private storage before they're copied). */
    const val MAX_BYTES = 4L * 1024 * 1024 * 1024

    data class Item(val key: String, val messageId: String, val ref: AttachmentRef, val fileName: String)

    enum class Status { WAITING, SAVING, SAVED, FAILED, CANCELLED }

    sealed interface Plan {
        data class Ready(val items: List<Item>) : Plan
        data class TooMany(val count: Int, val limit: Int) : Plan
        data class TooLarge(val bytes: Long, val limit: Long) : Plan
        data object Empty : Plan
    }

    fun key(messageId: String, refId: String) = "$messageId/$refId"

    /**
     * The batch for [picked] (message id and attachment, in the order shown), leaving out [excluded] keys. The same file
     * picked twice counts once; names that would clash in one folder get " (1)", " (2)" before the extension.
     */
    fun plan(picked: List<Pair<String, AttachmentRef>>, excluded: Set<String> = emptySet()): Plan {
        val unique = picked.distinctBy { (mid, ref) -> key(mid, ref.id) }.filter { (mid, ref) -> key(mid, ref.id) !in excluded }
        if (unique.isEmpty()) return Plan.Empty
        if (unique.size > MAX_ITEMS) return Plan.TooMany(unique.size, MAX_ITEMS)
        val bytes = unique.sumOf { it.second.size.coerceAtLeast(0) }
        if (bytes > MAX_BYTES) return Plan.TooLarge(bytes, MAX_BYTES)
        val names = uniqueNames(unique.map { (_, ref) -> MediaGallery.safeName(ref.name, ref.mime) })
        return Plan.Ready(unique.mapIndexed { i, (mid, ref) -> Item(key(mid, ref.id), mid, ref, names[i]) })
    }

    /** Case-insensitive de-duplication, the way most file systems compare names. */
    fun uniqueNames(names: List<String>): List<String> {
        val taken = HashSet<String>()
        return names.map { name ->
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            val stem = name.substring(0, dot)
            val ext = name.substring(dot)
            var candidate = name
            var n = 1
            while (!taken.add(candidate.lowercase())) candidate = "$stem (${n++})$ext"
            candidate
        }
    }

    data class Summary(val total: Int, val saved: Int, val failed: Int, val cancelled: Int, val running: Boolean) {
        val finished get() = !running && saved + failed + cancelled == total
        val retryable get() = failed + cancelled
    }

    fun summary(status: Map<String, Status>): Summary = Summary(
        total = status.size,
        saved = status.values.count { it == Status.SAVED },
        failed = status.values.count { it == Status.FAILED },
        cancelled = status.values.count { it == Status.CANCELLED },
        running = status.values.any { it == Status.WAITING || it == Status.SAVING },
    )
}
