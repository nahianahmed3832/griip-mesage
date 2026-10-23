package com.meldiq.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The sign-in screen shows "Sign in with a passkey" only when server-info offers passkeys (API 0.30.0). */
class ServerPasskeysInfoTest {
    private val base = """"name":"Meldiq relay","version":"0.30.0","calling":true,"queueTtlSeconds":1,"attachmentMaxBytes":1,"attachmentTtlSeconds":1,"encryption":"e""""

    @Test
    fun offeredWhenTheServerListsThem() {
        val info = GriipJson.decodeFromString<ServerInfo>("{$base,\"passkeys\":{\"webOrigins\":[\"https://web.meldiq.app\"]}}")
        assertEquals(listOf("https://web.meldiq.app"), info.passkeys?.webOrigins)
    }

    @Test
    fun notOfferedWhenOffOrOnAnOlderServer() {
        assertNull(GriipJson.decodeFromString<ServerInfo>("{$base,\"passkeys\":null}").passkeys)
        assertNull(GriipJson.decodeFromString<ServerInfo>("{$base}").passkeys)
    }
}
