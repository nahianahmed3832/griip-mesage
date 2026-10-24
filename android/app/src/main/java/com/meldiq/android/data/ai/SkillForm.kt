package com.meldiq.android.data.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A skill's options as form fields (as Meldiq Web's `SkillFields`): the top-level properties of its input JSON Schema
 * that a phone form can show (choices, numbers, switches, short text, lists of choices or lines). The brief is asked
 * separately. What the server's schema allows is checked there again. Unit-tested in `SkillFormTest`.
 */
object SkillForm {
    sealed interface Field {
        val key: String
        data class Choice(override val key: String, val options: List<String>, val default: String?) : Field
        data class Toggle(override val key: String, val default: Boolean) : Field
        data class Number(override val key: String, val integer: Boolean, val min: Double?, val max: Double?, val default: Double?) : Field
        data class MultiChoice(override val key: String, val options: List<String>, val default: List<String>) : Field
        data class Lines(override val key: String) : Field
        data class Text(override val key: String, val maxLength: Int?, val default: String?) : Field
    }

    private val LABELS = mapOf(
        "targetSeconds" to "Length (seconds)", "captions" to "Captions", "title" to "Title", "brandText" to "Brand text",
        "task" to "Task", "format" to "Format", "length" to "Length", "tone" to "Tone", "audience" to "Audience",
        "size" to "Image size", "count" to "Images", "platforms" to "Platforms", "posts" to "Posts", "drafts" to "Drafts",
        "targetLanguage" to "Translate into", "hoursPerDay" to "Working hours per day", "urls" to "Public pages (one per line)",
    )

    /** "targetLanguage" → "Target language", unless the web's label list names it. */
    fun label(key: String): String = LABELS[key] ?: key.replace(Regex("([A-Z])"), " $1").trim().lowercase().replaceFirstChar { it.uppercase() }

    fun fields(schema: JsonElement?): List<Field> {
        val props = (schema as? JsonObject)?.get("properties") as? JsonObject ?: return emptyList()
        return props.entries.filter { it.key != "brief" }.mapNotNull { (k, v) -> (v as? JsonObject)?.let { field(k, normalize(it)) } }
    }

    /** A nullable property ({anyOf: [X, {type: "null"}]}) as X, keeping the outer default. */
    private fun normalize(p: JsonObject): JsonObject {
        val any = p["anyOf"] as? JsonArray ?: return p
        val real = any.map { it.jsonObject }.firstOrNull { (it["type"] as? JsonPrimitive)?.contentOrNull != "null" } ?: return p
        val default = p["default"] ?: real["default"]
        return if (default != null) JsonObject(real + ("default" to default)) else real
    }

    private fun types(p: JsonObject): Set<String> = when (val t = p["type"]) {
        is JsonPrimitive -> setOfNotNull(t.contentOrNull)
        is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        else -> emptySet()
    }

    private fun strings(e: JsonElement?): List<String> = (e as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    private fun field(key: String, p: JsonObject): Field? {
        val t = types(p)
        val default = p["default"]
        p["enum"]?.let { return Field.Choice(key, strings(it), (default as? JsonPrimitive)?.contentOrNull) }
        if ("boolean" in t) return Field.Toggle(key, (default as? JsonPrimitive)?.booleanOrNull ?: false)
        if ("integer" in t || "number" in t) {
            return Field.Number(key, "integer" in t && "number" !in t, p["minimum"]?.jsonPrimitive?.doubleOrNull, p["maximum"]?.jsonPrimitive?.doubleOrNull, (default as? JsonPrimitive)?.doubleOrNull)
        }
        if ("array" in t) {
            val items = p["items"] as? JsonObject
            items?.get("enum")?.let { return Field.MultiChoice(key, strings(it), strings(default)) }
            if (items != null && "string" in types(items)) return Field.Lines(key)
            return null
        }
        if ("string" in t) return Field.Text(key, p["maxLength"]?.jsonPrimitive?.intOrNull, (default as? JsonPrimitive)?.contentOrNull)
        return null
    }

    /**
     * The job's input: the options the person set (defaults are the server's), and the brief. A blank text or number
     * is left out, so the skill's own default applies.
     */
    fun input(brief: String, values: Map<String, JsonElement>): JsonObject {
        val kept = values.filterValues { v -> v !is JsonNull && !(v is JsonPrimitive && v.isString && v.content.isBlank()) }
        return JsonObject(kept + ("brief" to JsonPrimitive(brief.trim())))
    }

    fun lines(text: String): JsonElement = JsonArray(text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map(::JsonPrimitive))
    fun linesText(value: JsonElement?): String = (value as? JsonArray)?.joinToString("\n") { it.jsonPrimitive.content }.orEmpty()
    fun chosen(value: JsonElement?, default: List<String>): List<String> = (value as? JsonArray)?.map { it.jsonPrimitive.content } ?: default
    fun choices(list: List<String>): JsonElement = JsonArray(list.map(::JsonPrimitive))

    /** A number as the schema wants it (whole numbers stay whole), or null for blank or unreadable text. */
    fun number(text: String, integer: Boolean): JsonElement? {
        val d = text.trim().toDoubleOrNull() ?: return null
        return if (integer) JsonPrimitive(d.toLong()) else JsonPrimitive(d)
    }
}
