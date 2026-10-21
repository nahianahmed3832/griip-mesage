package com.meldiq.android.ui.screens.workspace

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meldiq.android.R
import com.meldiq.android.ui.LocalContainer
import com.meldiq.android.ui.TimeFmt
import com.meldiq.android.ui.components.BannerKind
import com.meldiq.android.ui.components.ButtonTone
import com.meldiq.android.ui.components.ChipRow
import com.meldiq.android.ui.components.GlassSheet
import com.meldiq.android.ui.components.GriipTextField
import com.meldiq.android.ui.components.PillButton
import com.meldiq.android.ui.components.PillChip
import com.meldiq.android.ui.components.StatusBanner
import com.meldiq.android.ui.friendly
import com.meldiq.android.ui.theme.Griip
import kotlinx.coroutines.launch

/** Member timeouts (docs/product/MELDIQ_FEATURE_GAP_MATRIX.md MOD-06): the durations offered, and who is offered the action. */
object TimeoutRules {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** The server's limit. */
    const val MAX_MS = 28 * DAY

    /** Label and length, shortest first. */
    val DURATIONS: List<Pair<Int, Long>> = listOf(
        R.string.to_60s to MINUTE,
        R.string.to_5m to 5 * MINUTE,
        R.string.to_10m to 10 * MINUTE,
        R.string.to_1h to HOUR,
        R.string.to_1d to DAY,
        R.string.to_1w to 7 * DAY,
    )

    /**
     * Mirrors the server (it decides): never yourself or an owner; owners may time out anyone else, others need
     * `members.timeout` and only reach members and guests.
     */
    fun canOffer(myRole: String?, myPermissions: Set<String>, targetRole: String?, self: Boolean): Boolean {
        if (self || targetRole == null || targetRole == "OWNER") return false
        if (myRole == "OWNER") return true
        return "members.timeout" in myPermissions && targetRole in setOf("MEMBER", "GUEST")
    }

    /** Whether a timeout ending at [until] is still running at [now]. */
    fun running(until: Long?, now: Long = System.currentTimeMillis()): Boolean = until != null && until > now

    /** "18:40" today, otherwise the day and the time. */
    fun untilText(until: Long, now: Long = System.currentTimeMillis()): String =
        if (TimeFmt.sameDay(until, now)) TimeFmt.clock(until) else "${TimeFmt.dayHeader(until)} ${TimeFmt.clock(until)}"
}

/** Times a member out for a chosen length, with an optional reason for the audit log; the server checks who may. */
@Composable
fun TimeoutSheet(orgId: String, userId: String, name: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var chosen by remember { mutableIntStateOf(3) }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    GlassSheet(onDismiss, title = stringResource(R.string.to_title, name)) {
        Text(stringResource(R.string.to_body), style = Griip.type.body, color = Griip.colors.textSecondary)
        Spacer(Modifier.height(12.dp))
        ChipRow {
            TimeoutRules.DURATIONS.forEachIndexed { i, (label, _) -> PillChip(stringResource(label), chosen == i, { chosen = i }) }
        }
        Spacer(Modifier.height(12.dp))
        GriipTextField(reason, { reason = it.take(300) }, stringResource(R.string.to_reason))
        error?.let { Spacer(Modifier.height(10.dp)); StatusBanner(it, BannerKind.Error) }
        Spacer(Modifier.height(16.dp))
        PillButton(stringResource(R.string.to_action), {
            busy = true
            scope.launch {
                val until = System.currentTimeMillis() + TimeoutRules.DURATIONS[chosen].second
                runCatching { container.orgs.timeout(orgId, userId, until, reason) }
                    .onSuccess {
                        android.widget.Toast.makeText(ctx, ctx.getString(R.string.to_done, name), android.widget.Toast.LENGTH_SHORT).show()
                        onDismiss(); onDone()
                    }
                    .onFailure { error = it.friendly() }
                busy = false
            }
        }, Modifier.fillMaxWidth().padding(bottom = 8.dp), tone = ButtonTone.Danger, loading = busy, height = 50.dp)
    }
}
