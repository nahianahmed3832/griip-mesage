package com.meldiq.android.data.model

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Profile fields and their privacy (2026-10-17, docs/PROFILE_AND_PRIVACY.md). Pure, unit-tested in `ProfileRulesTest`;
 * the server applies the same rules (server/src/profile.ts) and is the one that decides what others receive.
 */
enum class Audience(val wire: String) {
    EVERYONE("everyone"), CONTACTS("contacts"), NOBODY("nobody");

    companion object {
        fun of(wire: String?): Audience? = entries.firstOrNull { it.wire == wire }
    }
}

/** A part of the profile with its own audience, and its default (the server's defaults). */
enum class PrivacyField(val wire: String, val default: Audience) {
    PHOTO("photo", Audience.EVERYONE),
    BIO("bio", Audience.EVERYONE),
    NAME("name", Audience.EVERYONE),
    BIRTHDAY("birthday", Audience.NOBODY),
    PHONE("phone", Audience.NOBODY),
    LAST_SEEN("lastSeen", Audience.CONTACTS),
    FIND_BY_PHONE("findByPhone", Audience.NOBODY),
    FIND_BY_USERNAME("findByUsername", Audience.EVERYONE),
}

object ProfileRules {
    /** The person's settings: what the server sent over the defaults (unknown values fall back to the default). */
    fun privacy(stored: Map<String, String>?): Map<PrivacyField, Audience> =
        PrivacyField.entries.associateWith { f -> Audience.of(stored?.get(f.wire)) ?: f.default }

    private val USERNAME = Regex("^[a-z0-9](?:[a-z0-9._]{1,22})[a-z0-9]$")

    /** What is wrong with a username (screens word it in the person's language). */
    enum class UsernameIssue { TOO_SHORT, TOO_LONG, CHARACTERS, DOUBLE_DOT }

    /** Why a username can't be used, or null (same rule as the server's). */
    fun usernameIssue(u: String): UsernameIssue? {
        val v = u.trim().lowercase()
        return when {
            v.length < 3 -> UsernameIssue.TOO_SHORT
            v.length > 24 -> UsernameIssue.TOO_LONG
            !USERNAME.matches(v) -> UsernameIssue.CHARACTERS
            ".." in v -> UsernameIssue.DOUBLE_DOT
            else -> null
        }
    }

    /** [usernameIssue] in English. */
    fun usernameProblem(u: String): String? = when (usernameIssue(u)) {
        UsernameIssue.TOO_SHORT -> "At least 3 characters"
        UsernameIssue.TOO_LONG -> "At most 24 characters"
        UsernameIssue.CHARACTERS -> "Letters a–z, digits, dots and underscores; start and end with a letter or digit"
        UsernameIssue.DOUBLE_DOT -> "No two dots in a row"
        null -> null
    }

    /** "+880 1712-345678" → "+8801712345678"; null when it isn't an international number. Empty stays empty. */
    fun normalizePhone(raw: String): String? {
        val v = raw.replace(Regex("[\\s()\\-.]"), "")
        if (v.isEmpty()) return ""
        return v.takeIf { Regex("^\\+[1-9]\\d{6,14}$").matches(it) }
    }

    /** A birthday as the server stores it: "YYYY-MM-DD", or "--MM-DD" without the year. */
    data class Birthday(val year: Int?, val month: Int, val day: Int) {
        val wire: String get() = (year?.let { "%04d".format(it) } ?: "-") + "-%02d-%02d".format(month, day)

        fun display(locale: Locale = Locale.getDefault()): String {
            val m = java.time.Month.of(month).getDisplayName(TextStyle.FULL, locale)
            return if (year != null) "$day $m $year" else "$day $m"
        }

        companion object {
            fun parse(s: String?): Birthday? {
                val m = Regex("^(?:(\\d{4})|-)-(\\d{2})-(\\d{2})$").matchEntire(s ?: return null) ?: return null
                val year = m.groupValues[1].takeIf { it.isNotEmpty() }?.toInt()
                val month = m.groupValues[2].toInt()
                val day = m.groupValues[3].toInt()
                return runCatching { LocalDate.of(year ?: 2000, month, day) }.getOrNull()?.let { Birthday(year, month, day) }
            }
        }
    }

    /** The name to show: the display name, which the person sets; first and last names suggest one when it's empty. */
    fun suggestedDisplayName(first: String, last: String): String = listOf(first.trim(), last.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    /** What a phone-number or birthday row says to its owner about who sees it. */
    fun audienceSummary(a: Audience): String = when (a) {
        Audience.EVERYONE -> "Everyone"
        Audience.CONTACTS -> "My contacts"
        Audience.NOBODY -> "Nobody"
    }
}
