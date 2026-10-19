package com.meldiq.android.ui.screens.workspace.apps

import com.meldiq.android.data.api.AppSummaryDto
import com.meldiq.android.data.api.ResourceDto
import com.meldiq.android.data.model.LinkPreview

/**
 * Pure rules for the app directory and sharing from apps (docs/INTEGRATIONS.md). The server decides what each person
 * may do (each app's `actions`); this only orders and presents it. Unit-tested in `AppRulesTest`.
 */
object AppRules {
    val CATEGORIES = listOf("files", "docs", "calendar", "communication", "projects", "engineering", "crm", "automation")

    /** The one action a card shows, most useful first; null when there is nothing this person can do. */
    fun primaryAction(actions: List<String>): String? =
        listOf("approve", "install", "request", "reconnect", "connect", "configure").firstOrNull { it in actions }

    fun filter(apps: List<AppSummaryDto>, query: String, category: String?): List<AppSummaryDto> {
        val q = query.trim().lowercase()
        return apps.filter { a ->
            (category == null || a.category == category) &&
                (q.isEmpty() || a.name.lowercase().contains(q) || a.summary.lowercase().contains(q) || a.publisher.lowercase().contains(q))
        }.sortedWith(compareBy<AppSummaryDto>({ stateRank(it.state) }, { !it.featured }, { it.name.lowercase() }))
    }

    /** Usable apps first, then installable, then the rest; "coming soon" last. */
    fun stateRank(state: String): Int = when (state) {
        "connected", "error" -> 0
        "installed" -> 1
        "approval_required" -> 2
        "available" -> 3
        "disabled", "blocked" -> 4
        "needs_configuration" -> 5
        else -> 6
    }

    fun installed(apps: List<AppSummaryDto>) = apps.filter { it.installState == "installed" || it.installState == "disabled" }
    fun requests(apps: List<AppSummaryDto>) = apps.filter { it.state == "approval_required" }
    fun mine(apps: List<AppSummaryDto>) = apps.filter { it.myAccount != null }

    /** Apps this person can share from right now: connected, with a search or browse capability granted. */
    fun shareable(apps: List<AppSummaryDto>) = apps.filter { a ->
        a.state == "connected" && a.capabilities.any { it.granted && it.accountType == "member" && it.kind == "read" }
    }

    /**
     * The message for sharing [r] from [appName]: its name and link as text (so every app version shows something), and a
     * link card with the provider's metadata. Only https links get a card (the message rules refuse anything else).
     * Null when the item has no link to share.
     */
    fun linkMessage(r: ResourceDto, appName: String, subtitle: String): Pair<String, LinkPreview?>? {
        val url = r.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
        val text = "${r.title.take(200)}\n$url"
        val card = if (url.startsWith("https://")) LinkPreview(url, r.title.take(200), subtitle.take(300).ifBlank { null }, appName.take(80)) else null
        return text to card
    }

    /** "Spreadsheet · Pat · 2.1 MB" for a resource row (the parts that exist). */
    fun subtitle(r: ResourceDto, kindLabel: String, size: (Long) -> String, time: (Long) -> String): String =
        listOfNotNull(
            kindLabel,
            r.status,
            r.detail,
            r.owner,
            r.container,
            r.size?.takeIf { it > 0 }?.let(size),
            (r.startAt ?: r.modifiedAt)?.let(time),
        ).distinct().joinToString(" · ")

    /** How a sign-in flow ended, for the screen: still waiting, connected (perhaps partly), or a failure with its reason. */
    sealed interface FlowOutcome {
        data object Waiting : FlowOutcome
        /** The provider said yes; the app finishes it when the browser hands back ("Return to Meldiq"). */
        data object Approved : FlowOutcome
        data object Connected : FlowOutcome
        /** Connected, but the person allowed less than asked: [note] says what won't work. */
        data class ConnectedPartly(val note: String) : FlowOutcome
        data object Denied : FlowOutcome
        data object Expired : FlowOutcome
        data class Failed(val reason: String) : FlowOutcome
    }

    fun outcome(status: String, error: String?, note: String? = null): FlowOutcome = when (status) {
        "connected" -> if (note.isNullOrBlank()) FlowOutcome.Connected else FlowOutcome.ConnectedPartly(note)
        "approved" -> FlowOutcome.Approved
        "denied" -> FlowOutcome.Denied
        "expired" -> FlowOutcome.Expired
        "failed" -> FlowOutcome.Failed(error ?: "")
        else -> FlowOutcome.Waiting
    }

    /** Still worth asking the server again (the browser may not have handed back yet). */
    fun stillOpen(o: FlowOutcome): Boolean = o is FlowOutcome.Waiting || o is FlowOutcome.Approved

    /**
     * Links worth asking the server about (docs/INTEGRATIONS.md › Previews): the hosts Meldiq's connectors recognise.
     * Anything else is never sent to the server, so ordinary links stay private to the conversation.
     */
    fun mightUnfurl(url: String): Boolean {
        if (!url.startsWith("https://")) return false
        val host = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host in UNFURL_HOSTS || UNFURL_SUFFIXES.any { host.endsWith(it) }
    }

    private val UNFURL_HOSTS = setOf(
        "docs.google.com", "drive.google.com", "calendar.google.com", "www.google.com", "github.com", "notion.so", "www.notion.so",
        "linear.app", "1drv.ms", "onedrive.live.com", "sandbox.meldiq.invalid",
    )
    private val UNFURL_SUFFIXES = listOf(".atlassian.net", ".sharepoint.com", ".notion.site")

    /** Only https begin links (or the local test server's http) are opened in the browser. */
    fun safeBeginUrl(url: String, server: String): Boolean {
        if (url.startsWith("https://")) return true
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
        val serverHost = runCatching { java.net.URI(server).host }.getOrNull()
        return url.startsWith("http://") && host == serverHost && (host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2")
    }
}
