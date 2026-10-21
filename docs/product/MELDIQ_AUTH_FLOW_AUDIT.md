# Meldiq sign-in and recovery flow audit (2026-10-21)

Owner's brief (Ultimate Master Prompt §5): normal sign-in and email verification stay simple. The recovery choices
appear only after the person asks for help. Every working recovery mechanism is kept.

## 1. What was on screen before

Two screens showed recovery choices all the time:

| Screen | Source | Permanently shown |
|---|---|---|
| Sign in (`LogInEmailScreen`) | `android/.../ui/screens/account/AccountScreens.kt` | "Forgot your password? Reset it", "Lost access to your email? Use a recovery code", and a "Move an existing account" card |
| Unlock your messages (`DeviceSetupScreen`, right after a new phone signs in or verifies) | `android/.../ui/screens/account/DeviceSetupScreens.kt` | "Get a code by email", then under "Can't get the email?": "Approve from another phone", "Use my recovery key", "Start fresh" |

The four ways to unlock a new phone are the **four existing methods** the brief refers to. The "recovery code
option" is the recovery key. The other three are the emailed code (key backup), approval from another phone, and
starting fresh. The email verification screen (`VerifyEmailScreen`) was already clean: code, Verify, resend, and
"Use another one". It is unchanged.

## 2. What these flows are for (kept separate)

| Purpose | Flow | Server | Secret involved |
|---|---|---|---|
| Identity: who is signing in | Email + password, then the 6-digit email code on first sign-in | `routes/auth.ts`, `accounts/service.ts` | password (scrypt-hashed), code (hashed, 15 min) |
| Password recovery | Forgot → emailed reset code → new password | same | reset code (hashed) |
| Account recovery (lost email) | Recovery code + new address → code to the new address | `accounts/recovery.ts` | single-use recovery codes (hashed) |
| Legacy-account migration | Username + recovery key, once | `accounts/legacy.ts` | legacy recovery key (proves the old identity) |
| End-to-end key recovery on a new phone | Emailed unlock code + password (key backup), another phone, recovery key, or start fresh | `mongo/keyBackups.ts`, key backup routes | keys sealed under the password; the server never holds them in the clear |

Meldiq has no separate MFA factor today. Nothing here adds or removes one.

## 3. What changed

**Sign in.** The screen shows email, password, Sign in, "New to Meldiq? Create account", and one quiet link,
**"Having trouble signing in?"**. The link opens a bottom sheet (`AccountHelpSheet`) titled "Having trouble signing
in?", with a one-line explanation and these choices:

- **I forgot my password** → Forgot (emailed reset code).
- **I can't get into my email** → Recover (recovery code + new address). Hidden during a legacy move (that account
  has no recovery codes yet), as before.
- **Move an existing account** → LegacyCredentials. Hidden where it was hidden before (the Account Gate's sign-in).
- **Back to sign in** closes the sheet.

**Unlock your messages.** What the screen shows depends on what the server says about the key backup:

| Key backup | Screen shows |
|---|---|
| Exists for the current password (`keyBackup == true`) | An explanation, the main button **Get a code by email**, and the link **"Can't access your email?"**. The link opens "Other ways to unlock" with Approve from another phone, Use my recovery key, Start fresh, and "Back to the email code". |
| Still being checked | "Checking for a backup of your keys…" with a spinner (new: before, the options flashed first). |
| None (for example after a password reset) | The existing explanation and the three ways listed directly: with no email way, they *are* the normal path. |
| The server couldn't say | A warning with **Try again** (new: `AccountFlowViewModel.checkKeyBackup()`), and the three ways. |

Nothing opens on its own: the sheets open only when the link is tapped. The typed email is held in
`AccountFormState`, so it survives opening a sheet, choosing a way, and coming back. No server code, API, or security
check changed.

Files:
- `AccountHelpSheets.kt` (new): option lists, `AuthHelpLink`, `AccountHelpSheet`.
- `AccountScreens.kt`, `DeviceSetupScreens.kt`, `AccountFlowViewModel.kt`: the changes above.
- `android/i18n/strings_keybackup.py`: new strings (English, Simplified Chinese, Bengali), generated into
  `res/values*/strings_keybackup.xml`.

## 4. Verification (actual)

**Unit tests.** `./gradlew :app:testDebugUnitTest --tests 'com.meldiq.android.ui.screens.account.*' --tests
'com.meldiq.android.data.account.*'`: 61 tests, 0 failures. New `AccountHelpTest`:
- sign-in help lists only the allowed ways;
- unlock help lists the three ways;
- a new phone with a backup is offered the emailed code;
- a failed backup check can be tried again;
- choosing help keeps the typed email.

The existing `AccountFlowViewModelTest` and `KeyBackupFlowTest` still pass.

**Emulator.** Medium_Phone_API_36.1 with the local API 0.13.0 server (`MAIL_TRANSPORT=file`), debug build of this
change:
1. Sign up → code screen (clean) → verify → recovery key shown once → in the app.
2. App data cleared (a "new phone") → sign-in screen shows only email, password, Sign in and the help link
   (`screens-2026-10-21/auth-1-sign-in.png`).
3. The help link opens the sheet with three choices (`auth-2-sign-in-help.png`). "I can't get into my email" opens
   Recover with the email filled in. Back returns to sign-in with the email still there.
4. Sign in → Unlock your messages, showing only Get a code by email and "Can't access your email?"
   (`auth-3-unlock.png`). The link opens "Other ways to unlock" (`auth-4-unlock-help.png`). "Back to the email code"
   closes it.
5. Get a code by email → code from the mail file → Unlock → the app opened with the same identity.

**Not verified.**
- A physical phone.
- The "couldn't check" banner on a device (unit-tested only).
- Bengali and Chinese layouts (text written by the developer, not reviewed by native speakers).

## 4a. Meldiq Web (`/Volumes/code_files/orbit_web`, repository meldiq-web)

The web had the same problem:
- Sign-in listed "Forgot password?", "Lost access to your email?", "Move my account" and "Link with your phone
  instead" at all times.
- "Set up this browser" showed every way as tabs.

Changed to match Android:
- `src/ui/auth/AccountForms.tsx`: sign-in shows Sign in, "Having trouble signing in?" and "Create an account". The
  link swaps the form for a `SignInHelp` view with the same choices as Android, plus "Link with your phone instead"
  (a web-only way in), and "Back to sign in". The form stays mounted, so the typed email is kept.
- `src/ui/auth/DeviceSetupViews.tsx`: with a key backup, only "Email me a code" and "Can't access your email?". The
  link shows the phone and recovery-key ways, with "Back to the email code". It also shows "Checking for a backup of
  your keys…" while checking, and a "Try again" banner when the server couldn't say.
- `src/styles/views.css`: `.auth-choices` / `.auth-choice`.

Verification:
- `tsc -b`, `eslint .` and `prettier --check` are clean. `npm test`: 255 passed.
- Playwright (Chromium, against this repository's API 0.13.0 server): `e2e/accounts.spec.ts` (2 passed: sign-up,
  reload, a second browser through "Can't access your email?" → recovery key, and the forgotten password through
  the help view) and `e2e/cross-device.spec.ts` (3 passed: linking with the phone through the help view).
- Other browsers were not run this time.

## 5. Acceptance (brief §5.4)

| Criterion | Result |
|---|---|
| Default sign-in doesn't permanently show recovery methods | Done (emulator) |
| Email verification screen stays clean | Unchanged, clean (emulator) |
| Discreet help link available | Done |
| Link opens recovery selection | Done (bottom sheet) |
| All four methods still reachable when eligible | Done: email code on the screen, other three in the sheet; without a backup, the three are listed |
| Choosing an option launches its real flow | Done: Recover, Forgot, Legacy, Link, RestoreKey and StartFresh are the existing steps |
| Back returns to the right place | Done: sheet dismiss and system Back |
| Email verification and session creation still work | Done (emulator, unit tests) |
| No login loop or lost email | Done (unit test + emulator) |
| MFA and cryptographic recovery rules intact | No security logic changed |
| Android and web consistent | Done: same help links and choices (web adds "Link with your phone instead") |
