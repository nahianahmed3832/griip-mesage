package com.meldiq.android.data.model

/**
 * Batch B4b: join applications (Discord reference ADMIN-CTRL-008). A public community asks up to [MAX_QUESTIONS]
 * questions; people answer every one and wait; moderators with `members.approve` approve or deny. The limits match the
 * server's (routes/applications.ts), so the app refuses what the server would.
 */
object ApplicationRules {
    const val MAX_QUESTIONS = 5
    const val QUESTION_MAX = 200
    const val ANSWER_MAX = 1000
    const val NOTE_MAX = 300

    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val DENIED = "denied"
    const val WITHDRAWN = "withdrawn"

    /** The questions as they'll be saved: trimmed, blanks left out, cut to the limits; null when none are left (off). */
    fun cleanQuestions(raw: List<String>): List<String>? =
        raw.map { it.trim().take(QUESTION_MAX) }.filter { it.isNotEmpty() }.take(MAX_QUESTIONS).ifEmpty { null }

    /** Answers can be sent when there's one for every question. */
    fun complete(questions: List<String>, answers: List<String>): Boolean =
        answers.size == questions.size && answers.all { it.isNotBlank() }

    fun cleanAnswers(answers: List<String>): List<String> = answers.map { it.trim().take(ANSWER_MAX) }

    /**
     * What the preview offers someone who isn't a member: [Offer.Waiting] while their application is pending,
     * [Offer.Apply] when the community takes applications (also after a denial or withdrawal), else joining or asking.
     */
    fun offer(takesApplications: Boolean, latestStatus: String?, openJoin: Boolean): Offer = when {
        latestStatus == PENDING -> Offer.Waiting
        takesApplications -> Offer.Apply
        openJoin -> Offer.Join
        else -> Offer.AskForInvite
    }

    enum class Offer { Waiting, Apply, Join, AskForInvite }
}
