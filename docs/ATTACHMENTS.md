# Attachments: several at once, quality, and file safety

Added 2026-10-17. Up to 10 photos, videos or files can be sent as one message (an album), after the phone checks each
file. Photos default to Standard quality; videos and other files are sent as they are unless compression is chosen.
Files can be up to **1 GB** each (Orbit 2.0) where the server allows it: the app shows and enforces the server's own
limit from `/v1/server-info` (older servers: 25 MB), re-read every 10 minutes so a raised limit applies without a restart.

> **UI-003 (2026-10-18), the 283.8 MB video that failed twice:** production advertises `attachmentMaxBytes` 26214400
> (25 MiB; read from the public `/v1/server-info`, API 0.2.0), so "Original" was refused before upload and "Compress
> (1080p)" (about 34 MB for 56 s at 5 Mbit/s) failed only after a long compression. The fix on the server is the 0.4.0
> deploy with the limit raised (owner's steps in `ORBIT_UI003_CALLING_ATTACHMENTS_2026-10-18.md` §3). The app now warns
> before compressing when the estimate is over the limit and names the quality that fits, says why a compression failed,
> and no longer clips the reason under a failed bubble. **Owner's decision the same day: 1 GB per file and no other
> upload limits** (no daily, stored, total, kept or new-account quotas; `docs/ABUSE_CONTROLS.md`).

## Sending

1. **Pick.** The attach panel (UI-003, `AttachPanel`) has six tiles: "Photos and videos" opens the Android photo picker
   for up to 10 items; "Camera" and "Record video" use the phone's camera app (the camera permission is asked then);
   "Files" opens the document picker (the first 10 are kept, with a note); then contact and location. The panel shows
   this server's limit, and on a 25 MB server says long videos only fit compressed. No storage permission is needed:
   the phone's photos aren't shown inline in the panel, which would need READ_MEDIA_IMAGES/VIDEO (Google Play restricts
   it to apps whose core use needs broad photo access); that's an owner decision, not done.
2. **Preview** (`BatchComposer`). Every file with a remove button, a "+" tile to add more, the quality (when there are
   photos or videos), one caption, and what the checks found. Files that can't be sent are marked and left out; the
   button says how many will go. Nothing is uploaded before Send.
3. **Queue** (`MessageRepository.sendBatch`). The message appears at once with all its files. In the background (it
   carries on when you leave the chat) each file is prepared, encrypted on the phone and uploaded, at most two at a
   time (the server's per-person limit), with progress on the bubble. The message itself is sent when every file is
   uploaded. One message carries all the files; older Orbit versions show them one under the other.
4. **Failure, retry and cancel.** A failure marks the message failed with the reason. Tap to retry: files already
   uploaded aren't uploaded again. Long-press › Cancel upload stops a message that hasn't been sent yet and removes it from
   the phone. If the app was closed before a file was prepared, the original is no longer reachable and the message says
   so (pick the files again).

A swipe-to-reply target applies to the album and is cleared once the album is queued.

## Quality

| | Photos | Videos |
|---|---|---|
| Standard | longest edge ≤ 1600 px, JPEG 82 (photo default) | "Compress (720p)": short side ≤ 720 px, H.264 ~2 Mbit/s, AAC |
| HD | longest edge ≤ 4096 px, JPEG 92 | "Compress (1080p)": short side ≤ 1080 px, H.264 ~5 Mbit/s, AAC |
| Original | the file as it is | the file as it is (video default) |

The preview shows an estimate of the total size to send (for compressed videos from their length and bitrate).

- Photos are decoded upright (EXIF rotation applied) and re-encoded, so camera metadata, **including location**, isn't
  carried over at Standard and HD. Transparent images stay PNG. GIFs are never re-encoded (they may be animated).
- Videos are re-encoded on the phone with AndroidX Media3 Transformer 1.9.0 (HDR tone-mapped to SDR so every phone can
  play them). A video already within the quality (resolution and bitrate) is sent as it is. Photos and videos picked
  at Standard/HD may be up to 1 GB before compression; the result must fit the server's limit or the message fails with a
  reason that uses the preview's own words ("still over 25 MB after compressing… Try Compress (720p), or a shorter
  video"). Since UI-003 the preview flags a compressed video whose estimate (length × bitrate) is over the limit before
  anything is compressed ("May be too big", and which smaller choice fits); it stays sendable because it's an estimate.
  An encoder failure says "couldn't be compressed on this phone" (its cause is logged by class name) instead of the old
  generic "Couldn't send this file". WebM videos are sent as they are.
- Original says plainly that location data in the file is kept.

## Safety checks (`AttachmentPolicy`, unit-tested in `AttachmentPolicyTest`)

Files are end-to-end encrypted on the phone: the server stores only ciphertext and **cannot scan files for malware**.
The app never claims it does. The checks run on the phone:

- **Real type from the first bytes** (JPEG, PNG, GIF, WebP, HEIF/AVIF, BMP, MP4/MOV/3GP, WebM, PDF, ZIP, programs:
  Windows MZ, ELF, Mach-O, `#!` scripts). A file is sent and shown as a photo or video only if its bytes are one: a
  program named `photo.jpg` is sent as a plain file with a neutral type, so no phone tries to show it as a picture. The
  bytes are checked again when the file is read for sending.
- **Files that install or run software** (APK and other installers, Windows/macOS/Linux programs and scripts,
  macro-enabled Office files, disk images, shortcuts, and a hidden second extension like `invoice.pdf.exe`): sending
  shows a warning and the button becomes "Send anyway"; opening one that was received asks first ("Open anyway").
- **Names** are cleaned when picked and when received: no folders, control or invisible characters, no
  right-to-left override (which can make `gpj.exe` look like `exe.jpg`), no reserved characters, at most 120 characters.
- **Sizes**: up to the server's limit per file (500 MB at most), 1 GB before compression, empty files refused, at most
  10 files sent and at most 20 shown per received message.
- **Gallery**: Save to Gallery only accepts files whose bytes really are a photo or video.

## Large files

Files over 25 MB use a chunked format (`ChunkedAttachmentCrypto`, `ChunkedAttachmentCryptoTest`): 1 MiB chunks, each
AES-256-GCM with its own nonce (the file nonce with the chunk index) and associated data binding its index and whether
it's the last chunk, so chunks can't be reordered, dropped or cut off, and neither phone ever holds the whole file in
memory. The attachment reference says `chunk` (absent for the original format). Smaller files keep the original format,
so older Orbit versions still open them; older versions can't open chunked files (they couldn't receive files that large
anyway). The server needs `ATTACHMENT_MAX_BYTES` (default 500 MB since 2026-10-17; `deploy/.env.example`) and a reverse
proxy that accepts bodies that large (nginx `client_max_body_size`); production is still at 25 MB until that's changed.

## Where files are kept (2026-10-17)

The server stores files on its disk or in Amazon S3 (`STORAGE_BACKEND`, docs/STORAGE_AND_DATA.md). It only ever holds
ciphertext, under random ids. Files expire after 7 days unless a member's phone keeps them for its encrypted history
(docs/MESSAGE_HISTORY.md). Kept files stay `ATTACHMENT_KEEP_DAYS` (365) with their own quota, so a restored or new phone
can still open them. With presigned downloads on, the app is redirected to a short-lived S3 link after the server's
checks. The app follows it and drops its own credentials on the way.

## Encryption

Unchanged format (`griip-envelope-v1` attachments): one AES-256-GCM key and nonce per file, streamed through the cipher
in 64 KiB steps while the ciphertext is hashed, so a file is never held whole in memory while it's encrypted.
Decryption verifies the hash first and only keeps the result once the GCM tag verifies (it buffers up to one file,
≤ 25 MB, inside the cipher; constant memory would need a chunked format, which would break compatibility).

## Not verified yet

Video compression ran in no device test tonight (the unit tests cover the rules, not the encoder). Check it on phones:
a long 1080p/4K clip at Standard and HD, an HDR clip, and a portrait clip.
