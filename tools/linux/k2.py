#!/usr/bin/env python3
"""Checkpoint К2 on the test stand (docs/desktop.md): the player window and the system integration, end to end.

  tools/linux/k2.py            runs it; PASS/FAIL per check, screenshots in $STAND/shots/k2-*.png

Everything happens on the stand's display :87 and its own D-Bus (stand.sh): the user's screen, focus, desktop and sound
are not touched (mpv plays into ao=null), the app runs niced. The player's state is read back from MPRIS (status, position,
volume, title), the windows from X11 (_NET_WM_STATE, geometry, frame), the screensaver from fake_screensaver.py.
The player settings start from scratch (first launch) and are put back afterwards, as is the test account's watch data.
Needs K2_EPISODES (three consecutive episodes) in local/dev.env, see tools/dev.env.example.
"""
import os, re, shutil, subprocess, sys, time
from Xlib import X, XK, display
from Xlib.ext import xtest
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import devenv

R = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
STAND = os.environ.get("STAND") or os.path.expanduser("~/.cache/sleepyowl-test")
STAND_SH = os.path.join(R, "tools/linux/stand.sh")
PREFS = os.path.join(STAND, "config/sleepyowl")
LOG = os.path.join(STAND, "state/sleepyowl/log/sleepyowl.log")
E2, E3, E4 = devenv.need("K2_EPISODES").split()
results = []


def sh(*args, env=None, check=True):
    return subprocess.run(args, env=env, check=check, capture_output=True, text=True).stdout


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  — {detail}" if detail else ""), flush=True)


def near(a, b, tol):
    return abs(a - b) <= tol


def same_place(p, place):
    # ±2 px: the window manager rounds the size to the video's proportions (874×491.6 → 491 or 492).
    return all(near(v, w, 2) for v, w in zip((p["x"], p["y"], p["w"], p["h"]), place))


# --- Stand ---

sh(STAND_SH, "up")
d = display.Display(":87")
root = d.screen().root
BUS = dict(os.environ, DBUS_SESSION_BUS_ADDRESS=open(os.path.join(STAND, "run/dbus.addr")).read().strip())


def atom(name):
    return d.intern_atom(name)


def prop(w, name, kind=X.AnyPropertyType):
    p = w.get_full_property(atom(name), kind)
    return p.value if p else None


def windows():
    """The app's windows in mapping order (the library first): name, states, client geometry on the screen, frame."""
    out = []
    for wid in prop(root, "_NET_CLIENT_LIST") or []:
        w = d.create_resource_object("window", wid)
        try:
            name = prop(w, "_NET_WM_NAME", atom("UTF8_STRING"))
            name = name.decode() if isinstance(name, bytes) else (w.get_wm_name() or "")
            states = [d.get_atom_name(a).replace("_NET_WM_STATE_", "") for a in (prop(w, "_NET_WM_STATE") or [])]
            g = w.get_geometry()
            t = w.translate_coords(root, 0, 0)
            motif = prop(w, "_MOTIF_WM_HINTS")
            out.append(dict(id=wid, name=name, states=states, x=-t.x, y=-t.y, w=g.width, h=g.height,
                            framed=bool(motif is None or (motif[0] & 2 == 0) or motif[2] != 0)))
        except Exception:
            pass
    return out


def player():
    ws = windows()
    return ws[1] if len(ws) > 1 else None


def library():
    ws = windows()
    return ws[0] if ws else None


def press(code, down):
    xtest.fake_input(d, X.KeyPress if down else X.KeyRelease, code)
    d.sync()


def keycode(name):
    return d.keysym_to_keycode(XK.string_to_keysym(name))


def key(chord, raw=None):
    *mods, name = chord.split("+")
    mc = [keycode({"ctrl": "Control_L", "shift": "Shift_L", "alt": "Alt_L"}[m]) for m in mods]
    for c in mc: press(c, True)
    c = raw or keycode(name)
    press(c, True); time.sleep(0.03); press(c, False)
    for c in reversed(mc): press(c, False)
    time.sleep(0.15)


def move(x, y):
    xtest.fake_input(d, X.MotionNotify, x=int(x), y=int(y)); d.sync(); time.sleep(0.05)


def click(x, y, button=1, times=1):
    move(x, y)
    for _ in range(times):
        xtest.fake_input(d, X.ButtonPress, button); d.sync(); time.sleep(0.03)
        xtest.fake_input(d, X.ButtonRelease, button); d.sync(); time.sleep(0.06)


def drag(x1, y1, x2, y2, steps=25):
    move(x1, y1)
    xtest.fake_input(d, X.ButtonPress, 1); d.sync(); time.sleep(0.1)
    for i in range(1, steps + 1):
        move(x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps)
    time.sleep(0.15)
    xtest.fake_input(d, X.ButtonRelease, 1); d.sync(); time.sleep(0.2)


def shot(name):
    path = os.path.join(STAND, "shots", f"k2-{name}.png")
    sh("import", "-display", ":87", "-window", "root", path)
    return path


def top_line(name, p):
    """The window's top line (the compact layout's title and ×) as grey pixels: compared on a paused frame."""
    return list(Image.open(shot(name)).convert("L").crop((p["x"], p["y"], p["x"] + p["w"], p["y"] + 36)).getdata())


def diff(a, b):
    return sum(abs(x - y) for x, y in zip(a, b)) / max(1, len(a))


# --- MPRIS and the screensaver ---

MPRIS = ["gdbus", "call", "--session", "--dest", "org.mpris.MediaPlayer2.sleepyowl", "--object-path", "/org/mpris/MediaPlayer2"]


def mpris_raw(prop_name):
    return subprocess.run(MPRIS + ["--method", "org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", prop_name],
                          env=BUS, capture_output=True, text=True).stdout


def mpris(prop_name):
    out = mpris_raw(prop_name)
    m = re.search(r"<(?:int64 )?'?([^'>]*)'?>", out)
    if prop_name == "Metadata":
        t = re.search(r"xesam:title': <'([^']*)'", out)
        return t.group(1) if t else None
    if not m:
        return None
    v = m.group(1)
    return float(v) if re.fullmatch(r"-?[\d.]+", v) else v


def mpris_call(method, *args):
    subprocess.run(MPRIS + ["--method", f"org.mpris.MediaPlayer2.Player.{method}", *args], env=BUS, capture_output=True)


def mpris_present():
    return "org.mpris.MediaPlayer2.sleepyowl" in sh("busctl", "--user", "list", "--no-pager", env=BUS, check=False)


def wait(cond, timeout, step=0.3):
    end = time.time() + timeout
    while time.time() < end:
        v = cond()
        if v:
            return v
        time.sleep(step)
    return cond()


def pos():
    return (mpris("Position") or 0) / 1e6


ss_log = os.path.join(STAND, "k2-screensaver.txt")


def ss_lines():
    return open(ss_log).read().splitlines()


def new_log():
    """This launch's log: the app starts a new file each time (the previous one becomes sleepyowl.1.log)."""
    return open(LOG, encoding="utf-8", errors="replace").read()


def run_logs():
    """Both launches of this run."""
    return "".join(open(f, encoding="utf-8", errors="replace").read() for f in (LOG.replace(".log", ".1.log"), LOG) if os.path.exists(f))


def start_app(item, start):
    env = dict(os.environ, DESKTOP_NOBUILD="1", SLEEPYOWL_MPV_OPTIONS="ao=null")
    subprocess.run(["nice", "-n", "15", STAND_SH, "app", "--play", item, "--start", str(start)], env=env, check=True, capture_output=True)
    ok = wait(lambda: mpris_present() and player() and (mpris("Metadata") or ""), 60, 0.5)
    time.sleep(2)
    return ok


# --- The run ---

backup = os.path.join(STAND, "k2-prefs-backup")
os.makedirs(backup, exist_ok=True)
sh(STAND_SH, "stop")
for f in ("desktop_player.preferences_pb", "desktop.preferences_pb"):
    if os.path.exists(os.path.join(PREFS, f)):
        shutil.move(os.path.join(PREFS, f), os.path.join(backup, f))
subprocess.run(["python3", os.path.join(R, "tools/linux/watchdata.py"), "snapshot"], check=True, capture_output=True)
fake = subprocess.Popen(["python3", "-u", os.path.join(R, "tools/linux/fake_screensaver.py")], env=BUS,
                        stdout=open(ss_log, "w"), stderr=subprocess.STDOUT)
sh("setxkbmap", "-display", ":87", "-layout", "us")
time.sleep(1)

try:
    print("== first open (§1.1, §1.2)", flush=True)
    check("player opens, MPRIS up", start_app(E2, 60))
    p = player()
    check("mini 640×360 in the bottom right corner, 24 px in", p and near(p["x"], 1256, 2) and near(p["y"], 696, 2) and p["w"] == 640 and p["h"] == 360, p)
    check("mini: on top, no frame, focused once", p and "ABOVE" in p["states"] and not p["framed"] and "FOCUSED" in p["states"], p and p["states"])
    check("MPRIS: playing, title", mpris("PlaybackStatus") == "Playing" and "Серия 2" in (mpris("Metadata") or ""), mpris("Metadata"))
    check("screensaver inhibited", wait(lambda: any(l.startswith("Inhibit") for l in ss_lines()), 5))

    print("== keys (§1.3)", flush=True)
    key("space"); time.sleep(1)
    check("Space pauses", mpris("PlaybackStatus") == "Paused")
    check("pause lifts the screensaver inhibition", wait(lambda: any(l.startswith("UnInhibit") for l in ss_lines()), 4))
    key("space"); time.sleep(1)
    check("Space plays again", mpris("PlaybackStatus") == "Playing")
    p0 = pos()
    key("Right"); key("Right"); key("Right"); time.sleep(2.5)
    p1 = pos()
    check("→ ×3: +30 s in one seek", 26 <= p1 - p0 <= 36, f"{p0:.1f} → {p1:.1f}")
    key("shift+Left"); time.sleep(2.5)
    p2 = pos()
    check("Shift+←: −60 s", -64 <= p2 - p1 <= -54, f"{p1:.1f} → {p2:.1f}")
    v0 = mpris("Volume")
    key("Down"); time.sleep(0.5)
    check("↓: volume −5 %", v0 is not None and near(mpris("Volume"), v0 - 0.05, 0.001), f"{v0} → {mpris('Volume')}")
    key("Up"); time.sleep(0.5)
    key("m"); time.sleep(0.5)
    check("M: mute", mpris("Volume") == 0.0)
    key("m"); time.sleep(0.5)
    check("M again: sound back", near(mpris("Volume") or 0, v0, 0.001))
    m_code = keycode("m")
    sh("setxkbmap", "-display", ":87", "-layout", "ru")
    time.sleep(0.5)
    key("m", raw=m_code); time.sleep(0.5)
    muted_ru = mpris("Volume") == 0.0
    key("m", raw=m_code); time.sleep(0.5)
    sh("setxkbmap", "-display", ":87", "-layout", "us")
    check("Russian layout: physical M (Ь) mutes and unmutes", muted_ru and near(mpris("Volume") or 0, v0, 0.001))
    before = len(re.findall(r"смена аудио|aid", new_log()))
    key("a"); time.sleep(1.5); key("a"); time.sleep(1.5)
    check("A ×2: audio track switched and back", len(re.findall(r"смена аудио|aid", new_log())) - before >= 2)

    print("== autohide (§1.3), on a paused frame", flush=True)
    p = player()
    key("space"); time.sleep(1.5)
    move(p["x"] + p["w"] // 2, p["y"] + p["h"] // 2); time.sleep(0.2); move(p["x"] + p["w"] // 2 + 5, p["y"] + p["h"] // 2 + 5)
    time.sleep(0.4)
    shown = top_line("osd-shown", p)
    time.sleep(3.0)
    rested = top_line("osd-rested", p)
    check("pointer resting 2 s in mini: controls hidden", diff(shown, rested) > 4, f"diff {diff(shown, rested):.1f}")
    move(p["x"] + 100, p["y"] + 100); time.sleep(0.4)
    again = top_line("osd-again", p)
    check("pointer moves: shown again", diff(shown, again) < 1.5, f"diff {diff(shown, again):.1f}")
    move(150, 60); time.sleep(0.5)
    left = top_line("osd-left", p)
    check("pointer leaves: hidden at once", diff(rested, left) < 1.5 and diff(shown, left) > 4, f"diff {diff(rested, left):.1f} / {diff(shown, left):.1f}")
    key("space"); time.sleep(1)
    check("playing again", mpris("PlaybackStatus") == "Playing")

    print("== drag and resize the mini window (§1.1)", flush=True)
    p = player()
    cx, cy = p["x"] + p["w"] // 2, p["y"] + p["h"] // 2
    drag(cx, cy, cx - 600, cy - 300); time.sleep(1)
    q = player()
    check("drag by the video moves it", near(q["x"], p["x"] - 600, 12) and near(q["y"], p["y"] - 300, 12), f"{p['x']},{p['y']} → {q['x']},{q['y']}")
    check("a drag is not a click: still playing", mpris("PlaybackStatus") == "Playing")
    drag(q["x"] + q["w"] - 3, q["y"] + q["h"] // 2, q["x"] + q["w"] + 237, q["y"] + q["h"] // 2); time.sleep(1.2)
    r = player()
    check("right edge resizes, 16:9 kept", near(r["w"], q["w"] + 240, 20) and near(r["h"], r["w"] * 9 / 16, 3), f"{q['w']}×{q['h']} → {r['w']}×{r['h']}")
    mini_place = (r["x"], r["y"], r["w"], r["h"])

    print("== modes (§1.1)", flush=True)
    key("t"); time.sleep(2.5)
    p = player()
    check("T: normal window 1280×720 in the middle, framed, not on top", p["w"] == 1280 and p["h"] == 720 and near(p["x"], 320, 2) and near(p["y"], 180, 40)
          and p["framed"] and "ABOVE" not in p["states"], p)
    key("f"); time.sleep(2.5)
    p = player()
    check("F: full screen", "FULLSCREEN" in p["states"] and p["w"] == 1920 and p["h"] == 1080, p["states"])
    key("Escape"); time.sleep(2.5)
    p = player()
    check("Esc: back to the normal window", "FULLSCREEN" not in p["states"] and p["w"] == 1280 and p["framed"], p)
    shot("window")
    key("t"); time.sleep(2.5)
    p = player()
    check("T: mini again at its own place and size", "ABOVE" in p["states"] and same_place(p, mini_place), f"{mini_place} → {(p['x'], p['y'], p['w'], p['h'])}")
    click(p["x"] + p["w"] // 2, p["y"] + p["h"] // 2, times=2); time.sleep(2.5)
    p = player()
    check("double click in mini: normal window, still playing", p["framed"] and "ABOVE" not in p["states"] and mpris("PlaybackStatus") == "Playing", p["states"])
    click(p["x"] + p["w"] // 2, p["y"] + p["h"] // 2, times=2); time.sleep(2.5)
    p = player()
    check("double click in the window: full screen", "FULLSCREEN" in p["states"])
    key("Escape"); time.sleep(2.5)
    key("t"); time.sleep(2.5)
    p = player()
    check("Esc, T: mini", "ABOVE" in p["states"] and not p["framed"])
    check("focus stayed with the player through the modes", "FOCUSED" in p["states"])

    print("== menu (§1.3)", flush=True)
    click(p["x"] + p["w"] // 2, p["y"] + p["h"] // 2, button=3); time.sleep(0.8)
    shot("menu")
    key("Escape"); time.sleep(0.5)

    print("== next episode, focus elsewhere (§1.2)", flush=True)
    lib = library()
    click(lib["x"] + 200, lib["y"] - 15); time.sleep(1)   # the library's title bar
    check("library has the focus", "FOCUSED" in library()["states"] and "FOCUSED" not in player()["states"])
    pid = player()["id"]
    mpris_call("Next")
    check("MPRIS Next: S1E3", wait(lambda: "Серия 3" in (mpris("Metadata") or ""), 15), mpris("Metadata"))
    time.sleep(2)
    p = player()
    check("same window, on top, focus not taken", p["id"] == pid and "ABOVE" in p["states"] and "FOCUSED" not in p["states"] and "FOCUSED" in library()["states"], p["states"])
    m = re.search(r"mpris:length': <int64 (\d+)>", mpris_raw("Metadata"))
    mpris_call("SetPosition", f"/ru/stenfredd/sleepyowl/track/{E3}", str(int(m.group(1)) - 8_000_000) if m else "0")
    check("end of S1E3 plays S1E4 by itself", wait(lambda: "Серия 4" in (mpris("Metadata") or ""), 40), mpris("Metadata"))
    time.sleep(2)
    p = player()
    check("…in the same window, focus still elsewhere", p["id"] == pid and "FOCUSED" not in p["states"], p["states"])
    mpris_call("Previous")
    check("MPRIS Previous: S1E3", wait(lambda: "Серия 3" in (mpris("Metadata") or ""), 15))
    time.sleep(3)
    p0 = pos(); mpris_call("Seek", "60000000"); time.sleep(2.5)
    check("MPRIS Seek +60 s", 55 <= pos() - p0 <= 66, f"{p0:.1f} → {pos():.1f}")
    mpris_call("Pause"); time.sleep(1)
    check("MPRIS Pause", mpris("PlaybackStatus") == "Paused")
    mpris_call("PlayPause"); time.sleep(1)
    check("MPRIS PlayPause", mpris("PlaybackStatus") == "Playing")

    print("== close and reopen (§1.2 п. 7, §1.1)", flush=True)
    p = player()
    move(p["x"] + p["w"] // 2, p["y"] + p["h"] // 2); time.sleep(0.2); move(p["x"] + p["w"] - 21, p["y"] + 21); time.sleep(0.5)
    shot("before-close")
    click(p["x"] + p["w"] - 21, p["y"] + 21); time.sleep(2)
    check("× closes the player, library stays", player() is None and library() is not None)
    check("…and the library has the focus", "FOCUSED" in library()["states"], library()["states"])
    calls = [l for l in ss_lines() if l.startswith(("Inhibit", "UnInhibit"))]
    check("MPRIS gone, screensaver allowed", not mpris_present() and calls and calls[-1].startswith("UnInhibit"), calls[-2:])
    sh(STAND_SH, "stop")
    check("reopens", start_app(E2, 90))
    p = player()
    check("…in mini at the last place and size", "ABOVE" in p["states"] and same_place(p, mini_place), f"{mini_place} → {(p['x'], p['y'], p['w'], p['h'])}")
    move(p["x"] + p["w"] // 2, p["y"] + p["h"] // 2); time.sleep(0.2); move(p["x"] + p["w"] - 21, p["y"] + 21); time.sleep(0.5)
    click(p["x"] + p["w"] - 21, p["y"] + 21); time.sleep(2)
    check("closed again", player() is None)
    errors = re.findall(r"^\S+ E .*|.*Exception.*", run_logs(), re.M)
    check("no errors in the log", not errors, errors[:3])
finally:
    sh(STAND_SH, "stop", check=False)
    sh("setxkbmap", "-display", ":87", "-layout", "us", check=False)
    fake.terminate()
    for f in os.listdir(backup):
        shutil.move(os.path.join(backup, f), os.path.join(PREFS, f))
    time.sleep(5)
    print(subprocess.run(["python3", os.path.join(R, "tools/linux/watchdata.py"), "restore"], capture_output=True, text=True).stdout.strip(), flush=True)

failed = [n for n, ok in results if not ok]
print(f"\n{len(results) - len(failed)}/{len(results)} passed" + (": failed " + "; ".join(failed) if failed else ""))
sys.exit(1 if failed else 0)
