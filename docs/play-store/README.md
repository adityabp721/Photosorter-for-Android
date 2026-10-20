# PhotoSorter — Play Store kit

Everything needed to publish `com.adityabalagere.photosorter`. Policy details change; check each
item against the current Play Console help when you submit.

## Files in this folder

| File | Use |
|---|---|
| `../../app/build/outputs/bundle/release/app-release.aab` | Upload to Play (rebuild with `./gradlew bundleRelease`) |
| `icon-512.png` | Store icon, 512 x 512 |
| `feature-graphic-1024x500.jpg` | Feature graphic |
| `screenshots/` | 5 phone screenshots, ready to upload |
| `../privacy-policy.html` | Privacy policy — host it at a public URL (GitHub Pages works) |

## Upload key (keep it safe)

- Keystore: `~/.android-keys/photosorter-upload.jks`, passwords in `~/.android-keys/photosorter-keystore.properties`.
- The project links to it through `keystore.properties` (git-ignored).
- **Back both files up** (password manager or encrypted drive). Enroll in **Play App Signing**:
  Google then holds the real app-signing key, and a lost upload key can be reset through Play support.
- Upload certificate SHA-256: `9C:15:08:96:A6:13:8C:0C:A3:1D:FB:18:C6:47:7C:2E:3D:A5:11:5A:C2:6E:5E:1D:CC:0F:C2:30:3E:FE:32:BD`

## Store listing

**App name** (max 30): `PhotoSorter: AI Photo Sorter`

**Short description** (max 80):
`Sort photos into trips, food & screenshots with private on-device AI. Find dupes`

**Full description** (max 4000):

```
PhotoSorter organizes your gallery for you. It looks at each photo with an AI model that runs right on your phone, then files it into clear folders: trips, food, screenshots, people, pets, documents and more.

TRIPS, AUTOMATICALLY
PhotoSorter spots photos taken away from home, using the time zone your camera records and GPS when available. Every photo from those days lands in one folder like japan-trip, including the food, the people and the sights.

SMART FOLDERS
• Food, people, pets, nature, documents, shopping, events and more
• Screenshots and WhatsApp images get their own folders instantly
• A one-line caption is saved with each photo

FREE UP SPACE
• Finds exact duplicates and look-alikes (bursts, edits, re-saves) across your whole library in minutes
• Always keeps the original; RAW photos are never selected automatically
• Swipe to compare copies side by side before you decide
• Finds blurry, shaky and accidental pocket shots, so you can review and remove them
• Removed photos go to the trash for 30 days, so mistakes can be undone

PRIVATE BY DEFAULT
• The AI model runs on your phone and works offline
• No account, no ads, no tracking
• Optional cloud mode uses Google Gemini with your own API key, only after you agree

SAFE BY DESIGN
• Copy mode never touches your originals
• Move mode verifies every copy before the original goes to the trash
• Nothing is removed without your confirmation

WORKS IN THE BACKGROUND
Long jobs keep running with a progress notification, and you can stop them anytime.

The on-device model is downloaded once inside the app: Standard (about 1.6 GB, best results) or Lite (about 550 MB) for phones with less memory.
```

**Category:** Photography · **Tags:** photo organizer, gallery, duplicates
**Contact email:** bpa721studios@gmail.com · **Privacy policy URL:** https://adityabp721.github.io/Photosorter-for-Android/privacy-policy.html (GitHub Pages from `docs/`)

## App content answers

| Section | Answer |
|---|---|
| Privacy policy | URL of the hosted `privacy-policy.html` (fill in the contact email first) |
| Ads | No ads |
| App access | All functionality available without login |
| Content rating | Questionnaire: utility/productivity app, no user-generated content shared, no violence, etc. Expected: Everyone / PEGI 3 |
| Target audience | 13+ (not designed for children) |
| News app | No |
| Government app | No |
| Financial features | None |
| Health | None |

## Data safety form

Answers below match the code as of this release.

- **Does your app collect or share any of the required user data types?** Yes (only in optional features).
- **Is all of the user data collected by your app encrypted in transit?** Yes (HTTPS).
- **Do you provide a way for users to request that their data is deleted?** The developer stores no user data; explain this in the form's text.

| Data type | Collected? | Shared? | Optional? | Purpose | Notes |
|---|---|---|---|---|---|
| Photos and videos → Photos | Yes | No (sent to Google at the user's request, with their own API key) | Yes — only in Cloud mode, after consent | App functionality | Downscaled photo sent to the Gemini API for classification |
| Location → Precise location | Yes | No | Yes — Cloud mode or the "Place names from GPS" setting | App functionality | GPS from photo EXIF, used for trip names |
| Everything else (personal info, contacts, app activity, device IDs, etc.) | No | — | — | — | No analytics, ads or crash SDKs |

On-device processing (the default) does not count as collection because nothing leaves the phone.

## Permission declarations

**Photo and video permissions (READ_MEDIA_IMAGES).** Core purpose: the app organizes the user's whole photo
library (AI sorting into folders, trip detection) and finds and removes duplicate photos across all
albums. This needs broad, persistent access; the Android photo picker cannot provide it.

**Foreground service types.**
- `mediaProcessing` (Android 15+): user-started on-device AI classification of photos and duplicate
  scanning, with a progress notification and a Stop button.
- `dataSync`: user-started download of the on-device AI model (about 550 MB or 1.6 GB) and on Android 14 and older.
Play asks for a short video of each: start the job, show the notification, and leave the app.

**ACCESS_MEDIA_LOCATION.** Reads GPS stored in the user's photos to recognize trips.

## Release checklist

1. [ ] Create a Google Play Developer account ($25 one-time) and finish identity verification.
2. [ ] Back up the upload key files (above).
3. [x] Support email in `privacy-policy.html`; enable GitHub Pages (master, /docs) to publish it.
4. [ ] Create the app in Play Console: name, default language, App, Free.
5. [ ] Fill in App content (table above), Data safety, permission and foreground-service declarations.
6. [ ] Store listing: text, icon, feature graphic, 2–8 phone screenshots.
7. [ ] Upload `app-release.aab` to a **closed testing** track; enroll in Play App Signing when asked.
8. [ ] Invite at least 12 testers and keep them opted in for 14 days in a row (required for new personal accounts).
9. [ ] Apply for production access, then roll out (a staged rollout starting at 20% is a safe choice).

## Before each update

- Raise `versionCode` (and `versionName`) in `app/build.gradle.kts`.
- `./gradlew testDebugUnitTest bundleRelease`
- Check 16 KB alignment of native libraries:
  `zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk`
