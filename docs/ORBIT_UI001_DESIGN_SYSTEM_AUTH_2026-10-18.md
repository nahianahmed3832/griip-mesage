# Orbit UI-001: design system and authentication redesign (2026-10-18)

Scope: the shared Orbit design tokens and components, and the signed-out screens (welcome, sign-up, log in, restore with
the recovery key, link from another phone, server sheet). The home (UI-002) and chat/profile (UI-003) redesigns are not
started: every signed-in screen keeps its current palette. Committed and pushed to `internal-beta` together with UI-002 (2026-10-18).

## What was inspected

- **Repo instructions.** `CLAUDE.md` (AWS guidance only; nothing applies to this UI task).
- **Earlier design work.** The audit `ORBIT_PRODUCT_TECHNICAL_AUDIT_2026-10-17.md` §6, plus `DESIGN_SYSTEM.md`,
  `UI_REDESIGN.md` and `LANGUAGES.md`.
- **Theme and components.** `ui/theme` (`Color`, `Type`, `Tokens`, `Theme` with accents and wallpapers) and
  `ui/components` (`PillButton`, `CircleIconButton`, `GriipTextField`, `GlassCard`, `StatusBanner`, `MenuRow`, `OrbitLogo`
  and others).
- **Onboarding as it was.** One file, `Onboarding.kt`: a three-slide pager welcome, create identity, restore, link device
  and the server sheet. Its state lived in the composables, which called `SessionRepository` directly; there were no
  ViewModels.
- **Navigation.** `AppRoot` shows `OnboardingFlow` while `SessionState.SignedOut` and switches to the main navigation by
  itself once the session signs in. There is no NavHost for onboarding, and system back wasn't handled: back from any
  onboarding step left the app.
- **Auth APIs and errors.** `SessionRepository.register / restore / startPairing / pollPairing / usernameAvailable` and
  `ApiException`. The server's own error codes are `invite_required`, `invalid_invite`, `registration_limited`,
  `registration_paused` and `too_many_devices`. The app's own codes are `bad_recovery`, `expired` and `network` (status 0).
- **References.** `docs/design-references/` (auth, home and chat-profile references) **does not exist** in the repo. The
  owner's attached image was used: three dark cocoa phone screens (group chat with peach @mentions, chat list with
  story row, chips and a floating bottom bar, profile with stat tiles). Colours were sampled from its pixels. The
  backdrop runs from `#6D534C` at the top to about `#100804`, @mentions are a warm peach-orange, and the page behind the
  phones is ivory `#F5EDE9`. There is no auth reference, so the auth screens follow the brief: warm ivory, dark type,
  charcoal buttons.

**Reused:** the token structure (`GriipColors`, `GriipType`, `Space`, `Shapes`, `Motion`), every component, Plus
Jakarta Sans, the `containerViewModel` helper, the i18n generator, `OnboardingRules`, `checkServer`, the Orbit logo and
lockup.

**Refined:** colour sets, button fill and sizing, the text field (focus, errors, shape), and the onboarding screens and
their state.

## Design system (shared, reusable by UI-002 and UI-003)

**Palettes** (`ui/theme/OrbitPalettes.kt`). Both are complete `GriipColors` sets, so every existing component draws
correctly in either:

| Token | Ivory (auth) | Cocoa (main app, for UI-002/003) |
|---|---|---|
| background / surface | `#F7F2ED` / `#FFFDFB` | `#171313` / `#292120` |
| text primary / secondary / tertiary | `#1C1716` / `#5F5450` / `#766963` | `#FFF8F3` / `#B8AAA5` / `#968883` |
| action (buttons) / label | charcoal `#221B1A` / `#FFF8F3` | peach `#F6A585` → pink `#EE8AA6` / `#2A1914` |
| accent gradient | `#B4502F` → `#A93A62` (dark enough for text on ivory) | `#F6A585` → `#EE8AA6` |
| outline (inputs) | `#8F817B` (3.4:1 on the page) | `#7D6E69` (3.2:1 on surfaces) |
| danger / success | `#B3261E` / `#276E45` | `#FF8A80` / `#7FD8A6` |

The brief's starting points were kept exactly for Cocoa. `OrbitPalettesTest` checks every text pair at 4.5:1 or more
(primary text 7:1) and input outlines at 3:1, for both palettes.

**New tokens.**
- Colours, in every palette: `outline`, `accentStart`, `accentEnd`, `onAccent`. In the existing light and dark palettes
  `outline` equals `border` and the accent equals the chosen accent colour, so signed-in screens don't change.
- Type and layout: `Griip.type.hero` (32/38 ExtraBold), `Space.screen` (24 dp), `Shapes.field` (16 dp) and
  `Shapes.panel` (24 dp).

**Theme API.**
- `ProvideGriipPalette(colors) { }` draws a subtree in one palette, with the matching Material scheme.
- `griipColorScheme(colors)` is extracted from `GriipTheme`, which now calls `ProvideGriipPalette`.
- `GriipColors.accentBrush()` gives the accent gradient.

**Components:**
- **`PillButton`:**
  - the primary fill is the palette's `actionStart → actionEnd` gradient (solid where both are equal, as in every
    existing palette);
  - the height is a minimum, so the button grows with large font sizes instead of clipping, and labels wrap to two
    lines;
  - a disabled or loading button keeps its button role and is marked disabled in semantics;
  - the outlined tone uses `outline`.
- **`CircleIconButton`:** a disabled icon button is now marked disabled in semantics. Before, it lost its click
  semantics and was read as a plain label. It looks the same as before.
- **`GriipTextField`:**
  - a focus outline in the primary colour;
  - `shape` and `textStyle` parameters;
  - the error is set as the field's semantic error, and the error text is a polite live region.
- **`ChoiceCard`** (new): an icon tile, title, explanation and chevron, merged into one button in semantics.
- **`GriipTopBar`:** the "Back" label comes from string resources.

**What signed-in screens notice.** Their palettes are unchanged, but the shared components changed a little
everywhere:
- text fields get the primary-colour outline while focused, and a focused field scrolls fully into view above the
  keyboard;
- a `PillButton` label that doesn't fit wraps to a second line and the button grows, instead of being cut off with an
  ellipsis;
- "Back" in top bars is localised;
- disabled icon buttons and buttons are marked disabled in semantics.

Primary buttons look the same, because `actionStart` equals `actionEnd` in the light and dark palettes and every accent.

## Authentication screens

All existing fields, rules, API calls, loading and error behaviour are kept. No auth method was added.

- **Flow** (`Onboarding.kt`):
  - Welcome → Sign up, or Welcome → Log in → (Link from another phone | Use your recovery key).
  - System back now walks the steps: Restore and Link go to Log in, which goes to Welcome. Before, back left the app
    from any step.
  - Back is held while keys are being made or an account restored, because leaving mid-way could leave a
    half-registered account.
  - Steps slide and fade (220 ms). The flow is always ivory, and `MainActivity` asks for dark system-bar icons while
    signed out, also in dark mode. Not checked on a device.
- **Welcome** (`WelcomeScreen.kt`):
  - top: the Orbit lockup (7 taps still toggle developer options) and the server button where it was allowed before;
  - middle: the Orbit mark in a thin accent orbit over a soft glow, the two-line headline (second line in the accent
    gradient), one supporting line and an "End-to-end encrypted · Your keys stay on this phone" note;
  - bottom: **Sign up** (charcoal, primary) and **Log in** (outlined, secondary).
  - The three-slide pager and its animated emerald art (`ui/art/HeroArt.kt`, an infinite animation) were removed; the
    slides' messages are condensed into the headline and the two lines below it.
  - It is built to scroll on small screens or with large fonts and caps its width at 520 dp. Neither was checked on
    a device.
- **Sign up** (`SignUpScreen.kt`, `SignUpViewModel.kt`):
  - a profile preview with an avatar-colour radio group (48 dp targets in an even 4 + 4 grid, a person icon until a name
    is typed);
  - fields: display name, username (suggested from the name until edited, checked with the server after a 400 ms pause
    with "Checking…", available, taken or "can't reach the server" states) and invitation code;
  - the keys-on-this-phone card, the server line and the cleartext warning as before.
  - Keyboard: Next moves through the fields; Done on the invitation code submits when the form is complete.
  - Username format problems are localised: `ProfileRules.usernameIssue` returns which rule failed, and
    `usernameProblem` keeps its English text.
  - Invitation refusals (`invite_required`, `invalid_invite`) show under the invitation field; other failures show in a
    banner; a lost connection has its own localised message.
  - Codes have autocorrect off, and fields are disabled while submitting.
  - While the keyboard is open, the "Already have an account?" link hides, and the focused field (with its help text)
    stays above the pinned button.
- **Log in** (`SignInScreens.kt`): explains that Orbit has no passwords, offers two choice cards (link from another
  phone, use your recovery key) and links back to Sign up.
- **Restore:**
  - username and recovery key (monospace, on a password-type keyboard: no suggestions, learning or composing; the key
    stays visible), with a helper saying where the key lives;
  - `bad_recovery` shows under the key field;
  - "Restoring…" while working.
- **Link:**
  - the link code in groups of five, with a letter-by-letter content description for TalkBack;
  - "Waiting for approval…", inside a polite live region;
  - on failure (expired code, no connection), a banner with **Try again** that asks for a fresh code;
  - polling (every 2 s, as before) stops when the screen is left.
- **Server sheet:** strings localised, otherwise unchanged.
- **State:** the logic moved out of the composables into `SignUpViewModel`, `RestoreViewModel` and
  `LinkDeviceViewModel`, behind `AuthGateway`. In the app that is `SessionAuthGateway`, which forwards every call
  unchanged to `SessionRepository`, so keys, signatures and requests are untouched. The ViewModels outlive a step
  change, so going back and forth keeps what was typed.
- **Strings:** 69 new keys in English, Bengali and Simplified Chinese, from `android/i18n/strings.py`.
  - `gen.py` now escapes a value starting with `@` or `?`: `@%1$s is available` would otherwise be read as a resource
    reference. `UiRulesTest` guards this.
  - The Bengali and Chinese text needs a native-speaker review, as with earlier rounds.

**Not changed:** server, API, database schema, encryption, key management, permissions, message delivery, the
signed-in screens' look, the launcher icon and branding assets. No test credentials, dummy users or simulated success
are in the app; the fakes live only in test source sets.

## Changed files

Main code (`android/app/src/main/java/com/griipbuddy/orbit/`):
- `ui/theme/OrbitPalettes.kt` (new): the Ivory and Cocoa palettes and `accentBrush()`.
- `ui/theme/Color.kt`: the `outline`, `accentStart`, `accentEnd` and `onAccent` tokens, set in the light and dark
  palettes.
- `ui/theme/Theme.kt`: `ProvideGriipPalette` and `griipColorScheme`; accents also colour the accent gradient.
- `ui/theme/Type.kt`: the `hero` style.
- `ui/theme/Tokens.kt`: `Space.screen`, `Shapes.field` and `Shapes.panel`.
- `ui/components/Buttons.kt`: `PillButton` gradient fill, growing height and disabled semantics; disabled semantics for
  `CircleIconButton`.
- `ui/components/Inputs.kt`: `GriipTextField` focus outline, shape, text style and error semantics; a focused field
  brings its whole box and help text into view, also while the keyboard opens.
- `ui/components/Surfaces.kt`: `ChoiceCard`.
- `ui/components/Chrome.kt`: localised Back label.
- `ui/screens/onboarding/Onboarding.kt`: the new flow (steps, back handling, palette) and the localised server sheet;
  `RevokedScreen` and `OnboardingRules` unchanged.
- `ui/screens/onboarding/` (new):
  - `AuthGateway.kt`: the gateway and error sorting;
  - `AuthScaffold.kt`: the frame, error banner and switch link;
  - `WelcomeScreen.kt`, `SignUpScreen.kt`, `SignUpViewModel.kt`, `SignInScreens.kt`, `SignInViewModels.kt`.
- `ui/art/HeroArt.kt` (deleted): only the old welcome used it.
- `data/model/ProfileRules.kt`: `UsernameIssue` and `usernameIssue()`. `usernameProblem()` is unchanged in behaviour.
- `MainActivity.kt`: dark system-bar icons while signed out.

Resources and tooling:
- `res/values{,-bn,-zh-rCN}/strings.xml`: generated, additions only.
- `android/i18n/strings.py`: the new strings.
- `android/i18n/gen.py`: the leading `@` / `?` escape.

Tests:
- `test/.../ui/theme/OrbitPalettesTest.kt` (new): 4 tests.
- `test/.../ui/screens/onboarding/` (new): `FakeAuthGateway.kt`, `SignUpViewModelTest.kt` (11),
  `SignInViewModelsTest.kt` (8) and `AuthFlowTest.kt` (3).
- `test/.../data/model/ProfileRulesTest.kt`: +1 test.
- `test/.../ui/UiRulesTest.kt`: an escape check in the strings test.
- `androidTest/.../OnboardingFlowTest.kt` (new): 7 Compose UI tests, run on the owner's phone: **7/7 pass** (see below).

Docs: this report, `DESIGN_SYSTEM.md` (2026-10-18 note) and `LANGUAGES.md` (coverage).

## Tests run and results

The Gradle checks ran on the Mac with `JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home`. The device
checks ran on the owner's phone (HONOR ELI-AN00, Android 16, `AREBVB4530006465`) with the SDK adb, against the
side-by-side debug build only. No emulator was used.

### Gradle checks (Mac)

**Run of record**, on the final UI-001 code including the phone-review fixes (2026-10-18 02:17–02:24):

```bash
cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:compileDebugAndroidTestKotlin
```

`BUILD SUCCESSFUL in 7m 43s`, exit 0.

| Check | Result |
|---|---|
| `testDebugUnitTest` | **PASS**: 331 tests in 63 classes, 0 failures, 0 errors, 0 skipped |
| `lintDebug` | **PASS**: 0 errors, 80 warnings, 5 hints (details below) |
| `assembleDebug` | **PASS**: `app-debug.apk` |
| `assembleRelease` | **PASS**: `app-release-unsigned.apk`, minified with R8 (unsigned, as in CI) |
| `compileDebugAndroidTestKotlin` | **PASS**; the tests themselves ran on the phone (below) |

**Unit tests, by class:**

| Class | Tests | Result | What it covers |
|---|---|---|---|
| `SignUpViewModelTest` (new) | 11 | PASS | Username suggestion, input cleaning, format problems (never sent to the server), the 400 ms check with only the latest answer counting, taken/unreachable states, re-check on a server change, loading and the single registration call, invitation refusal on its field, network error, an incomplete form never sent |
| `SignInViewModelsTest` (new) | 8 | PASS | Restore validation, loading, `bad_recovery` on the key field; link code then linked after polls, expired code with retry for a new code, start failure, stop on leaving, no duplicate start |
| `AuthFlowTest` (new) | 3 | PASS | Back targets and slide direction; error sorting (network, server, unknown, invite, recovery key); cancellation passed on |
| `OrbitPalettesTest` (new) | 4 | PASS | Ivory and Cocoa text contrast ≥ 4.5:1 (primary text ≥ 7:1) and input outlines ≥ 3:1; the brief's Cocoa values; accent choice colours the gradient |
| `ProfileRulesTest` | 5 (1 new) | PASS | `usernameIssue` names each rule; `usernameProblem`'s English text unchanged |
| `UiRulesTest` | 6 | PASS | Includes every language having every key with the same placeholders, plus the new check that no value starts with an unescaped `@` or `?` |
| Other existing classes (57) | 294 | PASS | Includes `InitOrderTest`, `RegisterReqTest`, `InviteContractTest`, `ApiErrorsTest`, `ServerProfilesTest`, `NavMotionTest` and the crypto tests |

**String generation.** `python3 -I android/i18n/gen.py` reproduced the three committed `strings.xml` files byte for byte:
`values` has 241 keys, `values-bn` and `values-zh-rCN` 239 each (the two untranslatable brand keys are English only).
The script imports `strings.py`, so even with `-I` it rewrites the tracked
`android/i18n/__pycache__/strings.cpython-314.pyc`. That file was restored after each run and isn't part of this change.

**Lint.** None of the 80 warnings is in a file added by UI-001. The ones in touched files existed before:
- `ModifierFactoryExtensionFunction` on `Wallpapers.modifier` in `Theme.kt`, which only moved lines;
- `ModifierParameter` in `Display.kt`, which wasn't edited;
- five `UnusedResources` in `strings.xml`: `brand_attribution`, `brand_tagline`, `composer_message`, `translate_failed`
  and `translate_copy`, all unused at `066c1de` too.

The rest are dependency-version, `UseKtx` and SDK notices. Compared with the first run that day, the only change is one
more `NewerVersionAvailable`, a version lookup.

**Earlier Gradle runs.** The same command set passed three times on earlier states of the code. Each was followed by
edits:
- the run-time review fixes (`ProvideGriipPalette` changes only colours, loading buttons aren't dimmed, the Link screen
  pads for the navigation bar, `CircleIconButton` disabled semantics);
- the phone-review fixes below.

### Compose UI tests on the phone: `OnboardingFlowTest`, 7/7 PASS

The app and test APKs were built with `-Porbit.sideBySide=true` and installed with `adb install -r`, which keeps Orbit
Test's data; the owner tapped the HONOR install prompt. The tests ran with:

```bash
adb -s AREBVB4530006465 shell am instrument -w -r -e class com.griipbuddy.orbit.OnboardingFlowTest com.griipbuddy.orbit.androidtest/androidx.test.runner.AndroidJUnitRunner
```

`am instrument` was used rather than `connectedAndroidTest`, which uninstalls the app afterwards.

| Run | Result | What happened |
|---|---|---|
| 1 | 4 pass, **3 fail** | Two crashed with SQLCipher "connection pool has been closed" / "database is locked". The test opened and closed an isolated `AppContainer` around each test, and its background outbox query raced the close and reopen: a test-harness problem. The third (`linkingShows…`) looked for the code's visible text, which is deliberately replaced by a letter-by-letter TalkBack description: a wrong assertion. |
| 2 | 6 pass, 1 fail | After switching to one container per class: only the link assertion left. |
| 3 | **7/7 pass** (`OK (7 tests)`, 24 s) | After asserting on the description, which the semantics dump showed as `Link code: A B C D E 1 2 3 4 5` |
| 4 | **7/7 pass** | On the final code with the phone-review fixes, plus a new check that the Welcome trust line is shown |

The test's leftover isolated profile (`griip_identity_itestonboarding.xml`, `griip-itestonboarding.db`) was deleted from
Orbit Test after each run; nothing else in its storage was touched.

### Visual and behaviour review on the phone

Setup:
- The phone was in **dark mode** at font scale 1.0.
- The owner added an empty, signed-out profile in Orbit Test (Settings › Accounts › Add account), so their own two
  profiles weren't touched.
- An isolated local server (current `server/src`, its own data folder, `REGISTRATION_MODE=open`, port 18091) was
  reached with `adb reverse`, so nothing touched production.

| Screen or flow | Result |
|---|---|
| Welcome | Ivory in dark mode, dark status-bar icons; hierarchy and Orbit branding as intended. **Defect:** the lock icon sat far left when the trust line wrapped. Fixed (icon inside the text); the line now fits on one row. |
| Server sheet | Ivory sheet; rides above the keyboard; focused field shows the darker outline; **Test** showed the success banner for the local server; **Save** applied it |
| Log in | Heading, the no-passwords explanation, two choice cards, "New to Orbit? Sign up" link |
| Restore | Dimmed button until complete. A malformed key shows "That recovery key isn't valid. Check for typos." under the key field with a red outline; no request was sent. **Defects:** the keyboard showed suggestions built from the key, and upper-casing while the keyboard was composing lost characters (`jkmn-pqrs` became `JKMNPQR`). Fixed with a password-type keyboard. Afterwards the phone's secure keyboard appeared (screenshots are blocked while it's up) and the key came through exactly as typed. |
| Link | Real `POST /v1/pairing` to the local server; the code shows in groups of five with "Waiting for approval…". Back stops the polling: 6 polls, still 6 seven seconds later. |
| Sign up | Name → suggested `@ui.tester` → "@ui.tester is available" from the server, with a green tick. Next moves through the fields. **Defect:** with the keyboard open, the focused field was half hidden behind the pinned "Create account" bar. Fixed: focused fields bring their whole box and help text into view, and the "Already have an account?" link hides while typing. Rechecked for the name and invitation fields. The colour swatches now form an even 4 + 4 grid. |
| Successful sign-up | Done on the keyboard submitted; the server logged `challenge` 200 and `register` 200. The app left onboarding by itself and opened Chats for the new account, in the existing dark emerald look, so signed-in theming is unchanged. |
| Crash found and fixed | The first version of the trust-line fix crashed while composing (`appendInlineContent` refuses an empty alternate text). It was fixed before the final runs and is now covered by `welcomeOffersSignUpAndLogIn`. |

Also seen:
- **Your profiles reached my first server.** Orbit Test's other two profiles point at `localhost:18090`. While a first
  test server ran there (01:58–02:05), Orbit Test reached it. All 39 logins got the generic 401 "Invalid device proof"
  (an unknown device), never `device_revoked` or `account_disabled`, so their session state can't have changed. The
  test server was moved to 18091 and the 18090 forward removed.
- **Focus highlight.** Hardware-key focus (from `adb` key events) shows the standard grey focus highlight; on the
  lockup it is now rounded.

**No backend, auth or crypto change.**
`git diff --stat` is empty for `SessionRepository.kt`, `data/api`, `crypto`, `data/db`, `server/`, `AndroidManifest.xml`,
`app/build.gradle.kts` and `gradle/libs.versions.toml`. The onboarding's only account calls are `SessionAuthGateway`'s
one-line forwards to `SessionRepository.register / restore / startPairing / pollPairing / usernameAvailable`, with the
same arguments the old composables passed.

**Still not verified:**
- TalkBack with a screen reader running;
- large font scales and small or landscape screens;
- a completed restore or link: there was no second signed-in device or saved key on the local server;
- dark-mode-off rendering. The auth screens use fixed ivory, but the phone stayed in dark mode.

## Remaining issues

- **Partly verified on the phone.** See "Still not verified" above. For future on-device runs, install the side-by-side
  build with the SDK adb and use `am instrument`; `connectedAndroidTest` uninstalls the app afterwards.
- **The recovery key is now a password field for the system.** With the password keyboard, Compose marks the field as a
  password. TalkBack then reads typed characters only if the phone's "speak passwords" setting allows, and the phone
  blocks screenshots while that keyboard is up. Both seem right for a secret key, but it's a change.
- **A pre-existing container-close race.** `AppContainer.close()` cancels its scope and then closes the database while a
  Room query can still be running; the instrumented test hit it as an uncaught "connection pool has been closed". The
  app closes containers on a profile switch or erase, so the same race may exist there. It isn't caused by UI-001 and
  wasn't changed.
- **No design references in the repo.** `docs/design-references/` is missing, so the auth look follows the written
  brief; compare with `auth-reference.png` once it's added.
- **Cocoa isn't used yet.** It is defined and contrast-tested; UI-002 and UI-003 should adopt it with
  `ProvideGriipPalette(CocoaColors)`, or make it the dark theme.
- **Server messages stay English.** They come from the server (`invite_required`, `registration_limited` and others)
  and are shown as sent. So do `RevokedScreen` and the "by GriipBuddy" lockup line.
- **Auth is always ivory.** In dark mode the signed-out screens stay light by design (brief); a dark variant would be a
  follow-up if wanted.
- **Unused parameter.** `OnboardingFlow`'s `pendingInvite` was already unused before this change. An invite link opened
  while signed out still doesn't fill the invitation code.
- **No window-size classes.** Wide screens get a centred column capped at 520 dp, not a two-pane layout.
