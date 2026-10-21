# Infrastructure register (formerly the connector register)

The outside services Meldiq itself runs on, with their real state. Since the community rebuild
(`COMMUNITY_REBUILD_2026-10-21.md`) there are **no optional product connectors**: the app directory, every OAuth
connector (Google, Microsoft, GitHub, GitLab, Jira, Linear, Notion, Figma, Asana, Zoom, the sandbox), incoming and
outgoing webhooks, API keys, the Unified Inbox's Meta/TikTok channels and the AI providers were removed from the server
and the app. The earlier register, with its OAuth app registrations and owner actions, is in this file's Git history
(before `2d71db7`).

The live answer is `GET /v1/admin/connectors` (platform admins only; app: Settings › Service health › Connectors).

| Id | Service | Needs |
|---|---|---|
| `email` | Account email (SMTP or Amazon SES) | `MAIL_TRANSPORT` and its settings |
| `storage` | Encrypted files: this server's disk or Amazon S3 | `STORAGE_BACKEND` (and the S3 settings) |
| `push-unifiedpush` | Wake-ups through UnifiedPush (ntfy) | `PUSH_UNIFIEDPUSH_HOSTS` |
| `push-fcm` | Wake-ups through Firebase Cloud Messaging | `FCM_PROJECT_ID`, `FCM_SERVICE_ACCOUNT_FILE` |
| `calls` | LiveKit for calls and voice channels | `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, `LIVEKIT_PUBLIC_URL` |
| `mongodb` | MongoDB for the accounts and preferences domains | `MONGODB_URI` |

## How a state is decided

| State | Meaning |
|---|---|
| `working` | A measured success: a real call or a check. |
| `configured` | Settings are present but nothing has been measured yet. Never shown as working. |
| `failing` | The latest outcome is a failure from the last 24 hours. Only the error kind is shown. |
| `needs_configuration` | Settings are missing. The register lists their **names** (never values). |

`POST /v1/admin/connectors/:id/check` runs only checks that send nothing to anyone and cost nothing: an SMTP sign-in,
listing the store, an FCM access token, a MongoDB ping. Each check is audited (`connector.check`).

## After deploying this version

The removed settings are no longer read: `INTEGRATIONS_*`, the providers' `*_OAUTH_*` and `GITHUB_APP_*`,
`AI_*`, `META_*`, `INSTAGRAM_*`, `TIKTOK_*`, `INBOX_*`. A server whose environment still has them starts normally;
delete them and their secret files once this version is live, and revoke the OAuth apps registered for them at each
provider (GitHub App `meldiq`, Notion, Atlassian, Linear, Google OAuth client, Meta app). Rows those modules stored,
including sealed provider tokens, are reported by `node dist/admin.js retired-data` and deleted by
`retired-data --purge --yes` after a backup. A `MONGODB_DOMAINS` that still lists `integrations` is ignored; its
collections in MongoDB are not touched and can be dropped by hand after the purge.

Code: `server/src/ops/connectors.ts`, `server/test/ops_connectors.test.ts`; Android `ui/screens/profile/ConnectorsScreen.kt`.
