# ADR 0003: Voice messages are encrypted attachments with two optional fields

Date: 2026-10-16. Status: accepted.

## Context

Voice messages were requested inside existing conversations. The app already has an encrypted attachment pipeline (per-file
AES-256-GCM key and nonce, ciphertext hash, resumable transfers, retry from a local copy), and a wire format frozen by
`PayloadVectorsTest`.

## Decision

- A voice message is an attachment (`audio/mp4`, AAC) whose `AttachmentRef` carries `voice: true` and `durationMs`. Both fields are
  optional and omitted for other files, so existing payloads are byte-for-byte unchanged and older clients show the clip as a file.
- No new message type, no new endpoint, no new cryptography, no server change.
- Recording uses `MediaRecorder` into the app's cache folder; playback uses one app-wide `MediaPlayer` with audio focus.
- In-app playback only for attachments marked voice **and** typed `audio/*`.

## Consequences

- Retention, quotas and size limits are the attachment ones (7 days on the server, 25 MB).
- No waveform, transcription or background recording (out of scope).
- Voice messages in threads aren't offered, matching file attachments, which also go to the main conversation.
