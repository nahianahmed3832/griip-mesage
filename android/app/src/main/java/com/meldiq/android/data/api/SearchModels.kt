package com.meldiq.android.data.api

import kotlinx.serialization.Serializable

// ───────── Universal search (servers with `search`, docs/SEARCH.md, round 7 slice C2) ─────────
// The server searches what it can read and the searcher may see; each kind is a page with a cursor for the next one.

@Serializable data class SearchRes(val query: String = "", val results: SearchResultsDto = SearchResultsDto())

@Serializable data class SearchResultsDto(
    val people: PeoplePageDto? = null,
    val channels: ChannelPageDto? = null,
    val workspaces: WorkspacePageDto? = null,
    val groups: ConversationPageDto? = null,
    val documents: DocumentPageDto? = null,
)

@Serializable data class PeoplePageDto(val items: List<UserDto> = emptyList(), val next: String? = null)
@Serializable data class ChannelPageDto(val items: List<ChannelHitDto> = emptyList(), val next: String? = null)
@Serializable data class WorkspacePageDto(val items: List<WorkspaceHitDto> = emptyList(), val next: String? = null)
@Serializable data class DocumentPageDto(val items: List<DocumentDto> = emptyList(), val next: String? = null)
@Serializable data class ConversationPageDto(val items: List<ConversationDto> = emptyList(), val next: String? = null)

/** A channel in any of your workspaces that you could open (joined, or public in your scope). */
@Serializable data class ChannelHitDto(
    val id: String,
    val orgId: String? = null,
    val orgName: String? = null,
    val name: String? = null,
    val topic: String? = null,
    val kind: String? = null,
    val scopeName: String? = null,
    val joined: Boolean = false,
    val voice: Boolean = false,
    val memberCount: Int = 0,
)

@Serializable data class WorkspaceHitDto(val id: String, val name: String, val slug: String = "", val myRole: String? = null)
