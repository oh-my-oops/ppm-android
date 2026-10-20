#!/usr/bin/env python3
"""A real mouse for an emulator, which `adb shell input mouse` is not.

`input mouse motionevent` sends mouse-typed events with no button pressed, and Compose ignores a
mouse press that has no button (`awaitFirstDown`, `clickable`, the tab drag). This registers a USB
mouse through the system's `uinput` tool instead and plays a list of steps on it:

    python3 -I scripts/virtual-mouse.py emulator-5582 "move 1740 90" down "move 1500 1150" "shot /tmp/drag.png" up

Steps: `move X Y` (device pixels), `down` / `up` (left button), `rdown` / `rup` (right button),
`wait MS`, `shot PATH` (a screencap). The device lives as long as the script.

Android moves the pointer about 2 px per unit at this density, a little more when fast, so a move
with no button held steers by the position `dumpsys input` reports. That position is not reported
while a button is down, so a move then goes by the ratio measured at the start.
"""
import json
import re
import subprocess
import sys
import time

POINTER = re.compile(r"PointerEventDispatcherOverlay0.*?\n\s*DeviceId\(\d+\):\[(.*?)\]\s*$", re.M)
AT = re.compile(r"Pointer\(id=0, MOUSE\) at \(([-\d.]+), ([-\d.]+)\)")
EV_SYN, EV_KEY, EV_REL = 0, 1, 2
BTN_LEFT, BTN_RIGHT, BTN_MIDDLE = 0x110, 0x111, 0x112
REL_X, REL_Y, REL_WHEEL = 0, 1, 8


class Mouse:
    def __init__(self, serial):
        self.adb = ["adb", "-s", serial]
        self.uinput = subprocess.Popen(self.adb + ["shell", "uinput", "-"], stdin=subprocess.PIPE, text=True)
        self.send({
            "id": 1, "command": "register", "name": "PPM Test Mouse", "vid": 0x1234, "pid": 0x5678, "bus": "usb",
            "configuration": [
                {"type": 100, "data": [EV_KEY, EV_REL]},  # UI_SET_EVBIT
                {"type": 101, "data": [BTN_LEFT, BTN_RIGHT, BTN_MIDDLE]},  # UI_SET_KEYBIT
                {"type": 102, "data": [REL_X, REL_Y, REL_WHEEL]},  # UI_SET_RELBIT
            ],
        })
        time.sleep(1.5)
        self.inject(EV_REL, REL_X, 1, EV_REL, REL_Y, 1)  # hover, so that the position is reported
        time.sleep(0.3)
        self.held = False
        self.at = None
        self.ratio = self.measure()

    def send(self, command):
        self.uinput.stdin.write(json.dumps(command) + "\n")
        self.uinput.stdin.flush()

    def inject(self, *events):
        self.send({"id": 1, "command": "inject", "events": list(events) + [EV_SYN, 0, 0]})

    def position(self):
        # A touch in between (an `input tap`) stops the hover: a wiggle starts it again.
        for wiggle in (0, 1, -1, 1, -1):
            if wiggle:
                self.inject(EV_REL, REL_X, wiggle, EV_REL, REL_Y, wiggle)
                time.sleep(0.2)
            dump = subprocess.run(self.adb + ["shell", "dumpsys", "input"], capture_output=True, text=True).stdout
            found = [m for m in (AT.search(s.group(1)) for s in POINTER.finditer(dump)) if m]
            if found:
                return float(found[-1].group(1)), float(found[-1].group(2))
        raise SystemExit("no hovering mouse in dumpsys input")

    def nudge(self, ux, uy):
        """Moves by (ux, uy) units in steps of at most 8, slow enough to stay clear of acceleration."""
        steps = max(1, int(max(abs(ux), abs(uy)) / 8))
        for i in range(steps):
            dx = round(ux * (i + 1) / steps) - round(ux * i / steps)
            dy = round(uy * (i + 1) / steps) - round(uy * i / steps)
            self.inject(EV_REL, REL_X, dx, EV_REL, REL_Y, dy)
            time.sleep(0.016)
        time.sleep(0.15)

    def measure(self):
        self.move(1280, 800)
        x0, y0 = self.position()
        self.nudge(320, 160)
        x1, y1 = self.position()
        return (x1 - x0) / 320, (y1 - y0) / 160

    def move(self, x, y):
        if self.held:
            self.nudge(round((x - self.at[0]) / self.ratio[0]), round((y - self.at[1]) / self.ratio[1]))
            self.at = (x, y)
            return
        for _ in range(60):
            px, py = self.position()
            if abs(x - px) <= 1.5 and abs(y - py) <= 1.5:
                self.at = (px, py)
                return
            self.nudge(round((x - px) / 2), round((y - py) / 2))
        raise SystemExit(f"could not bring the pointer to {x},{y}")

    def button(self, code, down):
        if down:
            self.at = self.position()
        self.held = down
        self.inject(EV_KEY, code, 1 if down else 0)
        time.sleep(0.1)

    def shot(self, path):
        png = subprocess.run(self.adb + ["exec-out", "screencap", "-p"], capture_output=True, check=True).stdout
        with open(path, "wb") as f:
            f.write(png)

    def close(self):
        self.uinput.stdin.close()
        self.uinput.wait()


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    mouse = Mouse(sys.argv[1])
    try:
        for step in sys.argv[2:]:
            op, *args = step.split(maxsplit=1)
            if op == "move":
                x, y = args[0].split()
                mouse.move(float(x), float(y))
            elif op in ("down", "up"):
                mouse.button(BTN_LEFT, op == "down")
            elif op in ("rdown", "rup"):
                mouse.button(BTN_RIGHT, op == "rdown")
            elif op == "wait":
                time.sleep(int(args[0]) / 1000)
            elif op == "shot":
                mouse.shot(args[0])
            else:
                raise SystemExit(f"unknown step: {step}")
    finally:
        mouse.close()


if __name__ == "__main__":
    main()
