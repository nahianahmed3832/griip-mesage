# Encrypted backups

Added 2026-10-17 (`BackupCrypto`, `BackupManager`, `BackupWorker`, Settings › Data & Storage).

## What's in a backup
An account's chats, messages, the people in them (with their safety-number pins) and, if chosen, the photos, videos and
files downloaded to the phone. **Never** private keys or the recovery key. On a new phone: sign in to the account first
(recovery key, or link from another phone), then restore the backup into it. Restoring only adds chats and messages the
phone doesn't have; nothing is replaced or deleted. A backup of another account is refused.

## Encryption
File = `ORBITBK1` ‖ header length ‖ readable header (version, key derivation parameters, account, time) ‖ body.
The key is PBKDF2-HMAC-SHA256 of the backup password (600,000 rounds, random 16-byte salt). The body (a ZIP) is
AES-256-GCM in 256 KB chunks, each bound to the header's hash, its position and whether it's last, so the header can't be
swapped, chunks can't be reordered or dropped, and a cut-off file is detected; a wrong password fails at the first chunk.
Restore decrypts and checks the whole file before changing anything. Tests: `BackupCryptoTest`.

## Where backups go
- **Back up now**: any file you choose with Android's file picker (phone storage, SD card, Google Drive…).
- **Automatic backup**: choose a file once (Google Drive works through its file-picker provider), daily or weekly
  (WorkManager, battery not low). The backup password's derived key is kept on the phone sealed by the Android Keystore,
  so backups can run unattended; it is never uploaded. Meldiq won't overwrite a backup at that location that is newer than
  the last one it wrote. The last success time or failure shows in Data & Storage.
- A native Google Drive integration (Drive API, app folder) would need an OAuth client registered for the app's signing
  certificate in a Google Cloud project; that isn't configured, and no credentials are in the app.

## Losing access
Without the backup password, a backup can't be opened by anyone, including GriipBuddy. If you lose every signed-in
device and your recovery key, the account can't be recovered, and messages that weren't backed up are gone.
