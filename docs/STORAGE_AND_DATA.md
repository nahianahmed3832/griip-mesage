# What is stored where (audit, updated 2026-10-17)

Messages are end-to-end encrypted on the phones. The account's server routes them and, since 2026-10-17, also keeps
the account's history, encrypted with keys only the account's own devices (and its recovery key) can derive
(docs/MESSAGE_HISTORY.md). Phones keep their own copy too.

## On the phone (per account)
- **Encrypted database** (SQLCipher; key sealed by the Android Keystore): chats, messages, people, call history,
  settings state, and the queue of history changes to upload.
- **Keys:** identity and device private keys, sealed by the Keystore and never exported (the recovery key is shown only
  on request).
- **Decrypted copies of downloaded files:** app-private storage.
- **Caches** (thumbnails, profile photos): app cache.

## On a server

| Data | Content visible to the server | Kept |
|---|---|---|
| Accounts: username, display name, avatar colour, bio, names, birthday, phone, privacy settings | yes (published profile data, shown per the person's settings) | until the account is removed |
| Profile photos (`profile-photos/<user>-<revision>` in the file store) | yes (not end-to-end encrypted) | until replaced or removed; the old one is deleted on replace |
| Devices (public keys, certificates), sessions | public keys only | sessions expire; devices until revoked |
| Conversations, members, roles, workspaces | names and membership | while they exist; a deleted workspace is removed at once (docs/WORKSPACES.md) |
| Delivery queue (`delivery_queue`) | **ciphertext only** | until the device acknowledges it, at most 7 days |
| **Message history** (`history_items`) | **ciphertext only**, under opaque tags; counts, sizes and times are visible | until the person deletes it or the account is removed (docs/MESSAGE_HISTORY.md) |
| Attachments (`attachments/<random id>` in the file store) | **ciphertext only** | 7 days; files kept for history: `ATTACHMENT_KEEP_DAYS` (365) after the last request to keep them |
| Call records (who started, answered, connected, times) | metadata, no media | 90 days (`CALL_RECORD_RETENTION_DAYS`) |
| Call media | relayed by LiveKit, end-to-end encrypted when on | not stored |
| Audit log (security events, never message content) | metadata | 365 days (`AUDIT_RETENTION_DAYS`) |
| Workspace deletions (`org_deletions`: id, hashed name, who, when, counts) | no names | kept, so a retried deletion gets the same answer |
| Federation outbox/inbox | signed envelopes with ciphertext | until delivered or expired |
| Blocks | who blocked whom | until unblocked |

So servers keep metadata (who talks to whom and when, sizes, membership, profiles people publish) and ciphertext.
They never hold plaintext messages, files, call media or private keys. Migrations 008 and 009 only add columns and
tables. Nothing existing is deleted or rewritten.

## The file store: the server's disk or Amazon S3

Files (attachments and profile photos) go through one storage layer (`server/src/storage/`), chosen with
`STORAGE_BACKEND`. The plan, costs and AWS setup are in docs/AWS_STORAGE_PLAN.md.

- **`local` (default).** `DATA_DIR/attachments/<id>` and `DATA_DIR/profile-photos/`, exactly as before. Nothing moves
  when updating. Apps upload through the server (`POST /v1/attachments`).
- **`s3`.** A private bucket, used through the AWS SDK for JavaScript v3 (`@aws-sdk/client-s3` 3.1144.0). The server
  keeps the database: who may read what, expiry and quotas. S3 holds only bytes, under keys of the form
  `<prefix>attachments/<random id>` and `<prefix>profile-photos/<user id>-<revision>`. Keys carry no file names,
  conversation ids or senders.

### Direct uploads (S3 only)

The app sends the ciphertext straight to S3; the server never relays it.

1. **Request.** `POST /v1/attachments/uploads` with `{conversationId, size, sha256}`. The server checks:
   - authentication, membership and permission to post;
   - the size limit;
   - the uploader's quotas, with open uploads counted as if stored;
   - the number of open uploads (`DIRECT_UPLOAD_MAX_PENDING_PER_USER`).

   It then picks a 128-bit random id and records an upload session.
2. **Presigning.** The server returns a presigned PUT, valid `S3_UPLOAD_URL_TTL_SECONDS` (15 minutes). These headers are
   signed into it, so S3 refuses anything else:
   - the exact `content-length`;
   - `x-amz-checksum-sha256` of the ciphertext, so S3 rejects other bytes (`BadDigest`);
   - `if-none-match: *`, so it can never overwrite an object;
   - the SSE setting.

   Above `S3_MULTIPART_THRESHOLD` (64 MiB), it starts a multipart upload with SHA-256 part checksums instead. The app
   asks for part URLs (`POST …/parts`), each bound to its part's exact size and hash.
3. **Upload.** The app sends the bytes to S3 with exactly those headers and no Meldiq credentials.
   - **Interrupted part:** only that part is sent again.
   - **After a restart:** the app asks which parts S3 already has (`GET …/parts`) and skips them.
   - **Expired URL:** the app gets a new one (`POST …/url`).
4. **Completion.** `POST …/complete`. The server checks membership again (someone removed meanwhile can't add a file),
   completes the multipart upload after matching every part's ETag, size and hash, then reads the object's size and
   SHA-256. Only then does it record the attachment, atomically with closing the session. It's safe to repeat.
5. **Cleanup.**
   - `DELETE …` cancels an upload.
   - A session not completed within `DIRECT_UPLOAD_SESSION_SECONDS` (6 h) is abandoned. The retention job aborts its
     multipart upload, or deletes its object, retrying with backoff.
   - The bucket's lifecycle rule aborts multipart parts older than a day as a backstop.
   - `admin.js storage-orphans` lists stored objects no record refers to (a dry run; `--delete` queues them).
6. **Downloads.** After the membership check, the server answers 302 to a presigned GET valid 5 minutes
   (`S3_PRESIGNED_DOWNLOADS`, on by default with S3). The app's HTTP client follows it, drops the Meldiq token on the way
   to the other host, and keeps its Range header. Outsiders get the same 404 as for a file that doesn't exist, and no
   link.

**Older apps and federation.** Apps from before direct uploads, and servers pushing files to each other (federation),
still use the original paths. On S3 the server spools the upload, then puts it with its SHA-256; files over 64 MiB go
in 16 MiB parts, and a failed upload is aborted. Apps use direct uploads only when `server-info.features` lists
`direct-upload`, and otherwise fall back by themselves.

**Profile photos** are uploaded through the server, which re-checks them and then stores them in S3. They are served
through the server after each person's photo-privacy check, never from a public bucket.

**Errors.** An S3 outage gives a clear 503 (`storage_unavailable`): a direct upload stays open and completes once S3
answers again, and nothing is left half-recorded. The SDK retries up to four times, and file uploads re-open the file
between attempts.

**Credentials** live in the server's environment only, never in the app:

- the IAM user's key (`S3_ACCESS_KEY_ID` / `S3_SECRET_ACCESS_KEY`); or
- nothing set, in which case the SDK's default chain is used (e.g. IAM Roles Anywhere through `credential_process`).

Errors carry only S3's status and error code. Presigned URLs appear only in the answers to the uploading or downloading
member and are never logged.

**Encryption at rest.** `S3_SSE=AES256` (default; S3-managed keys) or `aws:kms` with `S3_KMS_KEY_ID`, on top of Meldiq's
end-to-end encryption.

**Diagnostics.** `admin.js storage-check` reports the backend, bucket, region and whether the store answers, never
secrets.

**Switching without moving files.** Turning S3 on doesn't copy files already on the disk, and going back doesn't copy
files from S3. Set `STORAGE_READ_FALLBACK` to the previous backend:

- `local` when moving to S3;
- `s3` when going back to the disk.

New files go to `STORAGE_BACKEND` only. Files in the other store are still served (streamed by the server, or a
presigned link when they're in S3) until they expire or are replaced, and deleted from wherever they are. Nothing is
removed from either store because of the switch.

### Tests

The suites below use a fake S3 and no AWS account. `test/s3_live.test.ts` runs the same checks against a real, isolated
test bucket, and only when asked to (results and the command are in docs/AWS_STORAGE_PLAN.md §6).

- **`test/storage_s3.test.ts`:**
  - AWS's published SigV4 examples (for the fake's own checker);
  - whole and ranged reads;
  - multipart assembly and retries;
  - no-overwrite and hash checks;
  - presigned links;
  - the whole server on S3: attachments, expiry deletion, profile photos, an outage, and startup checks.
- **`test/direct_uploads.test.ts`:**
  - authorization, and cross-user and cross-conversation denial;
  - wrong bytes, a wrong size, a missing no-overwrite header, and an overwrite all refused;
  - size, quota and open-upload limits;
  - multipart with a dropped connection, resume and early completion;
  - expiry and cleanup;
  - removal from the conversation mid-upload;
  - an outage, recovery and the original path on S3;
  - URL renewal;
  - AES-256-GCM ciphertext stored byte-for-byte and decrypting after download;
  - disk servers not offering direct uploads.
- **The fake S3** (`test/support/fakeS3.ts`) checks the SDK's real signatures (header-signed and presigned), checksums
  and conditional writes.
- **App side:** `AttachmentUploaderTest`.
