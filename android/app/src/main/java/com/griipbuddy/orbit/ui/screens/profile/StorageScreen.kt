package com.griipbuddy.orbit.ui.screens.profile

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.griipbuddy.orbit.crypto.BackupCrypto
import com.griipbuddy.orbit.data.model.AttachmentPolicy
import com.griipbuddy.orbit.service.BackupWorker
import com.griipbuddy.orbit.ui.LocalContainer
import com.griipbuddy.orbit.ui.TimeFmt
import com.griipbuddy.orbit.ui.components.BannerKind
import com.griipbuddy.orbit.ui.components.ChipRow
import com.griipbuddy.orbit.ui.components.GlassCard
import com.griipbuddy.orbit.ui.components.GlassSheet
import com.griipbuddy.orbit.ui.components.GriipSwitch
import com.griipbuddy.orbit.ui.components.GriipTextField
import com.griipbuddy.orbit.ui.components.GriipTopBar
import com.griipbuddy.orbit.ui.components.MenuRow
import com.griipbuddy.orbit.ui.components.PillButton
import com.griipbuddy.orbit.ui.components.PillChip
import com.griipbuddy.orbit.ui.components.SectionHeader
import com.griipbuddy.orbit.ui.components.StatusBanner
import com.griipbuddy.orbit.ui.theme.Griip
import com.griipbuddy.orbit.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun sizeOf(f: File): Long = if (f.isDirectory) f.listFiles()?.sumOf { sizeOf(it) } ?: 0L else f.length()

/** What a backup sheet is for: a one-off backup, turning on automatic backups, or restoring. */
private enum class Flow { BACKUP_NOW, AUTO, RESTORE }

/**
 * Data & Storage (2026-10-17, docs/BACKUP.md): what Orbit stores on this phone, encrypted backups (now, automatically, or
 * restore), and clearing the cache. Nothing here deletes messages.
 */
@Composable
fun StorageScreen(nav: NavHostController) {
    val container = LocalContainer.current
    val ctx = LocalContext.current
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var sizes by remember { mutableStateOf<Triple<Long, Long, Long>?>(null) }
    LaunchedEffect(refresh) {
        sizes = withContext(Dispatchers.IO) {
            Triple(
                ctx.getDatabasePath(container.profile.dbName).length(),
                sizeOf(File(ctx.filesDir, container.profile.attachmentsDir)),
                sizeOf(ctx.cacheDir),
            )
        }
    }
    var auto by remember { mutableStateOf(container.backups.auto) }
    var flow by remember { mutableStateOf<Flow?>(null) }
    var target by remember { mutableStateOf<Uri?>(null) }
    var autoDays by remember { mutableIntStateOf(1) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // message, success
    var busy by remember { mutableStateOf(false) }

    val createNow = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (uri != null) { target = uri; flow = Flow.BACKUP_NOW } }
    val createAuto = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            target = uri
            flow = Flow.AUTO
        }
    }
    val openRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { target = uri; flow = Flow.RESTORE } }
    val history by container.history.state.collectAsStateWithLifecycle()
    var confirmDeleteHistory by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { container.history.summary() }
    val fileName = "orbit-backup-${container.session.session?.username ?: "account"}-${java.time.LocalDate.now()}.orbitbackup"

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { GriipTopBar("Data & Storage", onBack = { nav.popBackStack() }) }
        status?.let { (msg, ok) -> item { StatusBanner(msg, if (ok) BannerKind.Success else BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) } }
        item { SectionHeader("On this phone", Modifier.padding(top = 6.dp, bottom = 6.dp)) }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), contentPadding = PaddingValues(4.dp)) {
                val s = sizes
                MenuRow(Icons.Outlined.Storage, "Messages", subtitle = s?.let { "${AttachmentPolicy.sizeLabel(it.first)} · encrypted database (SQLCipher)" } ?: "…", trailing = null)
                MenuRow(Icons.Outlined.Folder, "Downloaded photos, videos and files", subtitle = s?.let { "${AttachmentPolicy.sizeLabel(it.second)} · decrypted copies, inside Orbit's private storage" } ?: "…", trailing = null)
                MenuRow(Icons.Outlined.CleaningServices, "Clear cache", subtitle = s?.let { "${AttachmentPolicy.sizeLabel(it.third)} · thumbnails, profile photos, temporary files. Messages and downloads stay." } ?: "…", onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
                        refresh++
                        status = "Cache cleared" to true
                    }
                })
            }
        }
        // Encrypted history on the server (docs/MESSAGE_HISTORY.md): what survives a reinstall or a new phone.
        item { SectionHeader("Message history on the server", Modifier.padding(top = 16.dp, bottom = 6.dp)) }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), contentPadding = PaddingValues(4.dp)) {
                val server = history.server
                MenuRow(
                    Icons.Outlined.CloudSync, "Keep my messages on the server",
                    subtitle = when {
                        history.supported == false -> "Your server doesn't keep message history yet (it needs an update). Messages stay only on your phones."
                        !history.enabled -> "Off. Messages stay only on this phone and in your backups."
                        history.restoring -> "Downloading your history…"
                        history.error != null -> history.error
                        history.pending > 0 -> "Encrypting and uploading ${history.pending} change${if (history.pending == 1) "" else "s"}…"
                        server != null -> "On · ${server.items} item${if (server.items == 1L) "" else "s"}, ${AttachmentPolicy.sizeLabel(server.bytes)}, encrypted" +
                            (history.lastUploadAt?.let { " · backed up ${TimeFmt.relative(it)}" } ?: "")
                        else -> "On"
                    },
                    trailing = {
                        GriipSwitch(history.enabled, { on -> scope.launch { container.history.setEnabled(on); container.history.summary() } }, enabled = history.supported != false)
                    },
                )
                if (history.enabled && history.supported != false) {
                    MenuRow(Icons.Outlined.Restore, "Restore history from the server", subtitle = "Adds any messages this phone is missing. Nothing on this phone is replaced.", onClick = {
                        scope.launch {
                            busy = true
                            val r = runCatching { container.history.catchUp(fromStart = true) }
                            val unreadable = container.history.state.value.unreadable
                            status = r.fold(
                                { n ->
                                    (if (n == 0) "Nothing was missing on this phone" else "Restored $n message${if (n == 1) "" else "s"} and chats") +
                                        (if (unreadable > 0) ". $unreadable item(s) were made with a different identity key and can't be read." else "") to true
                                },
                                { e -> (if (e is com.griipbuddy.orbit.data.api.ApiException) e.message else "Couldn't restore history") to false },
                            )
                            container.history.summary()
                            busy = false
                        }
                    })
                }
                if (server != null && server.items > 0) {
                    MenuRow(
                        Icons.Outlined.DeleteForever, "Delete my history from the server",
                        subtitle = "Turns this off and deletes the server's encrypted copy. Messages on your phones stay.",
                        tint = c.danger, tintBg = c.dangerSoft, onClick = { confirmDeleteHistory = true },
                    )
                }
            }
        }
        item {
            Text(
                "Messages are encrypted on your phone with a key from your account's identity before they're uploaded; the server " +
                    "can't read them. Your other devices and your recovery key can. If you lose every device and your recovery key, " +
                    "nobody can decrypt this history. Files you send or receive are kept with it, encrypted, for as long as the server keeps them.",
                style = Griip.type.caption, color = c.textTertiary, modifier = Modifier.padding(horizontal = Space.gutter + 4.dp, vertical = 8.dp),
            )
        }
        item { SectionHeader("Encrypted backup", Modifier.padding(top = 16.dp, bottom = 6.dp)) }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), contentPadding = PaddingValues(4.dp)) {
                MenuRow(Icons.Outlined.Backup, "Back up now", subtitle = "Save an encrypted backup file anywhere you choose", onClick = { createNow.launch(fileName) })
                MenuRow(
                    Icons.Outlined.CloudUpload, "Automatic backup",
                    subtitle = when {
                        auto.uri == null -> "Off. Choose a file in Google Drive (or anywhere) and Orbit keeps it up to date."
                        auto.lastError != null -> "Every ${if (auto.frequencyDays == 1) "day" else "${auto.frequencyDays} days"} · last attempt failed: ${auto.lastError}"
                        auto.lastSuccessAt != null -> "Every ${if (auto.frequencyDays == 1) "day" else "${auto.frequencyDays} days"} · last backup ${TimeFmt.relative(auto.lastSuccessAt)}"
                        else -> "Every ${if (auto.frequencyDays == 1) "day" else "${auto.frequencyDays} days"} · no backup yet"
                    },
                    trailing = {
                        GriipSwitch(auto.uri != null, { on ->
                            if (on) createAuto.launch(fileName) else {
                                container.backups.disableAuto()
                                BackupWorker.cancel(ctx, container.profile.id)
                                auto = container.backups.auto
                            }
                        })
                    },
                )
                MenuRow(Icons.Outlined.Restore, "Restore from a backup", subtitle = "Adds a backup's chats to this account. Nothing on this phone is replaced.", onClick = { openRestore.launch(arrayOf("*/*")) })
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 12.dp)) {
                MenuRow(
                    Icons.Outlined.Info, "What a backup holds",
                    subtitle = "Your chats, messages and the people in them, and if you choose, downloaded photos, videos and files, encrypted on this phone " +
                        "with your backup password before the file is written. It never holds your private keys or recovery key: on a new phone, sign in with " +
                        "your recovery key first, then restore. Without the backup password the file can't be opened, by anyone, including GriipBuddy. If you " +
                        "lose every signed-in device and your recovery key, your account and anything not backed up can't be recovered.",
                    trailing = null,
                )
            }
        }
    }

    if (confirmDeleteHistory) {
        com.griipbuddy.orbit.ui.components.ConfirmDialog(
            "Delete your history from the server?",
            "The encrypted copy of your messages on the server is deleted and server history is turned off. Messages on this " +
                "phone and your other phones stay. A new phone signed in to this account won't get them from the server.",
            "Delete", onConfirm = {
                confirmDeleteHistory = false
                scope.launch {
                    status = runCatching { container.history.deleteFromServer() }.fold(
                        { "Deleted your history from the server" to true },
                        { e -> (if (e is com.griipbuddy.orbit.data.api.ApiException) e.message else "Couldn't delete it") to false },
                    )
                }
            },
            onDismiss = { confirmDeleteHistory = false }, destructive = true,
        )
    }

    val f = flow
    val uri = target
    if (f != null && uri != null) {
        BackupPasswordSheet(
            flow = f, busy = busy,
            onDismiss = { if (!busy) { flow = null; target = null } },
            onConfirm = { password, media, days ->
                busy = true
                scope.launch {
                    val result = runCatching {
                        when (f) {
                            Flow.BACKUP_NOW -> {
                                val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("That file can't be written")
                                val h = out.use { container.backups.export(it, password, media) }
                                "Backup saved (${TimeFmt.clock(h.createdAt)}). Keep the password: it's the only way to open it."
                            }
                            Flow.AUTO -> {
                                withContext(Dispatchers.Default) { container.backups.enableAuto(uri, days, media, password) }
                                BackupWorker.schedule(ctx, container.profile.id, days)
                                val ok = container.backups.runAuto()
                                auto = container.backups.auto
                                if (ok) "Automatic backup is on. First backup saved." else "Automatic backup is on, but the first backup failed: ${auto.lastError}"
                            }
                            Flow.RESTORE -> {
                                val dry = ctx.contentResolver.openInputStream(uri)?.use { container.backups.restore(it, password, dryRun = true) } ?: throw java.io.IOException("That file can't be read")
                                val r = ctx.contentResolver.openInputStream(uri)?.use { container.backups.restore(it, password) } ?: throw java.io.IOException("That file can't be read")
                                "Restored ${r.messages} messages in ${r.conversations} new chats" + (if (r.media > 0) " and ${r.media} files" else "") +
                                    (if (r.skippedExisting > 0) ". ${r.skippedExisting} were already on this phone and were kept as they are." else ".") +
                                    " (The backup held ${dry.messages} messages.)"
                            }
                        }
                    }
                    password.fill(' ')
                    status = result.fold({ it to true }, { e -> ((e as? BackupCrypto.BadBackup)?.message ?: e.message ?: "That didn't work") to false })
                    busy = false
                    flow = null
                    target = null
                    refresh++
                }
            },
        )
    }
}

@Composable
private fun BackupPasswordSheet(flow: Flow, busy: Boolean, onDismiss: () -> Unit, onConfirm: (CharArray, Boolean, Int) -> Unit) {
    val c = Griip.colors
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var media by remember { mutableStateOf(false) }
    var days by remember { mutableIntStateOf(1) }
    val creating = flow != Flow.RESTORE
    val problem = when {
        !creating -> null
        pw.length < 10 -> "At least 10 characters"
        pw != pw2 -> "The passwords don't match"
        else -> null
    }
    GlassSheet(onDismiss, title = when (flow) { Flow.BACKUP_NOW -> "Back up now"; Flow.AUTO -> "Automatic backup"; Flow.RESTORE -> "Restore from a backup" }) {
        Text(
            if (creating) "Choose a password for this backup. Orbit can't recover it for you: without it, the backup can't be opened."
            else "Enter the backup's password. Everything is checked before anything is added to this account.",
            style = Griip.type.bodySmall, color = c.textSecondary,
        )
        Spacer(Modifier.height(10.dp))
        GriipTextField(pw, { pw = it }, "Backup password", visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), error = if (creating && pw.isNotEmpty()) problem.takeIf { it == "At least 10 characters" } else null)
        if (creating) {
            Spacer(Modifier.height(8.dp))
            GriipTextField(pw2, { pw2 = it }, "Repeat the password", visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), error = if (pw2.isNotEmpty() && pw != pw2) "The passwords don't match" else null)
            Spacer(Modifier.height(8.dp))
            MenuRow(Icons.Outlined.Folder, "Include photos, videos and files", subtitle = "Only those downloaded to this phone. Makes the backup much larger.", trailing = { GriipSwitch(media, { media = it }) })
        }
        if (flow == Flow.AUTO) {
            Text("How often", style = Griip.type.label, color = c.textSecondary, modifier = Modifier.padding(top = 8.dp))
            ChipRow { listOf(1 to "Daily", 7 to "Weekly").forEach { (d, label) -> PillChip(label, days == d, { days = d }) } }
            Text("The password is kept on this phone, protected by its secure hardware, so backups can run on their own. It is never uploaded.", style = Griip.type.caption, color = c.textSecondary)
        }
        Spacer(Modifier.height(14.dp))
        PillButton(
            when (flow) { Flow.BACKUP_NOW -> "Create backup"; Flow.AUTO -> "Turn on"; Flow.RESTORE -> "Restore" },
            { onConfirm(pw.toCharArray(), media, days) }, Modifier.fillMaxWidth(),
            enabled = !busy && pw.isNotEmpty() && problem == null, loading = busy,
        )
        Spacer(Modifier.height(16.dp))
    }
}
