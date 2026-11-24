# Boxly — HTML/CSS/JS → APK (on-device)

Android app that turns pasted code or a ZIP into an installable APK, fully on the phone.

## Build the APK on GitHub (no Android Studio needed)
1. Create a GitHub repo and push all files of this folder (including `.github/`).
2. Open **Actions → Build APK** (runs on every push; or press *Run workflow*).
3. When it finishes, download the **Boxly-APK** artifact, unzip it, install `app-release.apk`.

Local build: open the folder in Android Studio, or run `gradle assembleRelease` (JDK 17).

## How it works
- The APK copies itself, replaces `assets/www/` with your files, rewrites the package id and app name
  (same-length edits inside the binary manifest), and signs it (apksig, key in Android Keystore).
- Generated apps open `assets/www/index.html` in a WebView and get the `Android` JS bridge:
  `Android.toast(msg)`, `Android.vibrate(ms)`, `Android.showNotification(title, body)`,
  `Android.share(text)`, `Android.saveFile(name, mime, base64)`.
  `<input type="file" accept="image/*">` opens camera or file picker; `getUserMedia({video:true})` works.

## Known limits
- App name: ASCII only, max 32 chars. Package id is generated automatically.
- Signing key lives in this device's Keystore: wiped if you clear Boxly data/uninstall (apps built later can't update older ones).
- targetSdk is 29 on purpose (repacked APK). Generated apps also carry the builder code and the install permission.
