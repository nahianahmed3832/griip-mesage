package com.meldiq.android.data.repo

import android.content.Context
import java.io.File

/** Folders for downloaded pictures that stay until their profile is removed ([ProfileStorage.picturesDir]). */
object KeptPictures {
    /** [name] inside [profileId]'s pictures folder; what an older version cached in [legacyCache] moves in once. */
    fun folder(context: Context, profileId: String, name: String, legacyCache: String): File =
        folder(File(context.filesDir, ProfileStorage.of(profileId).picturesDir), name, File(context.cacheDir, legacyCache))

    internal fun folder(pictures: File, name: String, legacy: File): File {
        val dir = File(pictures, name)
        if (!dir.isDirectory && legacy.isDirectory) {
            pictures.mkdirs()
            // Same volume (the app's own storage): a rename, no copy. If it fails, the files are fetched once more.
            if (!legacy.renameTo(dir)) legacy.deleteRecursively()
        }
        return dir.apply { mkdirs() }
    }
}
