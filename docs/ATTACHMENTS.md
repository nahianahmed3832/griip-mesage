# Attachments: several at once, quality, and file safety

Added 2026-10-17. Up to 10 photos, videos or files can be sent as one message (an album), at Standard, HD or Original
quality, after the phone checks each file.

## Sending

1. **Pick.** "Photos and videos" opens the Android photo picker for up to 10 items; "Files" opens the document picker
   (the first 10 are kept, with a note). No storage permission is needed.
2. **Preview** (`BatchComposer`). Every file with a remove button, a "+" tile to add more, the quality (when there are
   photos or videos), one caption, and what the checks found. Files that can't be sent are marked and left out; the
   button says how many will go. Nothing is uploaded before Send.
3. **Queue** (`MessageRepository.sendBatch`). The message appears at once with all its files. In the background (it
   carries on when you leave the chat) each file is prepared, encrypted on the phone and uploaded, at most two at a
   time (the server's per-person limit), with progress on the bubble. The message itself is sent when every file is
   uploaded. One message carries all the files; older Orbit versions show them one under the other.
4. **Failure and retry.** A failure marks the message failed with the reason. Tap to retry: files already uploaded
   aren't uploaded again. If the app was closed before a file was prepared, the original is no longer reachable and the
   message says so (pick the files again).

A swipe-to-reply target applies to the album and is cleared once the album is queued.

## Quality

| | Photos | Videos |
|---|---|---|
| Standard (default) | longest edge ≤ 1600 px, JPEG 82 | short side ≤ 720 px, H.264 ~2 Mbit/s, AAC |
| HD | longest edge ≤ 4096 px, JPEG 92 | short side ≤ 1080 px, H.264 ~5 Mbit/s, AAC |
| Original | the file as it is (≤ 25 MB) | the file as it is (≤ 25 MB) |

- Photos are decoded upright (EXIF rotation applied) and re-encoded, so camera metadata, **including location**, isn't
  carried over at Standard and HD. Transparent images stay PNG. GIFs are never re-encoded (they may be animated).
- Videos are re-encoded on the phone with AndroidX Media3 Transformer 1.9.0 (HDR tone-mapped to SDR so every phone can
  play them). A video already within the quality (resolution and bitrate) is sent as it is. Photos and videos picked
  at Standard/HD may be up to 1 GB before compression; the result must fit 25 MB or the message fails with a reason
  ("try Standard or a shorter video"). WebM videos are sent as they are.
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
- **Sizes**: 25 MB per file after compression (the server's limit), 1 GB before compression, empty files refused, at
  most 10 files sent and at most 20 shown per received message.
- **Gallery**: Save to Gallery only accepts files whose bytes really are a photo or video.

## Encryption

Unchanged format (`griip-envelope-v1` attachments): one AES-256-GCM key and nonce per file, streamed through the cipher
in 64 KiB steps while the ciphertext is hashed, so a file is never held whole in memory while it's encrypted.
Decryption verifies the hash first and only keeps the result once the GCM tag verifies (it buffers up to one file,
≤ 25 MB, inside the cipher; constant memory would need a chunked format, which would break compatibility).

## Not verified yet

Video compression ran in no device test tonight (the unit tests cover the rules, not the encoder). Check it on phones:
a long 1080p/4K clip at Standard and HD, an HDR clip, and a portrait clip.
