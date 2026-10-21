# Meldiq backlog: B14, B16 and B15 (added 2026-10-22)

The owner added three batches on 2026-10-22, while B10 was being built. They come **after B13**, in this order:

| Order | Batch | Starts when | Ends with |
|---|---|---|---|
| 1 | **B14** Minimal DM chat and conversation details | B1–B13 are built **and the owner has phone-tested and approved B13** | APK, checklist, then **stop for the owner's phone test** |
| 2 | **B16** Transitions, tab animations, interaction polish | B14 approved | APK, checklist, then **stop** |
| 3 | **B15** Final quality gate (always last) | B16 approved, or the owner authorises an earlier audit | Release-readiness report and APK, then **stop**; no deploy |

The overnight rule ("build B4b–B13 without waiting") ends at B13. From B14 on, each batch waits for the owner's
physical-phone approval before the next starts. None of these batches may interrupt a batch in progress.

Rules for all three, unchanged from the main plan:
- No push, deploy or production change.
- No destructive migration or AWS change without the owner's authorisation.
- The MongoDB-first server design and the end-to-end encryption model stay as they are.
- Strings in English, Chinese and Bengali.
- Nothing is marked phone-verified until the owner has tested it.

---

## B14: minimal, professional DM chat and conversation details

**Goal:** turn today's crowded DM screen into a calm, clear one, using two reference screenshots for layout and
behaviour only, not pixel copies. Every existing feature is kept: messages, encryption, attachments, voice messages,
calls, screen share, pins and search.

### The references (described, not stored)

The owner supplied three phone screenshots. They show other people's private conversations, so they're described
here rather than committed to the repository.

1. **Reference 1, a clean DM in another messaging app.** The header has only these:
   - back, with an unread badge;
   - avatar and name (tap for details);
   - voice call, video call and search.

   The timeline has:
   - the avatar and name once per group of messages;
   - compact voice-message bubbles (play button, waveform, duration);
   - an image preview of limited width with its caption directly below;
   - a single-row composer: "+", the input, then emoji and microphone.
2. **Reference 2, a conversation-details screen.** It has:
   - a header with avatar, name and username, plus search, notifications and settings icons;
   - tabs: **Members · Media · Pins · Links · Files**;
   - a "New group with this person" row, then a compact member list.
3. **Reference 3, Meldiq's current DM (Android).** It is crowded:
   - the header carries video, voice, pin, lock and search, so the name shrinks to "ErinS…" or "…";
   - a separate **Files** chip row takes vertical space;
   - a screen-share screenshot shows at full height;
   - call records are large pills;
   - the composer has the paperclip, emoji, input and microphone.

### Work, in order

1. **Audit first.**
   - Map every control in `ConversationScreen`, `ConversationInfoScreen`, the composer, the message bubbles and
     their view models: what each does, which ones are duplicated, which are broken.
   - Record defects, and decide which are in scope.
2. **Header.**
   - Shows back, the avatar, the name with presence, voice call, video call and search.
   - Pins, files, notifications, security (the lock), conversation settings and members move to the details
     screen or to an overflow menu. Nothing is removed.
   - Keep an accurate encryption indicator, but not as a permanent icon.
3. **Timeline.**
   - Consistent grouping, spacing and alignment for all of these:
     - sender and avatar;
     - consecutive messages;
     - replies;
     - reactions;
     - the "edited" mark;
     - voice notes;
     - images and videos, with a maximum width and the right aspect ratio, and tap to expand;
     - documents;
     - call records and screen-share previews;
     - captions under their attachment;
     - unread separator and system messages.
   - Only real data: no mock-ups.
4. **Composer.**
   - One compact row: an action button, the input, emoji, and the microphone, which becomes **Send** when there's text.
   - Files, camera, gallery, stickers and the other existing actions go in an action sheet.
   - The keyboard never covers the composer, and the input grows for long text.
   - Upload progress, the recording permission and the lifecycle, and the empty, loading, failure and sending
     states all keep working.
5. **Conversation details.**
   - Tabs only where real data backs them: Members (groups), Media, Pins, Links (where available), Files.
   - A DM shows the person's profile and the conversation settings; a group shows its identity, members and actions.
   - "New group with this person" uses the real group-creation flow.
   - Notifications, security, mute and block live here. Permission checks stay.
6. **Design.**
   - Use the existing tokens (`MELDIQ_DESIGN_SYSTEM.md`), with fewer icons and consistent icon sizes.
   - No decorative chips or borders; restrained surfaces; accessible touch targets.
   - The DM stays distinct from community channels.
7. **Check every interaction.** Each of these must work:
   - opening details and coming back;
   - calls;
   - search;
   - media and files;
   - pins;
   - member details;
   - notification and privacy settings;
   - message actions;
   - voice playback;
   - sending text and attachments;
   - full-screen preview;
   - returning to the same conversation and scroll position.
8. **Scope.**
   - Android first.
   - Change the server only where the new screens need it, for example a links list, if one doesn't exist.
   - Write up what the web app would need; don't redesign it.

### Acceptance (before the phone test)

1. The header is visibly simpler and fits a small screen.
2. Secondary functions are reachable without crowding the header.
3. Grouping and spacing are consistent.
4. Voice notes, images, captions and attachments render correctly.
5. The composer is compact and works.
6. Details organise members and shared content where implemented.
7. Message actions and call entry points still work.
8. There are no sign-in, encryption or messaging regressions.
9. Tests pass.
10. The debug APK builds.
11. Results and limits are written down.

**Completion:**
- Tests, lint and build.
- Emulator screenshots at small and large sizes, compared with the references.
- Fix what the comparison finds.
- The APK path and a phone checklist.
- Update the matrix and handoff.
- **Stop.**

---

## B16: smooth transitions, tab animations and interaction polish

**Goal:** motion that clarifies where you are and responds instantly, without slowing anything down. This is not a
redesign.

1. **Audit before animating.**
   - Cover the bottom tabs, the community rail and panel, categories, channel switching, the DM list and
     conversations, details, profile and settings, search, threads and forum posts, calls, and sheets, dialogs and
     menus.
   - Find abrupt jumps, needless recomposition and reloads, and lost scroll positions.
   - Don't use animation to hide architectural problems.
2. **Main tabs:**
   - a short fade or directional slide, and a clear selected state;
   - fast, with one small consistent set of effects.
3. **Tab state:**
   - each tab keeps its own back stack, scroll position, filters, search, drafts and selections;
   - no duplicate API calls, and no restarted uploads or playback;
   - back behaves predictably, and rapid taps don't corrupt the state;
   - balanced against memory and battery.
4. **Communities and channels:**
   - switching communities;
   - the navigator;
   - category expand and collapse without the list jumping;
   - instant feedback on channel selection;
   - settings sheets, forums, threads and member lists;
   - messages are not re-animated on every switch.
5. **DMs** (on top of B14):
   - transitions between list and conversation, and to and from details, media, pins, members, threads and the
     attachment picker;
   - drafts, scroll position and the keyboard are kept;
   - nothing reloads just to animate.
6. **Micro-interactions** follow real state only, never a false "sent":
   - reactions and the emoji picker;
   - send and delivery state;
   - pin;
   - previews and upload progress;
   - mute and camera;
   - layout choice;
   - menus;
   - theme;
   - mark as read.
7. **Sheets, dialogs and menus:** consistent open and dismiss motion, and gestures that don't fight nested scrolling.
   Destructive confirmations stay clear and quick.
8. **Timeline motion:**
   - subtle insertion, and no scroll jump on incoming messages;
   - media space reserved when the size is known;
   - reading position kept when older messages load;
   - a predictable "jump to latest";
   - stable keys, with no whole-list animation.
9. **Calls:**
   - entering, leaving, expanding and collapsing;
   - layouts, controls, screen share, camera switch and picture-in-picture;
   - media never waits on a decorative transition, and calls survive tab changes and sheets.
10. **Motion system:**
    - shared tokens or helpers for duration and easing by purpose: feedback, small controls, content, navigation,
      sheets, expand, progress, success and failure;
    - documented in `MELDIQ_DESIGN_SYSTEM.md`.
11. **Performance and accessibility:**
    - recomposition, allocations, list keys, main-thread work and layout shift;
    - measure frame pacing before and after where possible;
    - respect the reduced-motion setting and screen readers.
12. **Web** (`orbit_web`): the same principles with web techniques and `prefers-reduced-motion`. If not done, say
    so.

**Acceptance (12 points):**
1. Tab switching is smoother and has no delay.
2. Selected states are clear.
3. Tab state is preserved.
4. Categories and channels don't jump.
5. DM context is kept.
6. Feedback matches real state.
7. Overlays move consistently.
8. Calls survive navigation.
9. Rapid taps are safe.
10. Performance doesn't regress.
11. Reduced motion is respected.
12. Tests cover navigation and state.

**Completion:**
- Tests, including Compose UI tests where practical, lint and build.
- A before-and-after inspection.
- A phone checklist: tabs, chat, categories, sheets, rapid taps, keyboard, calls.
- Update the matrix and handoff, with the APK path.
- **Stop.**

---

## B15: final quality gate (last)

**Goal:** one coherent product. This batch is for verification and targeted fixes, not new features.

1. **Coverage audit.**
   - For everything marked implemented in `DISCORD_FEATURE_AUDIT.md`, the matrix and the handoff, check the source
     and the tests.
   - Find:
     - placeholder callbacks;
     - screens not backed by real data;
     - Android and web mismatches;
     - duplicates;
     - missing loading, empty, offline and error states;
     - statuses without evidence;
     - deferred bugs with no reason given.
   - Correct the statuses.
2. **End-to-end journeys** with dedicated test accounts:
   - account (register or sign in, verify, recover, restore, sign out and in);
   - social (find, request, DM, group);
   - community (create, channels, invite, join, permissions);
   - messaging (send, reply, react, edit, delete, pin, search, media);
   - files (large ones where possible, access limits);
   - calls (camera switch, remote video, group, screen share, reconnect);
   - administration (roles, channel permissions, removal; access is lost at once).
3. **Security and privacy:**
   - isolation of communities and private channels;
   - attempts to escalate a role;
   - editing, deleting or downloading without permission;
   - invite expiry, revocation and bans;
   - sessions and recovery;
   - rate limits and upload validation;
   - realtime events after a permission change;
   - recovery and key boundaries;
   - AI consent, if built;
   - leaks of secrets or content in logs.

   Fix what's found, with regression tests; never weaken a check to pass.
4. **MongoDB and AWS:**
   - MongoDB is the only canonical store, once the owner approves the migration;
   - indexes, duplicate prevention, pagination, concurrency, reconnect;
   - migration integrity, backup and a restore test;
   - S3 access and multipart cleanup;
   - job idempotency and retries;
   - IAM least privilege, secrets, monitoring, alerts and cost.

   Nothing destructive, and no production change, without authorisation.
5. **Calls and media on real hardware:** cameras, local and remote video, groups, the microphone and camera
   lifecycle, screen share, reconnect, track cleanup, weak networks, resource use. The emulator doesn't count as
   verified for hardware behaviour.
6. **Performance and usability:**
   - startup, requests, recomposition, queries, leaks and listeners;
   - long histories, previews, realtime;
   - scrolling and the keyboard;
   - controls staying responsive during uploads or calls;
   - small screens;
   - accessibility, themes and the three languages.

   Measure first.
7. **Regression:** the Android, web, server and MongoDB suites, lint, type checks and the APK, compared with the
   baseline. For each failure, the exact test, error and cause.
8. **Release preparation** (no deploy):
   - the readiness report;
   - verified features and open issues;
   - migration status;
   - AWS prerequisites;
   - backup and rollback;
   - an environment and secrets checklist, with no secret values;
   - a dependency and vulnerability review;
   - a release-build checklist and a phone checklist;
   - web compatibility.
9. **Gate (8 points):**
   1. Accurate statuses.
   2. Critical journeys tested.
   3. Serious security defects fixed or blocked.
   4. MongoDB and AWS verified as far as possible.
   5. Critical regressions fixed.
   6. Builds and tests recorded.
   7. The APK available.
   8. Limits stated honestly.

   Update the matrix and handoff. **Stop** for the owner's test; no deploy.
