# UniFi Phone (Unofficial)

A native Android SIP softphone for **UniFi Talk**, designed for dedicated /
kiosk-locked handsets (wall phones, warehouse phones, ring-group extensions).
Not affiliated with Ubiquiti.

## Features (v1)

- Native SIP registration to UniFi Talk (FreeSWITCH) over UDP/TCP/TLS — no WebRTC
- Audio calls: dial, answer, decline, hang up, hold, mute, speaker, in-call DTMF
- UniFi-style UI with light / dark / system theme
- Phone label + registered extension on the home screen, live registration status pill
- Contacts, home-screen speed dials, and **paging targets** (dialled as `*0*<group ext>`)
- Call history with a **"show missed calls" toggle** for ring-group handsets
- Three built-in ringtones + import your own audio file
- Single-app **kiosk mode** via Lock Task (full lock when provisioned as Device Owner)
- Foreground service keeps registration alive with the screen off

## Build (produces the sideloadable APK)

1. Install Android Studio (Koala or newer).
2. Open this folder as a project; let Gradle sync (fetches the Linphone SDK
   from linphone.org's Maven repo — declared in `settings.gradle.kts`).
3. Build → **Build APK(s)**, or from a terminal:
   `./gradlew assembleDebug`
4. APK lands at `app/build/outputs/apk/debug/app-debug.apk`.
   Sideload: `adb install app-debug.apk` (or copy to the phone and open it).

For a release build, add a signing config or use Android Studio's
Generate Signed APK wizard — a debug APK is fine for internal handsets.

## UniFi Talk enrolment

1. Talk → Devices → Add Device → third-party SIP device. Talk issues an
   extension, SIP username, and password.
2. In the app: Settings → SIP account. Server = the Talk console's **LAN IP**
   (Talk has been known to advertise a WAN hostname — use the local IP),
   port 5060, transport UDP.
3. Save & register — the status pill should go green.

## Kiosk provisioning (full lock)

Screen pinning works out of the box (toggle in Settings), but is escapable.
For a proper unescapable kiosk, make the app **Device Owner** on a handset
with no accounts configured (fresh/factory-reset device, skip Google sign-in):

```
adb install app-debug.apk
adb shell dpm set-device-owner au.josh.unifiphone/.kiosk.PhoneDeviceAdminReceiver
```

Then enable "Lock to this app" in Settings. The app also declares the HOME
intent category, so it can be set as the default launcher on the handset.

To remove device owner later:
`adb shell dpm remove-active-admin au.josh.unifiphone/.kiosk.PhoneDeviceAdminReceiver`
(only works while the app permits it, otherwise factory reset).

## Ringtones

Three original tones ship in `res/raw`. Ubiquiti's own Talk-phone ringtones
are their copyrighted assets so they aren't bundled — if you want them on
your own kit, pull the audio files from one of your Talk phones / the Talk
install and load them via **Settings → Import ringtone file** (WAV works
best; the ringer is played by the Linphone core, which handles WAV natively —
transcode MP3/OGG to WAV if a tone won't play).

## Known limitations / backlog

- No BLF/presence, no transfer UI yet (attended/blind transfer is a
  straightforward addition via `call.transferTo()`)
- No voicemail (MWI) indicator yet
- History capped at 200 entries
- Custom ringtone import assumes WAV for guaranteed playback
