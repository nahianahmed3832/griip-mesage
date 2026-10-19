package com.meldiq.android.data.account

import com.meldiq.android.data.account.AccountRules.PasswordIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountRulesTest {
    @Test
    fun `emails are trimmed and lower-cased, and shapes checked loosely like the server`() {
        assertEquals("ada@example.com", AccountRules.normalizeEmail("  Ada@Example.COM "))
        assertTrue(AccountRules.isEmail("ada@example.com"))
        assertFalse(AccountRules.isEmail("ada@example"))
        assertFalse(AccountRules.isEmail("ada example.com"))
        assertFalse(AccountRules.isEmail("a".repeat(250) + "@x.io"))
    }

    @Test
    fun `passwords follow the server's rules, counted in characters`() {
        assertEquals(PasswordIssue.TooShort, AccountRules.passwordIssue("short", "a@b.co"))
        assertNull(AccountRules.passwordIssue("ten chars!", "a@b.co"))
        // Ten characters outside the BMP are ten characters, not twenty.
        assertNull(AccountRules.passwordIssue("🔐".repeat(10), "a@b.co"))
        assertEquals(PasswordIssue.TooShort, AccountRules.passwordIssue("🔐".repeat(9), "a@b.co"))
        assertEquals(PasswordIssue.TooLong, AccountRules.passwordIssue("x".repeat(129), "a@b.co"))
        assertEquals(PasswordIssue.SameAsEmail, AccountRules.passwordIssue("Long@Example.com", "long@example.com"))
    }

    @Test
    fun `codes keep only what they can contain`() {
        assertEquals("123456", AccountRules.cleanVerifyCode("12 34-56 78"))
        assertTrue(AccountRules.isVerifyCode("123456"))
        assertFalse(AccountRules.isVerifyCode("12345"))
        assertEquals("ABCDEFGHJK", AccountRules.cleanResetCode("abcde-fghjk"))
        assertTrue(AccountRules.isResetCode("abcde-FGHJK"))
        assertFalse(AccountRules.isResetCode("abcde"))
    }
}
