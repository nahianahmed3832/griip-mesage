package com.meldiq.android.ui.screens.profile

import com.meldiq.android.data.api.ConnectorDto

/**
 * Pure rules for the connector register screen (docs/CONNECTORS.md), unit-tested: the order states are shown in, their
 * words, and the counts line. The server decides each state; the app never upgrades one.
 */
object ConnectorRules {
    /** Problems first, then what needs the owner, then what works, then what is planned. */
    val STATE_ORDER = listOf("failing", "needs_configuration", "awaiting_provider", "disabled", "configured", "working", "planned")

    fun label(state: String): String = when (state) {
        "working" -> "Working"
        "configured" -> "Configured, not verified yet"
        "failing" -> "Failing"
        "needs_configuration" -> "Needs configuration"
        "awaiting_provider" -> "Awaiting provider access"
        "disabled" -> "Turned off"
        "planned" -> "Planned"
        else -> state
    }

    enum class Tone { Good, Neutral, Warning, Bad, Muted }

    fun tone(state: String): Tone = when (state) {
        "working" -> Tone.Good
        "configured" -> Tone.Neutral
        "failing" -> Tone.Bad
        "needs_configuration", "awaiting_provider" -> Tone.Warning
        else -> Tone.Muted
    }

    /** Sections in [STATE_ORDER], each sorted by name; unknown states go last. */
    fun sections(list: List<ConnectorDto>): List<Pair<String, List<ConnectorDto>>> =
        list.groupBy { it.state }
            .toList()
            .sortedBy { (state, _) -> STATE_ORDER.indexOf(state).let { if (it < 0) STATE_ORDER.size else it } }
            .map { (state, items) -> state to items.sortedBy { it.name.lowercase() } }

    /** "3 working · 2 failing · 9 need configuration" (planned ones are left out). */
    fun summary(list: List<ConnectorDto>): String {
        val counts = list.groupingBy { it.state }.eachCount()
        return STATE_ORDER.filter { it != "planned" && (counts[it] ?: 0) > 0 }
            .joinToString(" · ") { "${counts[it]} ${label(it).lowercase()}" }
            .ifEmpty { "Nothing to show" }
    }

    /** Counts as "2 workspaces · 5 connections", skipping zeros. */
    fun usage(usage: Map<String, Int>): String? =
        usage.filterValues { it > 0 }.entries.joinToString(" · ") { (k, v) -> "$v ${words(k)}" }.ifEmpty { null }

    private fun words(key: String): String = key.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase()
}
