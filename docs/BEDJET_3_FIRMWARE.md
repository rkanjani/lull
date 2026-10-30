# BedJet 3 Wi-Fi and firmware updates

How Lull gives a BedJet 3 its Wi-Fi network and asks it to update its firmware. The code is in
`BedJetKit/Sources/BedJetKit/Codec.swift` and `Link.swift`; the rest of the BedJet 3 protocol is described in
their comments, and the V2 in `BEDJET_V2_PROTOCOL.md`.

## Sources

- **Official BedJet Android app** (`com.bedjet` 1.0.6, version code 18, "BedJet 3 Smart Remote"), decompiled with
  JADX 1.5.6. The code lives in `MainActivity.doBedJetUpdate`, `updatePhaseTask`, `decodeHome`, `writeSSID`,
  `writePassword`, `WifiFragment` and `MenuFragment`.
- **ESPHome's BedJet component** (`esphome/components/bedjet`), which names the same button `MAGIC_UPDATE` and the
  notification codes.
- **A status packet captured from a BedJet 3** on firmware V1.20, already used in `CodecTests`.

Each section below is labelled by how sure it is:

- **Observed in code**: the decompiled app does it this way.
- **Captured**: also seen in real traffic.
- **Inferred**: not confirmed by either source.

## The BedJet does the work (observed in code)

The firmware never passes through the phone. The BedJet downloads it from BedJet's server over its own Wi-Fi,
so a phone only has to give it a network and ask it to check. A BedJet 3 only joins 2.4 GHz networks.

## Giving it a network (observed in code)

| Characteristic | Contents |
| --- | --- |
| `00002002-bed0-0080-aa55-4265644a6574` | Network name, UTF-8, no terminator |
| `00002003-bed0-0080-aa55-4265644a6574` | Password, UTF-8, no terminator |

The app writes the name, then the password, each with a response. It sends no command afterwards, yet waits for
a connection test, so writing the password starts one. The app refuses passwords shorter than 8 characters.

**Inferred:** joining doesn't restart the unit, so a link lost during the test is only Bluetooth. Once it's back, Lull
takes the result showing only if the unit reported a step first or the result has changed, since otherwise it may
be the one from before, and it gives up after 20 seconds without the link.

**Inferred:** Lull also refuses network names over 32 bytes and passwords under 8 or over 63 bytes of UTF-8, the
limits of WPA2 itself. For the usual ASCII passwords, bytes and characters are the same.

**Captured: `0x2002` can't be read.** It reports itself readable and writable, but a BedJet 3 never answers a read.
Requests go one at a time, so everything after the read waits, and the link drops when the 30-second ATT
timeout runs out, then again after every reconnect that reads it. The official app never reads it, and neither
does Lull. To show which network a BedJet has, Lull remembers the name it last gave it.

The characteristics a BedJet 3 (V1.20 (407)) reports: `2000` read, write, notify; `2001` read, write; `2002` read,
write; `2003` write; `2004` write; `2005` read, write; `2006` read, write.

## Checking for an update (observed in code)

Button `0x43` (`01 43` on the command characteristic, `MAGIC_UPDATE` in the app). There is no separate check:
the unit checks, and when the server has newer firmware it installs it at once and restarts, which drops the
Bluetooth connection. The app's menu sends it from "Update Firmware" and then waits for the unit to reconnect.

**The unit restarts when asked, and checks while it's away (captured).** The app's dialog says "Waiting for
reconnect" from the moment it sends `0x43`, and it sets a flag (`stayGattConnected`) that reconnects whenever the
link drops. ESPHome's comment on the same button: "This will also restart the Bedjet." On a BedJet 3 running
V1.20 (407), with no newer firmware: the write was acknowledged within 0.1 s, the link was lost 4 s later, the unit
accepted a new connection within a second, and it reported 26 (No update needed) straight away. No steps below 20
were ever reported, before or after, over the next five minutes.

So Lull waits for the BedJet to come back (up to the whole check's ten minutes), and takes the result it reports
once it's back. It reads the firmware version before and after, so an update installed while the unit was away is
reported as one. A dropped link only counts as installing once the unit has reported step 6 (Updating).

The official app never sends `0x42` (`MAGIC_CONNTEST`), and neither does Lull.

## Progress: status byte 26 (observed in code, captured)

Byte 26 is in the second part of the status. Even on a link that takes 512-byte writes, a BedJet 3 sends the status
as a 20-byte notification flagged partial, and the other 11 bytes only to a read of `2000` (captured), which Lull
makes at most every 2 seconds. So progress arrives about every 2 seconds.

The BedJet reports where it is in byte 26 of its status, counting the leading partial-packet flag as byte 0, as
`Codec.parseStatus` does. Values below 20 mean it is still working; from 20 up it has finished.

| Value | Official app's text | Lull |
| --- | --- | --- |
| 0 | Idle | |
| 1 | Starting | Starting… |
| 2 | Connecting to AP | Joining Wi-Fi… |
| 3 | Got IP address | Connected to Wi-Fi… |
| 4 | Checking connection | Testing the connection… |
| 5 | Checking for update | Checking for an update… |
| 6 | Updating | Installing the update… |
| 7 | Restarting BedJet | Restarting your BedJet to check… |
| 20 | No WiFi Config | The BedJet has no network yet |
| 21 | Unable to connect | Wrong password, or not a 2.4 GHz network |
| 22 | DHCP failure | No address from the router |
| 23 | Unable to contact server | On Wi-Fi, but BedJet's server can't be reached |
| 24 | Connection test OK | Joined |
| 25 | Connection test failed | |
| 26 | No update needed | Up to date |
| 27 | Radio is disabled! | |
| 28 | Restarting BedJet | Installing |
| 29 | Update failed | |

**The last result stays.** The captured packet reports 21 long after any connection test, and the app ignores
21, 24 and 26 until the value has changed to something else. Lull therefore only accepts a result once the unit has
reported a step below 20, or a result different from the one it showed before. If neither happens within 20 seconds,
Lull reports the failure still showing, or that the BedJet didn't respond.

## Notifications (observed in code)

Byte 29 code 2 means "A newer version of firmware is available", and code 3 means "Unable to connect to the firmware
update server". The app shows these, then acknowledges with button `0x52`, as it does for every notification. For
code 2, Lull says newer firmware is available in the BedJet sheet and acknowledges it the same way. It leaves code 3
alone, as it does codes it doesn't show.

## Versions (observed in code)

Query `0x20` answers with the BedJet's firmware in payload bytes 0–14 and the remote control's in bytes 32–46,
both as text, for example `V1.20 (407)` and `V1.20 (265)` (captured). Bytes 16–31 hold a third, `V1.20 (778)`, which
the app doesn't show. Lull shows the BedJet's.

## No list of networks (captured)

A BedJet 3 can't be asked which Wi-Fi networks it can see. Asked for every value from `0x00` to `0x7f` (`41 <id> <tag>`,
read back from `0x2006`), it answers only `0x00` (name), `0x01`–`0x06` (memory and program names), `0x07`–`0x13`
(program steps) and `0x20` (versions), and nothing else. So Lull's Wi-Fi screen lists the networks the phone can
see: those nearby on Android, and on an iPhone, which can't list networks, only the one it's on.

## Status byte 27 (observed in code, captured)

Besides bit 0 (beeps muted) and bit 4 (light ring on), the app reads bit 2 as "unit set up" (the remote control's
first-run setup is done) and bit 5 as "connection test passed". Lull uses neither. The app's only reader of bit 5 is
an unused `getNeedWifiSetup()`, so its meaning isn't certain, and the captured packet has it set while byte 26
reports 21 ("Unable to connect").

## BedJet V2 (observed in code)

The app hides its firmware, Wi-Fi and clock controls for a V2 and only shows its version (firmware ÷ 16, then
firmware mod 16, as `V3.7`). The app does carry a V2 bootloader flasher with two bundled images (SKUs `6002NA` and
`7002EU`), but nothing reaches it: the message that would start it (`MSG_RE_FLASH`) is never sent. The retired
app (`com.bedjet.bedjet` 1.2.11, version code 54, "BedJet App (OLD)") carries the same flasher and images, and never
sends that message either, so neither app can update a V2. A V2 below firmware 48 is labelled "BedJet V1" in the
current app, which greys out Biorhythm. Lull doesn't update V2 firmware: the image is BedJet's, and flashing over
Bluetooth could leave the unit unusable.
