package com.meldiq.android.data.api

import kotlinx.serialization.Serializable

// ───────── Workspace documents (servers with `documents`, docs/DOCUMENTS.md, round 7 slice C4) ─────────
// Server-readable by design (the owner's decision); the app labels them as not end-to-end encrypted.

@Serializable data class DocPerson(val id: String, val name: String? = null)
@Serializable data class DocChannel(val id: String, val name: String? = null)
@Serializable data class DocMember(val userId: String, val role: String, val name: String? = null, val username: String? = null)

@Serializable data class DocumentDto(
    val id: String,
    val orgId: String,
    val title: String,
    val access: String,
    val defaultRole: String = "comment",
    val channel: DocChannel? = null,
    val role: String,
    val rev: Int,
    val archived: Boolean = false,
    val createdBy: DocPerson? = null,
    val updatedBy: DocPerson? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val content: String? = null,
    val members: List<DocMember>? = null,
    /** A search result's excerpt. */
    val snippet: String? = null,
)

@Serializable data class DocumentsRes(val documents: List<DocumentDto>)
@Serializable data class DocumentRes(val document: DocumentDto)

@Serializable data class DocMemberInput(val userId: String, val role: String)
@Serializable data class DocCreateReq(
    val title: String, val content: String, val access: String, val defaultRole: String = "comment",
    val channelId: String? = null, val members: List<DocMemberInput> = emptyList(),
)
@Serializable data class DocUpdateReq(val rev: Int, val title: String, val content: String)
@Serializable data class DocSharingReq(val access: String, val defaultRole: String, val channelId: String? = null, val members: List<DocMemberInput>)

@Serializable data class DocVersionDto(val rev: Int, val title: String, val author: DocPerson? = null, val createdAt: Long, val content: String? = null)
@Serializable data class DocVersionsRes(val versions: List<DocVersionDto>)
@Serializable data class DocVersionRes(val version: DocVersionDto)

@Serializable data class DocCommentDto(
    val id: String, val author: DocPerson? = null, val body: String, val quote: String? = null,
    val resolved: Boolean = false, val createdAt: Long, val canDelete: Boolean = false,
)
@Serializable data class DocCommentsRes(val comments: List<DocCommentDto>)
@Serializable data class DocCommentReq(val body: String, val quote: String? = null)
@Serializable data class DocResolveReq(val resolved: Boolean)

@Serializable data class DocTemplateDto(val id: String, val title: String, val content: String)
@Serializable data class DocTemplatesRes(val templates: List<DocTemplateDto>)
