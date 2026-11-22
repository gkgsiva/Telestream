# Telestream

Search and stream videos and music from Telegram channels and groups, on Android TV and phones.
One APK runs on both. It signs into your own Telegram account through
[TDLib](https://github.com/tdlib/td) and plays files directly from Telegram, downloading only the
part you are watching. There is no server.

## What works in this first version

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
  shuffle, repeat and the queue.

## Getting an APK

Every pull request and push to `main` runs the **Android** GitHub Actions workflow. It builds TDLib
for `arm64-v8a`, `armeabi-v7a` and `x86_64` (the first run takes about an hour, later runs use the
cache), then builds a debug APK. Download it from the run's **Artifacts** section
(`telestream-debug-apk`) and sideload it:

```sh
adb install -r app-debug.apk
```

On Android TV you can also install it with a file manager or "Send files to TV".

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
