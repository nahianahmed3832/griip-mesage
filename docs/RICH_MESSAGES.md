# Contacts, locations, animated emoji and forwarding

Added 2026-10-17. All of these travel inside the end-to-end encrypted message (`Payload`), with a readable text
fallback, so older Orbit versions show the text. Received content is bounded and checked (`RichRules`, `RichRulesTest`).

## Contact cards
Attach › Contact opens Android's contact picker for one phone entry; Orbit needs no contacts permission and never reads
the address book. The preview lets you edit the name and untick numbers. The card offers Add to contacts, Copy, and Find
on Orbit (exact phone lookup, only if that person allows it).

## Locations
Attach › Location asks for location permission at that moment, takes one fix from the phone's own providers (no
background or live tracking, no Google Play services), and shows a confirmation with coordinates, accuracy and an
optional place name. No map tiles are downloaded, so neither showing nor viewing a location tells a map service where it
is. Received locations open in the phone's map app (`geo:`); the fallback text holds an OpenStreetMap link.

## Animated emoji
The emoji button in the composer has Animated (send one on its own) and Emoji (insert into the text). Animated emoji
are drawn by the app (Compose animations of the emoji glyph: beat, bounce, wave, spin, wobble), so there are no assets to
license or download and no executable content. On their own they show large, without a bubble. Settings › Chats ›
Animated emoji turns the motion off (they're then still, also when the phone's animations are off).

## Forwarding
Message menu › Forward → choose a chat → confirm. Text, cards, locations and animated emoji go as a new message marked
"Forwarded" (the original sender isn't named; mentions aren't carried over). Files are decrypted copies on this phone
(downloaded first if needed), encrypted again with new keys and uploaded for the destination, because each
conversation's files can only be fetched by its members. Voice messages stay voice messages.

Storage: Room v6 `messages.richJson` and `messages.forwarded`.
