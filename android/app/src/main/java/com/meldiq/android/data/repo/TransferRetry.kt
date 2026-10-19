package com.meldiq.android.data.repo

import com.meldiq.android.data.api.ApiException
import kotlinx.coroutines.delay

/** Retry policy for attachment uploads and downloads (T2.6). */
object TransferRetry {
    /**
     * Worth retrying: no connection, a busy server (`too_many_uploads`) or a server error. Not worth it:
     * a file that's too large, a quota or full-storage refusal, or lost access. Those fail with the
     * server's message.
     */
    fun isTransient(e: ApiException) =
        e.isNetwork || e.code == "too_many_uploads" || e.code == "decode" || (e.status >= 500 && e.status != 507)

    /** Runs [block], retrying transient failures after each of [delays]; the last failure is rethrown. */
    suspend fun <T> run(delays: List<Long> = listOf(2_000L, 5_000L, 15_000L), block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: ApiException) {
                if (!isTransient(e) || attempt >= delays.size) throw e
                delay(delays[attempt++])
            }
        }
    }
}
