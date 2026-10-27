#!/usr/bin/env python3
"""L0: как обычный mpv ведёт себя плавающим окном в Cinnamon (эталон для L1/L8).

float_test.py <037|041>
Запускает mpv --ontop --no-border --geometry=640x360-24-24 и по IPC переключает режимы,
после каждого шага печатает геометрию окна, _NET_WM_STATE, рамку (_NET_FRAME_EXTENTS) и активное окно.
"""
import json, os, socket, subprocess, sys, time
from Xlib import X, display

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
import devenv

# mpv 0.37 из репозитория Mint (или распакованный из .deb без установки: apt download mpv; dpkg -x)
M037 = os.environ.get("MPV037", "mpv")
RUN = os.path.join(os.environ.get("XDG_RUNTIME_DIR", "/run/user/1000"), "l0")
os.makedirs(RUN, exist_ok=True)
ver = sys.argv[1]
sock = os.path.join(RUN, f"float{ver}.sock")
if os.path.exists(sock): os.unlink(sock)
item = devenv.need("L0_FLOAT_ITEM")
url = f"{devenv.need('JF_URL')}/Videos/{item}/stream?static=true&mediaSourceId={item}"
opts = ["--config=no", "--mute=yes", "--start=30%", "--hwdec=auto-safe", "--vo=gpu", "--gpu-api=opengl",
        "--ontop", "--no-border", "--geometry=640x360-24-24", "--screen=0", "--keep-open=yes",
        "--title=L0 float", f"--input-ipc-server={sock}"]
cmd = ([M037] if ver == "037" else ["flatpak", "run", "--filesystem=xdg-run/l0", "io.mpv.Mpv"]) + opts + [url]

d = display.Display(); root = d.screen().root
A = lambda n: d.intern_atom(n)
NET_ACTIVE, NET_STATE, FRAME, NAME = A("_NET_ACTIVE_WINDOW"), A("_NET_WM_STATE"), A("_NET_FRAME_EXTENTS"), A("_NET_WM_NAME")

def active():
    p = root.get_full_property(NET_ACTIVE, X.AnyPropertyType)
    return p.value[0] if p else None

def find_win():
    for _ in range(100):
        p = root.get_full_property(A("_NET_CLIENT_LIST"), X.AnyPropertyType)
        for wid in (p.value if p else []):
            w = d.create_resource_object("window", wid)
            n = w.get_full_property(NAME, 0)
            if n and b"L0 float" in (n.value if isinstance(n.value, bytes) else n.value.encode()): return w
        time.sleep(0.1)

def geom(w):
    g = w.get_geometry(); t = w.translate_coords(root, 0, 0)
    st = w.get_full_property(NET_STATE, X.AnyPropertyType)
    states = [d.get_atom_name(x).replace("_NET_WM_STATE_", "") for x in (st.value if st else [])]
    fe = w.get_full_property(FRAME, X.AnyPropertyType)
    return {"x": -t.x, "y": -t.y, "w": g.width, "h": g.height, "state": states,
            "frame": list(fe.value) if fe else None, "focused": active() == w.id}

class Ipc:
    def __init__(self):
        for _ in range(200):
            try: self.s = socket.socket(socket.AF_UNIX); self.s.connect(sock); break
            except OSError: time.sleep(0.05)
        self.f = self.s.makefile("rb")
    def cmd(self, *a):
        self.s.sendall(json.dumps({"command": list(a), "request_id": 1}).encode() + b"\n")
        while True:
            m = json.loads(self.f.readline())
            if m.get("request_id") == 1: return m

before = active()
proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
ipc = Ipc(); w = find_win(); time.sleep(1.5)
print("active before launch:", hex(before) if before else None)
steps = [("start (ontop, no border)", None),
         ("ontop=no", ("set", "ontop", "no")),
         ("border=yes", ("set", "border", "yes")),
         ("fullscreen=yes", ("set", "fullscreen", "yes")),
         ("fullscreen=no", ("set", "fullscreen", "no")),
         ("border=no + ontop=yes", ("set", "border", "no")),
         ("screen=1 portrait (geometry 640x360-24-24)", None),
         ("resize 960x540 via geometry", ("set", "geometry", "960x540-24-24"))]
for label, c in steps:
    if c: ipc.cmd(*c)
    if label.startswith("border=no"): ipc.cmd("set", "ontop", "yes")
    if label.startswith("screen=1"):
        ipc.cmd("set", "screen", "1"); ipc.cmd("set", "geometry", "640x360-24-24")
    time.sleep(1.2)
    print(f"{label:45s}", json.dumps(geom(w)))
ipc.cmd("quit"); proc.wait(timeout=10)
