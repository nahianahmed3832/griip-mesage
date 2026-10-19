package com.meldiq.android.data.model

import com.meldiq.android.data.api.GriipJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 14: the collaboration payloads (replies, threads, mentions, attachments, edits, deletions,
 * reactions, pins, read receipts, call invites) keep their wire format. Each vector is decoded with
 * the app's own JSON settings and must re-encode to the same JSON, so a renamed field fails here
 * instead of silently breaking other clients.
 */
class PayloadVectorsTest {
    private val v: JsonObject = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("griip-payload-v1-vectors.json")!!.readText(),
    ).jsonObject

    private fun vectors(group: String) = v[group]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["json"]!!.jsonObject }

    @Test
    fun everyPayloadRoundTripsUnchanged() {
        for ((name, wire) in vectors("payloads")) {
            val p = GriipJson.decodeFromString(Payload.serializer(), wire.toString())
            assertEquals(name, wire, GriipJson.encodeToJsonElement(Payload.serializer(), p))
        }
    }

    @Test
    fun fieldsLandWhereTheAppReadsThem() {
        val byName = vectors("payloads").toMap().mapValues { GriipJson.decodeFromString(Payload.serializer(), it.value.toString()) }
        byName.getValue("thread reply with mentions").let {
            assertEquals("m-1", it.threadRoot)
            assertEquals(listOf("u-sam"), it.mentions)
        }
        assertEquals("m-1", byName.getValue("reply").replyTo)
        assertEquals(false, byName.getValue("reaction remove").add)
        assertEquals(1234L, byName.getValue("attachment").attachments!!.single().size)
        assertEquals(null, byName.getValue("attachment").attachments!!.single().voice) // ordinary files carry no voice fields
        byName.getValue("voice message").attachments!!.single().let {
            assertEquals(true, it.voice)
            assertEquals(7300L, it.durationMs)
            assertEquals("audio/mp4", it.mime)
        }
        assertEquals(1760600000000L, byName.getValue("read receipt").upTo)
        assertEquals(true, byName.getValue("pin").pinned)
    }

    @Test
    fun payloadsFromNewerClientsStillDecode() {
        // Failing to decode would turn them into "couldn't decrypt" placeholders; unknown types are ignored instead.
        for ((name, wire) in vectors("forwardCompatible")) {
            val p = GriipJson.decodeFromString(Payload.serializer(), wire.toString())
            assertEquals(name, wire["type"]!!.jsonPrimitive.content, p.type)
        }
    }
}
