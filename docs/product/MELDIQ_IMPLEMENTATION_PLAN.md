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
| B6 to B13 | Below | Not started |

> **Gate changed 2026-10-21 23:13:** the owner asked for the remaining batches to be built one after another without
> waiting, and to phone-test them all together at the end. Each batch is still committed on its own, with its own APK
> and checklist in the handoff, so a defect can be traced to its batch. Nothing is marked phone-verified until the
> owner has tested it.

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
