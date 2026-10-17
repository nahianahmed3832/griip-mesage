# Workspaces

Workspaces (organizations: branches, departments, teams, channels, invitations) are reached from **Contacts ›
Workspace and channels**. They are not a main tab: Orbit's main tabs are Chats, Calls, Contacts and Settings. Everything
a workspace already supported is unchanged.

## Deleting a workspace (2026-10-17)

Until this change, the Delete Workspace sheet was a confirmation that deleted nothing. Deletion is now real.

**Who.** Only an **owner** sees **Manage › Danger zone › Delete workspace**. Admins and members don't. The server checks
the role again (403 otherwise) and answers 404 to someone who isn't a member.

**Confirmation.** The owner types the workspace's exact name (case matters; surrounding spaces don't), and the button
stays disabled until it matches. The server compares the name again (400 `confirm_mismatch`). The sheet says what will
be deleted and what won't.

**Old servers.** If the server is older than this feature (no `org-delete` in `server-info`), the sheet says so and the
button stays disabled.

**What happens** (`DELETE /v1/orgs/:id` with `{confirmName, requestId}`), in one immediate SQLite transaction:

1. Inside the write lock, the server checks again that the workspace exists and the caller is still an owner, so two
   owners deleting at once produce exactly one deletion. The other gets 404.
2. **Removed:**
   - its memberships, branches (and their members), departments, teams (and members) and invitations, so invite codes
     stop working;
   - its channels: their members, calls, call participants and file records;
   - queued messages and idempotency records for those channels, which have no foreign key.
3. **Queued for deletion:** the channels' stored files, from the disk or S3, by the retention job (`pending_blob_deletes`).
4. **Recorded:** a row in `org_deletions` (hashed name, who, when, counts, `requestId`) and an `org.delete` audit entry
   without names or content.
5. **Retries:** a retried request with the same `requestId` from the same person gets the same answer. Anything else
   afterwards gets 404.

**Afterwards:**

- **Calls.** Live calls in its channels are closed in LiveKit.
- **Other devices.** Every member's devices are told: `conv.updated` for each channel (older apps then see 404 and show
  the channel as left) and `org.updated` / `org.deleted`. Newer apps mark the channels as left, forget the workspace's
  cached details, and deselect it.
- **Untouched:** user accounts, their direct messages and groups, other workspaces, and people's encrypted message
  history (each person's own data).
- **Copies on phones** stay on those phones, shown as a channel they've left.
- **Retention:** channel files are deleted from the server immediately, whatever `ATTACHMENT_KEEP_DAYS` would have kept.

**Federation.** Channels are local to a server (people on other servers can't join a workspace), so nothing needs to be
sent to other servers.

**Tests:**

- `server/test/org_delete.test.ts`:
  - admins, members and outsiders are refused;
  - the exact name is required;
  - what is removed and what stays;
  - files are deleted and devices notified;
  - the audit entry;
  - retries with the same and a different `requestId`;
  - two owners deleting at once;
  - the name can be reused afterwards.
- `DeleteWorkspaceRulesTest`: owner only, exact name, feature check, and every failure message saying nothing was
  deleted.

**On the emulator (local server, a throwaway workspace).** A wrongly-cased name left the workspace in place; the exact
name deleted it (server row gone, `org_deletions` and audit recorded), and the app showed "No workspace yet". Never run
this against a real workspace.
