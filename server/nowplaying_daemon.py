#!/usr/bin/env python3
"""Echo Show 5 now-playing daemon.

Polls the Echo Show 5 (LineageOS) over adb/Wi-Fi for the Bluetooth media
session, resolves the track against the local NetEase API (search + lyric),
computes the current LRC line, and broadcasts updates to CalendarTab's
top now-playing bar via adb broadcasts.

Strings are sent base64-encoded so adb shell argument parsing can never
mangle Japanese/Chinese text or spaces.
"""
import base64
import json
import os
import re
import subprocess
import time
import urllib.parse
import urllib.request

DEVICE = os.environ.get("ECHO_DEVICE", "192.168.0.22:5555")
ADB = os.path.expanduser("~/platform-tools/adb")
API_BASE = "http://127.0.0.1:3000"
COOKIE_FILE = os.path.expanduser("~/netease-verification/.netease_cookie")
ACTION = "my.app.calendarkiosk.NOW_PLAYING"
POLL_SEC = 3
HEARTBEAT_SEC = 10
LOG_FILE = os.path.expanduser("~/echo-nowplaying/daemon.log")

_cookie = None


def log(msg):
    line = time.strftime("%Y-%m-%d %H:%M:%S ") + msg
    print(line, flush=True)
    try:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except Exception:
        pass


def adb(*args, timeout=20):
    return subprocess.run([ADB, "-s", DEVICE] + list(args),
                          capture_output=True, text=True, timeout=timeout)


def ensure_connected():
    try:
        r = subprocess.run([ADB, "devices"], capture_output=True, text=True, timeout=15)
        for line in r.stdout.splitlines():
            if line.startswith(DEVICE) and "\tdevice" in line:
                return True
        subprocess.run([ADB, "connect", DEVICE], capture_output=True, text=True, timeout=20)
        r = subprocess.run([ADB, "devices"], capture_output=True, text=True, timeout=15)
        return any(l.startswith(DEVICE) and "\tdevice" in l for l in r.stdout.splitlines())
    except Exception as e:
        log(f"ensure_connected error: {e}")
        return False


def fetch_session():
    """Return dict(state, position_ms, updated_ms, uptime_ms, title, artist, album) or None."""
    try:
        r = adb("shell", "dumpsys media_session; echo __UPTIME__; cat /proc/uptime", timeout=25)
    except Exception as e:
        log(f"dumpsys error: {e}")
        return None
    out = r.stdout or ""
    m = re.search(r"__UPTIME__\s*\n\s*([\d.]+)", out)
    uptime_ms = int(float(m.group(1)) * 1000) if m else None
    # Bluetooth session block only
    idx = out.find("BluetoothMediaBrowserService")
    if idx < 0:
        return None
    block = out[idx:idx + 6000]
    sm = re.search(r"state=PlaybackState \{state=(\d+), position=(\d+), .*?updated=(\d+)", block)
    dm = re.search(r"metadata: size=\d+, description=(.*)", block)
    if not sm or not dm:
        return None
    state = int(sm.group(1))
    pos = int(sm.group(2))
    updated = int(sm.group(3))
    desc = dm.group(1).strip()
    parts = desc.split(", ")
    title = parts[0] if parts else ""
    album = parts[-1] if len(parts) >= 2 else ""
    artist = ", ".join(parts[1:-1]) if len(parts) >= 3 else ""
    return {"state": state, "position": pos, "updated": updated,
            "uptime_ms": uptime_ms, "title": title, "artist": artist, "album": album}


def api(path, params):
    global _cookie
    if _cookie is None:
        try:
            _cookie = open(COOKIE_FILE, encoding="utf-8").read().strip()
        except Exception:
            _cookie = ""
    q = urllib.parse.urlencode(params)
    req = urllib.request.Request(f"{API_BASE}{path}?{q}", headers={"Cookie": _cookie})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return json.load(resp)


def parse_lrc(text):
    lines = []
    for raw in text.splitlines():
        times = re.findall(r"\[(\d+):(\d+(?:\.\d+)?)\]", raw)
        body = re.sub(r"\[[^\]]*\]", "", raw).strip()
        if not times or not body:
            continue
        for mm, ss in times:
            lines.append((int((int(mm) * 60 + float(ss)) * 1000), body))
    lines.sort()
    return lines


def resolve_track(sess):
    """Best-effort NetEase match. Returns (song_id, name, artists_str, lrc_lines) or None."""
    raw = f"{sess['title']} {sess['artist']} {sess['album']}"
    queries = []
    # NetEase sometimes packs "<song> (<cn>) - <artists>" into the artist field
    m = re.match(r"^(.*?)\s*\([^)]*\)\s*-\s*(.+)$", sess["artist"])
    packed_name, packed_artists = (m.group(1).strip(), m.group(2).strip()) if m else (None, None)
    for q in (packed_name, sess["album"], sess["title"]):
        if q and q not in queries:
            queries.append(q)
    best = None
    for q in queries:
        try:
            res = api("/search", {"keywords": q, "type": 1, "limit": 8})
        except Exception as e:
            log(f"search error ({q}): {e}")
            continue
        for song in res.get("result", {}).get("songs", []):
            name = song.get("name", "")
            album = (song.get("album") or {}).get("name", "")
            artists = [a.get("name", "") for a in song.get("artists", [])]
            score = 0
            if packed_name and name == packed_name:
                score += 5
            if name and name == sess["album"]:
                score += 4
            if name and name == sess["title"]:
                score += 4
            if album and album == sess["album"]:
                score += 2
            for a in artists:
                if a and a in raw:
                    score += 2
            if score and (best is None or score > best[0]):
                best = (score, song)
    if not best or best[0] < 4:
        log(f"no confident match for title={sess['title']!r} artist={sess['artist']!r} album={sess['album']!r} best={best}")
        return None
    song = best[1]
    sid = song["id"]
    name = song.get("name", "")
    artists_str = ", ".join(a.get("name", "") for a in song.get("artists", []))
    lines = []
    try:
        ly = api("/lyric", {"id": sid})
        lines = parse_lrc((ly.get("lrc") or {}).get("lyric", ""))
    except Exception as e:
        log(f"lyric error (id={sid}): {e}")
    log(f"resolved: id={sid} name={name!r} artists={artists_str!r} score={best[0]} lrc_lines={len(lines)}")
    return (sid, name, artists_str, lines)


def b64(s):
    return base64.b64encode((s or "").encode("utf-8")).decode("ascii")


def broadcast(state, title="", artist="", lyric=""):
    args = ["shell", "am", "broadcast", "-a", ACTION,
            "--es", "state", state,
            "--es", "title_b64", b64(title),
            "--es", "artist_b64", b64(artist),
            "--es", "lyric_b64", b64(lyric)]
    try:
        adb(*args, timeout=15)
    except Exception as e:
        log(f"broadcast error: {e}")


def current_line(lines, pos_ms):
    cur = ""
    for t, text in lines:
        if t <= pos_ms + 200:
            cur = text
        else:
            break
    return cur


def main():
    os.makedirs(os.path.dirname(LOG_FILE), exist_ok=True)
    log("daemon start")
    last_key = None
    last_sent = 0.0
    track_cache = {}
    cur_track_key = None
    cur_resolved = None
    stopped_sent = True
    while True:
        try:
            if not ensure_connected():
                time.sleep(10)
                continue
            sess = fetch_session()
            if sess is None or sess["state"] in (0, 1, 7):
                if not stopped_sent:
                    broadcast("stopped")
                    stopped_sent = True
                    last_key = ("stopped",)
                time.sleep(POLL_SEC)
                continue
            stopped_sent = False
            # NetEase rotates the BT title field (credits / current lyric line),
            # so track identity must use the packed song name + artist + album.
            pm = re.match(r"^(.*?)\s*\([^)]*\)\s*-\s*(.+)$", sess["artist"])
            stable_title = pm.group(1).strip() if pm else sess["title"]
            key_track = (stable_title, sess["artist"], sess["album"])
            if key_track != cur_track_key:
                cur_track_key = key_track
                if key_track in track_cache:
                    cur_resolved = track_cache[key_track]
                else:
                    cur_resolved = resolve_track(sess)
                    track_cache[key_track] = cur_resolved
            if cur_resolved:
                disp_title, disp_artist, lines = cur_resolved[1], cur_resolved[2], cur_resolved[3]
            else:
                disp_title, disp_artist, lines = sess["title"], sess["artist"], []
            if sess["state"] == 3 and sess["uptime_ms"]:
                pos = sess["position"] + max(0, sess["uptime_ms"] - sess["updated"])
            else:
                pos = sess["position"]
            lyric = current_line(lines, pos) if lines else ""
            state_str = "playing" if sess["state"] == 3 else "paused"
            key = (state_str, disp_title, lyric)
            now = time.time()
            if key != last_key or now - last_sent >= HEARTBEAT_SEC:
                broadcast(state_str, disp_title, disp_artist, lyric)
                last_key = key
                last_sent = now
        except Exception as e:
            log(f"loop error: {e}")
        time.sleep(POLL_SEC)


if __name__ == "__main__":
    main()
