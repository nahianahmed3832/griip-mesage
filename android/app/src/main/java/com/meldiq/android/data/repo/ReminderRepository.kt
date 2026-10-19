package com.meldiq.android.data.repo

import com.meldiq.android.crypto.ReminderKeys
import com.meldiq.android.crypto.unb64
import com.meldiq.android.data.api.ApiClient
import com.meldiq.android.data.api.ApiException
import com.meldiq.android.data.api.GriipJson
import com.meldiq.android.data.api.OkRes
import com.meldiq.android.data.api.SavedItemDto
import com.meldiq.android.data.model.Recurrence
import com.meldiq.android.data.model.Reminder
import com.meldiq.android.data.model.ReminderContent
import com.meldiq.android.data.model.ReminderRef
import com.meldiq.android.data.model.ReminderRules
import com.meldiq.android.data.model.ReminderState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.time.ZoneId

@Serializable data class ReminderRepeatDto(val recurrence: String, val localTime: String, val anchorDate: String)

@Serializable data class ReminderDto(
    val id: String,
    val sealed: String,
    val dueAt: Long,
    val timeZone: String,
    val repeat: ReminderRepeatDto? = null,
    val state: String,
    val firedAt: Long? = null,
    val deliveredAt: Long? = null,
    val delivery: String? = null,
    val rev: Int,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val completedAt: Long? = null,
)

@Serializable data class RemindersRes(val reminders: List<ReminderDto>)
@Serializable data class ReminderRes(val reminder: ReminderDto)
@Serializable data class CreateReminderReq(val id: String, val sealed: String, val dueAt: Long, val timeZone: String, val repeat: ReminderRepeatDto? = null)
@Serializable data class AckReminderReq(val firedAt: Long)

/**
 * Reminders kept and fired by the server (docs/REMINDERS.md). Content is sealed on this phone with a key from the
 * identity seed (crypto/ReminderCrypto.kt); the server only knows when each is due. The list here is refreshed from the
 * server on start, on reconnect and on every "reminders.changed" / "reminder.due" event; a reminder that went off is
 * shown once on this phone ([onFired]) and acknowledged.
 *
 * Saved messages' reminders (Later) are bridged here when the server offers "reminders": each saved item with a time
 * gets the reminder [ReminderRules.laterId] (the same id on every device), so the server fires it even when this
 * phone's timers don't run.
 */
class ReminderRepository(
    private val api: ApiClient,
    private val session: SessionRepository,
    private val features: ServerFeatures,
    /** Shows a reminder that went off; true when it was newly shown on this phone (once per occurrence). */
    private val onFired: suspend (Reminder) -> Boolean,
) {
    private val _reminders = MutableStateFlow<List<Reminder>>(emptyList())
    val reminders: StateFlow<List<Reminder>> = _reminders
    private val _available = MutableStateFlow(false)
    /** Whether this server keeps reminders (feature "reminders"). */
    val available: StateFlow<Boolean> = _available
    private val lock = Mutex()
    private val contentSer = serializer<ReminderContent>()

    private fun keys(): ReminderKeys? {
        val seed = session.keys?.identitySeed?.takeIf { it.isNotEmpty() } ?: return null
        val user = session.session?.userId ?: return null
        return ReminderKeys(seed.unb64(), user)
    }

    private fun decode(d: ReminderDto, k: ReminderKeys?): Reminder {
        val content = k?.let { runCatching { GriipJson.decodeFromString(contentSer, k.open(d.id, d.sealed).decodeToString()) }.getOrNull() }
        return Reminder(
            id = d.id, content = content, dueAt = d.dueAt, timeZone = d.timeZone,
            recurrence = Recurrence.of(d.repeat?.recurrence), localTime = d.repeat?.localTime, anchorDate = d.repeat?.anchorDate,
            state = ReminderState.of(d.state), firedAt = d.firedAt, deliveryFailed = d.delivery == "failed", rev = d.rev, completedAt = d.completedAt,
        )
    }

    private fun seal(id: String, content: ReminderContent): String {
        val k = keys() ?: error("This phone has no encryption keys yet")
        return k.seal(id, GriipJson.encodeToString(contentSer, content).toByteArray())
    }

    /** Re-reads every reminder; shows the ones that went off and weren't shown here yet. */
    suspend fun refresh(): List<Reminder> = lock.withLock {
        val on = features.has("reminders") == true
        _available.value = on
        if (!on) return@withLock emptyList()
        val k = keys()
        val list = api.get<RemindersRes>("/v1/reminders").reminders.map { decode(it, k) }
        _reminders.value = list
        list
    }.also { list -> showFired(list) }

    private suspend fun showFired(list: List<Reminder>) {
        for (r in list) {
            val firedAt = r.firedAt ?: continue
            // A repeating reminder that went off is scheduled again, so "fired" alone can't tell; firedAt can.
            if (System.currentTimeMillis() - firedAt > CATCH_UP_MS) continue
            if (r.state == ReminderState.DONE || r.state == ReminderState.DISMISSED) continue
            if (runCatching { onFired(r) }.getOrDefault(false)) runCatching { ack(r.id, firedAt) }
        }
    }

    private suspend fun ack(id: String, firedAt: Long) {
        api.post<AckReminderReq, OkRes>("/v1/reminders/$id/ack", AckReminderReq(firedAt))
    }

    /** A new reminder (personal, or pointing at [ref]). [zone] is where its local time is meant. */
    suspend fun create(title: String, note: String?, dueAt: Long, recurrence: Recurrence?, ref: ReminderRef? = null, zone: ZoneId = ZoneId.systemDefault(), id: String = ReminderRules.newId(), source: String = "personal"): Reminder {
        val content = ReminderContent(title = title.trim().take(ReminderRules.TITLE_MAX), note = note?.trim()?.takeIf { it.isNotEmpty() }?.take(ReminderRules.NOTE_MAX), ref = ref, source = source)
        val repeat = recurrence?.let { r -> ReminderRules.repeatRule(dueAt, zone).let { (time, date) -> ReminderRepeatDto(r.wire, time, date) } }
        val res = api.post<CreateReminderReq, ReminderRes>("/v1/reminders", CreateReminderReq(id, seal(id, content), dueAt, zone.id, repeat))
        val created = decode(res.reminder, keys())
        _reminders.value = _reminders.value.filterNot { it.id == created.id } + created
        return created
    }

    /** Snooze or reschedule: a new due time puts it back to scheduled. */
    suspend fun reschedule(id: String, dueAt: Long) = patch(id) { put("dueAt", dueAt) }

    suspend fun complete(id: String) = patch(id) { put("state", ReminderState.DONE.wire) }

    /** Dismissing only stops the reminder; it never completes what it points at. */
    suspend fun dismiss(id: String) = patch(id) { put("state", ReminderState.DISMISSED.wire) }

    suspend fun edit(id: String, content: ReminderContent, dueAt: Long, recurrence: Recurrence?, zone: ZoneId = ZoneId.systemDefault()) = patch(id) {
        put("sealed", seal(id, content))
        put("dueAt", dueAt)
        put("timeZone", zone.id)
        if (recurrence == null) put("repeat", JsonNull)
        else ReminderRules.repeatRule(dueAt, zone).let { (time, date) ->
            put("repeat", buildJsonObject { put("recurrence", recurrence.wire); put("localTime", time); put("anchorDate", date) })
        }
    }

    suspend fun delete(id: String) {
        api.delete<OkRes>("/v1/reminders/$id")
        _reminders.value = _reminders.value.filterNot { it.id == id }
    }

    /**
     * Sends a change based on the current revision. Another device may have changed it meanwhile: then the fresh copy is
     * fetched and the change applied to it once more.
     */
    private suspend fun patch(id: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): Reminder {
        suspend fun attempt(rev: Int): ReminderDto {
            val body: JsonObject = buildJsonObject { put("rev", rev); fields() }
            return api.request("PATCH", "/v1/reminders/$id", GriipJson.encodeToString(JsonObject.serializer(), body), ReminderRes.serializer()).reminder
        }
        val rev = _reminders.value.firstOrNull { it.id == id }?.rev ?: api.get<ReminderRes>("/v1/reminders/$id").reminder.rev
        val dto = try {
            attempt(rev)
        } catch (e: ApiException) {
            if (e.code != "stale_reminder") throw e
            attempt(api.get<ReminderRes>("/v1/reminders/$id").reminder.rev)
        }
        val updated = decode(dto, keys())
        _reminders.value = _reminders.value.map { if (it.id == id) updated else it }
        return updated
    }

    /**
     * Keeps saved messages' reminders on the server: one reminder per saved item with a time (made, moved or finished
     * to match). Runs on every device; the shared id and revisions keep them from fighting.
     */
    suspend fun syncLater(saved: List<SavedItemDto>) {
        if (!_available.value) return
        val byId = _reminders.value.associateBy { it.id }
        val wanted = saved.filter { it.remindAt != null && !it.done }.associateBy { ReminderRules.laterId(it.messageId) }
        val now = System.currentTimeMillis()
        for ((id, item) in wanted) {
            val at = item.remindAt ?: continue
            val existing = byId[id]
            // Already past when first bridged: it went off before (or is overdue in Later); don't notify it again.
            if (existing == null && at < now - BRIDGE_GRACE_MS) continue
            runCatching {
                when {
                    existing == null -> create(TITLE_SAVED, null, at, null, ReminderRef("message", item.conversationId, item.messageId), id = id, source = "later")
                    existing.dueAt != at && existing.state != ReminderState.DONE -> reschedule(id, at)
                }
            }
        }
        // Saved items done or without a time any more: their reminders are finished too.
        for (r in _reminders.value) {
            if (r.content?.source != "later" || r.id in wanted) continue
            if (r.state == ReminderState.SCHEDULED || r.state == ReminderState.FIRED) runCatching { complete(r.id) }
        }
    }

    companion object {
        /** Reminders that went off longer ago than this aren't shown as new notifications (they're in Overdue). */
        const val CATCH_UP_MS = 24 * 60 * 60_000L
        /** A saved item due less than this long ago is still bridged (it may be firing right now). */
        const val BRIDGE_GRACE_MS = 60_000L
        /** The sealed title of a saved message's reminder; the notification shows the message itself. */
        const val TITLE_SAVED = "Saved message"
    }
}
