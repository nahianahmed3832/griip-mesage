package com.meldiq.android.data.api

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GriipJson omits null fields, so "remove the team from its department" and "clear the description"
 * must still put an explicit null on the wire; the server treats a missing field as "unchanged".
 */
class ScopeRequestsTest {
    @Test
    fun clearingSendsAnExplicitNull() {
        assertEquals("""{"departmentId":null}""", GriipJson.encodeToString(TeamDepartmentReq.serializer(), TeamDepartmentReq(JsonNull)))
        assertEquals("""{"name":"Ops","description":null}""", GriipJson.encodeToString(UpdateDepartmentReq.serializer(), UpdateDepartmentReq("Ops", JsonNull)))
    }

    @Test
    fun settingSendsTheValue() {
        assertEquals("""{"departmentId":"dep-1"}""", GriipJson.encodeToString(TeamDepartmentReq.serializer(), TeamDepartmentReq(JsonPrimitive("dep-1"))))
    }

    @Test
    fun theOlderPatchRequestStillOmitsUnsetFields() {
        // PatchScopeReq relies on omission meaning "unchanged"; it must not start sending nulls.
        assertEquals("""{"archived":true}""", GriipJson.encodeToString(PatchScopeReq.serializer(), PatchScopeReq(archived = true)))
    }
}
