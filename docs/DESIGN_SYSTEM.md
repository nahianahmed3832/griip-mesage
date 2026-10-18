# Griip Message — Design System

> **2026-10-16 redesign:** the glass, gradient and teal/lavender/peach language below was replaced by a flat emerald-on-neutral
> system (tokens in `ui/theme/Color.kt`, components in `ui/components`). See `UI_REDESIGN.md` for the current system. The rest
> of this file is the original rationale, kept for history.
>
> **2026-10-18 (UI-001), the Orbit direction:** two new palettes in `ui/theme/OrbitPalettes.kt`, both complete `GriipColors`
> sets so every component draws in them:
> - **Ivory** (`IvoryColors`): warm ivory `#F7F2ED`, dark type `#1C1716`, charcoal buttons `#221B1A`, 3:1 input outlines.
>   Welcome, sign-up and sign-in always use it (light and dark mode alike), with dark status-bar icons.
> - **Cocoa** (`CocoaColors`): background `#171313`, raised surface `#292120`, text `#FFF8F3` / `#B8AAA5`, a peach `#F6A585`
>   → pink `#EE8AA6` accent. Since UI-002 it is the palette of every signed-in screen (tabs, settings, workspace, calls, the chat screen's colours), whatever the app theme; the chat and profile layouts follow in UI-003.
>
> New tokens: `outline`, `accentStart`, `accentEnd`, `onAccent` (every palette), `Griip.type.hero` (32/38 ExtraBold),
> `Space.screen` (24 dp), `Shapes.field` (16 dp) and `Shapes.panel` (24 dp). `ProvideGriipPalette(colors) { }` draws a
> subtree in one palette; `accentBrush()` gives the accent gradient. Components: `PillButton` fills with the palette's
> action gradient and grows with font size; `GriipTextField` gains a focus outline, a `shape`, a `textStyle` and sets its
> error in semantics; `ChoiceCard` (icon, title, explanation, chevron). Contrast is unit-tested in `OrbitPalettesTest`;
> the ivory auth screens were reviewed on one phone (HONOR, Android 16, dark mode); Cocoa hasn't been seen on a device.
> Details: `ORBIT_UI001_DESIGN_SYSTEM_AUTH_2026-10-18.md`.

## 1. Reference analysed

One reference was supplied: a six-screen mobile concept for a dental-care app ("Healthy Smile / Happy Life").
It sets the visual system for the whole of Griip Message. It shows these screens: onboarding hero, greeting dashboard, filterable
catalogue list, booking calendar, tabbed health report, and profile/settings. The reference was inspected
directly (image `1.webp`, 736×1104). Colours were sampled from the pixels with a script, not estimated by eye.

We translate its **visual language**. We do not copy its content: no teeth, no dental copy, no logos, no
3D product renders. Griip Message gets its own mark (a glossy speech bubble with a shield-lock) and its own
illustrations, drawn in code.

### Sampled values (reference → interpretation)

| Sample point | Pixel | Interpretation |
|---|---|---|
| "Get Started" CTA | `#2DBDAF` | Primary teal |
| Featured card, left → right | `#31C3C4` → `#41B9B0` | Cyan-teal gradient for hero surfaces |
| Selected nav item / toggle | `#1AABA1` / `#2FAC9D` | Primary, slightly deeper for small marks |
| "Happy Life" accent text (darkest AA pixel) | `#0C746A` | Deep teal for text-on-light accents |
| Headline text | `#00110D` | Near-black with a green-teal cast |
| Body copy | `#475A56` | Teal-grey secondary text |
| Description text | `#878278` | Warm grey tertiary text |
| Card fill inside phone | `#F5F2EC` / `#FEF9F6` | Warm translucent white |
| Background corners | mint `#BFE6E3`-ish → peach `#FFD5BC` | Diagonal mint → peach canvas |
| Purple icon tile | `#F3EAF9` | Lavender secondary accent |
| Emergency icon | `#E18464` | Coral for danger and urgency |

### Character of the reference

**Soft, premium, airy glassmorphism on a pastel gradient.**
- Pale mint → white → peach diagonal canvas. Content sits on translucent, frosted white cards with
  hairline white borders and very soft teal-tinted shadows.
- A single saturated colour, teal, does all the work for actions and selection. Everything else is neutral.
- Generous radii (cards ≈ 20–24 dp, pills fully round), generous whitespace, low information density,
  strong hierarchy built from weight and colour rather than lines or dividers.
- Primary CTAs are **full-width teal pills with a circular "arrow knob"** at the trailing end.
- Selection uses **filled teal pills** (filter chips, segmented tabs, time slots, calendar day) on neutral
  translucent pills.
- Icons are thin line icons inside **tinted rounded-square tiles** (teal, lavender, coral).
- Lists are **stacked frosted cards**. Each has a leading visual tile, a bold title, a grey description, a trailing
  value, and a circular teal arrow button.
- Screen headers use a centred title, a back arrow on the left and one contextual action on the right.
- The dashboard opens with a greeting ("Good Morning, Alex 👋"), the avatar and a bell, then a search pill,
  then a featured gradient card.
- Data is shown as a ring gauge, a slim progress bar and a three-up stat tile row.
- Bottom navigation has four items. The selected one is teal with a label; the others are grey.

## 2. How the reference maps to Griip Message

| Reference element | Griip Message use |
|---|---|
| Onboarding hero + two-tone headline + pill CTA + page dots | Welcome screen: "Talk freely. / Work together." with a code-drawn glossy bubble-and-shield hero |
| Greeting header + bell + avatar | Home: "Good morning, {name} 👋", connection dot, avatar |
| Search pill with trailing filter icon | Search across conversations, people and channels |
| "Your next appointment" gradient card | Live workspace card: active call (Join) or current branch with unread summary |
| Quick-action icon tiles | New message, Find people, Start call, Announce |
| Ring gauge "Dental Health Score" | "Inbox health": share of conversations read, plus connection and encryption status |
| Treatments list (tile, title, desc, price, arrow) | Teams, channels and branches lists (icon tile, name, topic, member count, arrow) |
| Filter chips (All / General / Cosmetic …) | Conversation filters (All / Direct / Groups / Channels) and the branch switcher |
| Segmented tabs (Overview / History / Analysis) | Workspace tabs (Teams / Channels / People), call participants vs chat |
| Calendar + time slot grid | Member role picker grid, invite expiry/uses picker |
| Doctor card with rating | Member / contact card with role badge and presence |
| Stat tile row (Cavities / Plaque / Gum) | Workspace stats (Members / Teams / Channels), call stats |
| Profile header card + menu rows + teal switch | Profile & settings (Devices, Recovery key, Security, Notifications, Appearance, Server) |
| "View Full Report" CTA | Primary actions at the bottom of detail screens (Create invite, Start call, Join) |

Messaging-specific components that the reference does not contain, such as chat bubbles, the composer, call
stage and screen-share banner, are built from the same rules:
- Outgoing bubbles use the teal gradient. Incoming bubbles are frosted glass.
- The composer is a frosted pill. The send button is the teal arrow knob.
- Call controls are frosted circular buttons. The end-call button is coral.
- The call stage keeps the tinted canvas instead of going pitch black, except under live video.

## 3. Tokens

All tokens live in `android/app/src/main/java/com/griipbuddy/message/ui/theme/`.

### 3.1 Colour — light

| Token | Value | Use |
|---|---|---|
| `primary` | `#1FB5A6` | CTAs, selection, links, outgoing bubble end |
| `primaryBright` | `#34C8C2` | Gradient start (hero cards, CTA, outgoing bubble) |
| `primaryDeep` | `#12897F` | Accent text on light surfaces, pressed state |
| `primarySoft` | `#D6F3EF` | Tinted icon tiles, selected-row wash, unread chips |
| `onPrimary` | `#FFFFFF` | Text and icons on teal |
| `lavender` / `lavenderSoft` | `#8E7CE0` / `#EEE8FB` | Secondary icon tiles (channels, roles) |
| `peach` / `peachSoft` | `#FF9F7A` / `#FFE6DA` | Warm accents (announcements, mentions) |
| `danger` / `dangerSoft` | `#E5604D` / `#FDE3DE` | Errors, end call, destructive actions |
| `warning` | `#F2A93B` | Reconnecting, pending |
| `success` | `#22B07D` | Online, delivered, connected |
| `canvasTop` → `canvasMid` → `canvasBottom` | `#E6F6F3` → `#F8FBFA` → `#FDEEE6` | Diagonal app background |
| `glass` | `#FFFFFF` @ 72 % | Default card / list surface |
| `glassStrong` | `#FFFFFF` @ 90 % | Sheets, dialogs, composer |
| `glassBorder` | `#FFFFFF` @ 95 % | 1 dp card stroke (the "frost edge") |
| `hairline` | `#13221F` @ 6 % | Rare separators, inactive pill tracks |
| `textPrimary` | `#13221F` | Titles, body |
| `textSecondary` | `#5A6B67` | Descriptions, previews |
| `textTertiary` | `#94A19D` | Timestamps, placeholders, inactive nav |
| `shadow` | `#0E5E57` @ 10–14 % | Soft card shadow (teal-tinted, never grey-black) |

### 3.2 Colour — dark

Dark mode keeps the same structure and swaps in a deep teal-ink canvas.

| Token | Value |
|---|---|
| `primary` / `primaryBright` / `primaryDeep` | `#3DD3C2` / `#4FE0D6` / `#7FE6DA` |
| `primarySoft` | `#16413D` |
| `canvasTop` → `canvasMid` → `canvasBottom` | `#0B1B1D` → `#0E1719` → `#1C1517` |
| `glass` / `glassStrong` | `#1B2E31` @ 72 % / `#1F3336` @ 94 % |
| `glassBorder` | `#FFFFFF` @ 8 % |
| `textPrimary` / `textSecondary` / `textTertiary` | `#E9F3F1` / `#A3B6B2` / `#6E817D` |

### 3.3 Typography

Font: **Plus Jakarta Sans**, a variable font under the SIL Open Font License 1.1, bundled in `res/font`. It shares the
reference's friendly geometric feel and holds up better than a pure geometric face at chat text sizes.

| Style | Size / line | Weight | Use |
|---|---|---|---|
| `display` | 30 / 36 | Bold | Onboarding headline (two-tone: ink + teal) |
| `headline` | 22 / 28 | Bold | Greeting name, profile name, call peer name |
| `title` | 17 / 24 | SemiBold | Centred screen titles |
| `titleSmall` | 15 / 20 | SemiBold | Card titles, conversation names |
| `body` | 15 / 22 | Regular | Message text |
| `bodySmall` | 13 / 18 | Regular | Previews, descriptions |
| `label` | 12 / 16 | Medium | Chips, buttons-small, nav labels |
| `caption` | 11 / 14 | Medium | Timestamps, badges |
| `section` | 13 / 18 | SemiBold | Section headers ("Quick actions" + teal "See all") |

### 3.4 Spacing, shape, elevation

- Spacing uses a 4 dp grid: `xs 4`, `sm 8`, `md 12`, `lg 16`, `xl 20`, `xxl 24`, `xxxl 32`.
  The screen gutter is **20 dp**, card padding 16 dp, the gap between stacked cards 12 dp, and the gap between sections 24 dp.
- Corner radii: hero cards 24 dp, list cards 20 dp, tiles 16 dp, icon tiles 14 dp, and pills fully rounded.
  Message bubbles use 20 dp corners with a 6 dp "tail" corner on the sender side.
- Elevation is a soft teal shadow (8 dp offset, 24 dp blur, 10 % alpha) plus the 1 dp frost border. No hard drop shadows
  and no Material tonal elevation.
- Touch targets are at least 48 dp. Small visual buttons (the 34 dp arrow knob) sit inside a 48 dp hit area.

### 3.5 Iconography and avatars

- Icons are Material Symbols *Rounded/Outlined* line glyphs (Apache 2.0), sized 20–22 dp, coloured by context,
  and placed inside 44 dp rounded tiles tinted with the matching `*Soft` colour.
- Avatars are circles. Without a photo, the avatar is the person's initials on a gradient picked deterministically
  from the user id (teal, lavender, peach and mint families). The ring is 2 dp white, and a 10 dp presence dot sits
  bottom-right (success green or tertiary grey).
- Organisation, branch and team marks are rounded-square tiles with initials.

### 3.6 Motion

- Standard duration 220 ms on FastOutSlowIn. Enter is fade + 12 dp rise. Exit is fade.
- Pressable cards and pills scale to 0.97 on press with a spring.
- Selection pills animate colour (180 ms). The segmented control slides its thumb.
- New messages fade and slide up. Status ticks crossfade between states.
- Loading uses a soft shimmer on glass placeholders. There are no spinners on full screens, only on buttons.
- Motion never blocks input, and nothing loops forever except the "calling…" pulse and the "connecting" shimmer.

### 3.7 States

Every reusable component supports these states:
- `enabled`, `pressed` (scale and darken), `selected` (teal fill, white content), `disabled` (40 % alpha, no shadow)
- `loading` (shimmer or inline progress in the button), `error` (coral text, coral-soft wash)
- `empty` (illustrated orb, title, one-line hint and a primary action)

Connection status uses a slim glass banner under the header: *Connecting…* (warning), *Offline — messages
will send when you reconnect* (tertiary), *Reconnected* (success, auto-hides).

## 4. Screen rules

1. Every screen sits on the gradient canvas. No screen uses a flat white or Material grey background.
2. Every list uses frosted cards, never edge-to-edge Material list items with dividers.
3. The screen header is the centred title pattern: back button in a frosted circle, action in a frosted circle.
   Tab roots (Home, Chats, Workspace, Profile) use a large left-aligned title instead.
4. There is one primary teal CTA per screen at most. Secondary actions are glass pills.
5. The bottom navigation is a floating glass bar with four items. It hides inside conversations and calls.
6. Edge-to-edge: system bars are transparent with dark icons in light mode and light icons in dark mode. Content respects insets
   and the IME. The composer rides above the keyboard.
7. Text never truncates mid-word without an ellipsis. Long names ellipsize at one line in lists and two lines in headers.

## 5. Verification

Rendered screenshots from the emulator are stored in `docs/screenshots/` and discussed in `docs/TESTING.md`
(§ Visual review).
