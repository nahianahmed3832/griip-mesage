package com.meldiq.android.data.repo

import com.meldiq.android.data.api.ApiClient
import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.api.GuideDto
import com.meldiq.android.data.api.GuideRes
import com.meldiq.android.data.api.OnboardingAnswersReq
import com.meldiq.android.data.api.OnboardingAnswersRes
import com.meldiq.android.data.api.OnboardingDto
import com.meldiq.android.data.api.OnboardingPutReq
import com.meldiq.android.data.db.GriipDatabase
import com.meldiq.android.data.db.KvEntity
import com.meldiq.android.data.model.GuideRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer

/**
 * Batch B8b: a community's guide and onboarding questions. Which guide tasks you did is kept on this phone (in the
 * profile's encrypted database), ticked when you open the task's channel.
 */
class CommunityGuideRepository(private val api: ApiClient, private val db: GriipDatabase) {
    private val _done = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    /** Community id → the guide task ids done on this phone. */
    val done: StateFlow<Map<String, List<String>>> = _done.asStateFlow()
    private val lock = Mutex()

    suspend fun load() {
        _done.value = db.kv().get(KEY)?.let { runCatching { GriipJson.decodeFromString(doneSer, it) }.getOrNull() }.orEmpty()
    }

    /** Opening [channelId] ticks the guide tasks of [orgId] that point to it. */
    suspend fun visited(orgId: String, guide: GuideDto?, channelId: String) = lock.withLock {
        val next = GuideRules.visit(_done.value, orgId, guide, channelId)
        if (next == _done.value) return@withLock
        _done.value = next
        db.kv().put(KvEntity(KEY, GriipJson.encodeToString(doneSer, next)))
    }

    /** Sets the guide, or removes it (null). */
    suspend fun setGuide(orgId: String, guide: GuideDto?): GuideDto? {
        val body = guide?.let { GriipJson.encodeToString(GuideDto.serializer(), it) } ?: "null"
        return api.request("PUT", "/v1/orgs/$orgId/guide", body, serializer<GuideRes>()).guide
    }

    suspend fun onboarding(orgId: String) = api.get<OnboardingDto>("/v1/orgs/$orgId/onboarding")

    suspend fun setOnboarding(orgId: String, req: OnboardingPutReq) = api.put<OnboardingPutReq, OnboardingDto>("/v1/orgs/$orgId/onboarding", req)

    suspend fun answer(orgId: String, optionIds: List<String>) =
        api.post<OnboardingAnswersReq, OnboardingAnswersRes>("/v1/orgs/$orgId/onboarding/answers", OnboardingAnswersReq(optionIds))

    companion object {
        private const val KEY = "guide.done"
        private val doneSer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    }
}
