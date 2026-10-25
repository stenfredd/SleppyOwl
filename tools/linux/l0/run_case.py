#!/usr/bin/env python3
"""L0: один прогон файла в mpv с замерами через IPC.

run_case.py <mpv: 037|041> <vo: gpu|gpu-next> <item_id> <name> [--sid N] [--aid N] [--sub-url URL]
           [--start SEC] [--play SEC] [--seek SEC] [--extra "--opt=val ..."]
Печатает одну строку JSON с результатами в stdout.
"""
import argparse, json, os, shlex, socket, subprocess, sys, threading, time

OUT = os.environ.get("L0_OUT") or os.path.join(os.environ.get("XDG_RUNTIME_DIR", "/tmp"), "l0", "out")
# mpv 0.37 из репозитория Mint (или распакованный из .deb без установки: apt download mpv; dpkg -x)
M037 = os.environ.get("MPV037", "mpv")
SERVER = "http://192.168.1.10:8096"
# Токен-заглушка: сервер отдаёт static-поток и без токена; проверяем, что заголовок доходит и не ломает запрос.
HEADER = 'Authorization: MediaBrowser Token="0123456789abcdef0123456789abcdef"'

p = argparse.ArgumentParser()
p.add_argument("ver"); p.add_argument("vo"); p.add_argument("item"); p.add_argument("name")
p.add_argument("--sid"); p.add_argument("--aid"); p.add_argument("--sub-url")
p.add_argument("--start", type=float); p.add_argument("--play", type=float, default=20)
p.add_argument("--seek", type=float, default=300); p.add_argument("--extra", default="")
a = p.parse_args()

os.makedirs(OUT, exist_ok=True)
tag = f"{a.name}_{a.ver}_{a.vo}"
RUN = os.path.join(os.environ.get("XDG_RUNTIME_DIR", "/run/user/1000"), "l0")
os.makedirs(RUN, exist_ok=True)
sock = os.path.join(RUN, f"{a.ver}{a.vo}.sock")  # путь сокета ≤ 108 символов
log = os.path.join(OUT, f"{tag}.log")
shot = os.path.join(OUT, f"{tag}.png")
for f in (sock, log, shot):
    if os.path.exists(f): os.unlink(f)

url = f"{SERVER}/Videos/{a.item}/stream?static=true&mediaSourceId={a.item}"
opts = [
    "--config=no", "--idle=no", "--force-window=yes", "--keep-open=yes",
    "--screen=0", "--geometry=640x360-24-24", f"--title=L0 {tag}",
    f"--vo={a.vo}", "--gpu-api=opengl", "--gpu-context=x11egl", "--hwdec=auto-safe",
    "--mute=yes", "--ao=pipewire,pulse,alsa", "--audio-channels=auto-safe",
    "--hr-seek=yes", "--cache=yes", "--demuxer-max-bytes=150MiB", "--demuxer-max-back-bytes=50MiB",
    "--osc=no", "--input-default-bindings=no", "--ytdl=no", "--tone-mapping=auto",
    f"--http-header-fields={HEADER}",
    f"--input-ipc-server={sock}", f"--log-file={log}",
    "--screenshot-format=png", "--screenshot-png-compression=1",
]
if a.vo == "gpu-next":
    opts = [o for o in opts if not o.startswith("--gpu-api") and not o.startswith("--gpu-context")]
if a.start is not None: opts.append(f"--start={a.start}")
else: opts.append("--start=40%")
if a.sid: opts.append(f"--sid={a.sid}")
if a.aid: opts.append(f"--aid={a.aid}")
if a.sub_url: opts += [f"--sub-files-append={a.sub_url}", "--sid=auto"]
opts += shlex.split(a.extra)

if a.ver == "037":
    cmd = [M037] + opts + [url]
else:
    cmd = ["flatpak", "run", f"--filesystem={OUT}", "--filesystem=xdg-run/l0", "io.mpv.Mpv"] + opts + [url]

t_launch = time.time()
proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def find_pid():
    # Хост-PID процесса mpv (во Flatpak он не совпадает с pid из IPC)
    for _ in range(100):
        out = subprocess.run(["pgrep", "-f", sock], capture_output=True, text=True).stdout.split()
        for pid in out:
            try:
                if open(f"/proc/{pid}/comm").read().strip() in ("mpv", "mpv-bin"): return int(pid)
            except OSError: pass
        time.sleep(0.05)
    return None

class Ipc:
    def __init__(self, path):
        for _ in range(200):
            try:
                self.s = socket.socket(socket.AF_UNIX); self.s.connect(path); break
            except OSError: time.sleep(0.05)
        else: raise RuntimeError("no ipc")
        self.buf = b""; self.rid = 0; self.lock = threading.Lock()
    def cmd(self, *args):
        with self.lock:
            self.rid += 1; rid = self.rid
            self.s.sendall(json.dumps({"command": list(args), "request_id": rid}).encode() + b"\n")
            while True:
                while b"\n" not in self.buf:
                    d = self.s.recv(65536)
                    if not d: raise RuntimeError("ipc closed")
                    self.buf += d
                line, self.buf = self.buf.split(b"\n", 1)
                m = json.loads(line)
                if m.get("request_id") == rid: return m.get("data") if m.get("error") == "success" else None
    def get(self, prop): return self.cmd("get_property", prop)

def proc_stats(pid):
    try:
        st = open(f"/proc/{pid}/stat").read().rsplit(")", 1)[1].split()
        cpu = (int(st[11]) + int(st[12])) / os.sysconf("SC_CLK_TCK")
    except OSError: cpu = None
    try:
        io = dict(l.split(": ") for l in open(f"/proc/{pid}/io").read().splitlines())
        rchar = int(io["rchar"])
    except (OSError, KeyError): rchar = None
    return cpu, rchar

def gpu_sampler(stop, samples):
    q = subprocess.Popen(["nvidia-smi", "--query-gpu=utilization.gpu,utilization.decoder",
                          "--format=csv,noheader,nounits", "-lms", "250"],
                         stdout=subprocess.PIPE, text=True)
    while not stop.is_set():
        line = q.stdout.readline()
        if not line: break
        try: samples.append([int(x) for x in line.split(",")])
        except ValueError: pass
    q.terminate()

res = {"name": a.name, "ver": a.ver, "vo": a.vo, "item": a.item}
try:
    ipc = Ipc(sock)
    pid = find_pid()
    # Первый кадр: VO получил параметры и время пошло
    first = None
    for _ in range(600):
        if ipc.get("video-out-params") and ipc.get("time-pos") is not None:
            first = time.time() - t_launch; break
        if proc.poll() is not None: break
        time.sleep(0.05)
    res["first_frame_s"] = round(first, 2) if first else None

    def counters():
        return {k: ipc.get(k) for k in ("frame-drop-count", "decoder-frame-drop-count",
                                        "vo-delayed-frame-count", "mistimed-frame-count")}
    def window(sec, label):
        global pid
        time.sleep(1.5)
        if pid is None: pid = find_pid()  # Flatpak стартует дольше, чем ждёт find_pid
        c0 = counters(); cpu0, r0 = proc_stats(pid); t0 = time.time(); pos0 = ipc.get("time-pos") or 0
        stop = threading.Event(); gs = []
        th = threading.Thread(target=gpu_sampler, args=(stop, gs)); th.start()
        av, pauses, was_paused, speeds = [], 0, False, []
        while time.time() - t0 < sec:
            cs = ipc.get("cache-speed")
            if cs is not None: speeds.append(cs)
            v = ipc.get("avsync")
            if v is not None: av.append(abs(v))
            pc = bool(ipc.get("paused-for-cache"))
            if pc and not was_paused: pauses += 1
            was_paused = pc
            time.sleep(0.2)
        stop.set(); th.join()
        c1 = counters(); cpu1, r1 = proc_stats(pid); dt = time.time() - t0; pos1 = ipc.get("time-pos") or 0
        w = {k.replace("-count", ""): (c1[k] - c0[k]) if c0[k] is not None and c1[k] is not None else None for k in c0}
        w["played_s"] = round(pos1 - pos0, 1); w["wall_s"] = round(dt, 1)
        w["cpu_pct"] = round(100 * (cpu1 - cpu0) / dt) if cpu0 is not None and cpu1 is not None else None
        w["net_mbit"] = round(sum(speeds) * 8 / len(speeds) / 1e6, 1) if speeds else None
        w["avsync_max_ms"] = round(1000 * max(av), 1) if av else None
        w["cache_pauses"] = pauses
        if gs:
            w["gpu_pct"] = round(sum(x[0] for x in gs) / len(gs)); w["nvdec_pct"] = round(sum(x[1] for x in gs) / len(gs))
        res[label] = w

    window(a.play, "play")
    info = {}
    for k in ("hwdec-current", "video-codec", "video-format", "container-fps", "estimated-vf-fps", "display-fps",
              "audio-codec-name", "current-ao", "seekable", "duration", "file-format", "vf"):
        info[k] = ipc.get(k)
    vp = ipc.get("video-params") or {}
    info["video-params"] = {k: vp.get(k) for k in ("pixelformat", "w", "h", "colormatrix", "primaries", "gamma", "sig-peak", "light")}
    vop = ipc.get("video-out-params") or {}
    info["video-out-params"] = {k: vop.get(k) for k in ("pixelformat", "gamma", "primaries", "sig-peak")}
    ap = ipc.get("audio-params") or {}
    info["audio-params"] = {k: ap.get(k) for k in ("format", "samplerate", "channel-count", "hr-channels")}
    tl = ipc.get("track-list") or []
    info["tracks"] = [f"{t['type'][0]}{t['id']}{'*' if t.get('selected') else ''}:{t.get('codec')}{'(ext)' if t.get('external') else ''}" for t in tl]
    res["info"] = info
    ipc.cmd("screenshot-to-file", shot, "window")

    # Перемотка вперёд (точная, как в приложении)
    pos = ipc.get("time-pos") or 0
    t0 = time.time()
    ipc.cmd("seek", str(a.seek), "relative+exact")
    seek_done = None
    time.sleep(0.1)
    for _ in range(600):
        tp = ipc.get("time-pos")
        if not ipc.get("seeking") and not ipc.get("paused-for-cache") and tp is not None and tp > pos + a.seek - 1:
            # дождаться, что время пошло
            t1 = ipc.get("time-pos"); time.sleep(0.25); t2 = ipc.get("time-pos")
            if t2 and t1 and t2 > t1: seek_done = time.time() - t0 - 0.25; break
        time.sleep(0.05)
    res["seek_s"] = round(seek_done, 2) if seek_done else None
    window(10, "after_seek")
    ipc.cmd("quit")
except Exception as e:
    res["error"] = repr(e)
try: proc.wait(timeout=10)
except subprocess.TimeoutExpired: proc.kill()

# Предупреждения и ошибки из лога
errs = []
try:
    for line in open(log, errors="replace"):
        parts = line.split(" ", 3)
        if "][e][" in line[:24] or "][w][" in line[:24]:
            errs.append(line.strip()[:200])
except OSError: pass
res["log_warn"] = errs[:15]; res["log_warn_n"] = len(errs)
print(json.dumps(res, ensure_ascii=False))
