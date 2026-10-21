# Meldiq design system: where it lives and the rules for new screens (2026-10-21)

The full design system is `docs/DESIGN_SYSTEM.md` (tokens, light and dark colour, type, spacing, shape, motion,
states, screen rules). This file doesn't repeat it. It indexes what new work must use, and the patterns added in the
community and Discord-reference rounds.

## Source of truth

| What | Android | Web |
|---|---|---|
| Colour, palettes (Graphite dark by default; Indigo, Plum, Amber presets) | `ui/theme/Color.kt`, `MeldiqPalettes.kt` | `src/styles/tokens.css` |
| Type, spacing, shapes | `ui/theme/Type.kt`, `Tokens.kt` (`Space`, `Shapes`) | `tokens.css` (`--r-*`, spacing) |
| Components | `ui/components/`: `PillButton`, `CircleIconButton`, `GriipTextField`, `SearchPill`, `PillChip`, `ChipRow`, `ChoiceCard`, `ListCard`, `StatusBanner`, `GlassSheet`, `ConfirmDialog`, `Avatar`, `IconTile`, `ShimmerList` | `src/ui/components/` plus classes in `views.css` |
| Brand | Meldiq logo and wordmark, Plus Jakarta Sans (`docs/licenses/PlusJakartaSans-OFL.txt`) | same |
| Emoji | The device's own emoji font; catalogue from Unicode (`docs/EMOJI.md`) | the browser's emoji font |

## Patterns added on 2026-10-21

- **Help behind a link.** Normal flows show the main path only. Alternatives such as recovery open from one quiet
  link into a sheet of `ChoiceCard`s with a "Back to …" link (`AccountHelpSheets.kt`; web `.auth-choice`). Use it
  wherever a screen would otherwise list rare options permanently.
- **Full picker in a sheet.** Search pill, a tone or variant button, category tabs that jump, sections with
  headings, 44 dp cells read out by name (`EmojiPanel.kt`; web `EmojiPicker.tsx`). A lazy grid never goes inside a
  scrolling column: give it a fixed height in the sheet.
- **Moderation sheets.** Title "Action Name?", one sentence on the consequence, choices as `PillChip`s, an optional
  reason "kept in the audit log", and a Danger `PillButton` (`BanSheet`, `TimeoutSheet`). Reversible actions show
  their state as a Warning `StatusBanner` with the undo action under it ("End timeout").
- **Read-only composer.** When someone can't post, the composer is replaced by one line with a lock icon that says
  why and, when known, until when (archived, announcement, timed out, removed).

## Rules for new screens

1. Use tokens and components above. No hard-coded colours, sizes or new button styles.
2. Discord is a reference for structure and behaviour only: no copied assets, icons, names or exact layouts
   (`DISCORD_FEATURE_AUDIT.md`).
3. Every control works or is visibly unavailable with a reason. No placeholders in production paths.
4. Strings go through `android/i18n/*.py` (English, Simplified Chinese, Bengali). Native-speaker review of the Chinese
   and Bengali text is still owed (`docs/LANGUAGES.md`).
5. Check light and dark, a 360 dp-wide phone, large font, and TalkBack labels before calling a screen done.
