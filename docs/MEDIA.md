# Music and video links

Added 2026-10-19 (`MediaLinks`, `MediaLinkCard`; tests in `RichContentRulesTest`).

A message with a YouTube, YouTube Music, Spotify, SoundCloud or Vimeo link shows a card: the provider, what it is
(video, short, live stream, track, album, playlist, artist, podcast or episode), the title when the sender's phone made a
link preview, and one action, **Open in <provider>**, which opens the link in the provider's own app (when installed) or
site through the person's link setting (Settings › Chats › Links).

## What Meldiq deliberately doesn't do

- **No embedded player.** Meldiq has no WebView (docs/LINKS.md), and playback belongs to the provider: their app or site
  handles accounts, Premium/subscription rules, regional availability, advertising and DRM. Meldiq never streams,
  downloads, re-hosts or proxies music or video.
- **Nothing is fetched to draw the card.** It's recognised from the address alone (https links only), so showing it
  tells no one that you looked at it. The title appears only if the *sender* turned on link previews, in which case
  their phone fetched the page title (docs/LINKS.md) and sent it inside the encrypted message.
- **No autoplay**, no background audio, nothing that competes with calls or notifications.
- **No "listen together" or synced playback.** Spotify's and YouTube's official APIs don't offer a supported way for a
  third-party messaging app to run shared listening sessions for arbitrary accounts; building one would mean scraping
  private interfaces or bypassing subscription rules. Sharing the official link is the supported path.

## Controls

Settings › Chats › Links › **Music and video cards** (on by default) shows these links as plain links when off. A
workspace-wide switch for entertainment features is a possible follow-up; it needs a workspace policy field on the
server and isn't implemented yet.
