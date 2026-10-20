# Meldiq 2.0: reference study and product decision register (2026-10-21)

Branches: `claude/meldiq-2-redesign` in this repository (server and Android) and in meldiq-web, both cut from the
round-1 candidates (`claude/remediation-round-1`).

**Release state (owner's instructions, 2026-10-20/21):**
- Pushed, then fast-forwarded into `internal-beta` (`f53cf27`) and `main` (`4a79225`).
- Deployed as API 0.9.0 and Meldiq Web `4a79225` (docs/DEPLOYMENT.md).
- Android 15 / 0.2.8-beta built; the owner signs and uploads it.
- The final-acceptance fixes (§9) are local commits on the same branches, **not pushed or deployed**:
  - meldiq `0cebcc5` and the docs commit after it;
  - web `a54ff5c`.

## 1. What the reference recording showed

One recording was supplied: `IMG_1141.MP4`, 4 min 23 s, an iPhone screen recording. It was read as frames, one every
3 seconds: 88 frames in six contact sheets. Motion was not judged frame by frame; nothing below claims timings
measured from the video.

- **0:00–2:50, Slack mobile, the "GriipBuddy" workspace.**
  - Tabs: Home, DMs, Activity, More, Search.
  - Home: quick cards (Slackbot, Huddles, To-dos, Saved) above collapsible Channels, Direct messages and Apps sections.
  - Workspace switcher: a sheet.
  - Channel screen: the channel header with a members count and tabs. Channel info: description, a member strip,
    notification levels, mute, convert to private.
  - DMs: filter chips (All, Unreads, External).
  - Activity: All / DMs / Mentions / Threads.
  - Also seen: thread view; status and presence popover; the Preferences tree (Notifications, Appearance with
    color mode, Accessibility with animation and haptics, Swipe actions, Language, Privacy, Advanced); the emoji
    picker; "Agents & Apps" with three apps.
- **2:50–4:23, Discord mobile.**
  - A vertical server rail with unread markers, then the DM list with a horizontal strip of friends.
  - Server channel list: emoji-labelled categories and a bottom user card.
  - Channel chat, and an emoji / GIF / sticker picker in tabs.
  - Server menu: Boost, Invite, Notifications, Settings, Mark as read, Create channel / category / event.
  - Channel long-press sheet: invite, pin, copy link, mark read, mute, notification settings, edit, duplicate, copy ID.
  - Server Settings: Settings, Expression, People, Moderation (AutoMod, audit log, bans, security), Community.
- **Not in the recording:** Discord voice channels in use, Slack huddles in use, desktop layouts, search results,
  threads on Discord, empty and error states beyond Slack's channel welcome. The folder's other videos (`1.mp4` to
  `3.mp4`, August) are product clips of phone cases and a fan, not app references; the 1.5 GB screen recording of
  2026-10-11 was not opened.

## 2. Design principles taken from it

1. **One tap between contexts.** Discord's rail beats Slack's sheet for people in several spaces. Meldiq already had a
   rail on the web and on Android's Workspace screen; Home now has it too, with an unread dot.
2. **Sections over destinations.** Slack's Home is one scrolling list of collapsible sections. Meldiq's Home already
   works this way, and nothing is gained by more tabs.
3. **Long-press/context sheets for power actions** (Discord) rather than more buttons. Meldiq already has them for
   chats; kept.
4. **Grouped settings.** Both apps group admin settings under headings (Discord: Settings / Expression / People /
   Moderation). Meldiq Web's 17-item workspace menu is now three groups.
5. **Voice rooms are places, listed once** (Discord) with who is in them. Meldiq listed them twice; fixed.
6. **Communities don't need corporate structure** (Discord). Meldiq's model already fits; it needed a kind and
   defaults, not a second system.

## 3. Brand (fixed)

- **Logo:** `brand/meldiq-logo.webp`: the cyan → blue → violet ribbon "M" with a chat tail, on navy `#071231`. Every
  launcher, notification and in-app mark is rendered from it (`android/scripts/render_brand.py`). Unchanged.
- **Primary brand colour: `#4B4FC9`** (dark mode: `#8B93FF` for accents; Android's filled buttons `#5A5FD8`). Settled
  2026-10-21 from this evidence:

  | Evidence | Finding |
  |---|---|
  | The logo itself (`brand/meldiq-logo.webp`, 2000 px, sampled) | A gradient: cyan ≈ `#00D0F0` (hue 188°) → blue ≈ `#3070F0` (222°) → violet ≈ `#8050F0` (258°) with lavender highlights, on navy `#071231`. **No single indigo pixel**; both candidates (hue 238° and 243°) are derived shades between its blue and violet |
  | What already ships with `#4B4FC9` | the Android app (Graphite, the default since round 6, on Play); every production account email (`server/src/accounts/mailTemplate.ts`); this repository's `docs/DESIGN_SYSTEM.md` |
  | Where `#4F46E5` came from | Meldiq Web only (2026-10-19, `bceb052`). It is exactly Tailwind CSS's stock `indigo-600`, a framework default rather than a value taken from the logo |
  | Contrast | both pass AA: white on `#4B4FC9` 6.4:1, on `#4F46E5` 6.3:1 |

  So `#4B4FC9` is the one authoritative token. Web changed in `a54ff5c`:
  - `--accent`, `--badge` and `--action` are `#4b4fc9`; accent text and hover `#353aa6` (Android's `primaryDeep`);
  - dark mode `#8b93ff` (Android's dark accent), accent text `#b8beff`;
  - every pair was recomputed: 5.5:1 to 9.1:1.

  The web's plum and amber plates, and Android's Aurora, Sunset and Meadow presets, are colour choices people make
  themselves. They are unchanged, as are platform surface differences (web frame `#231d62`, Android flat headers).
- **Kept:** the Graphite neutrals, the 4 dp spacing grid, the 8/12/16 dp radii, Plus Jakarta Sans on the web and
  system type on Android.
- **Added:** shared motion tokens (§6).

## 4. Product decision register

Status, re-checked 2026-10-21:
- **verified**: implemented, and covered by tests that ran on the final source (§9);
- **implemented, device check pending**: Android UI that only a phone can confirm;
- **hidden**: deliberately not shown, still working;
- **kept**: no change needed;
- **later**: not implemented, deferred;
- **blocked**: needs outside access;
- **owner**: the owner's decision.

| Area | Decision | Why | Status |
|---|---|---|---|
| Account model | One account; personal space, any number of team workspaces and communities | Already the 2.0 model; no new account types | kept |
| Communities | A `kind` on workspaces (`team` / `community`, migration 029), not a second system. Same members, roles, channels, invites, permissions and APIs | One data model for both audiences; existing workspaces stay teams | verified (server, web live test); Android create flow: device check pending |
| Community start | Created with #welcome, #announcements, #general, #introductions and a lounge voice room | First member finds somewhere to go; uses the existing template mechanism | verified |
| Community surface | Web hides Branches & teams and Customer inbox for communities; pages still work by link; owners switch kind both ways | Corporate tools up front confuse communities; hiding is reversible. The server applies **the same authorization in both kinds** (test: identical answers for a member and an outsider), and switching kind leaves every member, role, channel, channel membership, branch and setting byte-for-byte unchanged (test) | verified; Android has no single admin menu to hide entries from (hidden: n/a) |
| Public discovery of communities | Not built | Needs moderation, reporting and abuse controls first; a policy and abuse decision | later, owner |
| Workspace switching | Rail with unread dots on Android Home (web already had it) | Discord's strongest pattern for multi-space people | verified (rules unit-tested); Android rendering: device check pending |
| Workspace admin menu | Web: Workspace / Tools / Administration groups | 17 flat items was the densest surface | verified (rule tested, browser-checked) |
| Voice rooms | Listed once (Voice rooms section), not again under Channels; tapping the room's name opens its chat, the join button joins (both clients) | Duplicate entries on both clients | verified (both clients' rules tested); Android: device check pending |
| Home layout | Keep the section-list Home | Matches the reference and works; an alternative is drafted as an experiment | kept; experiment `home-layout` (draft) |
| Tabs | Keep Home / DMs / Activity (Android), rail sections on web | A More tab adds a step; Search is in the header on both | kept |
| Threads, mentions, Later, reminders, polls, stickers, files, calls, voice rooms | Keep | Core collaboration; implemented and tested | kept |
| Unified Inbox (business messaging) | Keep for teams, hidden for communities | Real value for support teams; not a community tool | hidden in communities; Meta and TikTok blocked (no real accounts) |
| AI | Keep off by default, per workspace | Already gated and honest about E2EE limits | kept |
| HR / workforce | Not surfaced | Plan only (docs/HR_INTEGRATION_PLAN.md); nothing to show | kept hidden |
| Telegram | No Telegram module, connector or design reference exists in the product: one code comment ("Telegram-style @username lookup", `server/src/routes/users.ts`) and an old document (`docs/TELEGRAM_UPSTREAM_ASSESSMENT.md`) | Nothing to remove; username lookup is ordinary messaging | kept |
| Federation | Keep as is; not on the product surface | Backend capability, no UI cost | kept |
| Message-to-task, meeting follow-ups, catch-up summaries | Not built this round | Each needs its own design; catch-up summaries conflict with E2EE unless done on the device | later |
| Experiments and analytics | Framework kept as infrastructure; every experiment a draft; both switches off | Commercial A/B testing later, with the owner's approval | verified (tests; production checked: off, 0 rows) |

## 5. Integrations

The directory listed 14 connectable apps plus 28 "coming soon" placeholders.

- **Planned apps.** The server now leaves the placeholders out unless `?planned=true` is asked for. Old clients simply
  see fewer rows.
- **Tiers.** Each app carries `tier`, and both clients show **Recommended** first, then **More apps**.

| Tier | Apps | Note |
|---|---|---|
| Essential | Google Drive, Google Calendar, Microsoft OneDrive/Excel, Outlook Calendar, GitHub, Jira, Notion, Linear, incoming webhooks | Microsoft is externally blocked: app registration failed (docs/CONNECTORS.md) |
| Optional | SharePoint, Figma, GitLab, Asana, Zoom | Figma's client secret was lost; GitLab, Asana and Zoom were never registered |
| Planned (hidden) | Gmail, Outlook mail, Teams, Planner, Bitbucket, Sentry, Datadog, PagerDuty, Jenkins, CircleCI, Confluence, Trello, monday, ClickUp, Coda, Airtable, Canva, Miro, Dropbox, Box, Salesforce, HubSpot, Zendesk, Intercom, Zapier, Make | Most can already post through an incoming webhook |
| Sandbox | Meldiq sandbox | Only where the operator enables it |

**Apps the server can't connect yet** are hidden from people who can't set them up (`0cebcc5`). These are apps with
no provider credentials, state `needs_configuration`: today that is every app in production. Ordinary members would
only see entries they can neither use nor request. Admins still see them, with what the operator must configure.
Nothing is ever shown as connected or available unless the server's state says so.

No connector code, tokens or installs were removed. Hiding is a listing change only.

**Still unfinished:**
- every OAuth connector waits for provider credentials on the production server;
- Microsoft is blocked (registration failed);
- Figma's secret was lost;
- GitLab, Asana and Zoom were never registered.

## 6. Design system: motion

| Token | Android `Motion.*` | Web | Use |
|---|---|---|---|
| Instant | 90 ms | `--t-instant` | what leaves first (fading tab) |
| Quick / fast | 160 ms | `--t-fast` 120 ms | selection, toggles, hover |
| Standard | 220 ms | `--t-std` 200 ms | panels, sheets, dialogs |
| Page | 260 ms | `--t-page` | whole screens |
| Enter / exit curves | `EmphasizedDecelerate` / `EmphasizedAccelerate` | `--ease-enter` / `--ease-exit` | arrivals decelerate, departures accelerate |

- **Reduced motion.** Android navigation snaps when the system animator scale is 0. The web's
  `prefers-reduced-motion` turns off every transition and animation.
- **Tokens.** Android navigation transitions now use these tokens instead of literal tweens. The web already used
  tokens everywhere; two were added.

## 7. Cross-platform consistency

| Item | State |
|---|---|
| Brand indigo | **one token**, `#4B4FC9` (§3) |
| Workspace rail with unread | both (Android Home, web rail) |
| Team / community choice at create, owner switch | both |
| Directory tiers, planned apps hidden | both (server-driven) |
| Voice rooms listed once | both |
| Workspace admin menu grouping | web only. Android has no single admin menu; its workspace tools live on separate screens |
| Experiments / analytics client | both, silent by default |

## 8. Not done, and why

- **Phone acceptance.** Still the owner's.
  - Test build: `meldiq-2-redesign-test.apk` (`com.meldiq.android.test` 15 / 0.2.8-beta), from the final source.
  - Since API 0.9.0 is in production, the test build shows communities and tiers against production.
  - Android was not run here; no emulators, by rule.
- **The web** was checked in the in-app browser against a local throwaway server: the create dialog, the community's
  channels, the grouped menu and the directory. The brand-colour change was checked by contrast calculation, not
  visually.
- **Public community discovery, moderation tools (AutoMod-style), community events.** Later; they need policy decisions.
- **A full visual redesign of every screen.** Not attempted in one round.

## 9. Final verification (2026-10-21, on meldiq `0cebcc5` and web `a54ff5c`)

| Check | Command | Result |
|---|---|---|
| Server typecheck | `npx tsc --noEmit -p .` | pass |
| Server build | `npm run build` | pass |
| Server, SQLite | `npx vitest run` | 77 files passed, 1 skipped; **748 passed, 10 skipped, 0 failed** |
| Server, MongoDB mode | `MELDIQ_TEST_MONGO_DOMAINS=accounts,preferences,integrations npx vitest run --maxWorkers=3` | **748 passed, 10 skipped, 0 failed** |
| Skipped | the 9 live-S3 tests (`s3_live.test.ts`, need a real test bucket) and 1 MongoDB-only race test in SQLite mode | not run |
| Web typecheck / lint / format | `npm run typecheck`, `npm run lint`, `npm run format:check` | pass; lint 0 warnings |
| Web unit | `npm test` | 38 files, **245 passed** |
| Web full, against this server | `ORBIT_SERVER_DIR=… npx vitest run` | 64 files, **395 passed** (includes `live-communities`, sidebar voice-room and workspace-page rules) |
| Web build | `npm run build` | pass (Vite's existing large-chunk notice) |
| Android unit | `./gradlew :app:testDebugUnitTest` | **539 passed, 0 skipped, 0 failed** |
| Android lint | `./gradlew :app:lintDebug` | 0 errors, 155 warnings, 8 hints (154 before round 2) |
| Android release | `./gradlew :app:assembleRelease :app:bundleRelease` | pass (with `google-services.json`) |
| Android test build | `./gradlew -Pmeldiq.sideBySide=true :app:assembleDebug` | `meldiq-2-redesign-test.apk` |
| GitHub CI | `internal-beta` `ee3db8b`, `main` `4a79225` | green (before the final-acceptance commits) |

Not run:
- web browser end-to-end tests (Playwright);
- Android instrumented tests;
- the live S3 suite;
- anything on a phone.
