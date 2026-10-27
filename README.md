# Auto Tapper

One small floating button that taps or swipes for you, from the saved file you pick.
Works on Android 6 to 16 (API 23-36). Android 6 has no tap API for apps, so taps there need a rooted phone.

## Get the APK (no PC needed)
1. Create a new repository on github.com and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab > **Build APK** > the latest run (about 3 minutes) > **Artifacts** > `AutoTapper-apk`.
3. To publish a download link: add a tag such as `v1.1` (Releases > Draft a new release > tag `v1.1`). The APK is attached automatically.

Build locally instead: Android Studio > Open this folder > Build APK, or `gradle assembleDebug`.

## First start (one time)
The app lists everything it needs and turns each line to a tick when done:
accessibility service, display over other apps, run in background (battery), notifications (Android 13+).
On Android 13+, if the accessibility switch is greyed out: App info > menu > **Allow restricted settings**.
Some brands also need Autostart / unrestricted battery in system settings.

## Using it
- Touch the button: run the loaded file's job invisibly. Touch again: stop.
- Long-press: bottom toolbar + targets. Long-press again: back to just the button.
- Drag the button to move it; its place is remembered.

The APK is signed with the debug key in `app/debug.keystore`, so new builds update older installs.
