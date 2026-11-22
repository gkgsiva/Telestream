# Telestream

Search and stream videos and music from Telegram channels and groups, on Android TV and phones.
One APK runs on both. It signs into your own Telegram account through
[TDLib](https://github.com/tdlib/td) and plays files directly from Telegram, downloading only the
part you are watching. There is no server.

## Features

- **Login:** enter your own Telegram API ID and hash (from [my.telegram.org](https://my.telegram.org)),
  then log in with a QR code (default on TV) or phone number and code (default on phones), including
  two-step verification passwords.
- **My chats:** every channel and group you have joined.
- **Search:** video, music or video/audio files across all your chats, or find public channels and
  groups by name, `@username` or `t.me` link.
- **Channel page:** Videos, Music and Files tabs, search inside the channel, endless scrolling.
- **Player:** Media3 ExoPlayer streaming through TDLib byte ranges, so seeking in a 2 GB file only
  downloads from the new position. Subtitle and audio-track selection, FFmpeg audio decoders for
  AC3/E-AC3/DTS, resume where you stopped.
- **Join:** a Join button on public channels and groups you have not joined, and private groups by
  pasting an invite link (`t.me/+...`) into Search, then Find channels.
- **Hide chats:** My chats, then "Hide chats", hides groups you don't want to see; "Show hidden" brings them back.
- **My lists:** add any video or song to your own lists with the list button next to it, then play,
  shuffle, reorder or remove items under the My lists tab.
- **Music:** plays as a queue in a background service with notification and lock-screen controls,
  plus a mini player (previous, play/pause, next) and a Now playing screen with progress,
  shuffle, repeat, playback speed and the queue, with cover art.
- **Continue watching:** a row at the top of My chats with progress bars; finished videos drop off.
- **Subtitles, audio tracks and speed:** from the player controls (subtitle button and settings gear).
- **Downloads:** the download button next to any item saves it for offline use; the Downloads tab
  shows progress and lets you pause, resume, play or delete.
- **Series:** the Series tab on a channel groups episodes named like `S01E02`, `1x03`,
  `Season 2 Episode 5` or `EP 12` into shows and seasons.
- **Thumbnails:** video thumbnails, channel photos and album art throughout.
- **Open in VLC:** the button at the top right of the video player hands the stream to the VLC app,
  starting where you were. Telestream serves the file from Telegram over a private local address
  while VLC plays, so nothing is downloaded first. Without VLC you can pick another player.
- **Cast:** the Cast button in the video player sends the video to a Chromecast or Google TV on
  the same Wi-Fi (Google's default receiver, so MP4/WebM work best; the phone streams it).
- **Phone as TV remote:** on the TV, Settings, then Phone remote shows a 4-digit code. On the phone,
  Settings, then Find my TV, pick the TV and enter the code. Then use play/pause/skip, or tap
  "Play on TV" in the phone's player to move the video to the TV.
- **Parental lock:** set a PIN in Settings, then lock chats under My chats, then Hide or lock.
  Locked chats leave chats, search and Continue watching until the lock icon at the top is unlocked.
- **New-upload notifications:** tap the bell on a channel page. Telestream checks about every 15 minutes.
- **Search filters and history:** filter results by size, length, quality (720p/1080p/4K) and date;
  remove single history entries or clear them all.
- **Duplicates:** the same file reposted in several chats shows once (Settings or the filter panel to turn off).
- **Picture-in-picture:** on phones, leaving the app while a video plays keeps it in a floating window.
- **Sleep timer and lyrics:** on Now playing, stop music after 15 to 90 minutes or at the end of the
  song, and show lyrics (synced when available) from [LRCLIB](https://lrclib.net).
- **Android Auto:** your lists with music appear in Android Auto.
- **Several accounts:** Settings, then Add another account; switch between them there.
- **Info and updates:** Settings, then Info shows the version and has Check for update, which
  downloads the newest signed APK from GitHub Releases and opens the installer. This needs the
  releases to be reachable without a login (a public repository).
- **Next episode:** playing an episode from the Series tab queues the rest of the show; a countdown
  offers the next episode near the end (turn autoplay off in Settings).
- **Android TV home screen:** Continue watching also appears in the TV launcher's Play next row.
- **Versions:** the same title in 720p, 1080p and 4K shows once in search, with a chip per version.
- **New in your channels:** the newest uploads from channels you follow with the bell, on My chats.
- **Subtitle download:** the Subtitles button in the player searches OpenSubtitles.com. Each user
  enters their own free OpenSubtitles API key in Settings, then Subtitles.
- **Smarter downloads:** Settings, then Downloads: Wi-Fi only, a storage limit, and deleting videos
  after they are watched.
- **Sound:** on Now playing, an equalizer, steady volume (Android 9+) and fading between songs.
- **Backup:** Settings, then Backup saves lists, hidden chats, Continue watching and followed
  channels to a file, and restores them.
- **Voice search:** the mic in the search field, or "Search by voice" on Android TV.
- **Sync between phone and TV:** lists, hidden chats and Continue watching are kept in a
  `telestream-sync.json` file in your Telegram Saved Messages. Log in with the same account on both
  devices and they stay in step. Tap the sync status at the top to sync now.

## Getting an APK

Every pull request and push to `main` runs the **Android** GitHub Actions workflow. It builds TDLib
for `arm64-v8a`, `armeabi-v7a` and `x86_64` (the first run takes about an hour, later runs use the
cache), then builds a debug APK. Download it from the run's **Artifacts** section
(`telestream-debug-apk`) and sideload it:

```sh
adb install -r app-debug.apk
```

On Android TV you can also install it with a file manager or "Send files to TV".

## Signed releases

Pushing a tag like `v0.2.0` builds a signed release APK and attaches it to a GitHub Release.
It needs four repository secrets (Settings, Secrets and variables, Actions):
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Create a keystore once
and keep a backup, because updates must be signed with the same key:

```
keytool -genkeypair -v -keystore telestream.jks -alias telestream -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 telestream.jks   # paste the output into KEYSTORE_BASE64
```

## Building locally

1. Install Android Studio (or the SDK command-line tools), a JDK 17, and `gperf`, `php`, `perl`, `make`.
2. Get TDLib, either:
   - download the `tdlib-<abi>` artifacts from a CI run, unzip them into one folder, then
     `scripts/install-tdlib.sh <folder>`; or
   - build it: `ANDROID_SDK_ROOT=~/Android/Sdk scripts/build-tdlib.sh` then
     `scripts/install-tdlib.sh tdlib-out`.
3. `./gradlew :app:assembleDebug`

The TDLib version is pinned in `scripts/tdlib-commit.txt`.

## Project layout

- `tdlib/`: Gradle module that holds TDLib's generated Java API and native libraries (not committed).
- `app/src/main/java/com/telestream/app/`
  - `telegram/`: `TdClient` (TDLib wrapper and login state), `TelegramRepository` (chats, search, media), models.
  - `player/`: `TelegramDataSource` (ranged streaming into ExoPlayer), `PlaybackService` (media session, background audio), `Playback` (UI-side controller).
  - `ui/`: Compose screens for login, chats, search, a channel, and the player. TV is detected with the Leanback feature and gets QR login, wider margins and D-pad focus highlights.

## Notes

- Your API ID and hash, and the Telegram session, are stored only in the app's private storage.
- Telegram throttles downloads on free accounts, so very high bitrate files may buffer.
- Use it with content you have the right to watch.
