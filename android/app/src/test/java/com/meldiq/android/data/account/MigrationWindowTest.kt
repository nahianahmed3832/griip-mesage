package com.meldiq.android.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationWindowTest {
    private val withMove = listOf("accounts-v2", "legacy-migration")

    @Test
    fun `a server without the move offers nothing`() {
        // Production before 0.4.0: no accounts block and no legacy-migration feature.
        assertEquals(MigrationWindow(open = false, canWait = true), MigrationWindow.of(listOf("profiles"), null))
        assertEquals(MigrationWindow(open = false, canWait = true), MigrationWindow.of(null, null))
    }

    @Test
    fun `the move can wait while the old sign-in works and it isn't required`() {
        val info = AccountsInfo(registration = true, legacyLogin = true, legacyMigration = true)
        assertEquals(MigrationWindow(open = true, canWait = true), MigrationWindow.of(withMove, info))
    }

    @Test
    fun `a required move has no Later`() {
        val info = AccountsInfo(registration = true, legacyLogin = true, legacyMigration = true, legacyMigrationRequired = true)
        assertEquals(MigrationWindow(open = true, canWait = false), MigrationWindow.of(withMove, info))
    }

    @Test
    fun `the move can't wait once the old sign-in is closed`() {
        val info = AccountsInfo(registration = true, legacyLogin = false, legacyMigration = true)
        assertEquals(MigrationWindow(open = true, canWait = false), MigrationWindow.of(withMove, info))
    }

    @Test
    fun `no move is offered while the server can't email codes`() {
        // The move ends with an emailed code: offering it (let alone requiring it) would leave people stuck there.
        // Production 0.4.1 before account email: legacy-migration listed, registration false.
        val required = AccountsInfo(registration = false, legacyLogin = true, legacyMigration = true, legacyMigrationRequired = true)
        assertEquals(MigrationWindow(open = false, canWait = true), MigrationWindow.of(withMove, required))
        val optional = AccountsInfo(registration = false, legacyLogin = true, legacyMigration = true)
        assertEquals(MigrationWindow(open = false, canWait = true), MigrationWindow.of(withMove, optional))
    }
}
