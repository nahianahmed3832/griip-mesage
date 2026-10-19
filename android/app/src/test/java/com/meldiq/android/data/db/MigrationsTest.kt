package com.meldiq.android.data.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Migration policy check (docs/DECISIONS.md D-13) without a device: for every exported schema version,
 * build the previous version's tables in SQLite, add sample rows, run the app's own migration SQL, then
 * require the result to match the exported schema of the next version, with the sample rows intact.
 */
class MigrationsTest {
    private val schemaDir = File("schemas/com.meldiq.android.data.db.GriipDatabase")
    private fun schema(version: Int): JsonObject =
        Json.parseToJsonElement(File(schemaDir, "$version.json").readText()).jsonObject["database"]!!.jsonObject

    private val versions = schemaDir.listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.sorted()

    @Test
    fun everySchemaVersionHasAMigrationAndTheLatestIsTheAppVersion() {
        assertEquals("schema files must run 1..N without gaps", (1..versions.last()).toList(), versions)
        assertEquals("GriipDatabase.VERSION must equal the newest exported schema", versions.last(), GriipDatabase.VERSION)
        for (v in versions.drop(1)) assertTrue("missing migration ${v - 1} → $v", Migrations.steps.containsKey(v - 1))
        assertTrue("no migration may target a version without an exported schema", Migrations.steps.keys.all { it + 1 in versions })
    }

    @Test
    fun eachMigrationProducesTheNextSchemaAndKeepsExistingRows() {
        for (from in versions.dropLast(1)) {
            DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
                create(c, schema(from))
                val fixtures = Fixtures.insert(c, from)
                Migrations.steps.getValue(from).forEach { c.createStatement().execute(it) }
                assertSchemaMatches(c, schema(from + 1), "after migrating $from → ${from + 1}")
                fixtures.forEach { (table, rows) -> assertEquals("rows in $table after $from → ${from + 1}", rows, count(c, table)) }
            }
        }
    }

    @Test
    fun theOldestSchemaBuildsCleanly() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            create(c, schema(versions.first()))
            assertSchemaMatches(c, schema(versions.first()), "fresh v${versions.first()}")
            // The sample rows themselves must be valid for the schema they target.
            Fixtures.insert(c, versions.first()).forEach { (table, rows) -> assertEquals(rows, count(c, table)) }
        }
    }

    @Test
    fun chatsExistingBeforeTheArchiveStayUnarchived() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            create(c, schema(4))
            Fixtures.insert(c, 4)
            Migrations.steps.getValue(4).forEach { c.createStatement().execute(it) }
            c.createStatement().executeQuery("SELECT COUNT(*) FROM conversations WHERE archivedAt IS NULL").use { it.next(); assertEquals(1, it.getInt(1)) }
        }
    }

    @Test
    fun knownPeopleStartWithoutAProfilePhoto() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            create(c, schema(5))
            Fixtures.insert(c, 5)
            Migrations.steps.getValue(5).forEach { c.createStatement().execute(it) }
            c.createStatement().executeQuery("SELECT COUNT(*) FROM users WHERE photoRev IS NULL").use { it.next(); assertEquals(1, it.getInt(1)) }
            c.createStatement().executeQuery("SELECT archivedAt FROM conversations WHERE id = 'c1'").use { it.next(); assertEquals(5, it.getInt(1)) }
        }
    }

    @Test
    fun historyQueueFollowsMeaningfulChangesOnly() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            create(c, schema(6))
            Fixtures.insert(c, 6)
            Migrations.steps.getValue(6).forEach { c.createStatement().execute(it) }
            val s = c.createStatement()
            fun queued(key: String): Long? = s.executeQuery("SELECT queuedAt FROM history_outbox WHERE `key` = '$key'").use { if (it.next()) it.getLong(1) else null }
            // Rows that existed before the upgrade aren't queued by the migration (HistorySync queues them when history is on).
            assertEquals(null, queued("m:m1"))
            s.execute(
                "INSERT INTO messages (id, conversationId, senderUserId, senderDeviceId, outgoing, kind, text, mentionsJson, attachmentsJson, sentAt, sortTs, status, deleted, pinned, reactionsJson, readByJson, deliveredToJson, forwarded) " +
                    "VALUES ('m2','c1','u1','d1',1,'text','new','[]','[]',2,2,'SENDING',0,0,'{}','[]','[]',0)",
            )
            val first = queued("m:m2")!!
            // Receipts don't count; sending finishing, edits and deletions do (each with a higher version).
            s.execute("UPDATE messages SET readByJson = '[\"u2\"]', deliveredToJson = '[\"u2\"]' WHERE id = 'm2'")
            assertEquals(first, queued("m:m2"))
            // Room rewrites whole rows: writing the same values again isn't a change either.
            s.execute("UPDATE messages SET text = text, status = status, attachmentsJson = attachmentsJson, deleted = deleted WHERE id = 'm2'")
            assertEquals(first, queued("m:m2"))
            s.execute("UPDATE messages SET status = 'SENT' WHERE id = 'm2'")
            val second = queued("m:m2")!!
            assertTrue(second > first)
            s.execute("UPDATE messages SET text = 'edited', editedAt = 9 WHERE id = 'm1'")
            assertTrue(queued("m:m1")!! > second)
            // Chats: preferences and membership count; drafts and unread counters don't.
            s.execute("UPDATE conversations SET draft = 'x', unreadCount = 3 WHERE id = 'c1'")
            assertEquals(null, queued("c:c1"))
            s.execute("UPDATE conversations SET membersJson = membersJson, muted = muted, archivedAt = archivedAt, name = name WHERE id = 'c1'")
            assertEquals(null, queued("c:c1"))
            s.execute("UPDATE conversations SET muted = 1 WHERE id = 'c1'")
            assertTrue(queued("c:c1") != null)
            // Removing a row never queues anything (history is never deleted from the server by accident).
            s.execute("DELETE FROM history_outbox")
            s.execute("DELETE FROM messages WHERE id = 'm2'")
            assertEquals(0, count(c, "history_outbox"))
            // The triggers are idempotent: the app recreates them on every open.
            HistoryTriggers.sql.forEach { s.execute(it) }
        }
    }

    private fun create(c: Connection, db: JsonObject) {
        for (e in db["entities"]!!.jsonArray.map { it.jsonObject }) {
            val table = e["tableName"]!!.jsonPrimitive.content
            c.createStatement().execute(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            e["indices"]?.jsonArray?.forEach { i -> c.createStatement().execute(i.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
        }
    }

    private fun assertSchemaMatches(c: Connection, db: JsonObject, context: String) {
        for (e in db["entities"]!!.jsonArray.map { it.jsonObject }) {
            val table = e["tableName"]!!.jsonPrimitive.content
            val actual = c.createStatement().executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                buildMap { while (rs.next()) put(rs.getString("name"), Triple(rs.getString("type").uppercase(), rs.getInt("notnull") == 1, rs.getInt("pk") > 0)) }
            }
            val pk = e["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
            val expected = e["fields"]!!.jsonArray.associate { f ->
                val o = f.jsonObject
                val name = o["columnName"]!!.jsonPrimitive.content
                name to Triple(o["affinity"]!!.jsonPrimitive.content.uppercase(), o["notNull"]?.jsonPrimitive?.boolean ?: false, name in pk)
            }
            assertEquals("$context: columns of $table", expected, actual)
            val indices = c.createStatement().executeQuery("PRAGMA index_list(`$table`)").use { rs ->
                buildSet { while (rs.next()) rs.getString("name").takeIf { it.startsWith("index_") }?.let(::add) }
            }
            val expectedIndices = e["indices"]?.jsonArray?.map { it.jsonObject["name"]!!.jsonPrimitive.content }?.toSet() ?: emptySet()
            assertEquals("$context: indices of $table", expectedIndices, indices)
        }
    }

    private fun count(c: Connection, table: String) = c.createStatement().executeQuery("SELECT COUNT(*) FROM `$table`").use { it.next(); it.getInt(1) }
}

/**
 * Representative rows for each schema version: messages, conversations, settings and call-key
 * references must survive every upgrade. Add an entry when a new version adds a table worth checking.
 */
private object Fixtures {
    fun insert(c: Connection, version: Int): Map<String, Int> {
        val s = c.createStatement()
        when (version) {
            6 -> {
                val base = insert(c, 5)
                s.execute("UPDATE users SET photoRev = 3 WHERE id = 'u1'")
                s.execute("UPDATE messages SET richJson = '{\"animoji\":\"🎉\"}', forwarded = 1 WHERE id = 'm1'")
                return base
            }
            5 -> {
                val base = insert(c, 4)
                s.execute("UPDATE conversations SET archivedAt = 5 WHERE id = 'c1'")
                return base
            }
            4 -> {
                val base = insert(c, 3)
                s.execute(
                    "INSERT INTO pending_controls (id, conversationId, targetId, senderUserId, payloadJson, serverTs, receivedAt, expiresAt) " +
                        "VALUES ('p1','c1','m9','u1','{}',1,1,9)",
                )
                return base + ("pending_controls" to 1)
            }
            3 -> {
                val base = insert(c, 2)
                s.execute("UPDATE quarantine SET reason = 'failed: SQLiteException' WHERE queueId = 7")
                return base
            }
            2 -> {
                val base = insert(c, 1)
                s.execute("INSERT INTO quarantine (queueId, conversationId, senderUserId, itemJson, receivedAt) VALUES (7, 'c1', 'u1', '{}', 1)")
                return base + ("quarantine" to 1)
            }
            1 -> {
                s.execute(
                    "INSERT INTO conversations (id, type, archived, memberCount, membersJson, joined, canPost, canManage, activeCallVideo, serverUpdatedAt, lastMessageAt, unreadCount, mentionCount, muted, removed) " +
                        "VALUES ('c1','DM',0,2,'[]',1,1,0,0,1,1,0,0,0,0)",
                )
                s.execute(
                    "INSERT INTO messages (id, conversationId, senderUserId, senderDeviceId, outgoing, kind, text, mentionsJson, attachmentsJson, sentAt, sortTs, status, deleted, pinned, reactionsJson, readByJson, deliveredToJson) " +
                        "VALUES ('m1','c1','u1','d1',0,'text','hello','[]','[]',1,1,'DELIVERED',0,0,'{}','[]','[]')",
                )
                s.execute("INSERT INTO users (id, username, displayName, avatarColor, pinnedIdentityKey, identityChanged, verified, online, updatedAt) VALUES ('u1','ayesha','Ayesha',1,'KEY',0,0,0,1)")
                s.execute("INSERT INTO kv (k, v) VALUES ('settings.readReceipts','true')")
                s.execute("INSERT INTO call_keys (callId, conversationId, key, fromUserId, receivedAt) VALUES ('call1','c1','K','u1',1)")
                return mapOf("conversations" to 1, "messages" to 1, "users" to 1, "kv" to 1, "call_keys" to 1)
            }
        }
        return emptyMap()
    }
}
