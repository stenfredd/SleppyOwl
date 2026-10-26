#!/usr/bin/env python3
"""Mouse and keyboard on an X display through XTest (python3-xlib), for the test stand (stand.sh in …).

  xinput.py click X Y [BUTTON]     move there and click (1 left, 3 right, 8 back)
  xinput.py dclick X Y             double click
  xinput.py move X Y
  xinput.py key NAME [NAME…]       keys by keysym name: Escape Return Tab BackSpace Left …; "alt+Left" for a chord
  xinput.py type TEXT              types Latin text, digits and punctuation
  xinput.py wait SECONDS
Several commands in one call are separated by ",": xinput.py click 10 10 , key Return
"""
import sys, time
from Xlib import X, XK, display
from Xlib.ext import xtest

d = display.Display()
MODS = {"alt": "Alt_L", "ctrl": "Control_L", "shift": "Shift_L"}


def code(name):
    keysym = XK.string_to_keysym(name)
    if keysym == 0 and len(name) == 1:
        keysym = ord(name)
    c = d.keysym_to_keycode(keysym)
    if not c:
        sys.exit(f"no key for {name!r}")
    return c


def press(c, down):
    xtest.fake_input(d, X.KeyPress if down else X.KeyRelease, c)
    d.sync()


def key(chord):
    *mods, name = chord.split("+")
    for m in mods: press(code(MODS[m]), True)
    c = code(name)
    press(c, True); time.sleep(0.03); press(c, False)
    for m in reversed(mods): press(code(MODS[m]), False)
    time.sleep(0.08)


def move(x, y):
    xtest.fake_input(d, X.MotionNotify, x=int(x), y=int(y)); d.sync(); time.sleep(0.05)


def click(x, y, button=1, times=1):
    move(x, y)
    for _ in range(times):
        xtest.fake_input(d, X.ButtonPress, int(button)); d.sync(); time.sleep(0.03)
        xtest.fake_input(d, X.ButtonRelease, int(button)); d.sync(); time.sleep(0.06)


SHIFTED = {c: True for c in '~!@#$%^&*()_+{}|:"<>?ABCDEFGHIJKLMNOPQRSTUVWXYZ'}
NAMES = {" ": "space", ".": "period", ":": "colon", "/": "slash", "-": "minus", "_": "underscore", "@": "at"}


def type_text(text):
    for ch in text:
        name = NAMES.get(ch, ch)
        c = code(name)
        shift = ch in SHIFTED
        if shift: press(code("Shift_L"), True)
        press(c, True); press(c, False)
        if shift: press(code("Shift_L"), False)
        time.sleep(0.02)


def run(cmd):
    op, args = cmd[0], cmd[1:]
    if op == "click": click(args[0], args[1], args[2] if len(args) > 2 else 1)
    elif op == "dclick": click(args[0], args[1], times=2)
    elif op == "move": move(*args)
    elif op == "key": [key(a) for a in args]
    elif op == "type": type_text(" ".join(args))
    elif op == "wait": time.sleep(float(args[0]))
    else: sys.exit(__doc__)


cmds, cur = [], []
for a in sys.argv[1:]:
    if a == ",": cmds.append(cur); cur = []
    else: cur.append(a)
if cur: cmds.append(cur)
if not cmds: sys.exit(__doc__)
for c in cmds: run(c)
