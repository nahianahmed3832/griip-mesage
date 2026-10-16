# Voice messages

Added 2026-10-16. Voice messages are recorded on the phone, encrypted on the phone and sent as an ordinary
encrypted attachment in the conversation. The server stores and forwards ciphertext only, exactly as for files.

## What the user sees

| Step | Behaviour |
|---|---|
| Composer | When the text field is empty (and you're not editing or in a thread), the send button becomes a microphone, labelled "Record a voice message" |
| Permission | The first tap asks Android for microphone access (the system dialog). If it's refused, a banner explains how to allow it in Android Settings. Nothing records without it |
| Recording | The composer turns into a recording bar: a delete button ("Cancel recording"), a pulsing red dot with a live timer, and a red stop button ("Stop recording"). The limit is 10 minutes; at the limit recording stops and the clip is kept |
| Preview | After stopping: delete ("Delete voice message"), play/pause with a seek bar and the length, and send ("Send voice message"). Nothing is sent until you tap send |
| Too short | A clip under one second is discarded with "Too short to send…" |
| In the chat | A voice bubble shows a play/pause button, a seek bar and the length (`0:07`), then `0:03 / 0:07` while playing. The first play downloads and decrypts the clip (a spinner shows progress) |
| Chat list and notifications | "🎤 Voice message (0:07)" |
| Accessibility | Buttons have spoken labels ("Play voice message, 7 seconds", "Pause voice message"); the seek bar announces "3 seconds of 7 seconds"; the recording bar announces the elapsed time |

Not included, on purpose: transcription, waveforms, background recording, playback speed, voice messages inside threads
(they would go to the main conversation, like files do today), and forwarding.

## How it works

```
 MediaRecorder (AAC, mono, 44.1 kHz, 48 kbit/s, MP4)        VoicePlayer (MediaPlayer, one clip app-wide)
        │  cacheDir/voice/rec-<uuid>.m4a (app-private)               ▲
        ▼                                                          │ decrypted copy in filesDir/attachments*
 VoiceDraftController ── Idle → Recording → Ready ── send ─▶ MessageRepository.sendAttachment(…, voiceDurationMs)
                                                            │  copy → encrypt (AES-256-GCM, random key+nonce)
                                                            │  → upload ciphertext → send envelope with AttachmentRef
                                                            ▼
                                     AttachmentRef { id, name, mime "audio/mp4", size, key, nonce, sha256,
                                                     voice: true, durationMs }   (inside griip-envelope-v1)
```

- **Code:** `voice/VoiceNotes.kt` (format helpers, `VoiceDraftController`, `VoiceTemp`), `voice/VoiceAndroid.kt`
  (`AndroidAudioRecorder`, `VoicePlayer`), `ui/screens/conversation/VoiceViews.kt` (recording bar, preview bar, bubble player),
  `ConversationViewModel` (start/stop/cancel/send, play/seek), `MessageRepository.sendAttachment` (the `voiceDurationMs` parameter).
- **Wire format:** two optional fields on the existing attachment reference, `voice` and `durationMs`. They're omitted for
  ordinary files, so file attachments keep their exact JSON (checked by `PayloadVectorsTest`, which now also has a
  "voice message" vector). Older app versions ignore unknown fields and show a voice message as an audio file they can open.
- **Encryption:** unchanged. The clip goes through the same `AttachmentCrypto.encryptFile`, upload retry, resumable download,
  hash check and GCM verification as any file (`SECURITY.md` §3). No new cryptography.
- **Playback safety:** only an attachment marked `voice` **and** typed `audio/*` plays in the app (`VoiceNote.isVoice`);
  anything else stays a file you open explicitly. A peer's `durationMs` is display-only and clamped.
- **Temporary files:** recordings live in `cacheDir/voice/` (app-private). Cancel, delete, sending and leaving the
  conversation delete them; files older than 6 hours (left by a crash) are removed the next time a conversation opens.
  After sending, the repository keeps its own copy in the attachment store (the retry source), exactly like a file.
- **Interruptions:** the app leaving the foreground (screen `ON_STOP`), an incoming or outgoing call, a microphone error and
  the length limit all stop recording; whatever was captured stays as a draft to review. Playback pauses on the same events and
  when another app takes audio focus. You can't record or play during a call.
- **Size:** about 360 KB per minute; the 10-minute limit is about 3.6 MB, well under the 25 MB attachment limit.
- **Retention:** voice messages follow the attachment rules (`RETENTION.md`): the server keeps the ciphertext at most 7 days.
- **Across servers (federation):** the sender's server pushes the clip's ciphertext to the recipient's server, which serves it to the
  recipient; the key stays in the E2EE message (`FEDERATION_PROTOCOL.md` §6.5). Verified: vitest (download, hash, decrypt on server B) and
  the emulator on server B playing a voice message sent from server A (`TESTING.md` §8).

## Tests

| Test | What it proves |
|---|---|
| `VoiceNotesTest` (3, JVM) | only audio marked as voice plays; labels, clock and spoken lengths; stale-file cleanup keeps fresh files |
| `VoiceDraftControllerTest` (6, JVM, fake recorder) | record → stop → preview → take; short/empty clips discarded; cancel deletes while recording and after; microphone failures reported, never hidden; the length limit stops into a preview; double taps don't start a second recording; closing cleans up |
| `PayloadVectorsTest` | the voice vector round-trips; ordinary attachments carry no voice fields |
| Emulator + local instance (2026-10-16) | a voice message from Griip Bot (`GRIIP_BOT_SEND_VOICE`) showed in the chat list as "🎤 Voice message (0:04)", downloaded, decrypted and played (`0:02 / 0:04`, back to play at the end, audio focus released), and seeking worked |
| Phone | see `TESTING.md` §5f |

The bot can send a voice message (`GRIIP_BOT_SEND_VOICE=<file.m4a> GRIIP_BOT_VOICE_MS=<ms>` with `chat`) and verifies any it
receives (hash, decryption, and that `size` matches the decrypted length).
