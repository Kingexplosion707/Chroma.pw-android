# Prints only structure/counts, never lyric text.
import json, urllib.request, urllib.parse, re
UA = {"User-Agent": "Mozilla/5.0 Chroma-probe"}
def get(url, headers=None):
    req = urllib.request.Request(url, headers={**UA, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.read().decode("utf-8", "replace"), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), dict(e.headers)
    except Exception as e:
        return -1, str(e), {}

songs = [("Juice WRLD", "Lucid Dreams"), ("The Weeknd", "Blinding Lights")]
for artist, title in songs:
    q = urllib.parse.urlencode({"artist_name": artist, "track_name": title})
    s, b, _ = get("https://lrclib.net/api/get?" + q)
    try:
        o = json.loads(b); syn = o.get("syncedLyrics") or ""
        print("LRCLIB", title, s, "lines", len(re.findall(r"\[\d+:\d+", syn)), "wordtags", len(re.findall(r"<\d+:\d+", syn)))
    except Exception as e:
        print("LRCLIB", title, s, "parse fail", b[:80])

APP = "web-desktop-app-v1.0"
H = {"Cookie": "AWSELBCORS=0; AWSELB=0", "Authority": "apic-desktop.musixmatch.com"}
s, b, _ = get(f"https://apic-desktop.musixmatch.com/ws/1.1/token.get?app_id={APP}", H)
tok = None
try:
    j = json.loads(b); print("MXM token http", s, "header", j["message"]["header"]); tok = j["message"]["body"].get("user_token")
except Exception as e:
    print("MXM token fail", s, b[:120])
print("token present", bool(tok))
if tok:
    for artist, title in songs:
        q = urllib.parse.urlencode({"q_artist": artist, "q_track": title, "usertoken": tok, "app_id": APP, "format": "json"})
        s, b, _ = get("https://apic-desktop.musixmatch.com/ws/1.1/matcher.track.get?" + q, H)
        try:
            j = json.loads(b); h = j["message"]["header"]; t = j["message"]["body"]["track"] if j["message"]["body"] else {}
            print("MXM match", title, h.get("status_code"), "id", t.get("track_id"), "has_richsync", t.get("has_richsync"), "has_subtitles", t.get("has_subtitles"))
            tid = t.get("track_id")
        except Exception as e:
            print("MXM match fail", s, b[:300].replace(chr(10)," ")); tid = None
        q = urllib.parse.urlencode({"format":"json","namespace":"lyrics_richsynched","subtitle_format":"mxm","app_id":APP,"q_artist":artist,"q_artists":artist,"q_track":title,"usertoken":tok})
        s2, b2, _ = get("https://apic-desktop.musixmatch.com/ws/1.1/macro.subtitles.get?" + q, H)
        try:
            j = json.loads(b2); mc = j["message"]["body"]["macro_calls"]
            print("MACRO", title, s2, "calls", sorted(mc.keys()))
            mt = mc["matcher.track.get"]["message"]
            print(" matcher hdr", mt["header"].get("status_code"), "track", (mt["body"] or {}).get("track",{}).get("track_id") if isinstance(mt["body"],dict) else None,
                  "rich", (mt["body"] or {}).get("track",{}).get("has_richsync") if isinstance(mt["body"],dict) else None)
            tid = (mt["body"] or {}).get("track",{}).get("track_id") if isinstance(mt["body"],dict) else None
            st = mc.get("track.subtitles.get",{}).get("message",{})
            print(" subtitles hdr", st.get("header",{}).get("status_code"))
        except Exception as e:
            print("MACRO fail", s2, str(e)[:60], b2[:300].replace(chr(10)," "))
        if tid:
            q = urllib.parse.urlencode({"track_id": tid, "usertoken": tok, "app_id": APP, "format": "json"})
            s, b, _ = get("https://apic-desktop.musixmatch.com/ws/1.1/track.richsync.get?" + q, H)
            try:
                j = json.loads(b); h = j["message"]["header"]
                body = j["message"]["body"]["richsync"]["richsync_body"] if j["message"]["body"] else "[]"
                arr = json.loads(body)
                print("MXM richsync", title, h.get("status_code"), "lines", len(arr), "keys", sorted(arr[0].keys()) if arr else None,
                      "wordkeys", sorted(arr[0]["l"][0].keys()) if arr and arr[0].get("l") else None)
            except Exception as e:
                print("MXM richsync fail", s, str(e)[:80], b[:150])
