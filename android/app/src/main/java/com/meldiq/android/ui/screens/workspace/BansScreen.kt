package com.meldiq.android.ui.screens.workspace

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.meldiq.android.R
import com.meldiq.android.data.api.BanDto
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.components.Avatar
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ConfirmDialog
import com.meldiq.android.ui.components.EmptyState
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.GriipTopBar
import com.meldiq.android.ui.components.ListCard
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.ShimmerList
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import com.meldiq.android.ui.theme.Space
import kotlinx.coroutines.launch

/** The community's bans (people with `members.ban`): who, why, by whom; lifting one lets them back in. */
@Composable
fun BansScreen(nav: NavHostController, orgId: String) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var bans by remember { mutableStateOf<List<BanDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var lifting by remember { mutableStateOf<BanDto?>(null) }
    fun load() = scope.launch { runCatching { container.orgs.bans(orgId) }.onSuccess { bans = it; error = null }.onFailure { error = it.friendly() } }
    LaunchedEffect(orgId) { load() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { GriipTopBar(stringResource(R.string.bn_title), subtitle = stringResource(R.string.cm_bans_sub), onBack = { nav.popBackStack() }) }
        error?.let { item { StatusBanner(it, BannerKind.Error, Modifier.padding(horizontal = Space.gutter, vertical = 4.dp)) } }
        val list = bans
        when {
            list == null -> item { ShimmerList(3) }
            list.isEmpty() -> item { EmptyState(Icons.Outlined.Block, stringResource(R.string.bn_title), stringResource(R.string.bn_empty)) }
            else -> items(list, key = { it.userId }) { b ->
                ListCard(
                    b.displayName, Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                    subtitle = listOfNotNull(b.reason, b.bannedByName?.let { stringResource(R.string.bn_by, it) }).joinToString(" · ").ifEmpty { "@${b.username}" },
                    leading = { Avatar(b.displayName, b.userId.hashCode(), size = 44.dp) },
                    trailing = { PillButton(stringResource(R.string.bn_unban), { lifting = b }, tone = ButtonTone.Glass, height = 38.dp) },
                )
            }
        }
    }
    lifting?.let { b ->
        ConfirmDialog(stringResource(R.string.bn_unban), b.displayName, stringResource(R.string.bn_unban), onConfirm = {
            lifting = null
            scope.launch { runCatching { container.orgs.unban(orgId, b.userId) }.onSuccess { load() }.onFailure { error = it.friendly() } }
        }, onDismiss = { lifting = null })
    }
}

/** Bans someone from the community (asked with an optional reason; the server checks who may ban whom). */
@Composable
fun BanSheet(orgId: String, userId: String, name: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    GlassSheet(onDismiss, title = stringResource(R.string.bn_ban_title, name)) {
        Text(stringResource(R.string.bn_ban_body), style = Griip.type.body, color = Griip.colors.textSecondary)
        Spacer(Modifier.height(12.dp))
        GriipTextField(reason, { reason = it.take(300) }, stringResource(R.string.bn_reason))
        error?.let { Spacer(Modifier.height(10.dp)); StatusBanner(it, BannerKind.Error) }
        Spacer(Modifier.height(16.dp))
        PillButton(stringResource(R.string.bn_ban), {
            busy = true
            scope.launch {
                runCatching { container.orgs.ban(orgId, userId, reason) }
                    .onSuccess {
                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.bn_done, name), android.widget.Toast.LENGTH_SHORT).show()
                        onDismiss(); onDone()
                    }
                    .onFailure { error = it.friendly() }
                busy = false
            }
        }, Modifier.fillMaxWidth().padding(bottom = 8.dp), tone = ButtonTone.Danger, loading = busy, height = 50.dp)
    }
}
