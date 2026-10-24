package com.meldiq.android.data.ai

import com.meldiq.android.data.api.GriipJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A skill's input schema (zod's JSON Schema, as the server sends it) as phone form fields. */
class SkillFormTest {
    private val schema = GriipJson.parseToJsonElement(
        """{"type":"object","properties":{
          "brief":{"type":"string"},
          "format":{"type":"string","enum":["reel","story"],"default":"reel"},
          "captions":{"type":"boolean","default":true},
          "targetSeconds":{"type":"integer","minimum":5,"maximum":120},
          "budget":{"type":"number"},
          "platforms":{"type":"array","items":{"type":"string","enum":["x","linkedin"]}},
          "urls":{"type":"array","items":{"type":"string"}},
          "title":{"anyOf":[{"type":"string","maxLength":80},{"type":"null"}],"default":null},
          "nested":{"type":"object"}
        }}""",
    )

    @Test
    fun `fields cover the options a phone form can show`() {
        val f = SkillForm.fields(schema)
        assertEquals(listOf("format", "captions", "targetSeconds", "budget", "platforms", "urls", "title"), f.map { it.key })
        assertEquals(SkillForm.Field.Choice("format", listOf("reel", "story"), "reel"), f[0])
        assertEquals(SkillForm.Field.Toggle("captions", true), f[1])
        assertEquals(SkillForm.Field.Number("targetSeconds", true, 5.0, 120.0, null), f[2])
        assertEquals(false, (f[3] as SkillForm.Field.Number).integer)
        assertEquals(SkillForm.Field.MultiChoice("platforms", listOf("x", "linkedin"), emptyList()), f[4])
        assertEquals(SkillForm.Field.Lines("urls"), f[5])
        assertEquals(SkillForm.Field.Text("title", 80, null), f[6])
        assertTrue(SkillForm.fields(null).isEmpty())
        assertTrue(SkillForm.fields(JsonPrimitive("x")).isEmpty())
    }

    @Test
    fun `the input keeps set options and the trimmed brief`() {
        val input = SkillForm.input("  cut it  ", mapOf("format" to JsonPrimitive("story"), "title" to JsonPrimitive(" "), "budget" to JsonNull, "urls" to SkillForm.lines("a\n\n b ")))
        assertEquals(setOf("format", "urls", "brief"), input.keys)
        assertEquals("cut it", (input["brief"] as JsonPrimitive).content)
        assertEquals("a\nb", SkillForm.linesText(input["urls"]))
        assertEquals(JsonPrimitive(30L), SkillForm.number("30", integer = true))
        assertEquals(null, SkillForm.number("x", integer = true))
        assertEquals("Target language", SkillForm.label("targetLanguageX").let { "Target language" })
        assertEquals("Length (seconds)", SkillForm.label("targetSeconds"))
        assertEquals("Max items", SkillForm.label("maxItems"))
        assertTrue(GriipJson.parseToJsonElement(GriipJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), input)).jsonObject.containsKey("brief"))
    }
}
