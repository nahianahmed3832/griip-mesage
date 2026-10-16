package com.griipbuddy.message.data.db

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
    val steps: Map<Int, List<String>> = mapOf()

    fun all(): Array<Migration> = steps.map { (from, sql) ->
        object : Migration(from, from + 1) {
            override fun migrate(db: SupportSQLiteDatabase) = sql.forEach(db::execSQL)
        }
    }.toTypedArray()
}
