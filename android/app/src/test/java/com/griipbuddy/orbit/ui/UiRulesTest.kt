package com.griipbuddy.orbit.ui

import com.griipbuddy.orbit.BuildConfig
import com.griipbuddy.orbit.data.db.ConversationEntity
import com.griipbuddy.orbit.data.db.UserEntity
import com.griipbuddy.orbit.data.repo.AutoDownload
import com.griipbuddy.orbit.notify.Channels
import com.griipbuddy.orbit.ui.media.MediaGallery
import com.griipbuddy.orbit.ui.screens.contacts.ContactsRules
import com.griipbuddy.orbit.ui.screens.onboarding.OnboardingRules
import com.griipbuddy.orbit.ui.theme.Accents
import com.griipbuddy.orbit.ui.theme.DarkColors
import com.griipbuddy.orbit.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Navigation, settings, notification channels and languages (2026-10-17). */
class UiRulesTest {
    private fun conv(id: String, type: String, members: String, removed: Boolean = false) = ConversationEntity(
        id = id, type = type, orgId = null, scope = null, scopeId = null, scopeName = null, kind = null, name = id, topic = null,
        archived = false, memberCount = 2, membersJson = members, joined = true, myRole = null, canPost = true, canManage = false,
        activeCallId = null, activeCallVideo = false, serverUpdatedAt = 1, removed = removed,
    )
    private fun m(id: String, name: String) = """{"id":"$id","username":"$id","displayName":"$name"}"""
    private fun user(id: String, name: String) = UserEntity(id, id, name, 0, null, null)

    @Test
    fun contactsComeFromYourChatsOnly() {
        val convs = listOf(
            conv("d1", "DM", "[${m("me", "Me")},${m("zara", "Zara")}]"),
            conv("g1", "GROUP", "[${m("me", "Me")},${m("adam", "Adam")},${m("zara", "Zara")}]"),
            conv("d2", "DM", "[${m("me", "Me")},${m("gone", "Gone")}]", removed = true),
        )
        val users = listOf(user("me", "Me"), user("zara", "Zara"), user("adam", "Adam"), user("gone", "Gone")).associateBy { it.id }
        assertEquals(listOf("adam", "zara"), ContactsRules.people(convs, users, "me").map { it.id })
        assertEquals(listOf("zara"), ContactsRules.people(convs, users, "me", "@zar").map { it.id })
    }

    @Test
    fun theServerSetupIsHiddenFromOrdinarySignUp() {
        assertFalse(OnboardingRules.showServerSetup(developerMode = false, serverUrl = BuildConfig.DEFAULT_SERVER_URL, debugBuild = false))
        assertTrue(OnboardingRules.showServerSetup(developerMode = true, serverUrl = BuildConfig.DEFAULT_SERVER_URL, debugBuild = false))
        assertTrue(OnboardingRules.showServerSetup(developerMode = false, serverUrl = "https://msg.other.example", debugBuild = false))
        assertTrue(OnboardingRules.showServerSetup(developerMode = false, serverUrl = BuildConfig.DEFAULT_SERVER_URL, debugBuild = true))
    }

    @Test
    fun accentsChangeOnlyTheActionColours() {
        assertEquals(LightColors, Accents.apply(LightColors, "orbit", dark = false))
        assertEquals(LightColors.primary, Accents.apply(LightColors, "unknown", dark = false).primary) // unknown: Orbit green
        val blue = Accents.apply(DarkColors, "blue", dark = true)
        assertNotEquals(DarkColors.primary, blue.primary)
        assertEquals(DarkColors.background, blue.background)
        assertEquals(DarkColors.textPrimary, blue.textPrimary)
        assertEquals(5, Accents.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun soundSettingsMapToTheirOwnChannels() {
        assertEquals("orbit_msg_s1v1", Channels.Ids.messages(sound = true, vibrate = true))
        assertEquals("orbit_msg_s0v1", Channels.Ids.messages(sound = false, vibrate = true))
        assertEquals("orbit_call_r0v0", Channels.Ids.calls(ring = false, vibrate = false))
        assertEquals("msg", Channels.Ids.category("orbit_msg_s0v0"))
        assertEquals("msg", Channels.Ids.category("messages")) // the original channel is migrated, not left behind
        assertEquals("call", Channels.Ids.category("incoming_call_v2"))
        assertEquals("mention", Channels.Ids.category("mentions"))
        assertEquals(null, Channels.Ids.category("ongoing_call"))
    }

    @Test
    fun languagesAndDownloads() {
        assertEquals("zh-CN", AppLanguage.normalize("zh-Hans-CN"))
        assertEquals("bn", AppLanguage.normalize("bn-BD"))
        assertEquals("en", AppLanguage.normalize("en-GB"))
        assertEquals("system", AppLanguage.normalize("fr"))
        assertEquals(listOf("system", "en", "zh-CN", "bn"), AppLanguage.SUPPORTED)
        assertTrue(MediaGallery.autoDownloadAllowed(AutoDownload.WIFI, unmetered = true))
        assertFalse(MediaGallery.autoDownloadAllowed(AutoDownload.WIFI, unmetered = false))
        assertFalse(MediaGallery.autoDownloadAllowed(AutoDownload.NEVER, unmetered = true))
        assertTrue(MediaGallery.autoDownloadAllowed(AutoDownload.ALWAYS, unmetered = false))
    }

    @Test
    fun everyLanguageHasEveryStringWithTheSamePlaceholders() {
        val res = File("src/main/res")
        fun strings(folder: String): Map<String, String> =
            Regex("""<string name="([a-z0-9_]+)">(.*?)</string>""").findAll(File(res, "$folder/strings.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }
        val en = strings("values")
        val placeholders = Regex("""%\d\$[ds]""")
        for (folder in listOf("values-zh-rCN", "values-bn")) {
            val other = strings(folder)
            val missing = en.keys - other.keys - setOf("app_name", "brand_attribution")
            assertTrue("$folder is missing $missing", missing.isEmpty())
            for ((k, v) in other) {
                assertEquals("$folder/$k placeholders", placeholders.findAll(en.getValue(k)).map { it.value }.toSet(), placeholders.findAll(v).map { it.value }.toSet())
                assertFalse("$folder/$k has an unescaped apostrophe", Regex("""(?<!\\)'""").containsMatchIn(v))
            }
        }
    }
}
