# Corresponding source — USpot

This repository contains the current source directly. The workflow builds the checkout on pushes to main or manual Actions runs; no source ZIP extraction is needed.

Each release includes **USpot.apk**, **USpot-source.zip**, **LICENSE**, **NOTICE** and this file. The source ZIP comes from `git archive` of the same commit that built the APK. Retain that matching source when redistributing the APK.

Repository: https://github.com/rare621/USpot
Releases: https://github.com/rare621/USpot/releases

For the exact display version and build number, read `versionName` and `versionCode` in this archive's `app/build.gradle.kts`. The release tag uses `v<versionName>-build-<versionCode>`.

## Build

Use Java 17, Android SDK platform 34, build-tools 35.0.0 and the included Gradle 8.11.1 wrapper.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :spotify:test --no-daemon --max-workers=2
cd webui-tests
npm install --no-audit --no-fund
npm test
```

CI restores the existing production key from `USPOT_SIGNING_KEYSTORE_BASE64`. Optional `USPOT_STORE_PASSWORD`, `USPOT_KEY_ALIAS` and `USPOT_KEY_PASSWORD` retain their defaults and previous meaning. Never commit signing material or generate a replacement key for an update. Local debug builds can use a different key.

The public release keeps application ID `com.music.spotui.modified`, and the versionName/versionCode declared in `app/build.gradle.kts`. The workflow checks the signed APK against the previous public release's certificate, application ID and version before publication. It also requires assembly, app unit tests and browser interaction checks to pass. Existing release tags are retained rather than relabeled.

Public builds use the normal Gradle command without preview flags. The release workflow rejects APKs with a different application ID or signing certificate, protecting in-place updates.

Original GPL version 3 licensing and third-party notices are retained. Original project: https://github.com/Spotui/Spotui . Desktop & Browser uses NanoHTTPD under its bundled BSD 3-Clause notice.


Build 91 bundles youtubedl-android 0.18.1 (GPL-3.0) and a pinned official yt-dlp 2026.10.21 zip executable (Unlicense with bundled third-party components). Exact dependency, source URLs and upstream license texts are in `third_party/playback-backup/`; the corresponding notices are bundled in Settings → About. The pinned resource is part of this source archive, with SHA-256 `b7af77f686e95f7845ce8d09d21957718febb8b48b30555adb9dd2292ad7bf14`. No server, remote code update or test-only mode is required. Python/QuickJS runtime files increase installed app size; they are separate from the managed temporary media cache.
