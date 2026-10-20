package com.meldiq.android.ui.screens.account

import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Workspaces
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meldiq.android.R
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ChoiceCard
import com.meldiq.android.ui.components.GlassCard
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.screens.onboarding.AuthScaffold
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Shapes
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.launch
import java.util.UUID

private const val KEY_GROUPS_PER_LINE = 4

/**
 * A new encryption identity's recovery key, shown once right after it was made. Without it (and without another
 * signed-in phone) the account's encrypted history can't be read on a new phone; the password can't help.
 */
@Composable
internal fun RecoveryKeySaveScreen(recoveryKey: String, onSaved: () -> Unit) {
    val c = Griip.colors
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var copied by remember { mutableStateOf(false) }
    AuthScaffold(
        // The key is shown once: only "I've saved it" leaves (system Back is held in AccountGate too).
        title = stringResource(R.string.acct_key_title), subtitle = stringResource(R.string.acct_key_subtitle), onBack = {}, backEnabled = false,
        bottomBar = { PillButton(stringResource(R.string.acct_key_saved), onSaved, Modifier.fillMaxWidth(), height = 56.dp) },
    ) {
        GlassCard(Modifier.fillMaxWidth(), shape = Shapes.panel) {
            val label = stringResource(R.string.restore_key)
            Text(
                // Four groups a line, so a group is never split across lines.
                recoveryKey.split("-").chunked(KEY_GROUPS_PER_LINE).joinToString("\n") { it.joinToString("-") },
                style = Griip.type.title.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, fontSize = 19.sp, lineHeight = 28.sp),
                color = c.textPrimary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    .clearAndSetSemantics { contentDescription = "$label: " + recoveryKey.filter { it.isLetterOrDigit() }.toList().joinToString(" ") },
            )
        }
        Spacer(Modifier.height(Space.md))
        PillButton(
            stringResource(if (copied) R.string.acct_key_copied else R.string.acct_key_copy),
            {
                scope.launch {
                    // Marked sensitive so Android 13+ doesn't show the key in its clipboard preview.
                    val clip = ClipData.newPlainText("recovery", recoveryKey).apply {
                        if (Build.VERSION.SDK_INT >= 33) description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                    }
                    clipboard.setClipEntry(clip.toClipEntry())
                    copied = true
                }
            },
            Modifier.fillMaxWidth(), tone = ButtonTone.Glass, showKnob = false, height = 48.dp,
        )
        Spacer(Modifier.height(Space.lg))
        StatusBanner(stringResource(R.string.acct_key_warning), BannerKind.Warning, icon = Icons.Rounded.Warning)
    }
}

/**
 * How a new account starts (docs/ORBIT_2_ARCHITECTURE §4): on its own personal workspace, by creating an organisation
 * (and owning it), or by joining one with an invitation. Organisation membership is never required.
 */
@Composable
internal fun WorkspaceStartScreen(onDone: (joinRequested: String?) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    // null: choosing; otherwise the kind being created (OrgKind.TEAM or OrgKind.COMMUNITY).
    var creatingKind by rememberSaveable { mutableStateOf<String?>(null) }
    val creating = creatingKind != null
    val communityLabel = stringResource(R.string.m2_create_community)
    // Communities only where the server has them; an older server would quietly make a team.
    var communities by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        communities = runCatching { container.features.get() }.getOrNull()?.contains(com.meldiq.android.data.repo.ServerFeatures.COMMUNITIES) == true
    }
    val isCommunity = creatingKind == com.meldiq.android.data.api.OrgKind.COMMUNITY
    var orgName by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // One id per create form: a retry or a double tap returns the organisation the first request made (server dedupes).
    val requestId = rememberSaveable { UUID.randomUUID().toString() }
    val personal: () -> Unit = {
        scope.launch {
            runCatching { container.workspaces.select(com.meldiq.android.data.account.WorkspaceDto.PERSONAL_ID) }
            onDone(null)
        }
    }
    BackHandler(enabled = !busy) { if (creating) creatingKind = null else personal() }
    Box(Modifier.fillMaxSize().background(Griip.colors.background)) {
        AuthScaffold(
            title = stringResource(R.string.acct_start_title), subtitle = stringResource(R.string.acct_start_subtitle),
            onBack = { if (creating) creatingKind = null else personal() }, backEnabled = !busy,
            bottomBar = if (!creating) null else {
                {
                    PillButton(
                        if (isCommunity) communityLabel else stringResource(R.string.acct_start_create_submit), {
                            busy = true
                            error = null
                            scope.launch {
                                runCatching {
                                    val org = container.orgs.create(orgName.trim(), null, requestId, creatingKind)
                                    container.analytics.track(if (isCommunity) "community.created" else "workspace.created")
                                    container.workspaces.refresh()
                                    container.workspaces.select(org.id)
                                }.onSuccess { onDone(null) }.onFailure { error = it.friendly() }
                                busy = false
                            }
                        },
                        Modifier.fillMaxWidth(), enabled = orgName.isNotBlank() && !busy, loading = busy, height = 56.dp,
                    )
                }
            },
        ) {
            if (!creating) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    ChoiceCard(Icons.Outlined.Person, stringResource(R.string.acct_start_personal), stringResource(R.string.acct_start_personal_sub), personal)
                    ChoiceCard(Icons.Outlined.Business, stringResource(R.string.acct_start_create), stringResource(R.string.acct_start_create_sub), { creatingKind = com.meldiq.android.data.api.OrgKind.TEAM })
                    if (communities) {
                        ChoiceCard(Icons.Outlined.Groups, stringResource(R.string.m2_start_community), stringResource(R.string.m2_start_community_sub), { creatingKind = com.meldiq.android.data.api.OrgKind.COMMUNITY })
                    }
                    ChoiceCard(Icons.Outlined.GroupAdd, stringResource(R.string.acct_start_join), stringResource(R.string.acct_start_join_sub), { onDone("") })
                }
            } else {
                GriipTextField(
                    orgName, { orgName = it.take(80) }, stringResource(if (isCommunity) R.string.m2_community_name else R.string.acct_start_org_name),
                    leadingIcon = if (isCommunity) Icons.Outlined.Groups else Icons.Rounded.Workspaces,
                    shape = Shapes.field, enabled = !busy,
                    helper = stringResource(if (isCommunity) R.string.m2_community_help else R.string.acct_start_org_help),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                )
            }
            error?.let {
                Spacer(Modifier.height(Space.md))
                StatusBanner(it, BannerKind.Error)
            }
        }
    }
}
