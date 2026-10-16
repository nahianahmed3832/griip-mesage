package com.griipbuddy.message.data.repo

import com.griipbuddy.message.data.api.ServerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerProfilesTest {
    @Test
    fun theOriginalProfileKeepsTheStorageExistingInstallsAlreadyHave() {
        // Changing any of these would make an existing account look signed out (or unreadable) after the update.
        val d = ProfileStorage.of(ProfileStorage.DEFAULT)
        assertEquals("griip.db", d.dbName)
        assertEquals("griip_identity", d.identityPrefs)
        assertEquals("griip_master_v1", d.keystoreAlias)
        assertEquals("attachments", d.attachmentsDir)
        assertEquals("serverUrl", d.serverKey)
        assertEquals(ProfileStorage.DEFAULT, ProfileList().active)
    }

    @Test
    fun otherProfilesShareNothingWithTheOriginal() {
        val id = ProfileList.newId()
        val p = ProfileStorage.of(id)
        val d = ProfileStorage.of(ProfileStorage.DEFAULT)
        assertNotEquals(d.dbName, p.dbName)
        assertNotEquals(d.identityPrefs, p.identityPrefs)
        assertNotEquals(d.keystoreAlias, p.keystoreAlias)
        assertNotEquals(d.attachmentsDir, p.attachmentsDir)
        assertNotEquals(d.serverKey, p.serverKey)
        assertTrue(p.dbName.contains(id))
        // Ids end up in file names: anything else is refused.
        for (bad in listOf("../x", "ABCDEFGH1", "short", "a/b/c/d/e/f", "")) assertThrows(IllegalArgumentException::class.java) { ProfileStorage.of(bad) }
    }

    @Test
    fun addActivateAndRemove() {
        var l = ProfileList()
        l = l.add("https://a.example", 1, id = "aaaaaaaa11")
        l = l.add("https://b.example", 2, id = "bbbbbbbb22")
        assertEquals(listOf(ProfileStorage.DEFAULT, "aaaaaaaa11", "bbbbbbbb22"), l.ids)
        assertEquals(ProfileStorage.DEFAULT, l.active) // adding doesn't switch by itself
        l = l.activate("aaaaaaaa11")
        assertEquals("aaaaaaaa11", l.active)
        assertThrows(IllegalArgumentException::class.java) { l.remove("aaaaaaaa11") } // not the active one
        assertThrows(IllegalArgumentException::class.java) { l.remove(ProfileStorage.DEFAULT) } // never the original
        assertThrows(IllegalArgumentException::class.java) { l.activate("cccccccc33") }
        l = l.remove("bbbbbbbb22")
        assertEquals(listOf(ProfileStorage.DEFAULT, "aaaaaaaa11"), l.ids)
        assertTrue(l.activate(ProfileStorage.DEFAULT).has("aaaaaaaa11"))
    }

    @Test
    fun aDamagedRegistryFallsBackToTheOriginalProfile() {
        val l = ProfileList(
            listOf(ServerProfile("aaaaaaaa11", "https://a", 1), ServerProfile("aaaaaaaa11", "https://dup", 2), ServerProfile("../evil", "https://x", 3), ServerProfile(ProfileStorage.DEFAULT, "https://y", 4)),
            active = "gone0000000",
        ).sanitised()
        assertEquals(listOf("aaaaaaaa11"), l.extra.map { it.id })
        assertEquals("https://a", l.extra.single().serverUrl)
        assertEquals(ProfileStorage.DEFAULT, l.active)
    }

    @Test
    fun serverAddressesAreNormalisedOrExplained() {
        fun ok(input: String, cleartext: Boolean = false) = ServerAddress.parse(input, allowCleartext = cleartext).getOrThrow()
        assertEquals("https://msg.example.org", ok("msg.example.org").url)
        assertEquals("https://msg.example.org", ok("  HTTPS://Msg.Example.org/  ").url)
        assertEquals("https://example.org:8443/griip", ok("https://example.org:8443/griip/").url)
        assertEquals(ServerAddress.Parsed("http://192.168.1.20:8080", true), ok("http://192.168.1.20:8080", cleartext = true))
        assertEquals("http://10.0.2.2:8080", ok("http://10.0.2.2:8080").url) // emulator host is allowed even in release
        for (bad in listOf("", "ftp://x.org", "https://", "https://u:p@x.org", "https://x.org/?a=1", "https://x.org/#f", "http://192.168.1.20")) {
            assertTrue(bad, ServerAddress.parse(bad, allowCleartext = false).isFailure)
        }
        assertTrue(ServerAddress.same("https://A.org/", "https://a.org"))
        assertFalse(ServerAddress.same("https://a.org", "https://b.org"))
    }

    private fun info(encryption: String = "griip-envelope-v1 (HPKE)", api: Int? = null, minClient: Int? = null) =
        ServerInfo("Griip Message relay", "0.1.0", true, 1, 1, 1, encryption, api = api, minClientApi = minClient)

    @Test
    fun compatibility() {
        assertEquals(ServerCompat.Verdict.Ok, ServerCompat.check(info())) // servers from before the api field
        assertEquals(ServerCompat.Verdict.Ok, ServerCompat.check(info(api = 1, minClient = 1)))
        assertEquals(ServerCompat.Verdict.Ok, ServerCompat.check(info(api = 3, minClient = 1))) // newer server, still accepts us
        assertTrue(ServerCompat.check(info(api = 2, minClient = 2)) is ServerCompat.Verdict.Unsupported)
        assertTrue(ServerCompat.check(info(api = 0)) is ServerCompat.Verdict.Unsupported)
        assertTrue(ServerCompat.check(info(encryption = "signal")) is ServerCompat.Verdict.Unsupported)
    }
}
