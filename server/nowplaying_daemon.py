#!/usr/bin/env python3
"""Echo Show 5 now-playing daemon (Bluetooth-only edition).

Polls the Echo Show 5 (LineageOS) over adb/Wi-Fi for the Bluetooth media
session and broadcasts updates to CalendarTab's top now-playing bar.

No NetEase API is used at all (by design, to keep the listening-stats
importer's view of the API/DB stable):
- Song title / artists come from the AVRCP artist field, where NetEase
  packs "<song> (<cn>) - <artists>".
- The lyric line comes from the AVRCP title field, which NetEase rotates
  in sync with playback (credits, then the current lyric line). Credit
  entries are filtered out; anything that is not a lyric line is blanked.

Strings are sent base64-encoded so adb shell argument parsing can never
mangle Japanese/Chinese text or spaces.
"""
import base64
import os
import re
import subprocess
import time

DEVICE = os.environ.get("ECHO_DEVICE", "192.168.0.22:5555")
ADB = os.path.expanduser("~/platform-tools/adb")
ACTION = "my.app.calendarkiosk.NOW_PLAYING"
TARGET_PKG = "my.app.calendarkiosk.roze"
POLL_SEC = 1
HEARTBEAT_SEC = 10
LOG_FILE = os.path.expanduser("~/echo-nowplaying/daemon.log")

CREDIT_RE = re.compile(r"^(作词|作曲|编曲|作詞|編曲|演唱|出品|制作|词|曲)\s*[:：]")


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
    """Return dict(state, title, artist, album) from the BT media session, or None."""
    try:
        r = adb("shell", "dumpsys media_session", timeout=25)
    except Exception as e:
        log(f"dumpsys error: {e}")
        return None
    out = r.stdout or ""
    idx = out.find("BluetoothMediaBrowserService")
    if idx < 0:
        return None
    block = out[idx:idx + 6000]
    sm = re.search(r"state=PlaybackState \{state=(\d+),", block)
    dm = re.search(r"metadata: size=\d+, description=(.*)", block)
    if not sm or not dm:
        return None
    desc = dm.group(1).strip()
    parts = desc.split(", ")
    title = parts[0] if parts else ""
    album = parts[-1] if len(parts) >= 2 else ""
    artist = ", ".join(parts[1:-1]) if len(parts) >= 3 else ""
    return {"state": int(sm.group(1)), "title": title, "artist": artist, "album": album}


def parse_bt(sess):
    """Return (display_title, display_artists, lyric_line) purely from BT fields."""
    m = re.match(r"^(.*?)\s*\([^)]*\)\s*-\s*(.+)$", sess["artist"])
    if not m:
        # Not NetEase-style metadata: title is a real title, no lyric rotation.
        return sess["title"], sess["artist"], ""
    song = m.group(1).strip()
    artists = ", ".join(a.strip() for a in re.split(r"[/,]", m.group(2)) if a.strip())
    cand = sess["title"].strip()
    if not cand or cand == song or cand == sess["album"] or CREDIT_RE.match(cand) or len(cand) > 60:
        lyric = ""
    else:
        lyric = cand
    return song, artists, lyric


def b64(s):
    return base64.b64encode((s or "").encode("utf-8")).decode("ascii")


def broadcast(state, title="", artist="", lyric=""):
    args = ["shell", "am", "broadcast", "-a", ACTION, "-p", TARGET_PKG,
            "--es", "state", state,
            "--es", "title_b64", b64(title),
            "--es", "artist_b64", b64(artist),
            "--es", "lyric_b64", b64(lyric)]
    try:
        adb(*args, timeout=15)
    except Exception as e:
        log(f"broadcast error: {e}")


def main():
    os.makedirs(os.path.dirname(LOG_FILE), exist_ok=True)
    log("daemon start (bluetooth-only)")
    last_key = None
    last_sent = 0.0
    stopped_sent = True
    last_track = None
    while True:
        loop_start = time.time()
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
            title, artists, lyric = parse_bt(sess)
            track_key = (title, artists, sess["album"])
            if track_key != last_track:
                last_track = track_key
                log(f"track: {title!r} / {artists!r}")
            state_str = "playing" if sess["state"] == 3 else "paused"
            key = (state_str, title, lyric)
            now = time.time()
            if key != last_key or now - last_sent >= HEARTBEAT_SEC:
                broadcast(state_str, title, artists, lyric)
                last_key = key
                last_sent = now
        except Exception as e:
            log(f"loop error: {e}")
        elapsed = time.time() - loop_start
        time.sleep(max(0.2, POLL_SEC - elapsed))


if __name__ == "__main__":
    main()
