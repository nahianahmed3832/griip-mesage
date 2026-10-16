# Troubleshooting

Symptom → likely cause → what to check. Commands assume the self-hosted layout in `SELF_HOSTING.md`
(`/opt/griip-internal-messaging`, Compose project `griip-internal-messaging`).

## Server

| Symptom | Likely cause | Check |
|---|---|---|
| `docker compose up` fails at build | missing `server/` next to `compose.yaml` | run `deploy/scripts/bundle.sh <dir>`; `compose.yaml` builds `./server` |
| API container restarts | bad `.env` value (e.g. `LIVEKIT_API_SECRET` under 32 characters, unknown `REGISTRATION_MODE`) | `bash scripts/validate-env.sh`; `docker compose -p griip-internal-messaging -f compose.yaml logs api` |
| `validate-env.sh`: "rendered with a different API key" | `.env` edited after `gen-config.sh` | `sh scripts/gen-config.sh`, then restart `livekit` |
| A shell error mentioning a word from `INSTANCE_NAME` | unquoted value with spaces | `INSTANCE_NAME="Example Community"` |
| `/healthz` fails | API down or database unreadable | `docker compose -p griip-internal-messaging -f compose.yaml ps`; `GRIIP_DIR=… bash scripts/verify.sh` |
| `/v1/server-info` says `calling: false` | LiveKit variables missing in `.env` | set `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, `LIVEKIT_PUBLIC_URL`; restart `api` |
| Sign-up says an invitation is needed | `REGISTRATION_MODE=invite` (default) | `docker compose -p griip-internal-messaging -f compose.yaml exec api node dist/admin.js reg-invite 1 7` |
| `restore.sh` refuses a snapshot | integrity check failed, not a Griip database, or a newer schema than the server | read the `verify-backup` output; upgrade the server first for a newer schema |

## App: server address

| Message | Meaning |
|---|---|
| "Can't reach the server. Check your connection." | no network, wrong address or port, server down, or `https://` to a server that only speaks `http` (a bare name becomes `https://`) |
| "That address answered, but not like a Griip Message server." | wrong host or path, or the proxy serves something else there |
| "This server needs a newer version of Griip Message." | server `minClientApi` is newer than the app; update the app |
| "This server is too old for this version of Griip Message." | server `api` is older than the app needs; update the server |
| "This app version connects to servers over HTTPS only" | `http://` in a release build; use HTTPS (debug builds accept `http` with a warning) |
| "This server is already on this phone." | use **Switch** instead of adding it twice |

## App: calls

| What you see | Likely cause | What to do |
|---|---|---|
| Stays on "Calling…" | the other phone hasn't shown the ring: app closed, no connection, or Android stopped it (no push) | they open the app; a ring still under 45 s old appears when it reconnects. "Stay connected in background" (Settings) keeps the app reachable |
| "Ringing…" but nobody answers | their phone is ringing | after 45 s the call ends as missed |
| "Busy" | they're in another call | |
| "Calling isn't set up on this server." | the server has no LiveKit configured | `SELF_HOSTING.md` §7 |
| "Couldn't get this call's encryption key, so the call wasn't joined…" | the per-call key didn't arrive in about 10 s | ask the caller to call again; check both phones are connected. The app never joins without the key while call encryption is on |
| "The call's media server didn't answer in time…" | LiveKit unreachable: UDP 7882 and TCP 7881 blocked, wrong `LIVEKIT_NODE_IP`, or the `wss://` name not proxied | check the firewall ports and `livekit.yaml`; try another network |
| "Connecting media…" for a long time, then works | UDP blocked, fell back to TCP | the note "Connected over TCP" confirms it; open UDP 7882 for better quality |
| "Reconnecting media…" | no media arrived for about 4 s (network change, tunnel) | usually recovers; otherwise hang up and call again |
| "Verifying encryption…" never turns into "End-to-end encrypted" | no remote frame decrypted with our key yet: the other side hasn't published, or it uses a different key | if media plays but this persists, treat the call as unverified. A wrong key can't decrypt; the app can't always report it (`CALLING_AND_E2EE.md` §4) |
| "Encryption problem" | missing key, encryption or decryption errors | hang up and call again |
| "Couldn't start the microphone/camera…" | permission denied or another app holds it | Android Settings → Apps → Griip Message → Permissions |

Call logs on the phone (no tokens, keys or addresses): `adb logcat -s CallDiag`. Room events and periodic media summaries are logged in every build; the join time and media-path lines only in debug builds.

## App: voice messages

| What you see | Cause |
|---|---|
| Microphone button missing | text in the composer, editing a message, or inside a thread (voice messages go to the main conversation only) |
| "…needs microphone access…" | permission denied; allow it in Android Settings |
| "Couldn't record from the microphone…" | a call or another app is using it |
| "Too short to send…" | under one second |
| "You can't record a voice message during a call." / "Voice messages can't play during a call." | by design |
| A voice message shows as a file | sent by an older app version, or not typed `audio/*` (only marked audio plays in the app) |
| Download spinner then an error | the server no longer has the file (kept ≤ 7 days) or the network failed; tap play again |

## Federation

| What you see | Likely cause | Check |
|---|---|---|
| "No one with that address" | wrong name or server, or that person's server answers 404 for privacy (same answer either way) | ask them for their exact address (Settings → Account) |
| "Not enough directory nodes answered…" (503) | fewer than the quorum of core nodes reachable, and the server isn't cached yet | `fed-status`; core nodes' `/core/v1/info` |
| "Directory nodes disagree…" (409) | core nodes return different servers for the domain | contact the network operators; nothing is routed until resolved |
| "That server doesn't accept contact from this server" | the other server blocks yours or uses an allowlist | their operator's policy |
| "That server was decommissioned or revoked" | retired for good | the person needs a new account elsewhere |
| Messages stay "Sent", banner "isn't reachable" | the other server is down or unreachable | `fed-status` shows queued items and attempts; they're retried with backoff for up to 7 days |
| Sends fail with "isn't keeping up" (503) | outbox to that server is full (`FEDERATION_OUTBOX_MAX_PER_PEER`) | the other server is down; items expire after 7 days |
| Server not `active` on core nodes | endpoint challenge failing: wrong `FEDERATION_PUBLIC_URL`, `/.well-known/griip-server` not proxied, http in production, domain bound to another server | core node logs (`audit` table), `curl https://<domain>/.well-known/griip-server` |
| No call buttons with someone on another server | your server has no LiveKit (calls are hosted by the caller's server) | `SELF_HOSTING.md` §7 |

## App: server profiles

| Situation | Explanation |
|---|---|
| No notifications from my other server | only the active profile is connected; switch to it |
| My contacts don't appear on the new server | accounts are per server; profiles never share contacts. To reach people on another federated server you don't need a profile there: use their `@name:server` address |
| Can't remove my first account | the first profile can only be erased (Security & recovery), never silently removed |
