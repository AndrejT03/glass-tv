#!/usr/bin/env python3
"""Presses buttons of a Google TV remote on an emulator (or a TV), as real hardware would.

`adb shell input keyevent` skips the accessibility key filters, so it can't reach Glass's Input
button service, and the emulator has no Input button at all. This registers a virtual remote over
uhid with Android's own `hid` tool (vendor 0957, product 0001: the Google TV remote, whose key
layout maps its consumer-page buttons) and presses the buttons through the normal input pipeline.

Usage: tools/remote_keys.py [-s <adb serial>] [--tcl] <button> [<button> ...]
Buttons: input up down left right ok back home settings quick, hold:<button> to keep it down for a second (Home
held opens the control center), or wait:<ms> between them. `quick` is Bookmark, which debug builds of
Glass take for the TCL remote's quick settings button (TCL's own key code 4514, which no remote here has).
With --tcl the virtual remote is the TCL's own (BT_RC833A_B5, vendor 0417, product 0304), on the TV:
its key layout there turns `quick` (Menu, scan code 139, as the real button sends it) into 4514, as
the real remote's button right of 0 does.
Example: tools/remote_keys.py input wait:1000 down down ok
"""
import json
import os
import subprocess
import sys
import tempfile

USAGES = {"input": 0x1BB, "up": 0x42, "down": 0x43, "left": 0x44, "right": 0x45, "ok": 0x41, "back": 0x224, "home": 0x223,
          "settings": 0x96, "quick": 0x22A}

# On the TCL's own remote the button right of 0 is the consumer page's Menu.
TCL_USAGES = {**USAGES, "quick": 0x40}

# One 16-bit consumer-page usage per report.
DESCRIPTOR = [0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00,
              0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00, 0xC0]


def script(buttons, tcl=False):
    usages = TCL_USAGES if tcl else USAGES
    remote = {"name": "TCL test remote", "vid": 0x0417, "pid": 0x0304} if tcl else \
        {"name": "Glass test remote", "vid": 0x0957, "pid": 0x0001}
    commands = [
        {"id": 1, "command": "register", **remote, "bus": "bluetooth", "descriptor": DESCRIPTOR},
        # Time for Android to set the new remote up.
        {"id": 1, "command": "delay", "duration": 1500},
    ]
    for button in buttons:
        if button.startswith("wait:"):
            commands.append({"id": 1, "command": "delay", "duration": int(button[5:])})
            continue
        held = button.startswith("hold:")
        usage = usages[button[5:] if held else button]
        commands += [
            {"id": 1, "command": "report", "report": [usage & 0xFF, usage >> 8]},
            {"id": 1, "command": "delay", "duration": 1000 if held else 70},
            {"id": 1, "command": "report", "report": [0, 0]},
            {"id": 1, "command": "delay", "duration": 250},
        ]
    commands.append({"id": 1, "command": "delay", "duration": 500})
    return "\n".join(json.dumps(c) for c in commands) + "\n"


def main():
    args = sys.argv[1:]
    adb = ["adb"]
    if args[:1] == ["-s"]:
        adb += ["-s", args[1]]
        args = args[2:]
    tcl = args[:1] == ["--tcl"]
    if tcl:
        args = args[1:]
    unknown = [b for b in args if b.removeprefix("hold:") not in USAGES and not b.startswith("wait:")]
    if not args or unknown:
        sys.exit(__doc__)
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
        f.write(script(args, tcl))
    try:
        subprocess.run(adb + ["push", f.name, "/data/local/tmp/remote_keys.json"], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(adb + ["shell", "hid", "/data/local/tmp/remote_keys.json"], check=True)
    finally:
        os.unlink(f.name)


if __name__ == "__main__":
    main()
