package com.meldiq.android.ui

import com.meldiq.android.ui.screens.workspace.documents.DocRules
import com.meldiq.android.ui.screens.workspace.documents.DocRules.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocRulesTest {
    @Test
    fun `roles rank view comment edit admin`() {
        assertTrue(DocRules.atLeast("edit", "comment"))
        assertFalse(DocRules.atLeast("comment", "edit"))
        assertFalse(DocRules.atLeast(null, "view"))
        assertTrue(DocRules.atLeast("admin", "admin"))
    }

    @Test
    fun `markdown subset parses line by line`() {
        val blocks = DocRules.parse("# Title\n\n\n## Agenda\n- one\n- [x] done\n* [ ] todo\n1. first\n> quoted\nplain text\n\n")
        assertEquals(
            listOf(
                Block.Heading(1, "Title"), Block.Gap, Block.Heading(2, "Agenda"), Block.Bullet("one"), Block.Bullet("done", true),
                Block.Bullet("todo", false), Block.Numbered("1", "first"), Block.Quote("quoted"), Block.Paragraph("plain text"),
            ),
            blocks,
        )
    }

    @Test
    fun `bold and code are the only inline styles`() {
        assertEquals(
            listOf(Triple("Date: ", false, false), Triple("today", true, false), Triple(" run ", false, false), Triple("npm test", false, true)),
            DocRules.inline("Date: **today** run `npm test`"),
        )
        assertEquals(listOf(Triple("no *markup* here", false, false)), DocRules.inline("no *markup* here"))
    }
}
