# Emoji picker and reactions (2026-10-21)

Master prompt §13. Meldiq Android and Meldiq Web share one emoji catalogue and one picker design.

## Catalogue

- **Source:** Unicode's `emoji-test.txt` 15.1 (which emoji exist, their order and groups) and CLDR 46 English
  annotations (names and search words), under the Unicode License v3 (`docs/licenses/UNICODE-LICENSE.txt`). No
  third-party emoji artwork: the phone's or browser's own emoji font draws them.
- **Generator:** `tools/emoji/gen_emoji.py`, run with `python3 -I tools/emoji/gen_emoji.py <downloads> <outputs…>`.
  The download URLs are in the script. It writes `android/app/src/main/assets/emoji/emoji.json`, and the same file is
  copied to Meldiq Web's `src/emoji/emoji.json`.
- **Content:** 1,898 fully-qualified emoji in Unicode's nine groups. Smileys has 168, People 385, Nature 153, Food 135,
  Travel 218, Activities 85, Objects 262, Symbols 223, Flags 269. The Component group is left out. 299 emoji have
  skin tones: the five one-tone variants are folded into their base. Two-person emoji with mixed tones are left out,
  because the picker uses one tone at a time.
- **Size:** 141 kB. On the web it loads as its own chunk (40 kB gzipped) the first time a picker opens.
- **Android:** the app leaves out emoji (and tones) the phone's font can't draw (`Paint.hasGlyph`), so the picker
  never shows empty boxes. The browser draws whatever its fonts have.

## Picker

`EmojiPanel` (Android, `ui/screens/conversation/EmojiPanel.kt`) and `EmojiPicker` (web, `src/ui/chat/EmojiPicker.tsx`)
offer the same things:
- **Search** by name or search word: every typed word must start a word ("thumb up" finds 👍). The best match comes
  first: the exact name, then names starting with the query, then words of the name, then search words. At most
  120 results.
- **Category tabs** (recent, then the nine groups) jump to their section. The tab of the section in view is
  highlighted.
- **Recently used** comes first: up to 32, newest first, on this device only.
- **One skin tone for all emoji**, chosen with the hand button and kept on this device. Emoji without tones stay as
  they are.
- Each emoji is read out by its name (TalkBack, screen readers). Cells are 44 dp on Android and 36 px on the web.

Where it is used:
- the composer's Emoji tab (Android sheet, web menu), which inserts into the message;
- reactions: on Android, message menu › **More reactions** (the button after the six quick reactions); on the web,
  the message toolbar's Add reaction.

## Reactions are emoji, not text

Reactions travel inside end-to-end encrypted control messages, so the server can't check them. Both apps check them
when sending and when receiving (`EmojiRules.isReaction`, web `src/emoji/rules.ts`):
- at most 64 UTF-16 units (the web payload schema's limit);
- no whitespace or control characters;
- no letters, unless the letter is shown as an emoji (followed by U+FE0F, as in ℹ️ and Ⓜ️).

Every emoji and tone in the catalogue passes, and so do keycaps, the England flag (tag sequence) and family ZWJ
sequences. A reaction that fails, for example "lol" from a modified client, is dropped.

## Tests

- **Android:** `EmojiCatalogTest` (5 tests) covers the groups, uniqueness, tones, search ranking, and the reaction
  rule over the whole catalogue.
- **Android emulator** (local server, 2026-10-21):
  - emoji sheet › Emoji opens the picker;
  - "thumbs" finds 👍 and 👎;
  - the medium tone applies to the results;
  - inserting and sending 👍🏽 works;
  - More reactions shows Recently used first;
  - the Flags tab jumps to Flags;
  - reacting with 🇧🇩 and 😑 shows "🇧🇩 reaction, 1, including you".

  Screenshots: `docs/product/screens-2026-10-21/emoji-*.png`.
- **Web:** `test/emoji.test.ts` (5 tests, the same checks). `e2e/emoji.spec.ts` runs in Chromium against the API
  0.13.0 server and covers search, skin tone, inserting into the composer, a reaction from the Flags section, and
  the Flags section actually being in view. That last check was added after a first run showed the tab highlighting
  Flags while the list showed Symbols, a layout bug fixed in the same change. Screenshots:
  `screens-2026-10-21/web-emoji-*.png`.

## Not done yet

- Custom community emoji and stickers uploaded by communities. This needs:
  - storage (S3, through the existing upload path);
  - a `community_emoji` model and permission (`manage_expressions`);
  - moderation;
  - rendering `:name:` inside encrypted messages. The reference must carry the asset hash so a client can check the
    image.

  It is specified in `docs/product/MELDIQ_FEATURE_GAP_MATRIX.md` (EXP-04/05).
- Emoji names are English on both apps. CLDR has Bengali and Chinese annotations: generate per-language search
  words next.
- Animated emoji stay the existing 12 app-drawn ones (`Animoji`).
