# NoRouterDrop

Send files from one of your Android phones to another with no internet, no
router, no account and no server. The phones talk to each other directly over
Wi-Fi Direct. After you pair two phones once, files sent between them are saved
on arrival with no "Accept" tap.

> **Early version, not audited.** The encryption design (X25519 pairing,
> AES-256-GCM) has not had an independent security review. Do not rely on it for
> anything where a failure would be serious.
>
> **What was tested on real phones, and what was not.** In three stages:
> 1. A private test build called "Direct Test" (build 46ecc1cc) worked in both
>    directions on two phones.
> 2. An earlier renamed NoRouterDrop build paired and sent one photo; repeat
>    sends then failed (Wi-Fi Direct invite error code 0).
> 3. The reconnect, send-queue, keep-alive and screen changes in this version
>    are **not yet verified on a phone**. Unit tests pass, but they do not cover
>    the radio.
>
> Later phone tests also showed a first transfer succeeding followed by failed
> sends or reconnects with Android error code 0 or group disconnections.
> Repeated transfers and reconnection remain unreliable on the tested phones.
> See "Tested devices".

## What it does

- Pair two phones once, face to face. Both screens show the same 6-digit code.
  You confirm "Codes match" on both. The code comes from an X25519 key
  exchange, so someone relaying the traffic in the middle would make the codes
  differ.
- After pairing, pick a trusted phone and a file. The receiving phone saves it
  automatically to `Downloads/NoRouterDrop` (through Android MediaStore).
- A phone you never paired is declined automatically.
- Everything after pairing is encrypted with AES-256-GCM. Each file is checked
  with SHA-256 before it is saved.
- Transfers do not use the internet. No analytics, no account. The only internet request is the optional menu item "Check for updates", which asks GitHub for the newest release when you tap it (it never installs anything by itself).
- If only the channel drops while the Wi-Fi Direct group stays up, the phone that joined the group opens the channel again by itself. A second channel from the same trusted phone replaces the older one, and a channel from an unknown phone is closed on its own, without touching the working link.
- When both phones try to connect at once, the phone with the smaller id invites first and the other waits, so the two invites do not cancel each other. An existing group is never torn down while the other phone may still be connecting to it.
- A round progress ring shows a file's percent while it is being received or sent. Several files can be queued; an open link is reused for the next file.
- The three-dot menu holds: Log (copy it when reporting a problem), Check for updates, and Contact / Support.

## Requirements

- Two Android phones with Wi-Fi Direct, **Android 13 (API 33) or newer on both**.
  The app installs from Android 8 (minSdk 26), but the pairing step refuses to
  run below Android 13, so below that it cannot pair or send.
- Wi-Fi on, Location on (Android needs Location on to find nearby devices; the
  app never reads your location), and "Nearby devices" and notification
  permission granted.

## Install

1. Build the APK (see BUILDING.md) or take one from the project's releases page
   if the author publishes one. Check its SHA-256 against the release notes.
2. Copy the APK to each phone and open it. Allow "install unknown apps" for the
   app you open it from.

## Pair and send

1. Open the app on both phones and keep both screens on, close to each other.
2. On phone B tap **2. Wait for the other phone**.
3. On phone A tap **1. Start pairing** and pick phone B from the list.
4. If Android shows a Wi-Fi Direct invite on phone B, accept it.
5. Both phones show a 6-digit code. If it is the same, tap **Codes match** on
   both.
6. To send: tap **Send a file to a trusted phone**, pick the phone, pick the
   file. Phone A may need to re-invite phone B; this takes a few seconds.

Only one phone should start the connection. If both invite at once, it fails.
Use "Wait" on the other phone.

## Known limits

- Android shows its own Wi-Fi Direct invite dialog on some phones when a link
  is made. No app can remove that without root.
- Wi-Fi Direct forms one group at a time. This is meant for your own 2 or 3
  phones, not a big network.
- Both phones should have the app open (screen on) when a link is made. Sending
  with the screen off is not tested.
- A saved Wi-Fi Direct address is not reliable on Android 10+, so the app finds
  the other phone by looking at who is nearby. With several phones around it
  can pick the wrong one (it only invites; an unpaired phone still cannot
  receive files).
- Windows, iOS and Android below 13 (API 33) are not supported.
- No resume for interrupted transfers: an interrupted file is sent again from the start (up to 3 tries). One file at a time. An idle link is closed after 5 minutes and reopens on the next send.
- Unit tests (JVM, no phone) cover the pairing key exchange, file-name cleaning, the JSON message format and framing, and the trusted-phone list saved on disk. The send-queue, tie-break and reconnect rules are also checked in a simulation of two phones on a virtual clock (`LinkSimulationTest`). That is a model of the logic with assumed timings, not the Android Wi-Fi Direct stack. The radio and transfer code itself was tested by hand, not automatically.

## Permissions (from AndroidManifest.xml) and why

| Permission | Why |
|---|---|
| NEARBY_WIFI_DEVICES (flag neverForLocation) | find nearby phones over Wi-Fi Direct (Android 13+) |
| ACCESS_FINE_LOCATION (up to Android 12 only) | Android's older rule for discovery; has no effect on Android 13+, which this app requires for pairing |
| ACCESS_WIFI_STATE, CHANGE_WIFI_STATE | read and control Wi-Fi Direct |
| ACCESS_NETWORK_STATE, CHANGE_NETWORK_STATE | check the state of the Wi-Fi Direct connection. Kept from the tested build; may be removable (not verified) |
| INTERNET | Android requires it to open the local TCP socket between the two phones. It is also used for the optional "Check for updates" request to api.github.com. No other internet connections |
| WAKE_LOCK | keeps the Wi-Fi link and CPU awake while a link is open (released when idle) |
| FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE | keep the link running as a visible foreground service |
| POST_NOTIFICATIONS | the foreground-service notice and a "file received" notice |

The manifest also declares the Wi-Fi Direct hardware feature as required.
Android also asks you to turn Location on while discovery runs.

## Tested devices

1. Private "Direct Test" build 46ecc1cc: worked in both directions on the two phones below (details under the table).
2. Earlier renamed NoRouterDrop build: pairing worked and one photo was sent from the Samsung to the Honor. Further sends failed with Wi-Fi Direct invite error code 0, and the link dropped about 10 seconds after a transfer.
3. This version (reconnect with clean-up and retries, send queue, keep-alive, only one phone invites at a time, new screen with progress ring and three-dot menu): not yet verified on a phone.

| Phone | Android |
|---|---|
| Samsung SM-A165F (shown as "A16") | not stated |
| Honor X8d (LNA-LX2) | 16 |

Stage 1, on those two phones, in both directions: pairing, trust, and sending png, heic,
mp4 and jpg (4 MB) files and a screenshot, saved on arrival with no tap on the
receiving phone.

Not tested: this version on a phone, other phones, other Android versions,
more than two phones, sending with the screen off.

## Security notes

- Pairing is trust on first use with a human check of the 6-digit code. If you
  skip the check, you lose the protection.
- The pair key is stored wrapped by an Android Keystore key. It never leaves
  the two phones.
- Report problems through the repository's issue tracker. Do not post keys,
  logs with personal data, or exploit details in a public issue.

## License

MIT. See LICENSE. Third-party libraries are listed in DEPENDENCIES.md.
