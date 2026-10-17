# Languages and translation

Added 2026-10-17.

## Interface language
Settings › Language: Same as the phone, English, 简体中文 (Simplified Chinese, `zh-CN`) or বাংলা (Bengali, `bn`),
independent of the phone's language. Android 13+ uses its per-app language setting (also in Android's app settings;
`res/xml/locales_config.xml`); older versions start the activity in the chosen locale. The Play bundle isn't split by
language, so every install has every language.

Strings live in `res/values{,-zh-rCN,-bn}/strings.xml`, generated from one table: `android/i18n/strings.py`
(run `python3 -I android/i18n/gen.py`). `UiRulesTest` checks that every language has every key and the same placeholders.

**Coverage (honest):** translated: the bottom navigation, the Settings tab and every settings screen added this round
(Chats, Notifications & Sounds, Calls, Language, Accounts), the chat list (header, filters, archive, swipe labels, empty
state), the composer's attach sheet and the message menu, Contacts, and translation. Still English: conversation details,
the call screen, onboarding, workspace screens, profile editing and privacy, many messages and errors. Notification text on
Android 12 and older follows the phone's language. The Chinese and Bengali text was written for this round and should be
reviewed by native speakers before release.

## Translating a message
Message menu › Translate → choose English, 中文 or বাংলা. The language is identified and the text translated **on the
phone** with ML Kit (`MessageTranslator`); the message text is never sent anywhere. The first time a language is used,
ML Kit downloads its model (about 30 MB) from Google; the sheet says so before the first translation. The translation shows
under the original (Show original removes it) and is never stored; the encrypted message isn't changed. Failures say what
went wrong (unknown language, model not available, e.g. without access to Google's servers); nothing is invented.
Cost: ML Kit adds about 17 MB to a Play install (one CPU architecture; 45 MB across the three in a universal APK).
