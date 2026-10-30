# Automations

Lull's actions let a shortcut or an automation control a BedJet: preheat at 9:30 pm, start a schedule when you
plug in your phone at bedtime, turn the BedJet off when your alarm goes off. On iPhone they are Shortcuts actions.
On Android, apps such as Tasker, MacroDroid or Samsung Modes and Routines send them as intents.

Each action reaches the BedJet without opening Lull, so it works with the phone locked. It connects only if the
BedJet is free, does what it was asked, and lets go again, so a partner's phone can use the BedJet straight away.
When the BedJet can't be reached that way (another phone has it, Bluetooth is off, or it's out of range), the action
says "Lull couldn't reach your BedJet in the background." On iPhone, Shortcuts then offers to continue in Lull,
which asks another phone for the BedJet, as its controls do. If Start Night for tonight's schedule finds every slot
on the BedJet taken there, Lull asks which to reuse, as its own Start Night does. On Android, open Lull to do it
there: other apps can't act through Lull's screen, as described below.

While a BedJet 3 joins a Wi-Fi network, checks for a firmware update or restarts to install one, actions are refused
with "Your BedJet is busy with its Wi-Fi or a firmware update. Try again once it's done." Continuing in Lull wouldn't
help, since Lull can't reach the BedJet either until it's back. Lull expects it back within ten minutes of the check.
Get BedJet Status still answers.

## The actions

| iPhone (Shortcuts) | Android action (`com.rohankanjani.lull.` …) | What it does |
| --- | --- | --- |
| Start Night | `START_NIGHT` | Starts a schedule: tonight's unless one is named. A night already running is left as it is. |
| Turn On BedJet | `TURN_ON` | Turns on the air, like the Remote: a mode (Heat unless another is chosen), and optionally a temperature, fan and how long until it turns off. Choosing a mode ends a schedule that's running. |
| Turn Off BedJet | `TURN_OFF` | Ends the night if a schedule is running, otherwise turns the air off. |
| Set Temperature | `SET_TEMPERATURE` | Changes the temperature of the air that's on. |
| Set Fan | `SET_FAN` | Changes the fan of the air that's on. |
| Set Run Time | `SET_RUN_TIME` | Changes when the air turns off. A running schedule sets its own, so this is refused then. |
| Light Ring | `SET_LIGHT_RING` | Turns the light ring on or off. |
| Mute Beeps | `MUTE_BEEPS` | Mutes or unmutes the beeps. |
| Get BedJet Status | `GET_STATUS` | Says what Lull last saw the BedJet doing, without connecting to it. |

Every action takes a BedJet. Without one, it uses the BedJet Lull shows. Values are kept to what the BedJet allows,
as the Remote keeps them: temperatures to the mode's range, the fan to 5–100% in steps of 5, and the run time to the
longest the BedJet allows.

Get BedJet Status hands the next action what it found: whether a night is running, the schedule, when the night ends,
the mode, temperature (in the unit Lull shows, with that unit, °F or °C), fan, and when Lull last heard from the BedJet. A shortcut can use it
to skip a preheat when a night has already started.

## iPhone

Find the actions under Lull in the Shortcuts app, or say "Start my night in Lull", "Turn on my BedJet with Lull",
"Turn off my BedJet with Lull" or "Is my night running in Lull".

For a preheat at a set time: in Shortcuts, open Automation, add a Time of Day automation, choose **Run Immediately**,
and add **Turn On BedJet** with Mode Heat and a temperature.

Each action's result is what it did, such as "Set the fan on My side to 70%.", for the next action to use; add **Show
Result** after it to see it. Lull doesn't show it as a message, since Shortcuts would wait for someone to tap Done before
running the next action, which nobody does in an automation. What goes wrong is still shown, and stops the shortcut.

## Android

Send the action as a broadcast to package `com.rohankanjani.lull` (Android only delivers it when the package is set),
with these extras. Each is optional unless noted, and can be sent as text, a number or a true/false value.

| Extra | For | Value |
| --- | --- | --- |
| `bedjet` | every action | The BedJet's name as Lull shows it, such as `My side`. |
| `schedule` | `START_NIGHT` | A schedule's name, which must pick out one schedule. |
| `mode` | `TURN_ON` | `cool`, `heat`, `gentle_heat`, `turbo` or `dry`. Heat if left out. |
| `temperature` | `TURN_ON`, `SET_TEMPERATURE` (required) | Degrees, in the unit Lull shows. |
| `fan` | `TURN_ON`, `SET_FAN` | Percent. 40 for `SET_FAN` if left out. |
| `minutes` | `TURN_ON`, `SET_RUN_TIME` | Minutes until the air turns off. 60 for `SET_RUN_TIME` if left out. |
| `on` | `SET_LIGHT_RING` | `true` to turn the light ring on. On if left out. |
| `muted` | `MUTE_BEEPS` | `true` to mute. Muted if left out. |
| `reply_package` | every action | Sends the reply to this app only. |

Lull replies with a `com.rohankanjani.lull.RESULT` broadcast carrying `action`, `ok`, `needs_lull` and `message`.
Get BedJet Status adds `bedjet`, `night_running`, `schedule`, `night_ends` and `last_seen` (milliseconds since 1970),
`mode` (as above, or `pause` or `off`), `temperature`, `temperature_unit` (`°F` or `°C`) and `fan`.

When `needs_lull` is true, Lull couldn't reach the BedJet from the background, usually because another phone has it
or it's out of reach: open Lull to do it there. Lull's screen doesn't take these actions from other apps, so an
automation never takes the BedJet from another phone. Long-pressing Lull's icon offers Start Night, Turn On and Turn
Off, which open Lull and, like its widgets, set the BedJet only once it's free.

Android runs these as expedited jobs, one at a time in the order they were sent, so a `TURN_ON` sent just before a
`SET_FAN` always comes first, and `GET_STATUS` sees what the actions sent before it did. A job can start a little late
when the phone is saving battery. An action that would run more than 2 minutes late is skipped, and the reply says so;
`GET_STATUS` is still answered, since a late status changes nothing.

### Tasker

Add a **Send Intent** action. Set Action to `com.rohankanjani.lull.TURN_ON`, Extra to `mode:heat` and Extra 2 to
`temperature:86`, Package to `com.rohankanjani.lull`, and Target to **Broadcast Receiver**. To act on the reply, add an
**Intent Received** profile for `com.rohankanjani.lull.RESULT`; the extras arrive as `%ok`, `%needs_lull`, `%message`
and so on.

### MacroDroid

Add a **Send Intent** action with Target **Broadcast**, Action `com.rohankanjani.lull.TURN_ON`, Package
`com.rohankanjani.lull`, and the extras above.

### From a computer

```bash
adb shell am broadcast -p com.rohankanjani.lull -a com.rohankanjani.lull.TURN_ON --es mode heat --ei temperature 86
```
