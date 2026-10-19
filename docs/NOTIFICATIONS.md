# Notifications and sounds

## Sounds (2026-10-17)
Meldiq has its own message tone and ringtone (`res/raw/orbit_message.ogg`, 0.45 s, two rising notes;
`res/raw/orbit_ringtone.ogg`, 2.6 s, a three-note motif that loops while ringing). Both were synthesised for Meldiq with
ffmpeg from sine tones, so they are original and carry no licence.

Android fixes a notification channel's sound and vibration when it's created, so each combination of Meldiq's settings
has its own channel (`Channels.Ids`, tested in `UiRulesTest`): `orbit_msg_s{0|1}v{0|1}`, `orbit_mention_…`,
`orbit_call_r{0|1}v{0|1}`. When a setting changes (or on first start after updating), Meldiq creates the matching channel
and deletes the old ones of that kind, including the original `messages`, `mentions` and `incoming_call_v2`. A channel the
person had blocked or turned down in Android's settings is recreated at that importance, not reset.

## Settings
Message notifications on/off; group notifications (off: groups and channels stay quiet except mentions of you); message
text in notifications (off: "New message" only, no sender or text, also on the lock screen); message sound; vibration;
call ringtone (off: calls vibrate only); per-chat mute (long-press a chat). Duplicate events never notify twice (a
message is stored once, and each chat has one notification).

## Delivery in the background
Meldiq uses no Google push service (no Firebase credentials in the app, by design). Messages and calls reach a phone over
its own connection to the server:
- while Meldiq is open;
- with "Stay connected in background" on (the default), which needs Android's ongoing "Meldiq is connected" notification;
- or, since 2026-10-17, with "Wake-ups through UnifiedPush" on. A distributor app such as ntfy, set to the push server
  your Meldiq server uses, wakes Meldiq with an empty message, and Meldiq then fetches over its own connection. This needs a
  server that offers it, and production doesn't yet (docs/BACKGROUND_DELIVERY.md). It hasn't been tested on a phone.

With both off, messages and calls arrive only when Meldiq is opened. A force-stopped app gets nothing, whatever the
setting. During a call, Android requires the ongoing-call notification; it stays.
