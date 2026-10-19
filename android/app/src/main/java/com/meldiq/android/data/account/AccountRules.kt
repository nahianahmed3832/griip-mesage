package com.meldiq.android.data.account

/**
 * Form checks for the account screens (unit-tested in `AccountRulesTest`). They only spare people a round trip: the
 * server applies the same rules and is the one that decides (server/src/accounts/passwords.ts, schemas.ts).
 */
object AccountRules {
    const val PASSWORD_MIN = 10
    const val PASSWORD_MAX = 128
    const val NAME_MAX = 80
    const val EMAIL_MAX = 254
    const val VERIFY_CODE_LENGTH = 6
    const val RESET_CODE_LENGTH = 10

    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    enum class PasswordIssue { TooShort, TooLong, SameAsEmail }

    fun normalizeEmail(input: String): String = input.trim().lowercase()

    fun isEmail(input: String): Boolean {
        val e = normalizeEmail(input)
        return e.length <= EMAIL_MAX && EMAIL.matches(e)
    }

    /** Counted in code points, like the server. */
    fun passwordIssue(password: String, email: String): PasswordIssue? {
        val length = password.codePointCount(0, password.length)
        return when {
            length < PASSWORD_MIN -> PasswordIssue.TooShort
            length > PASSWORD_MAX -> PasswordIssue.TooLong
            normalizeEmail(password) == normalizeEmail(email) && email.isNotBlank() -> PasswordIssue.SameAsEmail
            else -> null
        }
    }

    fun cleanName(input: String): String = input.take(NAME_MAX)

    /** Digits only, at most six: what the verification email contains. */
    fun cleanVerifyCode(input: String): String = input.filter { it.isDigit() }.take(VERIFY_CODE_LENGTH)

    /** "abcde-fghjk" → "ABCDEFGHJK": letters and digits of a reset code, upper case. */
    fun cleanResetCode(input: String): String = input.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(RESET_CODE_LENGTH)

    fun isVerifyCode(code: String): Boolean = code.length == VERIFY_CODE_LENGTH && code.all { it.isDigit() }

    fun isResetCode(code: String): Boolean = cleanResetCode(code).length == RESET_CODE_LENGTH
}
