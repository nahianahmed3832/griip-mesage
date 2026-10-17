package com.griipbuddy.orbit.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.griipbuddy.orbit.data.model.LinkRules
import com.griipbuddy.orbit.data.repo.LinkOpening
import com.griipbuddy.orbit.ui.components.BannerKind
import com.griipbuddy.orbit.ui.components.GlassSheet
import com.griipbuddy.orbit.ui.components.MenuRow
import com.griipbuddy.orbit.ui.components.StatusBanner
import com.griipbuddy.orbit.ui.theme.Griip

/**
 * Opening web links from messages (2026-10-17, docs/LINKS.md). "In Orbit" is a Custom Tab: the person's own browser
 * shown over Orbit, with that browser's TLS checks and safe browsing. Orbit has no embedded WebView, so pages can't
 * reach Orbit's data or code. Only http and https are ever opened.
 */
object LinkOpener {
    /** What to do with a tapped link: open it directly, or ask first (the setting says so, or it looks misleading). */
    fun needsConfirmation(url: String, pref: LinkOpening): Boolean = pref == LinkOpening.ASK || LinkRules.warning(url) != null

    fun open(context: Context, url: String, inApp: Boolean): Boolean {
        val u = LinkRules.normalize(url)?.let(Uri::parse) ?: return false
        return try {
            if (inApp) CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, u)
            else context.startActivity(Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }
}

/** The confirmation: where the link really goes, any warning, and how to open it. */
@Composable
fun LinkOpenSheet(url: String, onOpen: (inApp: Boolean) -> Unit, onCopy: () -> Unit, onDismiss: () -> Unit) {
    val c = Griip.colors
    val warning = LinkRules.warning(url)
    GlassSheet(onDismiss, title = LinkRules.domain(url) ?: "Open link") {
        Text(url, style = Griip.type.bodySmall, color = c.textSecondary, maxLines = 4)
        Spacer(Modifier.height(10.dp))
        warning?.let { StatusBanner(it, BannerKind.Warning, Modifier.fillMaxWidth(), icon = Icons.Rounded.Warning) }
        Spacer(Modifier.height(6.dp))
        MenuRow(Icons.Outlined.OpenInBrowser, "Open in Orbit", subtitle = "In your browser's secure view, over Orbit", onClick = { onOpen(true) })
        MenuRow(Icons.Outlined.OpenInNew, "Open in another app", subtitle = "Your browser or the app that handles this site", onClick = { onOpen(false) })
        MenuRow(Icons.Outlined.ContentCopy, "Copy link", onClick = onCopy)
        Spacer(Modifier.height(12.dp))
    }
}
