# Scope of Works — UniFi Talk Android Kiosk Softphone

## 1. Objective

Deliver a sideloadable native Android application ("UniFi Phone") that acts as
a dedicated SIP handset against a UniFi Talk PBX, suitable for kiosk-locked
deployment on cheap Android hardware (ring-group / common-area phones).

## 2. In scope (v1)

| # | Requirement | Implementation |
|---|-------------|----------------|
| 1 | SIP registration to UniFi Talk | Linphone SDK (`org.linphone:linphone-sdk-android`); standard SIP REGISTER over UDP/TCP/TLS to the Talk console (FreeSWITCH), using credentials from a Talk third-party SIP device. Avoids the WS/WSS 5066/7443 WebRTC path used by browser clients. |
| 2 | Full call handling | Dial, answer, decline, hang up, hold/resume, mute, speaker/earpiece switch, in-call DTMF (RFC 4733 via Linphone). |
| 3 | Kiosk lock | Android Lock Task Mode. Device Owner provisioning via `adb shell dpm set-device-owner` gives an unescapable single-app lock incl. keyguard suppression; non-provisioned devices degrade to screen pinning. HOME intent filter allows launcher replacement. |
| 4 | UniFi look & feel | Jetpack Compose + Material 3 with a custom UniFi-style scheme: #006FFF primary blue, neutral grey surfaces, green/amber/red status pills, 8–14 dp radii. |
| 5 | Light & dark modes | System / Light / Dark selector persisted in DataStore. |
| 6 | Phone identification | Free-text label + registered extension rendered on the home screen header alongside live registration state. |
| 7 | Missed-call control | Per-device "Show missed calls" toggle. When off: no missed-call badge and missed entries are hidden from Recents — intended for ring-group members. |
| 8 | Contacts & speed dials | Local directory (JSON in app storage) with three entry types: Contact, Speed Dial (pinned as home-screen chips), Page. |
| 9 | Paging | Page entries store a group extension and dial `*0*<ext>` per UniFi Talk's paging feature code. Rendered with a distinct megaphone icon. |
| 10 | Ringtones | Three original bundled tones (classic dual-tone, chime, digital) + user import via Storage Access Framework into app storage. Ubiquiti's own tones deliberately excluded (copyright); user can import them from their own hardware. |
| 11 | Persistence of registration | `phoneCall`-type foreground service, START_STICKY, push disabled (LAN device, always-on). |
| 12 | Deliverable | Buildable Android Studio project → debug APK for sideload. minSdk 26, targetSdk 35. |

## 3. Out of scope (v1) / backlog

- Video calling (Talk phones use a non-WebRTC RTP video path; parked)
- Call transfer UI (blind/attended), 3-way conference
- BLF / extension presence, MWI voicemail lamp
- Provisioning server / MDM config (settings are entered on-device)
- Bundling Ubiquiti ringtone or branding assets

## 4. Architecture

- **Language/UI:** Kotlin 2.0, Jetpack Compose, single-activity, 4-tab nav
  (Phone / Contacts / Recents / Settings) with a full-screen call overlay.
- **SIP/media:** Linphone SDK 5.3.x core owned by the `Application` class,
  wrapped in `SipEngine` (StateFlows for registration + call state).
- **State:** DataStore Preferences for settings; JSON files for
  contacts/history (deliberate — avoids Room/KSP for a <100-entry directory).
- **Kiosk:** `PhoneDeviceAdminReceiver` (DeviceAdminReceiver) + `KioskManager`
  wrapping `DevicePolicyManager.setLockTaskPackages` / `startLockTask`.

## 5. Acceptance tests

1. Register against Talk third-party device credentials → green "Registered" pill; survives screen-off ≥ 1 h.
2. Inbound call from another Talk extension rings with selected tone; answer/decline both function; missed inbound logs as Missed (visible only when toggle on).
3. Outbound call to extension and to PSTN (if trunked) with 2-way audio; hold, mute, speaker, DTMF verified against an IVR.
4. Page speed-dial chip dials `*0*<group>` and one-way audio reaches group handsets.
5. Device-owner-provisioned handset: Home/Recents/status-bar escape attempts fail while kiosk toggle is on; app relaunches into lock after reboot.
6. Theme switch Light/Dark applies immediately; label + extension shown on home screen.
