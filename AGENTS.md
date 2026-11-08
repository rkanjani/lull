# Working on Lull

Lull ships as two native apps from this one repository: an iPhone app (Swift, SwiftUI) and an Android
app (Kotlin, Jetpack Compose, in `android/`). **They are one product.** Anyone changing either app,
person or agent, follows the rules below.

## The parity rule

Every change to what Lull does lands in both apps in the same pull request: features, fixes,
protocol findings, copy, limits, defaults and tests. A change made only to one app is unfinished.

- **Same behaviour.** Same screens, flows, controls, limits, defaults, timings and error handling.
  The Android app is a line-by-line port: when you change a Swift function, change its Kotlin twin
  (see the map below), keeping the same names, structure and comments where the languages allow.
- **Same words.** User-facing text is identical, except where the platform differs (for example
  "Control Center" on iOS is "Quick Settings" on Android, and "iPhone" becomes "phone").
- **Same bytes.** Anything that touches the BedJet changes `Codec`, the `Link` helpers and `DemoLink`
  on both sides, with the same test added to both test suites. Hand-over UUIDs must stay identical so
  iPhones and Android phones keep passing a BedJet to each other (`handOverRequestMatchesTheIPhoneApp`
  pins this).
- **Same tests.** A test added to one suite gets its twin in the other (table below).
- **Same version.** Both apps read `MARKETING_VERSION` from `Lull.xcodeproj`. Release notes go in
  both `fastlane/metadata/en-US/release_notes.txt` and `fastlane/metadata/android/en-US/changelogs/default.txt`.
- **Platform-only work** (signing, permissions, Info.plist, the Android manifest, store plumbing) is
  fine on one side. Say so in the pull request.
- If something truly can't be matched, say why in the pull request and add it to "Known differences" below.

## Where each piece lives

| What | iPhone | Android |
| --- | --- | --- |
| Protocol types and limits | `BedJetKit/Sources/BedJetKit/Models.swift` | `android/bedjetkit/src/main/kotlin/com/rohankanjani/lull/bedjetkit/Models.kt` |
| Byte encoding, BedJet 3 | `BedJetKit/.../Codec.swift` | `android/bedjetkit/.../Codec.kt` |
| Byte encoding, BedJet V2 | `BedJetKit/.../CodecV2.swift` | `android/bedjetkit/.../CodecV2.kt` |
| Link protocol and helpers | `BedJetKit/.../Link.swift` | `android/bedjetkit/.../Link.kt` |
| Simulated BedJet | `BedJetKit/.../DemoLink.swift` | `android/bedjetkit/.../DemoLink.kt` |
| Bluetooth transport | `BedJetKit/.../BLELink.swift` | `android/app/src/main/java/com/rohankanjani/lull/BleLink.kt` |
| Hand-over UUID | `BLELink.handoffUUID` | `android/bedjetkit/.../Handoff.kt` |
| BedJets nearby, and binding a link to its unit | `BedJetKit/.../Discovery.swift`, `BLELink.takes` | `android/bedjetkit/.../Discovery.kt` (with `takes`), `android/app/.../BleDiscovery.kt` |
| App state: the BedJets, schedules, settings | `Lull/AppModel.swift` | `android/app/.../AppModel.kt` |
| One BedJet: connection, night, history | `Lull/BedJet.swift` | `android/app/.../BedJet.kt` |
| Schedules, phases, templates, formatting | `Lull/Schedule.swift` | `android/app/.../Schedule.kt` |
| Night history, grouping and sample nights | `Lull/History.swift` | `android/app/.../History.kt` |
| Colours and shared controls | `Lull/Theme.swift` | `android/app/.../ui/Theme.kt` |
| Tabs, alerts, screen scaffold | `Lull/Views/RootView.swift` | `android/app/.../ui/RootScreen.kt` |
| Remote tab | `Lull/Views/RemoteView.swift` | `android/app/.../ui/RemoteScreen.kt` |
| Schedule tab | `Lull/Views/ScheduleView.swift` | `android/app/.../ui/ScheduleScreen.kt` |
| Schedule and phase editors | `Lull/Views/ScheduleEditor.swift` | `android/app/.../ui/ScheduleEditor.kt` (`TemperatureSlider` is in `RemoteScreen.kt`, `ModePicker` in `Theme.kt`) |
| Night curve | `Lull/Views/NightCurve.swift` | `android/app/.../ui/NightCurve.kt` |
| BedJet sheet, BedJets nearby, choosing a BedJet | `Lull/Views/DeviceSheet.swift` | `android/app/.../ui/DeviceSheet.kt` |
| History tab | `Lull/Views/HistoryView.swift` | `android/app/.../ui/HistoryScreen.kt` |
| Launch and lifecycle | `Lull/LullApp.swift` | `android/app/.../MainActivity.kt` |
| What widgets are handed | `Shared/WidgetSnapshot.swift` | `android/app/.../WidgetSnapshot.kt` |
| Start Night and tab links from outside the app | `Shared/Intents.swift` | `android/app/.../Commands.kt` |
| Tonight widget | `LullWidgets/TonightWidget.swift` | `android/app/.../widgets/TonightWidget.kt` |
| Last Night widget | `LullWidgets/LastNightWidget.swift` | `android/app/.../widgets/LastNightWidget.kt` |
| Widget pieces and redraws | `LullWidgets/WidgetParts.swift` | `android/app/.../widgets/WidgetParts.kt`, `Drawing.kt` |
| Start Night and Remote controls | `LullWidgets/Controls.swift` | `android/app/.../widgets/Tiles.kt` (Quick Settings tiles and app shortcuts) |
| Protocol tests | `BedJetKit/Tests/BedJetKitTests/CodecTests.swift`, `CodecV2Tests.swift`, `DiscoveryTests.swift` | `android/bedjetkit/src/test/.../CodecTest.kt`, `CodecV2Test.kt`, `DiscoveryTest.kt` |
| App logic tests | | `android/app/src/test/.../ScheduleTest.kt` |
| Choosing, switching and forgetting BedJets | `LullTests/DeviceSelectionTests.swift` | `android/app/src/androidTest/.../DeviceSelectionTest.kt` (on a device, since the model needs Android) |
| Widget tests | `LullTests/WidgetTests.swift` | `android/app/src/test/.../WidgetTest.kt` |
| Widget UI tests | `LullUITests/HomeScreenWidgetTests.swift` | `android/app/src/androidTest/.../WidgetUiTest.kt` |
| Contrast tests | `LullTests/ContrastTests.swift` | `android/app/src/test/.../ContrastTest.kt` |
| Screen reader and text size tests | `LullTests/VoiceOverTests.swift`, `LullUITests/AccessibilityTests.swift` | `android/app/src/androidTest/.../AccessibilityTest.kt` (with the semantics checks in `LullUiTest.kt`) |
| UI tests | `LullUITests/LullUITests.swift` | `android/app/src/androidTest/.../LullUiTest.kt` |
| Store screenshots | `LullUITests/StoreScreenshots.swift` | `android/app/src/androidTest/.../StoreScreenshots.kt` |
| Store listing | `fastlane/metadata/en-US/` | `fastlane/metadata/android/en-US/` |
| Release | `fastlane ios release`, `.github/workflows/app-store.yml` | `fastlane android release`, `.github/workflows/play-store.yml` |

Launch flags match too: `-ephemeral`, `-demoElapsedMinutes`, `-demoLabelConnected`, `-demoHistory`,
`-demoChoose`, `-demoFirstUnitAway` and `-logConnections` on iOS are the intent extras of the same names on
Android.

A BedJet's unit is its `CBPeripheral.identifier` on iOS and its Bluetooth address on Android. Each phone knows
a unit by its own identifier, so the short code shown after a shared name differs between an iPhone and an
Android phone. The simulated BedJets use the same identifiers on both, so their codes match.

## Accessibility, on both

What VoiceOver reads on iOS, TalkBack reads on Android, in the same words: give each control the same spoken
label, value (`stateDescription`), hint (`onClickLabel`) and actions (`customActions`) as its `accessibilityLabel`,
`accessibilityValue`, `accessibilityHint` and `accessibilityAction`, and mark the same headings. Layouts that
stack at iOS's accessibility text sizes stack on Android when `isLargeText()` is true (font scale 1.5 and up).
Android text in `sp` grows with the person's setting already; iOS uses `scaledFont` for the same.

## Checking a change

Run both before opening a pull request. The **Tests** workflow runs the unit tests on every pull request.

```bash
cd BedJetKit && swift test
xcodebuild test -project Lull.xcodeproj -scheme Lull -destination 'platform=iOS Simulator,name=iPhone 17 Pro'
```

```bash
cd android && ./gradlew :bedjetkit:test :app:testDebugUnitTest
cd android && ./gradlew :app:connectedDebugAndroidTest   # needs an emulator or phone
```

Both simulators run against the simulated BedJet, so no hardware is needed. For UI changes, compare
the two apps side by side: the screens should look like the same app.

The tests run debug builds. Release builds are shrunk by R8, which can remove classes a library only
loads by name, so after adding or updating an Android dependency, install and open a release build too:

```bash
cd android && ./gradlew :app:installRelease   # signed with the debug key unless the upload key is set
```

## Known differences

Platform conventions, not behaviour:

- Android uses Material components where iOS uses system ones (bottom navigation bar, bottom sheets,
  the time picker, the long-press menu), styled to match Lull's look.
- Android asks for the "Nearby devices" permission when Lull first needs Bluetooth; iOS asks for Bluetooth.
- Google Play skips a release whose version is already on the production track, but unlike the App
  Store lane it doesn't replace a release that is still in review.
- iOS plays the night timeline as a VoiceOver audio graph (`accessibilityChartDescriptor`). Android has no
  equivalent, so TalkBack reads the same phases from the timeline's description instead.
- An iPhone advertises one set of services at a time, so iOS sends every BedJet's hand-over request in one
  advertisement (`HandoffRequests`). Android runs one advertisement per BedJet side by side.
- Android phones have no Lock Screen or StandBy widgets, so the Tonight and Last Night widgets are Home
  Screen only. Quick Settings tiles stand in for the Control Center controls, and app shortcuts (long-press
  the icon) for Siri and App Shortcuts. Both open Lull to start the night, as the iOS control does.
- iOS widget UI tests place the widgets on the Home and Lock Screens through Springboard. Android's draw each
  widget at each size with Glance's test runner, since launchers differ too much to drive.

## Style

Match the code around you, in either language. Small readable functions, names that say what they do,
comments for the *why* (BedJet quirks, platform workarounds). No third-party dependencies beyond the
ones each app already has without discussing it first. No analytics, tracking, accounts or network calls.
