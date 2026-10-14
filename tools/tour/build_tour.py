#!/usr/bin/env python3
"""
Builds the debug "tour" playlist: a small set of library files that together cover every distinct format feature on
the server (container, video codec/bit depth/resolution/HDR type/frame rate/interlacing, audio codec/channels,
subtitle codec) and the pairs that matter for engine choice (container x codec, codec x HDR, ...).

Each entry also fixes which audio and subtitle track to select, so subtitle/audio codecs are actually exercised.

Usage:
  python3 tools/tour/build_tour.py --server http://192.168.1.10:8096 --token <token> [--out app/src/debug/assets/tour.json]
  python3 tools/tour/build_tour.py --input library.json ...   # reuse a saved /Items response
Token from the TV: `source tools/tv/env.sh; tv_token`.
"""
import argparse
import json
import sys
import urllib.request
from collections import Counter

PAIR_KEYS = [("cont", "vcodec"), ("cont", "acodec"), ("vcodec", "range"), ("res", "range"), ("vcodec", "res"), ("cont", "sub")]


def fetch(server, token):
    def get(path):
        req = urllib.request.Request(server + path, headers={"Authorization": f'MediaBrowser Token="{token}"'})
        with urllib.request.urlopen(req, timeout=120) as r:
            return json.load(r)
    user = get("/Users/Me")["Id"]
    return get(f"/Users/{user}/Items?Recursive=true&IncludeItemTypes=Movie,Episode&Fields=MediaSources"
               "&EnableImages=false&EnableUserData=false&Limit=100000")


def res_class(w, h):
    if w >= 3000 or h >= 1700:
        return "4K"
    if w >= 1700 or h >= 900:
        return "1080p"
    if w >= 1100 or h >= 650:
        return "720p"
    return "SD"


def fps_class(f):
    if not f:
        return "?"
    for name, v in (("23.976", 23.976), ("24", 24), ("25", 25), ("29.97", 29.97), ("30", 30), ("50", 50), ("59.94", 59.94), ("60", 60)):
        if abs(f - v) < 0.02:
            return name
    return "other"


def video_features(v):
    codec = (v.get("Codec") or "?").lower()
    depth = v.get("BitDepth") or 8
    vc = f"{codec}-{depth}bit" if codec in ("h264", "hevc", "av1", "vp9") else codec
    return {
        "vcodec": vc,
        "res": res_class(v.get("Width") or 0, v.get("Height") or 0),
        "range": v.get("VideoRangeType") or v.get("VideoRange") or "SDR",
        "fps": fps_class(v.get("RealFrameRate") or v.get("AverageFrameRate")),
        "interlaced": "yes" if v.get("IsInterlaced") else "no",
    }


def audio_name(a):
    codec = (a.get("Codec") or "?").lower()
    profile = (a.get("Profile") or "").lower()
    if codec == "dts" and "ma" in profile:
        codec = "dts-hd-ma"
    elif codec == "dts" and "hra" in profile:
        codec = "dts-hd-hra"
    elif codec == "aac" and "he" in profile:
        codec = "he-aac"
    if a.get("AudioSpatialFormat") in ("DolbyAtmos",) or "atmos" in profile:
        codec += "-atmos"
    return codec


def channels(a):
    c = a.get("Channels") or 0
    return {1: "1.0", 2: "2.0", 6: "5.1", 8: "7.1"}.get(c, f"{c}ch")


def sub_name(s):
    if s is None:
        return "none"
    return (s.get("Codec") or "?").lower() + ("-ext" if s.get("IsExternal") else "")


def candidates(item):
    ms = (item.get("MediaSources") or [None])[0]
    if not ms or (item.get("RunTimeTicks") or 0) < 10 * 60 * 10**7:
        return []
    streams = ms.get("MediaStreams") or []
    videos = [s for s in streams if s["Type"] == "Video"]
    audios = [s for s in streams if s["Type"] == "Audio"]
    subs = [s for s in streams if s["Type"] == "Subtitle"]
    if not videos or not audios:
        return []
    base = video_features(videos[0])
    base["cont"] = (ms.get("Container") or "?").split(",")[0].lower()
    flags = []
    if any(s["Type"] == "EmbeddedImage" for s in streams):
        flags.append("cover-image")
    if len(subs) >= 10:
        flags.append("many-subs")
    if len(audios) >= 5:
        flags.append("many-audio")

    by_index = {s["Index"]: s for s in streams}
    default_audio = by_index.get(ms.get("DefaultAudioStreamIndex")) or audios[0]
    default_sub = by_index.get(ms.get("DefaultSubtitleStreamIndex"))
    audio_opts = {audio_name(default_audio) + channels(default_audio): default_audio}
    for a in audios:
        audio_opts.setdefault(audio_name(a) + channels(a), a)
    sub_opts = {sub_name(default_sub): default_sub, "none": None}
    for s in subs:
        sub_opts.setdefault(sub_name(s), s)

    combos = [(default_audio, s) for s in sub_opts.values()] + [(a, default_sub) for a in audio_opts.values()]
    out = []
    seen = set()
    for a, s in combos:
        key = (a["Index"], s["Index"] if s else None)
        if key in seen:
            continue
        seen.add(key)
        f = dict(base, acodec=audio_name(a), ach=channels(a), sub=sub_name(s))
        tokens = {f"{k}:{v}" for k, v in f.items()} | {f"flag:{x}" for x in flags}
        tokens |= {f"{k1}+{k2}:{f[k1]}+{f[k2]}" for k1, k2 in PAIR_KEYS}
        out.append((item, ms, a, s, f, flags, tokens))
    return out


def title(item):
    if item.get("Type") == "Episode":
        return f'{item.get("SeriesName", "")} — {item.get("Name", "")}'
    return item.get("Name", "")


def label(f, flags, a, s):
    video = f'{f["vcodec"]} {f["res"]} {f["range"]} {f["fps"]}' + (" interlaced" if f["interlaced"] == "yes" else "")
    audio = f'{f["acodec"]} {f["ach"]}'
    sub = "без субтитров" if s is None else f'субт. {f["sub"]}'
    return " · ".join([f["cont"].upper(), video, audio, sub] + flags)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server")
    ap.add_argument("--token")
    ap.add_argument("--input", help="saved /Items JSON instead of querying the server")
    ap.add_argument("--out", default="app/src/debug/assets/tour.json")
    args = ap.parse_args()
    data = json.load(open(args.input)) if args.input else fetch(args.server, args.token)

    cands = [c for item in data["Items"] for c in candidates(item)]
    universe = set().union(*(c[6] for c in cands))
    # How common each feature is: rarer features first in the tie-break, so odd files are represented by
    # themselves and not by whatever happens to also cover them.
    freq = Counter(t for c in cands for t in c[6])
    uncovered = set(universe)
    chosen = []
    while uncovered:
        best = max(cands, key=lambda c: (len(c[6] & uncovered), -min(freq[t] for t in c[6] & uncovered) if c[6] & uncovered else 0))
        gain = best[6] & uncovered
        if not gain:
            break
        chosen.append(best)
        uncovered -= gain

    chosen.sort(key=lambda c: (c[4]["cont"], c[4]["vcodec"], c[4]["res"], c[4]["range"]))
    entries = [{
        "id": item["Id"],
        "title": title(item),
        "audio": a["Index"],
        "subtitle": s["Index"] if s else None,
        "label": label(f, flags, a, s),
    } for item, ms, a, s, f, flags, _ in chosen]
    with open(args.out, "w") as fh:
        json.dump(entries, fh, ensure_ascii=False, indent=1)
    print(f"{len(cands)} candidates, {len(universe)} features → {len(entries)} files in {args.out}", file=sys.stderr)
    for i, e in enumerate(entries, 1):
        print(f'{i:3}. {e["title"][:40]:40} {e["label"]}', file=sys.stderr)


if __name__ == "__main__":
    main()
