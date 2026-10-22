# Meldiq implementation plan: gated batches (from 2026-10-21)

The owner's workflow: one bounded batch at a time. Each batch goes through:
- research;
- a plan;
- real frontend and backend work;
- automated tests;
- the Android APK;
- a phone checklist;
- a report.

Then **stop until the owner has tested the APK on their phone and approved it**. A successful build is not approval.
Defects the owner reports in a batch are fixed and rebuilt before anything else.

Feature IDs: `MELDIQ_FEATURE_GAP_MATRIX.md`. Control IDs and evidence: `MELDIQ_LIVE_DISCORD_INSPECTION.md` and the
three audits it links. Owner actions are marked **[owner]**. Nothing ships to production without the owner's
go-ahead (`docs/DEPLOYMENT.md`).

## Status

| Batch | Goal | State |
|---|---|---|
| B0 | Baseline, Discord research, inventory, batch plan | **Done 2026-10-21** (rounds 1–3) |
| B1 | Sign-in and unlock polish: recovery choices behind help links, session restore | **Done: phone-tested and approved by the owner 2026-10-21** |
| B2 | Community navigation and home | **Done: phone-tested and approved by the owner 2026-10-21** |
| B3 | Channels and categories | **Done: phone-tested and approved by the owner 2026-10-21** |
| B4 | Administration, part 1: reports, Security actions, audit filters, invite choices | **Built; APK ready; owner tests it with the rest** (2026-10-21) |
| B4b | Administration, part 2: join applications, activity alerts, https invite links with a signed-out page, invite from a profile, Meldiq's operator commands for reports | **Built; APK ready; owner tests it with the rest** (2026-10-21) |
| B5 | Chat interface: thread list, message links, translated menus, Unreads | **Built; APK ready; owner tests it with the rest** (2026-10-22) |
| B6 | Connections, message requests, DM permissions | **Built; APK ready; owner tests it with the rest** (2026-10-22) |
| B6b | Group photo and presence choices (split from B6) | **Built; APK ready; owner tests it with the rest** (2026-10-22) |
| B7 | Media and large files | **Already built; checked on the emulator 2026-10-22 (100 MB, identical on arrival); the 1 GiB real-network test is the owner's** |
| B8 | Forums and polls: guidelines, tag matching, post-card reactions, follow; poll duration and answer emoji | **Built; APK ready; owner tests it with the rest** (2026-10-22) |
| B8b | Event recurrence, community guide, onboarding questions (split from B8) | **Built; APK ready; owner tests it with the rest** (2026-10-22) |
| B9 | Community emoji and stickers | **Built; APK ready; owner tests it with the rest** (2026-10-22) |
| B10 | Voice, video, camera: deafen, noise reduction, pre-call check, camera and reconnect timing, a call-permission fix | **Built; APK ready; owner tests it with the rest** (2026-10-22) |
| B11 | Screen share and game streaming: share sheet, game mode, app sound, "stopped sharing", clean stop | **Built; APK ready; owner tests it with the rest** (2026-10-22) |
| B12 | Remaining gaps: localized dates, plurals, community emoji reactions, pronouns, role groups in the member list; two defects fixed | **Built; APK ready; owner tests it with the rest** (2026-10-22) |
| B13 | Integration and stability: security pass with three fixes, both-store regression, web parity notes; files domain to MongoDB left for its own batch | **Done (server only); waiting for the owner's phone test of B4–B13** (2026-10-22) |
| B14 | Minimal DM screen and details, voice channels as rooms (owner's go-ahead to start without the B13 phone test) | **Built; APK ready; stopped for the owner's phone test** (2026-10-22) |
| B16, B15 | `MELDIQ_BACKLOG_B14_B16_B15.md` | Next: B16, then B15 last; each stops for the owner's phone test |

> **Gate changed 2026-10-21 23:13:** the owner asked for the remaining batches to be built one after another without
> waiting, and to phone-test them all together at the end. Each batch is still committed on its own, with its own APK
> and checklist in the handoff, so a defect can be traced to its batch. Nothing is marked phone-verified until the
> owner has tested it.
>
> **Added 2026-10-22:** the overnight rule ends at B13. B14, B16 and B15 each wait for the owner's phone approval of
> the batch before them, and B15 is always the final quality gate.

Already built earlier the same day and included in the B1 APK (to be checked within their own batches later):
- full emoji picker (B9);
- member timeouts (B4);
- history in MongoDB (B13).

## Batches

Each batch lists what's in it (feature and control IDs), and what the phone test will check.

| Batch | Scope | Main IDs | Phone test focus |
|---|---|---|---|
| **B1** Sign-in and navigation polish | Help links on sign-in and unlock; email-code unlock first; session restore after restart | AUTH-08, AUTH-09 | Sign in, the help sheet, unlock by emailed code, restart keeps you signed in |
| **B2** Community navigation and home | <ul><li>Community notification dialog: suppress @everyone, per-channel override list (COMM-CTRL-002);</li><li>rail reorder (NAV-11);</li><li>creation templates (CREATE-CTRL-001/002);</li><li>unread and mention signals checked</li></ul> | COM-13, NAV-11, COM-01 | Open communities, change notification settings, reorder the rail, create from a template |
| **B3** Channels and categories | <ul><li>Timed mute for channel, category, community and DM;</li><li>"category default" level;</li><li>hide channels and channel browser;</li><li>slowmode (server-enforced)</li></ul> | CH-17, COM-16, CH-14 | Mute for 15 min and watch it end, hide and show channels, slowmode countdown |
| **B4** Administration and invitations | <ul><li>Reports (message and person; review step; moderator queue);</li><li>pause invites and member DMs;</li><li>join applications;</li><li>raid alerts;</li><li>audit log filters;</li><li>https invite links, signed-out preview, more expiry and use choices, invite from DM</li></ul> | MOD-07, DM-09, MOD-13, COM-18, MOD-10, COM-05, DM-08 | Report and handle a report, pause invites, share an https invite to a second phone, banned rejoin refused |
| **B5** Chat interface | <ul><li>Thread list per channel;</li><li>message link for members;</li><li>menu translations;</li><li>unread view in Activity</li></ul> | CH-18, MSG-20, MSG-19, NAV-04 | Message menus in all three languages, threads list, jump by link |
| **B6** DMs, groups and connections | <ul><li>Message requests;</li><li>DM permissions;</li><li>connections (request, accept, decline, cancel, remove; **needs approval of the DM-04 reversal**);</li><li>group photo;</li><li>presence choice (idle, invisible)</li></ul> | DM-03, DM-04, DM-07, NAV-08 | Two accounts: request, accept, decline, block; a stranger's DM lands in Requests |
| **B7** Media and large files | Picker, preview, progress, retry and cancel, download; the 1 GiB target over Wi-Fi and mobile data | MSG-16 | Large upload and download on both networks |
| **B8** Forums, polls, events, onboarding | <ul><li>Forum guidelines, Match All, follow post, card reactions;</li><li>poll duration and answer emoji;</li><li>event recurrence;</li><li>Server Guide;</li><li>onboarding questions</li></ul> | CH-11, MSG-21, MSG-13, COM-17, COM-07 | Create each, answer onboarding as a new member |
| **B9** Expressions | <ul><li>Custom community emoji (upload, manage, picker sections);</li><li>emoji name preview;</li><li>stickers</li></ul> | EXP-04, EXP-05 | Upload an emoji, use it, see it on another phone |
| **B10** Voice, video, camera | <ul><li>Stage-by-stage camera trace and fix;</li><li>deafen;</li><li>pre-call check;</li><li>reconnect measurement;</li><li>group layout</li></ul> | CALL-01–04, CALL-09, CALL-11 | Camera switch with a second device, rotation, network drop |
| **B11** Screen share and game streaming | Lifecycle, Android 14 capture rules, game mode (frame rate, H.264, game audio), viewer, measurements | CALL-05, CALL-06 | Share a landscape game to a second device; stop, leave and rejoin |
| **B12** Remaining gaps | What the matrix still lists as P2/P3 after B1–B11 | — | Per item |
| **B13** Integration and stability | <ul><li>MongoDB domains (files, then the rest);</li><li>AWS (staging);</li><li>notifications, realtime;</li><li>web parity (W1);</li><li>security and performance review</li></ul> | PLAT-01–06 | Full regression on the phone |
| **B14** Minimal DM chat and conversation details | <ul><li>Header: back, avatar, name and presence, voice, video, search; the rest moves to details or overflow;</li><li>timeline grouping, voice notes, media sizes, captions;</li><li>one-row composer with an action sheet;</li><li>details tabs Members / Media / Pins / Links / Files backed by real data</li></ul> | DM-UI (new) | Every header, timeline, composer and details control on a small and a large phone |
| **B16** Transitions and interaction polish | <ul><li>Shared motion tokens;</li><li>tab switching that keeps each tab's state;</li><li>community, channel, DM and call transitions;</li><li>sheets and dialogs;</li><li>timeline motion; reduced motion</li></ul> | MOTION (new) | Tabs, rapid taps, categories, sheets, keyboard, calls during navigation |
| **B15** Final quality gate | Coverage audit, end-to-end journeys, security and privacy, MongoDB and AWS checks, hardware calls, performance, regression, release preparation (no deploy) | QA-GATE (new) | Full regression and the release checklist |

**Rules carried through every batch:**
- Server permissions are enforced on the server.
- New server data is MongoDB-ready: store interface plus both stores (`MELDIQ_MONGODB_MIGRATION.md`).
- Strings go in all three languages.
- Tests come first where practical.
- The handoff is updated at the end of each batch.

## Owner actions that unblock batches

1. **[owner]** Phone-test the B1 APK (`MELDIQ_SESSION_HANDOFF.md` §6) and approve it, or report defects.
2. **[owner]** Approve or reject the connections model (DM-04 reversal) before B6.
3. **[owner]** For B4 links: the release signing-certificate SHA-256 fingerprint for `assetlinks.json`, and a
   deploy of meldiq.app with the `/invite/:code` page.
4. **[owner]** For B6, B10 and B11: two test accounts; a second Android device if possible.
5. **[owner]** Optional: a Discord test server and spare account, so friend, invite and call flows can be observed
   to the end.
6. **[owner]** Earlier items still open:
   - deploy API 0.14.0;
   - move the AWS tools off the root key;
   - MongoDB Atlas;
   - decide versionCode and versionName.
