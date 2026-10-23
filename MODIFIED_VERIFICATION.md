# USpot build 85 verification

Changes since build 82: Library utility layout and cover fallbacks; typography, menus, system bar theme updates and touch targets; owner-gated playlist editing; USpot Premium benefits sheet; aligned Radio tuning controls and matching browser UI; local phone route fallback and route-switch confirmation in Connect.

Identity: com.music.spotui.modified, USpot 1.1, versionCode 85. The release workflow requires the existing production certificate and verifies the APK identity before publishing. The updater compares numeric build numbers and supports skipped-version upgrades. This pass changes no existing preference names, stored data formats or download paths.

Release gate: GitHub Actions assembles the APK, runs app and Spotify-module unit tests and browser interaction checks, verifies the production signing certificate and package, then publishes the matching APK and complete source. Browser interaction and desktop/mobile visual checks passed for the shared preview source. Physical Android upgrade installation, Bluetooth/speaker switching, Android Auto and real account mutations were not exercised in this environment. Route availability and switching depend on Android and the device manufacturer.
