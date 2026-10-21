package com.meldiq.android.ui.screens.account

import com.meldiq.android.data.account.AccountRepository
import com.meldiq.android.data.account.FakeAccountApi
import com.meldiq.android.data.account.FakeAccountApi.Companion.PASSWORD
import com.meldiq.android.data.account.FakeAccountApi.Companion.URL
import com.meldiq.android.data.account.FakeKeyBackupApi
import com.meldiq.android.data.account.FakeMessagingSession
import com.meldiq.android.data.account.KeyBackupApi
import com.meldiq.android.data.account.KeyBackupStatus
import com.meldiq.android.data.account.LegacyMigration
import com.meldiq.android.data.account.MemoryVault
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The recovery choices open only from a help link (docs/product/MELDIQ_AUTH_FLOW_AUDIT.md). */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountHelpTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val api = FakeAccountApi()
    private val email = "ada@example.com"

    /** The server's key backup, failing [status] while [failing] is set (a dropped connection). */
    private class FlakyBackups(private val real: KeyBackupApi) : KeyBackupApi by real {
        var failing = false
        override suspend fun status(): KeyBackupStatus = if (failing) throw IOException("offline") else real.status()
    }

    private val backups = FlakyBackups(FakeKeyBackupApi(api))

    private fun phone() = AccountRepository(api, MemoryVault(), FakeMessagingSession(), deviceName = { "Phone" }, keyBackups = backups)

    private fun flow(accounts: AccountRepository) = AccountFlowViewModel(accounts, LegacyMigration(api, MemoryVault(), FakeMessagingSession(), accounts), { URL })

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `sign-in help always offers the password reset, and the rest only where they apply`() {
        assertEquals(
            listOf(AccountStep.Forgot, AccountStep.Recover, AccountStep.LegacyCredentials),
            signInHelpOptions(allowRecovery = true, allowLegacy = true).map { it.step },
        )
        assertEquals(listOf(AccountStep.Forgot), signInHelpOptions(allowRecovery = false, allowLegacy = false).map { it.step })
    }

    @Test
    fun `the unlock help lists another phone, the recovery key and starting fresh`() {
        assertEquals(listOf(AccountStep.Link, AccountStep.RestoreKey, AccountStep.StartFresh), deviceUnlockOptions.map { it.step })
    }

    @Test
    fun `a new phone with a key backup is offered the emailed code`() = runTest(dispatcher) {
        api.verifiedAccount(email)
        phone().login(URL, email, PASSWORD)
        val vm = flow(phone())
        vm.edit { it.copy(email = email, password = PASSWORD) }
        vm.logIn()
        vm.form.first { !it.submitting && !it.keyBackupChecking }
        assertEquals(AccountStep.DeviceSetup, vm.form.value.step)
        assertEquals(true, vm.form.value.keyBackup)
        assertFalse(vm.form.value.keyBackupChecking)
    }

    @Test
    fun `a failed backup check can be tried again`() = runTest(dispatcher) {
        api.verifiedAccount(email)
        phone().login(URL, email, PASSWORD)
        val vm = flow(phone())
        backups.failing = true
        vm.edit { it.copy(email = email, password = PASSWORD) }
        vm.logIn()
        vm.form.first { !it.submitting && !it.keyBackupChecking }
        assertEquals(AccountStep.DeviceSetup, vm.form.value.step)
        assertNull("the server couldn't say", vm.form.value.keyBackup)
        assertFalse(vm.form.value.keyBackupChecking)
        backups.failing = false
        vm.checkKeyBackup()
        vm.form.first { !it.keyBackupChecking }
        assertEquals(true, vm.form.value.keyBackup)
    }

    @Test
    fun `choosing help keeps the typed email`() = runTest(dispatcher) {
        val vm = flow(phone())
        vm.edit { it.copy(email = email) }
        vm.goTo(signInHelpOptions(allowRecovery = true, allowLegacy = true)[1].step)
        assertEquals(AccountStep.Recover, vm.form.value.step)
        assertEquals(email, vm.form.value.email)
        vm.back()
        assertEquals(AccountStep.LogIn, vm.form.value.step)
        assertEquals(email, vm.form.value.email)
    }
}
