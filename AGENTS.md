# Working on Lull

Lull ships as two native apps from this one repository: an iPhone app (Swift, SwiftUI) and an Android
app (Kotlin, Jetpack Compose, in `android/`), each with a watch app: Apple Watch (`LullWatch/`) and Wear OS
(`android/wear/`). **They are one product.** Anyone changing either app, person or agent, follows the rules
below. The watch apps are twins of each other in the same way the phone apps are.

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
| App state: the BedJets, schedules, settings | `Lull/AppModel.swift` | `android/app/.../AppModel.kt` (`AppModel.shared`, one per app as on iOS) |
| One BedJet: connection, night, history | `Lull/BedJet.swift` | `android/app/.../BedJet.kt` |
| Code the phone, its widgets and its watch app all use | `Shared/` | `android/shared/` (same package, `com.rohankanjani.lull`) |
| Schedules, phases, templates, formatting | `Shared/Schedule.swift` | `android/shared/.../Schedule.kt` |
| Night history, grouping and sample nights | `Shared/History.swift` | `android/shared/.../History.kt` |
| Colours and shared controls | `Shared/Theme.swift` | `android/shared/.../ui/Palette.kt` (colours, mode tints and icons), `android/app/.../ui/Theme.kt` (controls) |
| Tabs, alerts, screen scaffold | `Lull/Views/RootView.swift` | `android/app/.../ui/RootScreen.kt` |
| Remote tab | `Lull/Views/RemoteView.swift` | `android/app/.../ui/RemoteScreen.kt` |
| Schedule tab | `Lull/Views/ScheduleView.swift` | `android/app/.../ui/ScheduleScreen.kt` |
| Schedule and phase editors | `Lull/Views/ScheduleEditor.swift` | `android/app/.../ui/ScheduleEditor.kt` (`TemperatureSlider` is in `RemoteScreen.kt`, `ModePicker` in `Theme.kt`) |
| Night curve | `Shared/NightCurve.swift`, `Lull/Views/NightTimeline.swift` | `android/shared/.../ui/NightCurve.kt`, `android/app/.../ui/NightTimeline.kt` |
| BedJet sheet, BedJets nearby, choosing a BedJet | `Lull/Views/DeviceSheet.swift` | `android/app/.../ui/DeviceSheet.kt` |
| History tab | `Lull/Views/HistoryView.swift` | `android/app/.../ui/HistoryScreen.kt` |
| Launch and lifecycle | `Lull/LullApp.swift` | `android/app/.../MainActivity.kt` |
| What widgets are handed, and what each BedJet is doing | `Shared/WidgetSnapshot.swift`, `Shared/LiveStatus.swift` | `android/shared/.../WidgetSnapshot.kt`, `LiveStatus.kt` |
| Start Night and tab links from outside the app | `Shared/Intents.swift` | `android/shared/.../Commands.kt` (tabs and presets), `android/app/.../AppCommands.kt` (buttons open Lull through its unexported `CommandActivity`) |
| Shortcuts actions and automations | `Lull/BedJetIntents.swift`, `Shared/Shortcuts.swift` | `android/app/.../AutomationReceiver.kt` (intents for automation apps) |
| What an action does, its words and limits | `Lull/Automation.swift`, and the Automation part of `Lull/BedJet.swift` | `android/app/.../Automation.kt`, and the Automation part of `BedJet.kt` |
| Tonight widget | `LullWidgets/TonightWidget.swift`, `Shared/NightProvider.swift`, `Shared/TonightAccessory.swift` (Lock Screen) | `android/app/.../widgets/TonightWidget.kt` |
| Last Night widget | `LullWidgets/LastNightWidget.swift` | `android/app/.../widgets/LastNightWidget.kt` |
| Picking the BedJet a widget shows | `BedJetEntity`, `BedJetConfiguration` and `BothSidesConfiguration` in `Shared/Intents.swift` | `android/app/.../widgets/BedJetConfigureActivity.kt` |
| Both Sides widget | `LullWidgets/BothSidesWidget.swift` | `android/app/.../widgets/BothSidesWidget.kt` |
| Quick Remote widget, its presets and editing them | `LullWidgets/QuickRemoteWidget.swift`, `RemotePreset`, `PresetChoice`, `QuickRemoteConfiguration` and `QuickRemoteIntent` in `Shared/Intents.swift` | `android/app/.../widgets/QuickRemoteWidget.kt`, `QuickRemoteConfigureActivity.kt`, `RemotePreset` and `PresetChoice` in `Commands.kt` |
| Widget pieces and redraws | `Shared/WidgetParts.swift`, `LullWidgets/BedJetSummary.swift` (one BedJet now, for Tonight and Both Sides) | `android/app/.../widgets/WidgetParts.kt`, `BedJetSummary.kt`, `android/shared/.../widgets/Drawing.kt`, `WidgetText.kt` |
| Start Night and Remote controls | `LullWidgets/Controls.swift` | `android/app/.../widgets/Tiles.kt` (Quick Settings tiles and app shortcuts) |
| A running BedJet outside the app: Live Activity on iOS, Live Update notification on Android | `Shared/LiveStatus.swift`, `Shared/BedJetActivityAttributes.swift`, `Lull/LiveActivities.swift`, `LullWidgets/BedJetLiveActivity.swift` | `android/shared/.../LiveStatus.kt`, `android/app/.../LiveNotifications.kt` |
| Siri and App Shortcuts, on the phone and the watch | `Shared/Shortcuts.swift` | app shortcuts in `Tiles.kt`, and in `android/wear/.../WearActivity.kt` |
| What the watch and phone say to each other | `Shared/WatchMessages.swift` | `android/shared/.../WatchMessages.kt` |
| Errands from the watch, run on the phone | `Lull/PhoneSession.swift`, `runErrand` in `Lull/AppModel.swift` | `android/app/.../PhoneSession.kt` (with `ErrandWorker`), `runErrand` in `AppModel.kt`, `LullApplication.kt` |
| Watch app: launch, model, link to the phone | `LullWatch/LullWatchApp.swift`, `WatchModel.swift`, `WatchSession.swift` | `android/wear/.../WearActivity.kt`, `WatchModel.kt`, `WatchSession.kt`, `WatchStore.kt` |
| Watch pages: Tonight, Remote, Last Night, picking a BedJet | `LullWatch/Views/` | `android/wear/.../ui/` |
| Tonight on the watch face | `LullWatchWidgets/TonightComplication.swift` | `android/wear/.../TonightComplication.kt`, `TonightTile.kt` |
| Protocol tests | `BedJetKit/Tests/BedJetKitTests/CodecTests.swift`, `CodecV2Tests.swift`, `DiscoveryTests.swift` | `android/bedjetkit/src/test/.../CodecTest.kt`, `CodecV2Test.kt`, `DiscoveryTest.kt` |
| App logic tests, including wake times | `LullTests/ScheduleTests.swift` | `android/app/src/test/.../ScheduleTest.kt` |
| Choosing, switching and forgetting BedJets, and actions from automations | `LullTests/DeviceSelectionTests.swift` | `android/app/src/androidTest/.../DeviceSelectionTest.kt` (on a device, since the model needs Android) |
| How the Remote times air: longest, a duration or a time | `LullTests/RemoteTimerTests.swift` | `android/app/src/androidTest/.../RemoteTimerTest.kt` (on a device, since the model needs Android) |
| Widget tests | `LullTests/WidgetTests.swift` | `android/app/src/test/.../WidgetTest.kt` |
| Live Activity and Live Update tests | `LullTests/LiveStatusTests.swift`, `LiveActivitiesTests.swift`, `LullUITests/LiveActivityTests.swift` | `android/app/src/test/.../LiveStatusTest.kt`, `android/app/src/androidTest/.../LiveNotificationsTest.kt`, `LiveUpdateTest.kt` |
| Automation tests | `LullTests/AutomationTests.swift` | `android/app/src/test/.../AutomationTest.kt` |
| Widget UI tests | `LullUITests/HomeScreenWidgetTests.swift` | `android/app/src/androidTest/.../WidgetUiTest.kt`, `BedJetPickerTest.kt` and `QuickRemotePickerTest.kt` (the widgets' editors, which are system UI on iOS) |
| Contrast tests | `LullTests/ContrastTests.swift` | `android/app/src/test/.../ContrastTest.kt` |
| Screen reader and text size tests | `LullTests/VoiceOverTests.swift`, `LullUITests/AccessibilityTests.swift` | `android/app/src/androidTest/.../AccessibilityTest.kt` (with the semantics checks in `LullUiTest.kt`) |
| UI tests | `LullUITests/LullUITests.swift` | `android/app/src/androidTest/.../LullUiTest.kt` |
| Watch message tests | `LullTests/WatchMessagesTests.swift` | `android/app/src/test/.../WatchMessagesTest.kt` |
| Errand tests | `LullTests/WatchErrandTests.swift` | `android/app/src/androidTest/.../WatchErrandTest.kt`, and `android/app/src/test/.../OutboxTest.kt` (Android only: Watch Connectivity keeps messages in order itself) |
| Watch app tests | `LullWatchTests/WatchModelTests.swift`, `LullWatchUITests/CrownRowUITests.swift` | `android/wear/src/test/.../WatchModelTest.kt`, `android/wear/src/androidTest/.../CrownRowTest.kt`, and `StartNightTest.kt` (Android only: an Apple Watch starts a night through an App Intent, which other apps can't run) |
| Store screenshots | `LullUITests/StoreScreenshots.swift` | `android/app/src/androidTest/.../StoreScreenshots.kt` |
| Store listing | `fastlane/metadata/en-US/` | `fastlane/metadata/android/en-US/` |
| Release | `fastlane ios release`, `.github/workflows/app-store.yml` | `fastlane android release`, `.github/workflows/play-store.yml` |

Launch flags match too: `-ephemeral`, `-demoElapsedMinutes`, `-demoLabelConnected`, `-demoHistory`,
`-demoChoose`, `-demoFirstUnitAway`, `-noWakeTimes`, `-demoFirmwareUpdate` and `-logConnections` on iOS are the intent
extras of the same names on Android. `-noWakeTimes` starts from the starter schedules without their wake times, since one more than 16 hours
away can't be reached: tests that start a night use it so they don't pass or fail by the time of day. The Lull scheme passes it and
`-ephemeral` to the app the unit tests run in, so the VoiceOver tests start from the starter schedules each run and
leave the simulator's own copy of Lull alone. The watch apps take `-demo` (a pretend phone with the sample BedJets), `-demoHistory`,
`-demoElapsedMinutes` and `-ephemeral`, the Wear OS app as intent extras.

The watch never reaches the BedJet itself. Lull on the phone runs each errand the watch asks for: it connects,
waits to hear what the BedJet is doing, does the errand, hands the watch what the widgets show, and lets go of
the BedJet again unless Lull is on screen. The watch shows what the widgets show, played out by the clock. The
bytes of each command and reply are pinned by the same test on both sides.

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
xcodebuild test -project Lull.xcodeproj -scheme LullWatch -destination 'platform=watchOS Simulator,name=Apple Watch Series 11 (46mm)'
```

```bash
cd android && ./gradlew :bedjetkit:test :app:testDebugUnitTest :wear:testDebugUnitTest
cd android && ./gradlew :app:connectedDebugAndroidTest   # needs an emulator or phone
cd android && ./gradlew :wear:connectedDebugAndroidTest  # needs a Wear OS emulator or watch
```

To try a watch app with its phone, pair the simulators (`xcrun simctl pair <watch> <iPhone>`) and run Lull on
the iPhone, then the watch app. On its own, run the watch app with `-demo`. A Wear OS emulator pairs with a phone
emulator that has the Play Store, through Android Studio's Device Manager.

Both simulators run against the simulated BedJet, so no hardware is needed. For UI changes, compare
the two apps side by side: the screens should look like the same app.

The tests run debug builds. Release builds are shrunk by R8, which can remove classes a library only
loads by name, so after adding or updating an Android dependency, install and open a release build too:

```bash
cd android && ./gradlew :app:installRelease :wear:installRelease   # signed with the debug key unless the upload key is set
```

## Known differences

Platform conventions, not behaviour:

- Android uses Material components where iOS uses system ones (bottom navigation bar, bottom sheets,
  the time picker, the long-press menu), styled to match Lull's look.
- Android asks for the "Nearby devices" permission when Lull first needs Bluetooth; iOS asks for Bluetooth.
- Google Play skips a release whose version is already on the production tracks, the phone's and Wear OS's, but
  unlike the App Store lane it doesn't replace a release that is still in review.
- iOS plays the night timeline as a VoiceOver audio graph (`accessibilityChartDescriptor`). Android has no
  equivalent, so TalkBack reads the same phases from the timeline's description instead.
- The schedule editor's bedtime and wake-time pickers, and the Remote's "Turns off at", are iOS's compact time
  pickers, which VoiceOver calls only "Time Picker" whatever they're labelled, so it reads the row's label ("Usual
  bedtime", "Wake time", "Turns off at") just before each. Android's are buttons that read "Usual bedtime, 10:30 PM",
  "Wake time, 6:30 AM" and "Turns off at, 6:30 AM" in one, and open Material's time picker.
- An iPhone advertises one set of services at a time, so iOS sends every BedJet's hand-over request in one
  advertisement (`HandoffRequests`). Android runs one advertisement per BedJet side by side.
- Android phones have no Lock Screen or StandBy widgets, so the Tonight and Last Night widgets are Home
  Screen only. Quick Settings tiles stand in for the Control Center controls, and app shortcuts (long-press
  the icon: Start Night, Turn On, Turn Off and Remote) for Siri and App Shortcuts. Both open Lull and act there,
  as the iOS control does, setting the BedJet only once it's free; on iOS, Siri runs the actions without opening Lull.
- Android has no Shortcuts app, so Lull's Shortcuts actions are intents that automation apps such as Tasker send
  (`AutomationReceiver`), with the same actions, options, defaults and words. A `RESULT` broadcast says how each
  went, in place of what Shortcuts shows. Where iOS offers to continue in Lull, which asks another phone for the
  BedJet as the controls do, Android replies `needs_lull` and leaves it to the person to open Lull: only Lull's own
  buttons can act through its screen (`CommandActivity`), so no other app can take a BedJet from another phone.
  Android names BedJets and schedules by the name Lull shows
  rather than picking them from a list, so it has a few words of its own for a name it doesn't know.
- A background action on Android runs as an expedited WorkManager job, since a broadcast can't run long enough and
  Android 12 and later won't start a foreground service from the background. The jobs share one queue, so they run
  one at a time in the order sent, as Shortcuts runs actions, Get BedJet Status included. A job can start late when the
  phone saves battery, and an action more than 2 minutes late is skipped, though a status is still answered. iOS runs
  the action in Lull's process for as long as Shortcuts allows, which its Bluetooth background modes
  (`Lull/Info.plist`) make possible.
- A running BedJet shows as a Live Activity (Dynamic Island and Lock Screen) on iOS and as an ongoing
  notification promoted to a Live Update (status bar chip) on Android; where Android doesn't promote it
  (Android 16's first release, as seen on its emulator), it stays an ordinary ongoing notification. Android
  asks for the notification permission the first time a BedJet runs; iOS asks on the Lock Screen whether to
  allow Live Activities. iOS draws the night's curve and lights it up to the current time by itself; Android
  shows the phases as a segmented progress bar. Android can run briefly while Lull is closed, so while the
  phone is awake it moves that bar along every few minutes and on to each new phase, it wakes the phone to take
  out a BedJet that finished beside another, and it takes the Live Update down when the night or timer ends.
  Neither brings back one that was swiped away, or whose notifications were turned off, while Lull is closed. iOS can't run Lull while it's closed, so its Live Activity hides the phase once it may be out of
  date and keeps a finished night at 0:00 until Lull is opened; iOS also ends any Live Activity after 8 hours.
  With a BedJet on each side of the bed, both share one Live Activity and one Live Update, under one icon made of
  both their modes, since the Dynamic Island shows only one of an app's Live Activities; a third isn't shown. The
  Dynamic Island counts both down and draws a row for each; Android's notification has a line for each and counts
  down to whichever ends first, and draws a night's progress bar only for a single BedJet. Tapping a side's row
  in the Live Activity opens that BedJet; a notification can't link each line, so tapping the Live Update
  opens the first BedJet in it. ActivityKit takes at most 4 KB, so the Live Activity carries each night
  without its schedule's IDs; Android's Live Update is built on the phone and carries whole schedules. With
  two BedJets, iOS hides a night's phase once any phase may be out of date, but not at the other side's end,
  which only takes that side's countdown to 0:00; Android redraws instead, and takes the finished side out.
  In StandBy, on an iPhone charging on its side, the Live Activity fills the screen with a layout of its own;
  Android has no StandBy.
- The Tonight widget has a layout of its own in StandBy on iOS, read from across the room; Android has no StandBy.
- Each widget shows a BedJet picked by name (Both Sides two), and a Quick Remote's buttons are chosen too, by editing
  the widget: an App Intent on iOS, a configuration screen on Android. Android opens that screen as a widget is
  added when Lull knows more than one BedJet, and always for the Quick Remote, for its buttons, and sets the widget
  to the BedJet on screen. iOS adds a widget unset, following the BedJet on screen (Both Sides with it on the left)
  until it's edited: iOS works a default out once and reuses it, by when that BedJet may be forgotten. iOS types a Quick
  Remote button's time and temperature into fields; Android sets them with sliders, the time in steps of 5 minutes.
- On the iPhone, Tonight in StandBy and Both Sides light the night's curve up continuously, while Android's moves on each
  redraw, every few minutes. Android's countdowns are classic Chronometers, since Glance has no timer, and can read
  a second past zero before the widget is redrawn at the end.
- iOS widget UI tests place the widgets on the Home and Lock Screens through Springboard. Android's draw each
  widget at each size with Glance's test runner, since launchers differ too much to drive.
- The watch apps follow their platforms: the Apple Watch's settings turn with the Digital Crown, Wear OS's with
  the rotating side button or bezel. Apple Watch pages are left-aligned on a rectangular screen with the BedJet's
  name in the corner; Wear OS centres them, title and BedJet name at the top, for a round screen.
- Tonight on the watch face is a WidgetKit complication on the Apple Watch (circular, rectangular, inline and
  corner). Wear OS has a complication too (short text, long text and a ring), whose time left counts down by
  itself, and a tile in its place for the larger layout with Start Night. With more than one BedJet, the Apple
  Watch offers a Tonight complication for each by name, as well as one that follows the watch, so a face can show
  both sides of the bed. A Wear OS complication can't be set to a BedJet without a configuration screen of its
  own, so Wear OS's complications and the tile all show the BedJet the watch shows: the one picked on it, or else
  the one on screen on the phone, or the first Lull knows when none is.
- The watch reaches the phone over Watch Connectivity on iOS and the Wearable Data Layer's messages on Android,
  which go straight between the two. iOS wakes Lull in the background to run an errand (with the
  `bluetooth-central` background mode); Android runs it as expedited work on the app's one model.
- A phone running an errand in the background asks for the BedJet the same way, but an iPhone in the background
  only advertises its hand-over request where other iPhones look. An Android phone holding the BedJet doesn't
  see it, and the errand says another phone is using the BedJet.
- An iPhone in the background reaches only BedJets it has connected to before; a never-seen or moved one needs
  Lull opened. Android has no such limit.
- iOS can't start a Live Activity in the background, so a night started from the watch while Lull is off screen
  shows in a Live Activity once Lull is next opened and hears from the BedJet. Android posts its Live Update at once.

## Style

Match the code around you, in either language. Small readable functions, names that say what they do,
comments for the *why* (BedJet quirks, platform workarounds). No third-party dependencies beyond the
ones each app already has without discussing it first (the Wear OS app and the phone's link to it add Google
Play services' Wearable library and the AndroidX Wear libraries). No analytics, tracking, accounts or network
calls: the phone and watch talk over their own link, never through a server.
