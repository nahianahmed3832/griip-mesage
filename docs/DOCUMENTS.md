# Documents (round 7 slice C4, 2026-10-20)

Pages a workspace writes together: project briefs, meeting notes, onboarding guides, procedures, FAQs, policies.

## Encryption: the owner's decision

Documents are stored **readable by the server** so they can be searched (and, later and only on request, summarised).
They are **not end-to-end encrypted**, unlike messages, files and calls, and the app says so on the list and on every
document ("Not end-to-end encrypted"). The API marks each document `encryption: "server-readable"`. Platform admins
get no document access from being platform admins.

## Access

| Access | Who gets the default role (view / comment / edit) |
|---|---|
| Everyone in the workspace | every member who isn't a guest |
| Members of a channel | the channel's members; its managers administer the document. A private channel's document stays inside it, and follows its membership live |
| Only people added | nobody by default; **not even the workspace's owners and admins** |

People added to a document get their own role (view, comment, edit, admin) on top. The creator is an admin. Nobody
outside the workspace has access, whatever is listed: leaving or being removed from the workspace ends access at
once. Guests see only documents they're added to or whose channel they're in, and can't create documents.

Someone without a role gets **404** for the document, its versions, comments and export, and it never appears in their
list or search: no hint that it exists. The rules live in one place (`DocumentService.roleOf`) and every route goes
through it.

## Editing

Markdown (a small subset is shown: headings, lists, to-dos, quotes, bold, inline code; never HTML), up to 200,000
characters. Saving names the revision it started from: if someone saved meanwhile the server answers 409
`stale_revision` and the app says so, rather than one person silently overwriting another. There's no live
co-editing yet. Every save is kept as a version (newest 50 per document); an old version can be read and brought back
as a new revision (nothing is lost).

Comments (with an optional quoted passage) by anyone who may comment; anyone who may comment resolves or reopens
them; their author or a document admin deletes them. Archived documents are read-only (no edits or comments) until an
admin restores them; admins can delete a document for good. Export: the document as Markdown (the app shares the text
with an app the person picks; `GET /v1/documents/:id/export` serves a `.md` file).

## Search

`GET /v1/search` includes `documents`: title or text matches, title matches first, with a plain-text excerpt around
the match, only for documents the searcher may read. The app's search screen has a Documents filter.

## API

`GET /v1/documents/templates` · `GET|POST /v1/orgs/:orgId/documents` (`?archived=true`, `?channelId=`) ·
`GET|PUT|DELETE /v1/documents/:id` · `PUT /v1/documents/:id/sharing` · `POST /v1/documents/:id/archive|restore` ·
`GET /v1/documents/:id/export` · `GET /v1/documents/:id/versions[/:rev]` · `POST /v1/documents/:id/versions/:rev/restore` ·
`GET|POST /v1/documents/:id/comments` · `POST /v1/document-comments/:id/resolve` · `DELETE /v1/document-comments/:id`.
Server feature `documents`. Tables `documents`, `document_members`, `document_versions`, `document_comments`
(migration 023). Creates, sharing changes, archives and deletes are in the audit log (no titles or content).

Templates: blank, meeting notes, project brief, onboarding guide, standard operating procedure, team FAQ, policy.

## Not built yet

Live co-editing, suggestions (tracked changes), attachments inside documents, links from tasks and meetings (those
modules don't exist yet), AI summaries (slice C8, off until approved), a web view.

Tests: `server/test/documents.test.ts` (workspace, restricted and channel access including the owner left out of a
restricted document and a private channel's membership changing; guests and outsiders; versions, stale saves and
restore; archive; comments; sharing validation and leaving the workspace; export and search excerpts), `DocRulesTest`.
