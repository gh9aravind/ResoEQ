# Parametric EQ — Stage 1

A minimal, working system-wide equalizer for Android. This is the "get the
pipeline working" version described in chat: it captures system audio output
(no root) and runs it through Android's built-in `DynamicsProcessing` effect.

## What it does

1. `MainActivity` asks for `RECORD_AUDIO` + `POST_NOTIFICATIONS`, then asks
   the system for permission to capture audio output (`MediaProjection`).
2. `CaptureService` captures that audio with `AudioPlaybackCaptureConfiguration`,
   copies it into an `AudioTrack`, and attaches `DynamicsProcessing` (7-band EQ)
   to that track's session so the EQ is applied automatically on the way out.
3. Sliders in the UI push live gain changes to the running effect.

## Building without Android Studio (GitHub Actions)

If your machine is slow for Android Studio/Gradle, you can build the APK in
the cloud instead:

1. Create a free account at github.com if you don't have one.
2. Create a new repository (any name, Public or Private — doesn't matter).
3. On the empty repo's page, click **"uploading an existing file"**, then
   drag the *entire* `ParametricEQ` folder (the one containing this README)
   into the upload box. This includes the hidden `.github/workflows/build.yml`
   file, which is what tells GitHub how to build.
4. Click **Commit changes**.
5. Go to the **Actions** tab of your repo. A build should start automatically
   (or click **Run workflow** if it doesn't).
6. Wait for the green checkmark (a few minutes). Click into the finished run,
   scroll to **Artifacts**, and download **app-debug** — that's a zip
   containing `app-debug.apk`.
7. Transfer that APK to your phone (email it to yourself, Google Drive, etc.)
   and tap it to install. You may need to allow "install unknown apps" for
   whatever app you used to open it.

No Android Studio, no local Gradle, no laptop lag — GitHub's servers do the
build. You'll still want a text editor for changing code, but even GitHub's
own web editor (press `.` on the repo page) works for small edits.

## Before you build: open in Android Studio

1. Android Studio → Open → select the `ParametricEQ` folder.
2. Let it sync Gradle. It may prompt you to update the Android Gradle Plugin
   or Kotlin version — accepting that is fine.
3. Build → Run on a device running Android 10 (API 29) or newer.

## ADB setup (run once per install, from a computer or via Shizuku)

```
adb shell pm grant com.example.parametriceq android.permission.DUMP
adb shell appops set com.example.parametriceq PROJECT_MEDIA allow
adb shell appops set com.example.parametriceq SYSTEM_ALERT_WINDOW allow
```

- `PROJECT_MEDIA allow` is the important one — it pre-approves the
  MediaProjection capture so the app doesn't need to show that consent
  dialog every time you start it.
- `DUMP` and `SYSTEM_ALERT_WINDOW` mirror what RootlessJamesDSP requests;
  they help on some OEM skins but aren't strictly required by this code yet.
- If a command fails with "not a changeable permission" your device/Android
  version may phrase it differently — check `adb shell dumpsys package
  com.example.parametriceq` for the exact permission strings it declares.

## Testing it

1. Run the ADB commands above.
2. Launch the app, tap **Start**. If the projection dialog still appears
   once, accept it.
3. Open YouTube, YouTube Music, or a local video app and start playing
   something.
4. Move a slider — you should hear the change within well under a second.

## Known limitations (same ones we discussed)

- Apps that block internal audio capture (Spotify, Chrome, some DRM'd
  players) will not be affected — Android lets apps opt out of capture.
- Only one app using the system's audio-effect/capture chain at a time;
  this won't coexist with Wavelet, another RootlessJamesDSP instance, etc.
- The EQ bands here are cutoff-frequency based (closer to a graphic EQ)
  rather than true parametric bands with adjustable Q. See "Next steps."
- If Android kills the service (e.g. after long idle), you'll need to press
  Start again — the MediaProjection token doesn't survive a restart.

## Next steps (Stage 2)

Once this pipeline is confirmed working on your device, the natural upgrade
is to replace `DynamicsProcessing` with your own biquad peaking/shelf filters
applied to the PCM buffer directly in the capture thread, before
`track.write(...)`. That gets you real per-band center frequency + Q + gain
control — the level of precision Poweramp's parametric mode has. Happy to
build that as a follow-up once Stage 1 is confirmed working on your phone.
