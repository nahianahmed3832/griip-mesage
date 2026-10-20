package com.meldiq.android.ui.screens.conversation

import android.content.Context
import android.net.Uri
import com.meldiq.android.data.model.AttachmentRef
import com.meldiq.android.ui.media.BulkSave
import com.meldiq.android.ui.media.BulkSave.Status
import com.meldiq.android.ui.media.MediaExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs one "save several files" batch ([BulkSave]): one file at a time, each downloaded and decrypted through the
 * normal attachment path, then copied to the chosen folder or the gallery. Every file ends SAVED, FAILED (with the
 * reason) or CANCELLED; nothing is dropped silently, and failed or cancelled files can be retried.
 */
class BulkSaver(
    private val scope: CoroutineScope,
    private val app: Context,
    private val download: suspend (AttachmentRef) -> File,
    private val isMedia: suspend (AttachmentRef, File) -> Boolean,
) {
    sealed interface Target {
        data class Folder(val tree: Uri) : Target
        data object Gallery : Target
    }

    data class Batch(val items: List<BulkSave.Item>, val target: Target, val status: Map<String, Status>, val errors: Map<String, String> = emptyMap()) {
        val summary get() = BulkSave.summary(status)
    }

    private val _batch = MutableStateFlow<Batch?>(null)
    val batch: StateFlow<Batch?> = _batch.asStateFlow()
    private var job: Job? = null

    fun start(items: List<BulkSave.Item>, target: Target) {
        if (job?.isActive == true || items.isEmpty()) return
        _batch.value = Batch(items, target, items.associate { it.key to Status.WAITING })
        run(items.map { it.key })
    }

    /** Tries the failed and cancelled files again, into the same place. */
    fun retry() {
        val b = _batch.value ?: return
        if (job?.isActive == true) return
        val keys = b.status.filterValues { it == Status.FAILED || it == Status.CANCELLED }.keys
        if (keys.isEmpty()) return
        _batch.value = b.copy(status = b.status.mapValues { (k, v) -> if (k in keys) Status.WAITING else v }, errors = b.errors - keys)
        run(keys.toList())
    }

    fun cancel() {
        job?.cancel()
    }

    fun close() {
        cancel()
        _batch.value = null
    }

    private fun run(keys: List<String>) {
        job = scope.launch {
            try {
                for (key in keys) {
                    val b = _batch.value ?: return@launch
                    val item = b.items.firstOrNull { it.key == key } ?: continue
                    setStatus(key, Status.SAVING)
                    try {
                        saveOne(item, b.target)
                        setStatus(key, Status.SAVED)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _batch.update { it?.copy(status = it.status + (key to Status.FAILED), errors = it.errors + (key to reason(e))) }
                    }
                }
            } finally {
                // Whatever didn't finish (cancelled, or the screen closed) is marked so it can be retried.
                withContext(NonCancellable) {
                    _batch.update { b -> b?.copy(status = b.status.mapValues { (_, v) -> if (v == Status.WAITING || v == Status.SAVING) Status.CANCELLED else v }) }
                }
            }
        }
    }

    private fun setStatus(key: String, s: Status) = _batch.update { it?.copy(status = it.status + (key to s)) }

    private suspend fun saveOne(item: BulkSave.Item, target: Target) {
        val file = download(item.ref)
        when (target) {
            is Target.Folder -> MediaExport.saveToFolder(app, file, target.tree, item.fileName, item.ref.mime)
            Target.Gallery -> {
                if (!isMedia(item.ref, file)) throw NotMedia()
                MediaExport.saveToGallery(app, file, item.fileName, item.ref.mime)
            }
        }
    }

    private class NotMedia : Exception()

    private fun reason(e: Exception): String = when (e) {
        is NotMedia -> "Not a photo or video: save it to a folder instead"
        is com.meldiq.android.data.api.ApiException -> e.message
        is java.io.IOException -> e.message ?: "Couldn't write the file"
        is SecurityException -> "No permission to write there"
        else -> "Couldn't save this file"
    }
}
