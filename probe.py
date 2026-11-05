# Prints only structure/counts, never lyric text.
import json, urllib.request, urllib.parse
UA = {"User-Agent": "Mozilla/5.0 Chroma-probe"}
def get(url, headers=None):
    req = urllib.request.Request(url, headers={**UA, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=20) as r: return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e: return e.code, e.read().decode("utf-8", "replace")
    except Exception as e: return -1, str(e)
APP = "web-desktop-app-v1.0"; BASE = "https://apic-desktop.musixmatch.com/ws/1.1/"
H = {"Cookie": "AWSELBCORS=0; AWSELB=0"}
tok = json.loads(get(BASE + "token.get?app_id=" + APP, H)[1])["message"]["body"]["user_token"]
def macro(artist, title, dur=None):
    p = {"format": "json", "namespace": "lyrics_richsynched", "subtitle_format": "mxm", "app_id": APP, "q_artist": artist, "q_track": title, "usertoken": tok}
    if dur: p["q_duration"] = dur
    j = json.loads(get(BASE + "macro.subtitles.get?" + urllib.parse.urlencode(p), H)[1])
    t = j["message"]["body"]["macro_calls"]["matcher.track.get"]["message"]["body"]["track"]
    return t
for artist, title, dur in [("Juice WRLD", "Lucid Dreams", 239), ("The Weeknd", "Blinding Lights", 200)]:
    t = macro(artist, title, dur)
    print("MATCH", title, "id", t["track_id"], "common", t["commontrack_id"], "rich", t["has_richsync"], "name_ok", t["track_name"].lower().startswith(title.lower()[:5]))
    for host in ["https://apic-desktop.musixmatch.com/ws/1.1/", "https://apic.musixmatch.com/ws/1.1/"]:
        for key, val in [("track_id", t["track_id"]), ("commontrack_id", t["commontrack_id"])]:
            q = urllib.parse.urlencode({key: val, "usertoken": tok, "app_id": APP, "format": "json", "subtitle_format": "mxm"})
            s, b = get(host + "track.richsync.get?" + q, H)
            try:
                j = json.loads(b); hc = j["message"]["header"]["status_code"]
                rb = j["message"]["body"]["richsync"]["richsync_body"] if hc == 200 else None
                arr = json.loads(rb) if rb else []
                print(" RICH", host.split("//")[1][:12], key, "http", s, "hdr", hc, "lines", len(arr),
                      "linekeys", sorted(arr[0].keys()) if arr else None, "wordkeys", sorted(arr[0]["l"][0].keys()) if arr else None,
                      "words_in_line0", len(arr[0]["l"]) if arr else None)
            except Exception as e:
                print(" RICH", host[8:20], key, "http", s, "err", str(e)[:50], b[:120].replace("\n", " "))
