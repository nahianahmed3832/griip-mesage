# Meldiq implementation plan (from 2026-10-21)

Ordered by value and dependency. Each item names its matrix IDs (`MELDIQ_FEATURE_GAP_MATRIX.md`). Owner actions are
marked **[owner]**. Nothing ships to production without the owner's go-ahead (`docs/DEPLOYMENT.md`).

## Done on 2026-10-21 (this session)

| Item | IDs | Where |
|---|---|---|
| Recovery choices hidden behind help links (Android and web) | AUTH-08 | `MELDIQ_AUTH_FLOW_AUDIT.md` |
| Full Unicode emoji picker, more reactions, reaction validation (Android and web) | EXP-01–03, MSG-06 | `docs/EMOJI.md` |
| Encrypted history movable to MongoDB | PLAT-01 | `MELDIQ_MONGODB_MIGRATION.md` §5 |
| Member timeouts (server and Android) | MOD-06 | matrix |
| Feature audit, gap matrix, platform plans | — | `docs/product/` |

## Next, in order

### Phase 1: ship what's built, unblock the platform

1. **[owner]** Deploy API 0.14.0 (history store, timeouts, migration 034) after the usual backup and rehearsal. Build
   Android from the main folder. Deploy Meldiq Web with the two commits made locally on `main` (auth help, emoji
   picker). The web still targets the pre-0.12 API in places (W1).
2. **[owner]** Run the phone checks: camera switch and remote picture, landscape game share, 1 GiB upload on Wi-Fi
   and mobile data (`MELDIQ_REALTIME_MEDIA_PLAN.md` §7, MSG-16).
3. **[owner]** Move the AWS tools off the root key and enable CloudTrail (`MELDIQ_AWS_ARCHITECTURE.md` §5).
4. **[owner]** Create MongoDB Atlas (ap-southeast-1, M10) and give the URI to the server's `.env`.

### Phase 2: product gaps (P1/P2)

5. Reports (MOD-07), server and Android (`MELDIQ_FULLSTACK_SPEC.md` §2.2).
6. Timeouts on the web (MOD-06 W).
7. Slowmode (CH-14).
8. Message requests (DM-03).
9. Two-step sign-in (AUTH-05).
10. Custom community emoji (EXP-04).
11. Onboarding questions (COM-07).
12. Events: recurrence and voice-channel events (MSG-13).

### Phase 3: platform

13. MongoDB: the files domain, then federation, relay (with load tests), conversations and workspace with
    asynchronous `authz`, then identity (migration doc §3). Cut over domain by domain with the owner.
14. AWS: network, API on Fargate, observability, LiveKit on EC2 (AWS doc §3). Staging first.
15. AI foundation behind `AI_ENABLED=false`, then draft help (AI doc §5).
16. Game-streaming mode (media plan §5).

### W1: Meldiq Web community rebuild

The web has the new sign-in and emoji picker, but still shows the workspace layout and the retired AI, workflows,
documents and inbox screens, and calls removed routes. Rebuild it to Android's community layout:
- community rail and channel panel;
- categories with menus;
- forum with tags;
- voice channel permissions;
- You;
- removal of the retired modules.

Use Playwright for every flow. This is its own multi-session project.

## How each item is finished

Tests first where practical. The server suite runs on SQLite (and on MongoDB for moved domains). Android unit tests
and lint run, plus an emulator walk-through with screenshots. Web runs type check, lint, Vitest and Playwright. Then
update the matrix row, commit, fast-forward `/Volumes/code_files/meldiq_android` (`internal-beta`), and keep
`MELDIQ_SESSION_HANDOFF.md` current.
