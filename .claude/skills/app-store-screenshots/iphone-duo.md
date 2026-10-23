# Designing iPhone Duo screenshots

Read this before planning or seeding any iPhone Duo deck. It covers what the
displays are, what real Duo captures look like, what the App Store does with the
sets, what the first published sets do, and the rules this skill follows.

Research date: 7 October 2026. That is two days after App Store Connect opened
Duo uploads (5–6 October) and before the phone reaches customers (23 October).
The evidence is thin, so re-check the sources listed at the end before relying on
anything marked "unknown".

## The two displays

| | Outer display (closed) | Inner display (open) |
|---|---|---|
| Size | 5.4" | 7.6" |
| Points | 466 × 678 | 669 × 951 |
| Screenshot | 1398 × 2034 or 2034 × 1398 | 2007 × 2853 or 2853 × 2007 |
| Height ÷ width | 1.45 (a 6.9" iPhone is 2.17) | 1.42, about A-series paper |
| Size class | compact width | regular in both directions, like an iPad |
| Camera | front camera in the top trailing corner | FaceTime camera under the screen, invisible unless in use |

- **Both displays have nearly the same shape.** The outer display is a short,
  wide phone, not a small iPhone. The inner display is twice as wide as the
  outer at the same height.
- **The natural open pose is landscape.** Opening a closed phone held upright
  gives the inner display in landscape, with the fold running down the middle
  (x = 50%). Turned, the inner display is portrait and the fold runs across the
  middle (y = 50%).
- **The fold does not show in captures.** It is a "division region" that apps
  lay out around only while the phone is partly folded. Flat captures have no
  crease, so never draw one.

## What real Duo captures look like

Verified first-hand on 7 October 2026. Apple's Food Truck sample app was run on
the iPhone Duo simulator (Xcode 27.1 RC, iOS 27.1) in every pose, and each
capture was placed under Apple's own bezels. Check every capture against these
points before designing around it. A capture that looks like a stretched iPhone
screen will look wrong in the set.

- **The app lays itself out around the camera; screenshots must not.**
  - On the outer display, iOS puts the status cluster on the trailing rail,
    *below* the camera:
    - the time;
    - Wi-Fi inside a green battery ring (there is no separate battery icon);
    - then the app's own buttons (back, actions).
  - With a real capture under Apple's bezel, the camera covers nothing.
  - The camera only covers content in a mock-up or hand-made "capture" that
    ignores the rail. Never draw an app UI that runs into the top trailing
    corner of the outer display.
- **Outer display, portrait:**
  - the camera is in the top-right corner;
  - the rail with the time, status and controls runs down the right edge;
  - the content fills the rest;
  - the corners are square on the hinge side (left) and strongly rounded on the
    right.
- **Outer display, landscape:**
  - It exists only if the app supports landscape on iPhone. The outer display
    honours the app's supported orientations, so a portrait-only app (like Food
    Truck as shipped) stays portrait when the closed phone is turned. In that
    case, skip the outer landscape deck.
  - When it exists, iPhone hides the status bar, as on any iPhone in landscape,
    and the app's rail moves beside the camera.
  - **Turn the phone left.** Apple's "Outer Closed Landscape" bezel has the
    camera at the top left. Turned right, the camera is at the bottom right, so
    that capture would put the app's top-left title under the bezel's camera.
- **Inner display, landscape (open):**
  - This is the natural pose. The status cluster sits top right on the trailing
    rail.
  - No camera shows: it is under the display.
  - `NavigationSplitView` apps show the sidebar and detail side by side.
  - The home indicator runs along the bottom.
- **Inner display, partly folded ("Book"):**
  - Layouts move their split to exactly the fold (50/50), and the flat capture
    is still 2853 × 2007.
  - Use it when the two halves of the screen tell the story (list and detail,
    input and result).
- **Inner display, portrait:**
  - Bars return to a band across the top: sidebar button top left, status top
    right.
  - The sidebar collapses, and content reflows into two columns.
- **Inner layouts split at the fold.** Common shapes are:
  - a two-pane list and detail;
  - a sidebar with content;
  - a grid with an even number of columns, so nothing interactive sits on the
    fold.

  Apple's examples are Reminders in two 50/50 columns, Podcasts with the player
  beside its transcript, and Fitness keeping a wider gutter at the hinge.
- **Captures come from Xcode 27.1's simulator** at exactly the App Store sizes:
  outer 1398 × 2034 and inner 2007 × 2853, plus their landscape forms.
  - Raw captures are RGBA. The editor's export flattens them to RGB.
  - Never resize an iPhone capture to a Duo size: the editor letterboxes a
    mismatched capture and warns about it.

## Capturing on the iPhone Duo simulator

`scripts/capture-iphone-duo.sh` does all of this.

- **What it needs:**
  - Xcode 27.1 or later, plus the iOS 27.1 simulator runtime
    (`xcodebuild -downloadPlatform iOS`).
  - Simulator.app is replaced by **Device Hub**
    (`Xcode.app/Contents/Applications/DeviceHub.app`).
  - Poses are toolbar buttons with no command-line equivalent: **Closed**,
    **Partially Open**, **Open**, **Rotate Right**. The script presses them
    through Accessibility.
- **Status bar.** Set Apple's marketing status bar first:
  `xcrun simctl status_bar <udid> override --time 9:41 --dataNetwork wifi --wifiMode active --wifiBars 3 --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100`.
- **Capturing each display.** Use
  `xcrun simctl io <udid> screenshot --display=primary` for the outer display
  and `--display=primary-1` for the inner one. Captures come out the right way
  up for the pose: 2853 × 2007 open, 2007 × 2853 open and turned, and
  2034 × 1398 closed and turned (when the app supports it).
- **Showing a screen.** Give the app a debug-only launch argument that opens a
  given screen (e.g. `-CapturePanel orders`), so every capture is reproducible.
- **Where captures go.** The script writes them into the editor's layout:
  - `duo-outer/{portrait|landscape}/<locale>/NN.png`
  - `duo-inner/{portrait|landscape}/<locale>/NN.png`
  - Book-pose captures are written as `NN-book.png`.

## What the App Store does with the sets

- **Optional now, required from April 2027.** From then, every submission needs
  Duo screenshots. Until a Duo set exists, the store shows scaled 6.9" iPhone
  screenshots, which leave visible margins on the wider displays.
- **One to 10 screenshots per set**, PNG or JPEG, with no alpha channel. Portrait
  and landscape sizes are both accepted for each display.
- **Unknown: which set the store shows on which display, and in what
  orientation.** Apple hasn't documented how a product page renders on either
  display. App Store Connect has a preview tool for Duo product pages, so tell
  the user to check their sets there before submitting.
- **Badge and featuring.** Apps built with Xcode 27.1 get an "optimized for
  iPhone Duo" badge on the product page. A featuring nomination can say that the
  app supports every pose.
- **Apple's guidance is short.** "Share app previews and screenshots that
  highlight your app across orientations." App Review still expects more than
  half the screenshots in a set to show the app in use.

## What the first published sets do

No live product page showed Duo screenshots on the date above. The only
published Duo sets came from one developer's write-up for Kiradex, a
card-collecting app (blakecrosley.com, "Prepare Your App for iPhone Duo"). Its
iPhone, outer and inner sets were all built by one script from the same
simulator tour. What it shows:

- **Separate sets, one idea each, with copy written for each display.** The outer
  set (five frames) reuses the iPhone headlines. The inner set (five frames,
  landscape) has new lines that only make sense when the phone is open: "Open it.
  The binder fills the wall." and "Pick a card. The screen splits."
- **Inner landscape: one-line headline across the top, device below,** wide
  enough that the capture reads at store size. It bleeds off the bottom edge.
- **Outer: two short lines above a centred phone** that bleeds off the bottom,
  like an iPhone set on a shorter canvas.
- **One frame with no device in each set:** a single piece of content full-bleed
  on black, with its own headline.
- **Apple's bezels, straight on, unmodified,** with the captures dropped into the
  cutouts at exact size.

ASO write-ups add a few more rules, unproven but consistent with this:

- Use the inner width to show cause and effect in one frame, with the action in
  one pane and the result in the other.
- Keep copy left or top and the proof (the UI) right or below.
- The outer set is a glance: large type and no fine detail.

## Rules for this skill

### Plan the decks

1. **Lead with the inner display in landscape** when the app has a real two-pane
   or wide layout. That is where the Duo set earns its place: "If the screenshot
   could have come from a normal iPhone, the Duo slot is wasted."
2. **Add the outer portrait deck next.** It can reuse the iPhone story and
   headlines, recomposed for the shorter canvas.
3. **Make inner portrait and outer landscape decks only if the app has a real
   layout for them** and captures to match. A turned phone layout is not a
   reason for its own deck. An app that is portrait-only on iPhone has no outer
   landscape at all.
4. Aim for **4–6 screenshots per Duo deck**. The first one or two do the selling.

### Compose each display

- **Inner landscape (`duo-inner-landscape`):**
  - Open on `hero`: a one-line headline across the top, with the device large and
    allowed to bleed off the bottom.
  - Keep headlines to about 30 characters, or the line wraps. Use `\n` only when
    two lines are intended.
  - Pick captures that show two panes working together: list beside detail,
    sidebar beside canvas, input beside result.
- **Outer portrait (`duo-outer`):**
  - The canvas is only 1.45 × as tall as it is wide.
  - Use two short headline lines at most, then the phone.
  - Keep to one idea per screen, large type and no fine detail.
- **Folded + open (`two-devices` on any Duo deck):**
  - The second device is the same phone in its other state, drawn in that
    display's frame.
  - On the inner-landscape and outer-portrait decks, the pair is drawn to
    physical scale: the closed phone is as tall as the open one and half as wide.
  - Use it once per deck, for the moment that carries across states: "Close it
    for today. Open it for the week."
  - Its second capture must come from the other display (outer portrait for inner
    landscape, inner portrait for outer landscape). The editor never reuses the
    front capture there. A missing capture exports as an empty device, and the
    export warns.
- **`split-landscape`** works on both landscape decks. Give it a short caption
  column, not a full sentence.
- **`no-device`** works once per deck as a full-bleed brand moment.

### Copy

- Name what opening the phone gives the user, not the hardware: "Today and the
  week, side by side." Avoid "Now on iPhone Duo!".
- Use the same verbs across states, so the sets read as one story: "Close it
  for… / Open it for…".
- Localise every deck. Inner landscape lines are longer in German and shorter in
  Japanese, so check that each still fits on one line.

### Never

- **Never use tilted, perspective, 3D or half-folded device mock-ups.** Apple's
  marketing guidelines forbid altering Apple product images or simulating
  products in 3D, and ask for straight-on shots. The Duo frames therefore stay
  straight. Don't add device rotation to Duo slides.
- **Never draw a crease or fold line over a capture.**
- **Never use iPhone captures in a Duo deck,** or captures from one Duo display on
  the other.
- **Never crop the trailing rail off a capture.** It is where the app's controls
  live.
- **Never use Apple's bezels outside Apple-platform mock-ups.** The template
  bundles them (see SKILL.md Step 5). Apple licenses them only for mock-ups of
  apps for Apple platforms, so never use them for Android.

### QA before export

- Every Duo capture is the exact size of its deck: no letterbox warning in the
  inspector or the export toast.
- Each "Folded + open" slide has a capture from the other display.
- Zoom into the outer display's camera corner on every outer slide. Apple's
  camera must sit over the app's empty rail, not over a title or control. If it
  covers content, the capture is not a real one, or it was taken with the phone
  turned the wrong way.
- Inner landscape headlines sit on one line, in every locale.
- At least one inner screenshot shows something a 6.9" iPhone can't: two panes,
  a wider grid or a sidebar.
- Tell the user to check the sets in App Store Connect's Duo preview tool before
  submitting.

## Sources

- Apple, Prepare and submit your apps for iPhone Duo (developer news, October 2026)
- Apple, Three steps to make your app shine on iPhone Duo: developer.apple.com/iphone-duo/prepare/
- Apple, Screenshot specifications: developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications
- Apple, App Store asset best practices: developer.apple.com/app-store/asset-best-practices/
- Apple Tech Talk, Strike a pose with adaptive layouts on iPhone Duo: developer.apple.com/videos/play/tech-talks/111463/
- Apple Newsroom, Apple unveils iPhone Duo (September 2026)
- Blake Crosley: "Prepare Your App for iPhone Duo: A Worked Example", "iPhone Duo, Day Two", "iPhone Duo for Developers: The 1.42 Problem" (blakecrosley.com)
- Appvertiser, "Your App Store Screenshots Won't Work on iPhone Duo"; appdesigns.click, "iPhone Duo App Store Screenshot Sizes"
- Mac Observer, "iPhone Duo's Design Rules: The Outer Display, the Hinge and Five Named Poses"
