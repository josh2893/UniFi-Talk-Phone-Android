# Engine v2 — Pure-Kotlin SIP/RTP with H.265 Video

## Why the Linphone SDK was removed

UniFi Talk handsets (UTP-Touch etc.) negotiate video with exactly one codec:
**H.265**, over **plain RTP/AVP** (no SRTP, no ICE, no DTLS). Verified via
fs_cli capture of a handset↔handset video call:

```
m=video 44366 RTP/AVP 96
a=rtpmap:96 H265/90000
a=rtcp-fb:96 nack pli
a=rtcp-fb:96 ccm fir
```

Talk's dialplan routes calls through `route_to_video` with
`bypass_media=true`, so SDP and RTP travel **end-to-end** between endpoints —
FreeSWITCH does signalling only. A third-party endpoint that offers
H.265/AVP is negotiating directly with the handset.

Linphone (mediastreamer2) has no H.265 on any platform, so it can never
complete this negotiation. Android has hardware HEVC via `MediaCodec`.
Because the target environment is this simple (UDP SIP on a LAN, one known
FreeSWITCH server, G.711 in the codec list), the SDK was replaced with a
small pure-Kotlin stack — no NDK, no native builds.

## What's in `core/engine/`

| File | Purpose |
|---|---|
| `SipMessage.kt` | SIP parse/serialize |
| `DigestAuth.kt` | MD5 digest auth (REGISTER + INVITE challenges) |
| `Sdp.kt` | Offer/answer in the exact handset wire format |
| `SipClient.kt` | UA: register/refresh, INVITE dialogs, BYE/CANCEL, OPTIONS |
| `RtpSession.kt` | RTP + minimal RTCP (PLI/FIR in, PLI out) |
| `G711.kt` | PCMU codec (pure Kotlin) |
| `AudioStream.kt` | AudioRecord/Track, platform AEC/NS, RFC2833 DTMF |
| `H265Rtp.kt` | RFC 7798 packetizer/depacketizer |
| `VideoReceiver.kt` | RTP → MediaCodec HEVC decoder → Surface |
| `VideoSender.kt` | Camera2 → MediaCodec HEVC encoder → RTP |

`core/SipEngine.kt` keeps the same public API as the Linphone version, so the
UI layer is unchanged apart from the video surface in `CallScreen`.

## The video setting

**Settings → Behaviour → Video calls**

- **ON** — calls placed from the dial pad include an H.265 video m-line, so
  they arrive at the handset as video calls first time.
- **OFF** — outgoing calls are audio-only, but incoming video calls are
  still **accepted** (video is answered whenever offered).

## Status / known limitations

This engine is **untested against real hardware** — it was written against
fs_cli captures, not a live console. Expect to debug. Specifically:

1. **The gating unknown:** whether a UTP-Touch accepts H.265 video from a
   non-Talk endpoint at all. If the Talk client checks the peer device type,
   the answer SDP will come back `m=video 0` no matter how correct our offer
   is. **First test:** enable Video calls, dial a UTP-Touch, then check the
   answer SDP in `fs_cli` (`console loglevel 7`). Non-zero video port =
   green light.
2. Audio is **PCMU only** (no opus). Fine on a LAN; the handsets list PCMU.
3. Hold is local mute, not a SIP re-INVITE hold.
4. No RTP reordering buffer — loss recovery is "drop to next keyframe + PLI",
   which is acceptable on a switched LAN.
5. Retransmission handling is minimal (UDP on a LAN). If registration
   flaps, look here first.
6. `VideoSender` picks the front camera; kiosk tablets without cameras
   still work (send fails silently, receive still runs).
7. Multi-homed devices: local IP is picked by probing the route to the SIP
   server (this is the bug that broke inbound calls to Linphone on a
   multi-homed PC).

## Debugging

- Incoming INVITE headers + SDP are appended to
  `Android/data/<pkg>/files/sip_debug.log` (same as v1).
- On the console: `fs_cli` → `console loglevel 7`, watch the SDP both ways.
