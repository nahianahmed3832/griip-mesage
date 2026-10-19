# Environments and endpoints (2026-10-19)

Where Meldiq's addresses are configured, per environment, so the new Meldiq domain is a configuration change in a few
named places, not a code change. **The Meldiq domain isn't bought yet.** Production keeps today's hostnames
(`griipbuddy.cloud`) until the domain, its DNS, TLS and proxy are live and the switch is approved
([infrastructure plan](MELDIQ_INFRASTRUCTURE_PLAN_2026-10-19.md)). Nothing here points at a hostname that doesn't exist.

The names follow the project's existing conventions instead of adding a parallel set: the server's environment is
`APP_ENV` (what a new `MELDIQ_ENVIRONMENT` would have been), its public API address `PUBLIC_API_URL`
(`MELDIQ_API_BASE_URL`); there is no web client in this repository, so no web base URL is configured here (Orbit Web is
a separate repository).

## Environments

| | development | staging | production |
|---|---|---|---|
| Server `APP_ENV` | `development` (default) | `staging` | `production` (refuses file mail, weak hashing, http sign-in URLs, the sandbox) |
| Android builds | debug builds (default) | `-Pmeldiq.environment=staging` (debug or release) | release builds (default) |
| Android default server | `http://localhost:8080`: this computer, through `adb reverse tcp:8080 tcp:8080` | none: `meldiq.apiBaseUrl.staging` must be set | `https://msg.griipbuddy.cloud` today; the Meldiq API hostname after the approved switch |
| Plain HTTP | allowed (debug network config) | refused for the default address (Gradle check) | refused (Gradle check and the release network config) |

The Android app's default server is only where a new profile starts: people can still choose another server
(Welcome › server, or Settings › Accounts), and every signed-in profile keeps the server it signed in to.

## Android (android/app/build.gradle.kts)

| Setting (`-P…` or `android/local.properties`) | Meaning |
|---|---|
| `meldiq.environment` | `development`, `staging` or `production`. Debug builds: `development` unless set; release builds: `production` unless `staging` |
| `meldiq.apiBaseUrl.development` | Development address (default `http://localhost:8080`). The older `griip.serverUrl` key still sets it |
| `meldiq.apiBaseUrl.staging` | Staging address (no default; https only) |
| `meldiq.apiBaseUrl.production` | Production address (default today `https://msg.griipbuddy.cloud`; https only) |
| `meldiq.sideBySide=true` | Debug build as `com.meldiq.android.test` ("Meldiq Test") beside an installed Meldiq release |

They become `BuildConfig.DEFAULT_SERVER_URL` and `BuildConfig.MELDIQ_ENVIRONMENT`. The calls address is never built
in: the server sends it with each call token. App links are `meldiq://invite/CODE` (and `griip://invite/CODE` from older
invitations) and `meldiq://integrations` (sign-in return).

## Server (deploy/.env.example)

| Endpoint | Configured by | Path on the API host |
|---|---|---|
| REST API | `PUBLIC_API_URL` (the address apps use; not read by the API itself except as below) | `/v1/…` |
| Real-time socket | same host as the API | `wss://<api>/v1/ws` |
| Health check | same host | `/healthz` (also the container health check) |
| File uploads and downloads | same host; with `STORAGE_BACKEND=s3` the API hands out short-lived presigned S3 URLs | `/v1/attachments…` |
| Calls (signalling) | `LIVEKIT_PUBLIC_URL` (`wss://…`), sent to apps with each call token | LiveKit's own host |
| LiveKit → API webhook | `LIVEKIT_API_URL` / the LiveKit config | `/v1/livekit/webhook` |
| App sign-ins (OAuth callback) | `INTEGRATIONS_PUBLIC_URL`, else `PUBLIC_API_URL` | `/v1/integrations/oauth/callback` (+ `/oauth/begin/:flow`) |
| Provider webhooks | same | `/v1/integrations/webhooks/<connector>` |
| Incoming webhooks | same | `/v1/integrations/hooks/<id>` |
| Back to the app after a sign-in | `INTEGRATIONS_APP_RETURN_URL` (default `meldiq://integrations`) | — |
| Back to a web client after a sign-in | `INTEGRATIONS_WEB_RETURN_URLS` (none by default; https hash-route addresses, matched by exact origin) | — |
| Push wake-ups (ntfy) | `PUSH_UNIFIEDPUSH_HOSTS` (allowed push hosts) and ntfy's `base-url` (`deploy/ntfy/server.yml`) | ntfy's host |
| Federation | `FEDERATION_DOMAIN`, `FEDERATION_PUBLIC_URL` (not deployed) | `/.well-known/griip-server` (protocol name kept), `/fed/v1/…`, `/v1/federation/…` |
| Mail sender | `MAIL_FROM` (an SES-verified address or domain) | — |

**CORS:** the API sends no CORS headers: its clients are the Android app and server-to-server callers, not browsers.
A browser client (Orbit Web, a separate repository) would need an explicit allowed-origin list added with it.
