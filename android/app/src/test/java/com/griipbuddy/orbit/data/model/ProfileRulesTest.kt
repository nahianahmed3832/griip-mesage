package com.griipbuddy.orbit.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** Profile fields and privacy on the phone (2026-10-17); the server enforces the same rules (profile_privacy.test.ts). */
class ProfileRulesTest {
    @Test
    fun privacyDefaultsAreConservative() {
        val p = ProfileRules.privacy(null)
        assertEquals(Audience.NOBODY, p[PrivacyField.BIRTHDAY])
        assertEquals(Audience.NOBODY, p[PrivacyField.PHONE])
        assertEquals(Audience.NOBODY, p[PrivacyField.FIND_BY_PHONE])
        assertEquals(Audience.CONTACTS, p[PrivacyField.LAST_SEEN])
        assertEquals(Audience.EVERYONE, p[PrivacyField.PHOTO])
        // Stored values win; unknown values fall back to the default.
        val q = ProfileRules.privacy(mapOf("birthday" to "contacts", "phone" to "friends"))
        assertEquals(Audience.CONTACTS, q[PrivacyField.BIRTHDAY])
        assertEquals(Audience.NOBODY, q[PrivacyField.PHONE])
    }

    @Test
    fun usernamesFollowTheServersRule() {
        assertNull(ProfileRules.usernameProblem("nahian.ahmed"))
        assertNull(ProfileRules.usernameProblem("Nahian_1")) // compared in lower case
        assertNotNull(ProfileRules.usernameProblem("ab"))
        assertNotNull(ProfileRules.usernameProblem(".nahian"))
        assertNotNull(ProfileRules.usernameProblem("na..hian"))
        assertNotNull(ProfileRules.usernameProblem("nahian ahmed"))
        assertNotNull(ProfileRules.usernameProblem("x".repeat(25)))
    }

    @Test
    fun usernameIssuesNameWhatIsWrong() {
        assertNull(ProfileRules.usernameIssue("nahian.ahmed"))
        assertEquals(ProfileRules.UsernameIssue.TOO_SHORT, ProfileRules.usernameIssue("ab"))
        assertEquals(ProfileRules.UsernameIssue.TOO_LONG, ProfileRules.usernameIssue("x".repeat(25)))
        assertEquals(ProfileRules.UsernameIssue.CHARACTERS, ProfileRules.usernameIssue(".nahian"))
        assertEquals(ProfileRules.UsernameIssue.DOUBLE_DOT, ProfileRules.usernameIssue("na..hian"))
        assertEquals("No two dots in a row", ProfileRules.usernameProblem("na..hian")) // the English wording is unchanged
    }

    @Test
    fun phonesNeedTheCountryCode() {
        assertEquals("+8801712345678", ProfileRules.normalizePhone("+880 1712-345678"))
        assertEquals("+14155550123", ProfileRules.normalizePhone("+1 (415) 555.0123"))
        assertEquals("", ProfileRules.normalizePhone("  ")) // cleared
        assertNull(ProfileRules.normalizePhone("01712345678"))
        assertNull(ProfileRules.normalizePhone("+0123456789"))
    }

    @Test
    fun birthdaysWithAndWithoutTheYear() {
        val b = ProfileRules.Birthday.parse("1994-03-21")!!
        assertEquals("21 March 1994", b.display(Locale.ENGLISH))
        assertEquals("1994-03-21", b.wire)
        val noYear = ProfileRules.Birthday.parse("--02-29")!!
        assertNull(noYear.year)
        assertEquals("--02-29", noYear.wire)
        assertEquals("29 February", noYear.display(Locale.ENGLISH))
        assertNull(ProfileRules.Birthday.parse("1994-02-30"))
        assertNull(ProfileRules.Birthday.parse("21/03/1994"))
        assertNull(ProfileRules.Birthday.parse(null))
        assertEquals("Ayesha Rahman", ProfileRules.suggestedDisplayName(" Ayesha ", "Rahman"))
        assertEquals("Ayesha", ProfileRules.suggestedDisplayName("Ayesha", ""))
    }
}
