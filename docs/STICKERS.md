# Stickers

Added 2026-10-19. Composer › emoji button › **Stickers** (the sheet opens on it): search, favourites, recently used, then
each pack. Tap sends; long-press adds or removes a favourite. Recent and favourites stay on the phone (`StickerPrefs`).

## Where they come from (licensing)

Meldiq's stickers are **original designs drawn by the app**, not image files. Each one is a small spec in the shared
catalogue `shared/stickers/meldiq-stickers-v1.json`:

```json
{ "id": "lgtm", "emoji": "👍", "caption": "LGTM", "tone": "green", "motion": "stamp", "keywords": ["approve"] }
```

The app draws the emoji glyph with the **phone's own emoji font** (as any text), over a soft shape in the sticker's tone,
with the caption on a tilted label and one of twelve motions. No artwork, font or animation file from anyone else is
bundled or redistributed, so there is nothing third-party to license; captions and designs are Meldiq's own. Packs:
**Work** (12), **Celebrate** (8), **Feelings** (8).

Adding a pack: add it to the JSON (ids `[a-z0-9-]`, a known tone and motion, one emoji, a caption of at most 32
characters), bump nothing else. Removing one: delete it; messages that used it then show their fallback text. If a pack
ever includes outside artwork, its source and licence must be recorded next to it in the JSON and here, and only
material the project may redistribute (public domain/CC0 or a compatible licence with attribution) is allowed. Telegram
or other commercial sticker sets are never copied.

## In messages

A sticker message is an ordinary end-to-end encrypted message: `{"type":"text","text":"👍 LGTM","sticker":{"pack":"work","id":"lgtm"}}`.
The `text` is the readable fallback that older apps, notifications and screen readers get. A phone that doesn't know the
sticker (a newer catalogue) shows that text instead of a broken image. Received references are checked
(`RichRules.cleanSticker`); the catalogue file is parsed defensively (`StickerCatalog.parse`, unit-tested against the real
file in `RichContentRulesTest`).

## Motion and accessibility

A sticker plays its motion once or twice when it appears or is tapped, then rests; nothing loops while idle. With the
system's animations off, or Settings › Chats › Animated emoji off, stickers are still. Each sticker is announced as
"Sticker: <caption>". Tones carry white caption text at 4.5:1 or better.

## Web app

The web app reads the same JSON file and draws the same specs (CSS animations, `prefers-reduced-motion` respected).
Until it does, web shows the fallback text.
