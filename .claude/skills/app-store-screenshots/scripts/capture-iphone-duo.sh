#!/bin/zsh
# Captures an app on the iPhone Duo simulator in each pose, at the exact App
# Store sizes, into the editor's screenshot layout.
#
# Usage:
#   capture-iphone-duo.sh --bundle <bundle id> --app <path/to/App.app> --out <public/screenshots/apple> \
#     [--locale en] [--poses "closed open open-turned"] [--udid <simulator udid>] \
#     -- <screen name> [launch args...] [-- <screen name> [launch args...]]...
#
# Each "-- name args" pair is one screen: the app is relaunched with those
# launch arguments (e.g. a debug-only "-CapturePanel orders" switch) and every
# requested pose is captured. Screens are numbered in the order given.
#
# Poses and where they land:
#   closed         outer display, portrait  -> duo-outer/portrait/<locale>/NN.png    (1398 x 2034)
#   closed-turned  outer display, landscape -> duo-outer/landscape/<locale>/NN.png   (2034 x 1398)
#                  only if the app supports landscape on iPhone; otherwise iOS keeps
#                  the outer display in portrait and the capture is skipped.
#   open           inner display, landscape -> duo-inner/landscape/<locale>/NN.png   (2853 x 2007)
#   book           inner display, partly folded (layouts split at the fold)
#                                           -> duo-inner/landscape/<locale>/NN-book.png
#   open-turned    inner display, portrait  -> duo-inner/portrait/<locale>/NN.png    (2007 x 2853)
#
# Requirements: Xcode 27.1 or later with the iOS 27.1 simulator runtime, and
# Device Hub open (it replaced Simulator.app). Poses are set by pressing Device
# Hub's toolbar buttons through Accessibility, so the terminal running this
# needs Accessibility permission. The status bar is set to Apple's marketing
# standard (9:41, full Wi-Fi, cellular and battery) before capturing.
set -euo pipefail

bundle="" app="" out="" locale="en" poses="closed open open-turned" udid=""
while [[ $# -gt 0 && $1 != "--" ]]; do
  case $1 in
    --bundle) bundle=$2; shift 2 ;;
    --app) app=$2; shift 2 ;;
    --out) out=$2; shift 2 ;;
    --locale) locale=$2; shift 2 ;;
    --poses) poses=$2; shift 2 ;;
    --udid) udid=$2; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
[[ -n $bundle && -n $out ]] || { echo "--bundle and --out are required" >&2; exit 2; }

# Screens: "-- name args..." groups.
typeset -a names argsets
while [[ $# -gt 0 ]]; do
  shift # the "--"
  [[ $# -gt 0 ]] || break
  names+=("$1"); shift
  local_args=()
  while [[ $# -gt 0 && $1 != "--" ]]; do local_args+=("$1"); shift; done
  argsets+=("${(j: :)${(q)local_args[@]}}")
done
(( ${#names} > 0 )) || { names=(screen); argsets=(""); }

# Use an Xcode that knows the iPhone Duo.
if ! xcrun simctl list devicetypes | grep -q "iPhone-Duo"; then
  for x in /Applications/Xcode*.app; do
    if DEVELOPER_DIR=$x/Contents/Developer xcrun simctl list devicetypes 2>/dev/null | grep -q "iPhone-Duo"; then
      export DEVELOPER_DIR=$x/Contents/Developer; break
    fi
  done
fi
xcrun simctl list devicetypes | grep -q "iPhone-Duo" || { echo "No iPhone Duo simulator: install Xcode 27.1+ and its iOS 27.1 runtime" >&2; exit 1; }

if [[ -z $udid ]]; then
  udid=$(xcrun simctl list devices available | grep -m1 "iPhone Duo" | grep -oE "[0-9A-F-]{36}" || true)
  if [[ -z $udid ]]; then
    runtime=$(xcrun simctl list runtimes | grep -oE "com.apple.CoreSimulator.SimRuntime.iOS-[0-9-]+" | sort -V | tail -1)
    udid=$(xcrun simctl create "iPhone Duo" com.apple.CoreSimulator.SimDeviceType.iPhone-Duo "$runtime")
  fi
fi
xcrun simctl boot "$udid" 2>/dev/null || true
xcrun simctl bootstatus "$udid" -b > /dev/null
[[ -n $app ]] && xcrun simctl install "$udid" "$app"
xcrun simctl status_bar "$udid" override --time 9:41 --dataNetwork wifi --wifiMode active --wifiBars 3 \
  --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100

devicehub=$(ls -d ${DEVELOPER_DIR:-$(xcode-select -p)}/../Applications/DeviceHub.app 2>/dev/null || true)
[[ -n $devicehub ]] && open -g -a "$devicehub" && sleep 5

# Warm-up launch: the first cold start after install can take longer than the
# per-screen wait and would capture a blank screen.
xcrun simctl launch "$udid" "$bundle" > /dev/null && sleep 15
xcrun simctl terminate "$udid" "$bundle" 2> /dev/null || true

# Device Hub's toolbar buttons, by their help text (the tooltip).
press() {
  osascript - "$1" > /dev/null <<'APPLESCRIPT'
on run argv
  tell application "System Events" to tell process "DeviceHub"
    repeat with b in (buttons of group 3 of splitter group 1 of group 1 of splitter group 1 of group 1 of window 1)
      if (help of b as text) is (item 1 of argv) then
        click b
        return
      end if
    end repeat
    error "Device Hub has no \"" & (item 1 of argv) & "\" button. Select the iPhone Duo simulator in Device Hub first."
  end tell
end run
APPLESCRIPT
  sleep 3
}

capture() { # pose display folder suffix
  local pose=$1 display=$2 folder=$3 suffix=$4 i=0
  mkdir -p "$out/$folder/$locale"
  # The display just changed pose; let it settle before the first launch.
  sleep 5
  for name in $names; do
    i=$((i + 1))
    local file="$out/$folder/$locale/$(printf %02d $i)$suffix.png"
    # A launch straight after a pose change can paint nothing but the status
    # bar. Such a capture compresses to a fraction of a real one, so relaunch
    # and wait longer, up to twice.
    for attempt in 1 2 3; do
      xcrun simctl terminate "$udid" "$bundle" 2> /dev/null || true
      eval "xcrun simctl launch \"$udid\" \"$bundle\" ${argsets[$i]}" > /dev/null
      sleep $((4 + attempt * 4))
      xcrun simctl io "$udid" screenshot --display="$display" "$file" > /dev/null 2>&1
      (( $(stat -f %z "$file") > 150000 )) && break
      (( attempt == 3 )) && echo "warning: $file looks almost blank; check it"
    done
    local size=$(sips -g pixelWidth -g pixelHeight "$file" | awk '/pixel/ {print $2}' | paste -sd x -)
    if [[ $pose == closed-turned && $size == 1398x2034 ]]; then
      rm -f "$file"; echo "skip $name ($pose): the app keeps the outer display in portrait"; continue
    fi
    echo "$file  $size  ($name, $pose)"
  done
}

# Turned poses turn the phone LEFT (three right turns). That matches Apple's
# landscape bezels: the outer display's camera ends up top left, where the app
# keeps its layout clear. Turned right, the camera would sit bottom right and
# Apple's bezel would cover the app's top-left content.
turn_left() { press "Rotate Right"; press "Rotate Right"; press "Rotate Right"; }
turn_back() { press "Rotate Right"; }

for pose in ${=poses}; do
  case $pose in
    closed)        press Closed;            capture closed primary duo-outer/portrait "" ;;
    closed-turned) press Closed; turn_left; capture closed-turned primary duo-outer/landscape ""; turn_back ;;
    open)          press Open;              capture open primary-1 duo-inner/landscape "" ;;
    book)          press "Partially Open";  capture book primary-1 duo-inner/landscape -book ;;
    open-turned)   press Open; turn_left;   capture open-turned primary-1 duo-inner/portrait ""; turn_back ;;
    *) echo "unknown pose $pose" >&2; exit 2 ;;
  esac
done
press Closed
