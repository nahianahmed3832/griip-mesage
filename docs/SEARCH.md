# Search (round 7 slice C2, 2026-10-20)

One search screen across Meldiq. What can be searched where follows from end-to-end encryption: the server can only
search what it can read, and the brief rules out any master key.

| Kind | Where it's searched | Rule |
|---|---|---|
| People | server, `GET /v1/search` | others in a workspace you share (not as a guest), by name or username; anyone by exact @username who lets everyone find them that way; never someone who blocked you |
| Channels | server | every workspace you're in: joined channels, and public ones in your scope (you can join from the result); a private channel only if you're in it |
| Workspaces | server | the ones you belong to |
| Groups | server | group conversations you're in, by name |
| Conversations | phone | your conversation list, by title |
| Messages | phone | text in this phone's (decrypted) history |
| Pinned messages | phone | pinned messages whose text matches |
| Files | phone | file names in this phone's history |
| Reminders | not searched | sealed on the server; listed in Reminders |

## API

`GET /v1/search?q=<1–60 chars>&types=people,channels,workspaces,groups&orgId=<optional>&limit=<1–50, default 10>&cursor=<optional>`

- `types`: comma-separated; all four when absent. Unknown kinds are ignored.
- `orgId`: only that workspace's people and channels (workspaces and groups are then empty); 404 for a workspace you're
  not in.
- Each kind comes back as `{ items, next }`. To page, repeat the query with `types` set to that one kind and
  `cursor=next`. A cursor is an offset into the ranked list; up to 200 candidates are ranked per kind.
- Ranking: exact name, then name starts with the query, then a word starts with it, then contains it; for channels a
  topic-only match ranks below any name match; ties by name.
- Access is checked while reading, with the same rule as each resource (`Authz.canViewChannel` for channels, the
  people-search privacy rules for people), not by filtering a full list in the app.
- Advertised as the `search` server feature. Older apps keep using `/v1/users/search` and the workspace channel list.
- Queries aren't logged (request logs carry the path without being stored for search) and nothing is indexed: the
  server queries its own tables.

## App

Settings-free: the screen debounces typing by 250 ms, offers filters (All, Messages, Files, Pinned, People,
Channels, Workspaces), shows five of each kind under All and a page with "Show more" under a filter. Recent searches
(the last six you opened a result from) stay on this phone only. On a server without `search` it falls back to the
directory and the selected workspace's channels.

## Not searched yet

Documents, workflows and connector content (later slices: documents are server-readable by the owner's decision, so
they will join the server search); messages in conversations whose history isn't on this phone.

Tests: `server/test/search.test.ts` (ranking, opt-in and private channels, other workspaces, people privacy and blocks,
guests, workspaces and groups, paging, workspace filter), `SearchRulesTest`.
