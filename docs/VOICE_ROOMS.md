# Voice rooms (round 6, 2026-10-19)

A voice room is a workspace channel people drop into for a call. It stays in Home's Voice rooms section with who is in
it now; joining starts or joins its call; nobody's phone rings.

## Behaviour

- **Create**: New channel › Voice room (public or private; not an announcement channel). `voice: true` on
  `POST /v1/orgs/:orgId/channels`; audit `voice_room.create`.
- **Join**: Home › Voice rooms › Join, or the room's banner. `POST /v1/calls` on a voice room opens its call already
  active (no ring, no ring timeout) or joins the open one. The call is marked `room` and ends when the last person
  leaves. Its comings and goings aren't written into the conversation as call entries.
- **In the room**: the normal call screen (mute, camera, screen sharing, audio output, People list) plus **Raise hand**;
  hands are listed in order and go down when someone leaves.
- **Moderation** (the room's managers, `canManageConversation`): from the People list, Mute microphone (LiveKit
  `MutePublishedTrack`; only the person can unmute), Lower hand, Remove from call (they can join again unless removed
  from the room). The person is told; audit `voice_room.mute|lower_hand|remove`.
- **Who's in it** is on the conversation (`activeCall.participantIds`, at most 12) and follows the call's events.

## Encryption

Media is end-to-end encrypted exactly like other calls when Settings › Calls › End-to-end encrypt is on: one random key
per call, sent inside end-to-end encrypted messages, never through the server's call API. Because a room outlives the
person who opened it, **in voice rooms a key is also accepted from, and passed on by, anyone the server lists as
connected to that call** (`CallKeyVerifier`), not only its creator. That doesn't widen who can learn the key (it is
already sent to every member's devices), but a connected member could hand a newcomer a wrong key and stop them from
hearing the room (denial of service, not disclosure). Keys still aren't rotated when people join or leave.

## API

`POST /v1/calls/:id/hand` (`{raised}`), `POST /v1/calls/:id/moderate` (`{userId, action: mute|lower_hand|remove}`).
Calls carry `voiceRoom` and `hands`. Feature flag: `voice-rooms`. Migration 019 (`conversations.voice`, `calls.room`,
`call_hands`); app database v8 (`voice`, `roomPeopleJson`).

## Tests and limits

`server/test/voice_rooms.test.ts` (no ring, active at once, shared call, hands, moderation rights, mute and remove,
closing), `CallKeyVerifierTest`. A full test needs two devices in the room; see the round report. Not built: speaker
queues with stage roles, recording or transcription (would need consent and retention rules), invite links for guests.

## Who may join and speak (2026-10-21, Discord reference round)

Two channel permissions, granted to members and guests by default and changeable by roles and by category or channel
overwrites like any other (`permissions/channel.ts`):

- `voice.connect`: join the voice channel's call. Without it, `POST /v1/calls` and `POST /v1/calls/:id/join` answer
  403 "You can't join this voice channel", and the channel view says `canConnect: false` (the app shows a notice instead
  of Join).
- `voice.speak`: talk, share video or share the screen. Without it the LiveKit token is issued with `canPublish: false`
  and the join answer says `media.canSpeak: false`: the app joins with the microphone off, disables the microphone,
  camera and screen-share buttons, and says "You can listen in this voice channel, but not speak".

The check happens each time a token is issued (joining, rejoining). A permission changed during a call applies from
the next join; LiveKit doesn't revoke an already issued grant. Owners and admins skip overwrites; a custom role's
community-wide restriction of `voice.speak` still wins over a channel allow. Tests: `server/test/community_channels.test.ts`.

