package com.meldiq.android.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KeptPicturesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `pictures cached by an older version move in once, so nothing downloads again`() {
        val legacy = tmp.newFolder("cache", "avatars-default")
        File(legacy, "u_1-3.jpg").writeText("photo")
        val pictures = File(tmp.root, "files/pictures")
        val dir = KeptPictures.folder(pictures, "avatars", legacy)
        assertEquals("photo", File(dir, "u_1-3.jpg").readText())
        assertFalse(legacy.exists())
        // Later starts: the folder is used as it is.
        File(dir, "u_2-1.jpg").writeText("other")
        assertEquals(dir, KeptPictures.folder(pictures, "avatars", legacy))
        assertTrue(File(dir, "u_2-1.jpg").isFile)
    }

    @Test
    fun `a fresh install gets an empty folder`() {
        val dir = KeptPictures.folder(File(tmp.root, "files/pictures"), "expressions", File(tmp.root, "cache/none"))
        assertTrue(dir.isDirectory)
        assertEquals(0, dir.listFiles()?.size)
    }

    @Test
    fun `each profile has its own pictures folder, removed with it`() {
        assertEquals("pictures", ProfileStorage.of(ProfileStorage.DEFAULT).picturesDir)
        assertEquals("pictures-abcd1234", ProfileStorage.of("abcd1234").picturesDir)
    }
}
