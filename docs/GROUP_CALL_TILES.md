# Group calls: participant tiles

Fixed 2026-10-17. Reported: in group voice and video calls, participant tiles did not appear or update correctly as
more people joined.

## Root cause

The media layer was fine: a 4-party end-to-end encrypted call between test bots on LiveKit decoded every participant's
microphone and camera at every other participant (about 10 fps for 640×360 bot video), including screen share, camera
toggles and a leave. The call screen's choice of layout was wrong:

| Situation | Before | Now |
|---|---|---|
| Group call, one other person | caller screen (name and timer), no tiles | tiles for both |
| Group call, screen share | the shared screen replaced every tile | screen large, everyone in a strip under it |
| Group call, landscape or many people | 2-column lazy grid, fixed 0.8 tile shape: tiles fell off the screen | grid sized to fit everyone (up to 12, the media server's limit) in portrait and landscape |
| Camera on, video not arrived yet | avatar (looked like "camera off") | avatar with "Connecting video…" |
| Weak connection / encryption failure of one person | not shown per person | badge on that person's tile |
| Everyone left a group call | "Connecting media…" | "Waiting for others to join…" |

`CallTiles` (unit-tested) decides the mode (`Hero`, `OneToOne`, `ScreenWithStrip`, `Grid`), the tile order (others by
arrival, then you; one tile per identity; a leftover connection of your own identity never appears as a second you) and the
grid shape. The grid is one `Layout` with every tile keyed by identity, so a tile keeps its video renderer when others join,
leave or move. Tiles show mute, LiveKit's speaking signal, camera state, a weak connection and an encryption problem
(from the same frame-cryptor states as the call's encryption badge; never faked to show video). A People panel lists
everyone's state. One-to-one calls are unchanged.

## Verification

- Unit: `CallTilesTest` (5).
- Bots only, local LiveKit, E2EE: 4 participants, all subscribed and decoding each other; screen share, camera toggles and a
  leave propagated.
- HONOR ELI-AN00 (Android 16) with four bots, local server, E2EE: 2, 3, 4 and 5 people; audio-only and video together; a
  bot's camera toggling every 15 s (its tile switches between video and avatar); a screen share with the strip; joins and
  a leave (no stale tile, others keep their places); the phone muting and turning its camera off (its own tile shows it);
  leaving the app for 8 s and returning (video resumes on every tile); hanging up and starting a second call. Bots decoded
  the phone's camera at 720×1280 and its microphone.
- Not verified on the phone: landscape (auto-rotate was off), network loss and reconnect, calls with several real phones.
