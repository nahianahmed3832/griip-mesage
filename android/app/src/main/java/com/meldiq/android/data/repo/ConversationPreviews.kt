package com.meldiq.android.data.repo

import com.meldiq.android.data.db.GriipDatabase
import com.meldiq.android.data.model.MessagePreview

/** Recomputes the chat list's last message for [cid] from what's stored (after a deletion, an edit or a call entry). */
internal suspend fun GriipDatabase.refreshPreview(cid: String) {
    val conv = conversations().get(cid) ?: return
    val last = messages().latestVisible(cid)
    conversations().upsert(
        conv.copy(
            lastMessageId = last?.id,
            lastMessagePreview = last?.let(MessagePreview::of) ?: MessagePreview.DELETED,
            lastMessageAt = last?.sortTs ?: conv.lastMessageAt,
        ),
    )
}
