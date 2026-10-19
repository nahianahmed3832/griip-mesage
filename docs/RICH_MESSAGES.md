# Contacts, locations, animated emoji and forwarding

Added 2026-10-17. All of these travel inside the end-to-end encrypted message (`Payload`), with a readable text
fallback, so older Meldiq versions show the text. Received content is bounded and checked (`RichRules`, `RichRulesTest`).

## Contact cards
Attach › Contact opens Android's contact picker for one phone entry; Meldiq needs no contacts permission and never reads
the address book. The preview lets you edit the name and untick numbers. The card offers Add to contacts, Copy, and Find
on Meldiq (exact phone lookup, only if that person allows it).

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

## Polls (2026-10-19)
Attach › Poll: a question, two to ten options, and whether several answers are allowed. The poll travels inside the
end-to-end encrypted message (`poll: {question, options:[{id,text}], multi}`) with a readable fallback (`📊 question` and
the numbered options) for older apps. Votes are encrypted **controls**, like reactions: `{"type":"vote","target":<poll
message id>,"choices":[<option ids>]}`; an empty list takes the vote back. Each phone counts the votes it received
(`PollRules`): a voter's latest vote replaces their earlier one, only real option ids count, and a single-choice poll
keeps one choice. The poll's creator can close it (`{"type":"poll_close","target":…}`, accepted only from the poll's own
sender); a closed poll takes no more votes. The server never sees the question, the options or who voted for what. A
vote that arrives before its poll waits for it (pending controls). Stored in `messages.richJson` (`votes`,
`pollClosed`), so it's part of the encrypted server history like the rest of the message. Unit-tested in
`RichContentRulesTest`.

## Stickers (2026-10-19)
See docs/STICKERS.md.

## Music and video links (2026-10-19)
See docs/MEDIA.md.
