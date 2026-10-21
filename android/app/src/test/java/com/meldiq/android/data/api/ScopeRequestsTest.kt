package com.meldiq.android.data.api

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GriipJson omits null fields, so "clear the description" or "clear the rules" must still put an explicit null on the
 * wire; the server treats a missing field as "unchanged".
 */
class ScopeRequestsTest {
    @Test
    fun clearingSendsAnExplicitNull() {
        assertEquals("""{"name":"Club","description":null}""", GriipJson.encodeToString(UpdateOrgReq.serializer(), UpdateOrgReq("Club", JsonNull)))
        assertEquals(
            """{"name":"Club","description":"Hi","rules":null}""",
            GriipJson.encodeToString(UpdateOrgReq.serializer(), UpdateOrgReq("Club", JsonPrimitive("Hi"), rules = JsonNull)),
        )
    }

    @Test
    fun visibilityAndJoiningAreSentOnlyWhenChanged() {
        assertEquals(
            """{"name":"Club","description":null,"visibility":"public","joinPolicy":"open"}""",
            GriipJson.encodeToString(UpdateOrgReq.serializer(), UpdateOrgReq("Club", JsonNull, visibility = "public", joinPolicy = "open")),
        )
    }

    @Test
    fun overwriteRequestsAlwaysCarryBothLists() {
        // An empty allow and deny removes the rule, so both must be on the wire even when empty.
        assertEquals("""{"subject":"everyone","allow":[],"deny":["channels.view"]}""", GriipJson.encodeToString(OverwriteReq.serializer(), OverwriteReq("everyone", emptyList(), listOf("channels.view"))))
    }
}
