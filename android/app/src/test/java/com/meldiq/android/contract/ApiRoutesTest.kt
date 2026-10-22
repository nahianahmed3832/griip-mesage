package com.meldiq.android.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every `/v1` and `/v2` path literal in the app's sources is a route the server documents (docs/api/openapi.json,
 * generated from its contracts). A path the server retired fails here instead of as a 404 on a phone: that's how the
 * workspace-template and user-group calls were found (Architecture 1.0, F1/F2). Path parameters (`$id`, `${x.id}`) match
 * any `{param}`; a query string is ignored.
 */
class ApiRoutesTest {
    private fun above(rel: String): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, rel) }
        .firstOrNull { it.exists() }
        ?: error("$rel not found above ${File("").absolutePath}")

    /** Not HTTP routes: the WebSocket (docs/api/realtime.schema.json) and the prefix the client never re-authenticates on. */
    private val notRoutes = setOf("/v1/ws", "/v1/auth")

    private fun normalize(path: String): String =
        "/" + path.substringBefore('?').trim('/').split('/').joinToString("/") { if (it.startsWith("{") || it.contains('$')) "{}" else it }

    private val literal = Regex("\"(/v[12]/[^\"]*)\"")

    private fun appRoutes(): Map<String, List<String>> {
        val sources = above("app/src/main/java").walkTopDown().filter { it.extension == "kt" }
        val found = sortedMapOf<String, MutableList<String>>()
        for (f in sources) {
            f.readLines().forEachIndexed { i, line ->
                literal.findAll(line).forEach { found.getOrPut(normalize(it.groupValues[1])) { mutableListOf() } += "${f.name}:${i + 1}" }
            }
        }
        return found
    }

    @Test
    fun `every route the app calls is one the server documents`() {
        val documented = Json.parseToJsonElement(above("docs/api/openapi.json").readText()).jsonObject.getValue("paths").jsonObject.keys.map(::normalize).toSet()
        val app = appRoutes()
        assertTrue("found too few path literals: the scan is broken", app.size > 100)
        val unknown = app.filterKeys { it !in documented && it !in notRoutes }
        assertEquals("routes the app calls that the server doesn't have (retired, or a typo)", emptyMap<String, List<String>>(), unknown)
    }

    @Test
    fun `parameters and queries are normalized`() {
        assertEquals("/v1/orgs/{}/meetings", normalize("/v1/orgs/\$orgId/meetings"))
        assertEquals("/v1/meetings/{}", normalize("/v1/meetings/\${meeting.id}"))
        assertEquals("/v1/conversations/{}/calls", normalize("/v1/conversations/\$cid/calls?limit=50"))
        assertEquals("/v1/orgs/{}/meetings", normalize("/v1/orgs/{orgId}/meetings"))
    }
}
