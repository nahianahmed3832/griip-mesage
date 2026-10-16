package com.griipbuddy.orbit.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit, append-only schema migrations (docs/DECISIONS.md D-13). The local database holds the
 * only copy of a user's history, so every version bump ships a step here and never a destructive
 * fallback. Steps are plain SQL so `MigrationsTest` (JVM) runs exactly what the app runs against the
 * exported schemas in `app/schemas`, checking the resulting schema and that existing rows survive.
 */
object Migrations {
    /** fromVersion → statements that take the schema from `fromVersion` to `fromVersion + 1`. */
    val steps: Map<Int, List<String>> = mapOf(
        // v2 (T1.2): envelopes held because their sender's identity key changed.
        1 to listOf(
            "CREATE TABLE IF NOT EXISTS `quarantine` (`queueId` INTEGER NOT NULL, `conversationId` TEXT NOT NULL, `senderUserId` TEXT NOT NULL, " +
                "`itemJson` TEXT NOT NULL, `receivedAt` INTEGER NOT NULL, PRIMARY KEY(`queueId`))",
            "CREATE INDEX IF NOT EXISTS `index_quarantine_senderUserId` ON `quarantine` (`senderUserId`)",
            "CREATE INDEX IF NOT EXISTS `index_quarantine_conversationId` ON `quarantine` (`conversationId`)",
        ),
        // v3 (T1.4): items set aside after repeated processing failures share the table, with a reason.
        2 to listOf("ALTER TABLE `quarantine` ADD COLUMN `reason` TEXT"),
        // v4 (T2.1): edits, deletions, reactions and pins waiting for their target message.
        3 to listOf(
            "CREATE TABLE IF NOT EXISTS `pending_controls` (`id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `targetId` TEXT NOT NULL, " +
                "`senderUserId` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `serverTs` INTEGER NOT NULL, `receivedAt` INTEGER NOT NULL, " +
                "`expiresAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_pending_controls_conversationId_targetId` ON `pending_controls` (`conversationId`, `targetId`)",
        ),
    )

    fun all(): Array<Migration> = steps.map { (from, sql) ->
        object : Migration(from, from + 1) {
            override fun migrate(db: SupportSQLiteDatabase) = sql.forEach(db::execSQL)
        }
    }.toTypedArray()
}
