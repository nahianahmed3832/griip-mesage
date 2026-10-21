package com.meldiq.android.data.model

import com.meldiq.android.data.api.GriipJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Batch B9: a community's own emoji and stickers in messages. */
class ExpressionRulesTest {
    private val sha = "a".repeat(64)
    private fun ref(name: String, id: String = "emj_$name") = CustomEmojiRef(id, "o_1", name, sha)

    @Test
    fun `only well-formed references whose name is in the text are kept, one per name`() {
        val refs = listOf(ref("party"), ref("party", "emj_other"), ref("cat"), CustomEmojiRef("bad id", "o_1", "dog", sha), ref("x"), ref("wave").copy(sha256 = "nothex"))
        assertEquals(listOf("emj_party"), ExpressionRules.cleanRefs(refs, "hi :party: and :dog: :wave:").map { it.id })
        assertNull(ExpressionRules.cleanRef(ref("UPPER")))
        assertEquals(20, ExpressionRules.cleanRefs((1..30).map { ref("e$it") }, (1..30).joinToString(" ") { ":e$it:" }).size)
    }

    @Test
    fun `tokens find every use of a referenced name, and ignore names without a reference`() {
        val t = ExpressionRules.tokens(":party: hi :party::nope:", listOf(ref("party")))
        assertEquals(listOf(0 to 7, 11 to 18), t.map { it.start to it.end })
        assertTrue(ExpressionRules.tokens(":party:", emptyList()).isEmpty())
    }

    @Test
    fun `sending picks the community emoji the text names`() {
        assertEquals(listOf("cat"), ExpressionRules.forSending("look :cat:", listOf(ref("party"), ref("cat"))).map { it.name })
        assertEquals("party_parrot_1", ExpressionRules.suggestName("Party-Parrot (1).GIF"))
    }

    @Test
    fun `an image is shown only when its hash matches`() {
        val bytes = "hello".toByteArray()
        assertTrue(ExpressionRules.matches(bytes, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"))
        assertFalse(ExpressionRules.matches(bytes, sha))
    }

    @Test
    fun `received messages keep their references, a community sticker replaces the text, and plain messages stay plain`() {
        val p = Payload(type = "text", text = "hi :party:", customEmoji = listOf(ref("party")))
        assertEquals("emj_party", RichRules.fromPayload(p)?.customEmoji?.single()?.id)
        val st = RichRules.fromPayload(Payload(type = "text", text = ":wave:", customSticker = ref("wave", "stk_wave")))
        assertTrue(st?.structured == true)
        assertNull(RichRules.fromPayload(Payload(type = "text", text = "hi")))
        assertFalse(GriipJson.encodeToString(Payload.serializer(), Payload(type = "text", text = "hi")).contains("custom"))
    }
}
