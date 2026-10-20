package com.meldiq.android.data.model

import com.meldiq.android.data.model.ComposerAux.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerAuxTest {
    private val idle = State()

    @Test
    fun `typing hides the buttons`() {
        assertTrue(ComposerAux.onTextChanged(idle, "", "H").hidden)
    }

    @Test
    fun `moving the cursor is not typing`() {
        assertEquals(idle, ComposerAux.onTextChanged(idle, "Hello", "Hello"))
    }

    @Test
    fun `two idle seconds bring them back`() {
        val typed = ComposerAux.onTextChanged(idle, "", "Hi")
        assertFalse(ComposerAux.onIdle(typed, typed.generation).hidden)
    }

    @Test
    fun `a timer from before newer typing does nothing`() {
        val first = ComposerAux.onTextChanged(idle, "", "H")
        val second = ComposerAux.onTextChanged(first, "H", "He")
        assertEquals(second, ComposerAux.onIdle(second, first.generation))
        assertTrue(ComposerAux.onIdle(second, first.generation).hidden)
    }

    @Test
    fun `a touch outside brings them back at once and voids the pending timer`() {
        val typed = ComposerAux.onTextChanged(idle, "", "Hi")
        val outside = ComposerAux.onOutside(typed)
        assertFalse(outside.hidden)
        assertEquals(outside, ComposerAux.onIdle(outside, typed.generation))
    }

    @Test
    fun `a touch outside while they show changes nothing`() {
        assertEquals(idle, ComposerAux.onOutside(idle))
    }

    @Test
    fun `clearing or sending shows them at once`() {
        val typed = ComposerAux.onTextChanged(idle, "", "Hi")
        assertFalse(ComposerAux.onTextChanged(typed, "Hi", "").hidden)
    }

    @Test
    fun `pasting several lines hides them like typing`() {
        assertTrue(ComposerAux.onTextChanged(idle, "", "line one\nline two\nline three").hidden)
    }
}
