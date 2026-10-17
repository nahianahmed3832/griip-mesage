package com.griipbuddy.orbit.data.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorsTest {
    @Test
    fun anOlderServerWithoutTheRouteIsRecognised() {
        // What the production server answered to a profile-photo upload on 2026-10-17.
        assertTrue(ApiErrors.routeMissing(404, """{"message":"Route PUT:/v1/users/me/photo not found","error":"Not Found","statusCode":404}"""))
        assertTrue(ApiErrors.routeMissing(404, """{"message":"Route GET:/v1/history/items?after=0 not found","error":"Not Found","statusCode":404}"""))
    }

    @Test
    fun ordinaryNotFoundAnswersAreNotMistakenForIt() {
        assertFalse(ApiErrors.routeMissing(404, """{"error":{"code":"not_found","message":"No photo"}}"""))
        assertFalse(ApiErrors.routeMissing(404, """{"error":{"code":"not_found","message":"Route not found in this org"}}"""))
        assertFalse(ApiErrors.routeMissing(400, """{"message":"Route PUT:/x not found"}"""))
        assertFalse(ApiErrors.routeMissing(404, ""))
    }
}
