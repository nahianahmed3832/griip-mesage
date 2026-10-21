package com.meldiq.android.data.model

import com.meldiq.android.data.api.GuideDto
import com.meldiq.android.data.api.OnboardingPromptDto

/** Batch B8b: the community guide's progress, kept on this phone. */
object GuideRules {
    /** [done] after opening [channelId] in [orgId]: its guide tasks that point there are ticked. */
    fun visit(done: Map<String, List<String>>, orgId: String, guide: GuideDto?, channelId: String): Map<String, List<String>> {
        val ids = guide?.tasks.orEmpty().filter { it.channelId == channelId }.mapNotNull { it.id }
        if (ids.isEmpty()) return done
        val mine = done[orgId].orEmpty()
        val next = (mine + ids).distinct()
        return if (next == mine) done else done + (orgId to next)
    }

    /** How many of the guide's tasks are done, of how many. */
    fun progress(guide: GuideDto, done: List<String>): Pair<Int, Int> = guide.tasks.count { it.id in done } to guide.tasks.size
}

/** Batch B8b: answering onboarding questions. */
object OnboardingRules {
    /** Tapping [optionId] in [prompt]: a single-answer question replaces its choice, others toggle. */
    fun toggle(chosen: Set<String>, prompt: OnboardingPromptDto, optionId: String): Set<String> {
        val ids = prompt.options.mapNotNull { it.id }.toSet()
        return when {
            optionId in chosen -> chosen - optionId
            prompt.single -> (chosen - ids) + optionId
            else -> chosen + optionId
        }
    }

    /** The first required question with no answer, or null when the answers can be sent. */
    fun missing(prompts: List<OnboardingPromptDto>, chosen: Set<String>): OnboardingPromptDto? =
        prompts.firstOrNull { p -> p.required && p.options.none { it.id in chosen } }
}
