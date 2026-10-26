#!/usr/bin/env python3
"""Watch data of the test account on the server, before and after a test run (LINUX_PLAN.md §3 «Как проверяем»).

  watchdata.py snapshot [FILE]   saves the user's watch data (positions, played, counts, dates, favourites)
  watchdata.py diff [FILE]       what the run changed, compared with the snapshot
  watchdata.py restore [FILE]    puts the changed items back as they were in the snapshot

FILE defaults to $STAND/watchdata.json. The user is the test account: its token is taken from the test stand's saved accounts
($STAND/config/sleepyowl/jellyfin_session.preferences_pb, STAND=~/.cache/sleepyowl-test) or from JF_TOKEN.
JF_USER picks another saved account by name. Server: JF_URL, default the LAN address.

Restore writes the saved UserData back (POST /UserItems/{id}/UserData). An item the run started from nothing (not in
the snapshot) is reset: played and its date removed (DELETE /UserPlayedItems), position and count back to 0.
The resume list follows LastPlayedDate, so its order comes back with the dates.
"""
import json, os, re, sys, urllib.request

STAND = os.environ.get("STAND") or os.path.expanduser("~/.cache/sleepyowl-test")
URL = os.environ.get("JF_URL", "http://192.168.1.10:8096")
USER = os.environ.get("JF_USER", "test")
FIELDS = ("PlaybackPositionTicks", "PlayCount", "Played", "LastPlayedDate", "IsFavorite")


def account():
    token = os.environ.get("JF_TOKEN")
    path = os.path.join(STAND, "config", "sleepyowl", "jellyfin_session.preferences_pb")
    raw = open(path, "rb").read().decode("utf-8", "replace") if os.path.exists(path) else ""
    m = re.search(r"\[\{.*\}\]", raw)
    for a in json.loads(m.group(0)) if m else []:
        if a["userName"] == USER:
            return token or a["token"], a["userId"]
    sys.exit(f"no saved account {USER!r} in {path}: log in on the stand first (or set JF_TOKEN and JF_USER_ID)")


def api(method, path, body=None):
    req = urllib.request.Request(URL + path, method=method, data=None if body is None else json.dumps(body).encode())
    req.add_header("Authorization", f'MediaBrowser Token="{TOKEN}"')
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=30) as r:
        data = r.read()
        return json.loads(data) if data else None


def current():
    """Every item with any watch data: resumable, played or favourite (episodes, movies, series)."""
    items = {}
    for flt in ("IsResumable", "IsPlayed", "IsFavorite"):
        res = api("GET", f"/Items?userId={USER_ID}&Recursive=true&Filters={flt}&Fields=UserData&EnableImages=false")
        for it in res["Items"]:
            ud = it.get("UserData", {})
            items[it["Id"]] = {"Name": it.get("SeriesName", "") + (" · " if it.get("SeriesName") else "") + it.get("Name", ""),
                               **{k: ud.get(k) for k in FIELDS}}
    return items


def user_data(item_id):
    ud = api("GET", f"/UserItems/{item_id}/UserData?userId={USER_ID}")
    return {k: ud.get(k) for k in FIELDS}


def changes(before, after):
    out = []
    for item_id in sorted(set(before) | set(after)):
        b, a = before.get(item_id), after.get(item_id)
        if b is None:
            a = {**a, **user_data(item_id)}
        if a is None:
            a = {"Name": b["Name"], **user_data(item_id)}
        if b is None or any(b[k] != a[k] for k in FIELDS):
            out.append((item_id, b, a))
    return out


def show(item_id, b, a):
    name = (b or a)["Name"]
    diff = ", ".join(f"{k}: {None if b is None else b[k]} → {a[k]}" for k in FIELDS if b is None or b[k] != a[k])
    print(f"  {name} ({item_id}): {diff}")


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in ("snapshot", "diff", "restore"):
        sys.exit(__doc__)
    path = sys.argv[2] if len(sys.argv) > 2 else os.path.join(STAND, "watchdata.json")
    if sys.argv[1] == "snapshot":
        items = current()
        json.dump(items, open(path, "w"), ensure_ascii=False, indent=1)
        print(f"{len(items)} items with watch data saved to {path}")
        return
    before = json.load(open(path))
    diff = changes(before, current())
    if not diff:
        print("no changes")
        return
    for c in diff: show(*c)
    if sys.argv[1] == "diff":
        return
    for item_id, b, _ in diff:
        if b is None:
            api("DELETE", f"/UserPlayedItems/{item_id}?userId={USER_ID}")
            api("POST", f"/UserItems/{item_id}/UserData?userId={USER_ID}", {"PlaybackPositionTicks": 0, "PlayCount": 0, "Played": False})
        else:
            api("POST", f"/UserItems/{item_id}/UserData?userId={USER_ID}", {k: b[k] for k in FIELDS})
    left = changes(before, current())
    print(f"restored {len(diff)} items" + ("" if not left else f"; still different: {len(left)}"))
    for c in left: show(*c)


TOKEN, USER_ID = (os.environ["JF_TOKEN"], os.environ["JF_USER_ID"]) if os.environ.get("JF_USER_ID") else account()
if __name__ == "__main__":
    main()
