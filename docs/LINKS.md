# Links in messages

Added 2026-10-17 (`LinkRules`, `LinkOpener`, `LinkPreviews`; tests in `RichRulesTest`).

- Only `http://` and `https://` addresses (and `www.` ones, as https) become tappable. Any other scheme (`intent:`,
  `javascript:`, `file:`, app deep links) stays plain text. Trailing punctuation and unbalanced brackets are left out.
  The stored message text never changes.
- **Opening** (Settings › Chats › Links): in Orbit (a Custom Tab: the person's own browser shown over Orbit, with its TLS
  checks and safe browsing; Orbit has no embedded WebView, so pages can't reach Orbit's data), in another app, or ask
  every time.
- **Warnings**: a confirmation shows where the link really goes when the address hides its destination before `@`,
  uses look-alike letters from other alphabets (punycode), is a bare IP number, isn't encrypted (http), or has an
  unusually long chain of names. The link still opens if the person chooses.
- **Previews** are off by default. When on, the *sender's* phone fetches the page's title and description as they type
  (https only, no cookies, at most 3 https redirects, 256 KB, 5 s) and sends them inside the encrypted message; a cross
  removes it. Recipients never fetch the page, so a link can't be used to learn their IP address. No Orbit server is
  involved and no message content is sent anywhere; the site sees the sender's IP address and that the page was fetched.
