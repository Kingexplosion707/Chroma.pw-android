# Prints only status codes, JSON keys and counts. Never prints lyric text.
import json, urllib.request, urllib.parse, base64, zlib, re
UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
def req(url, headers=None, data=None, method=None):
    h = {"User-Agent": UA}; h.update(headers or {})
    r = urllib.request.Request(url, headers=h, data=data, method=method)
    try:
        with urllib.request.urlopen(r, timeout=25) as resp: return resp.status, resp.read(), dict(resp.headers)
    except urllib.error.HTTPError as e: return e.code, e.read(), dict(e.headers)
    except Exception as e: return -1, str(e).encode(), {}
def keys(b):
    try:
        o = json.loads(b); 
        return list(o.keys())[:20] if isinstance(o, dict) else ("list", len(o))
    except Exception: return ("non-json", len(b), b[:60])
T, A, AL, D, SID = "Blinding Lights", "The Weeknd", "After Hours", 200, "0VjIjW4GlUZAMYd2vXMi3b"
q = urllib.parse.quote

print("== SPICY")
s, b, _ = req(f"https://api.spicylyrics.org/v1/lyrics/{SID}"); print(s, keys(b), b[:160] if s != 200 else "")

print("== BINI")
for url in [f"https://lyrics-api.binimum.org/?track={q(T)}&artist={q(A)}&album={q(AL)}&duration={D}",
            f"https://lyrics-api.binimum.org/?s={q(T)}&a={q(A)}&d={D}",
            f"https://lyrics-api.binimum.org/getLyrics?s={q(T)}&a={q(A)}&d={D}",
            f"https://lyrics-api.binimum.org/?title={q(T)}&artist={q(A)}&duration={D}"]:
    s, b, _ = req(url); print(s, url.split('?')[0], url.split('?')[1][:20], keys(b))
    if s == 200:
        try:
            o = json.loads(b); print("  sample", {k: (str(v)[:60] if not isinstance(v, (dict, list)) else type(v).__name__) for k, v in o.items()} if isinstance(o, dict) else "")
        except Exception: pass

print("== UNISON")
for url in [f"https://unison.boidu.dev/lyrics?song={q(T)}&artist={q(A)}&album={q(AL)}&duration={D}",
            f"https://unison.boidu.dev/lyrics?s={q(T)}&a={q(A)}&d={D}",
            f"https://unison.boidu.dev/lyrics?title={q(T)}&artist={q(A)}&duration={D}",
            f"https://unison.boidu.dev/lyrics?track={q(T)}&artist={q(A)}&duration={D}"]:
    s, b, _ = req(url); print(s, url.split('?')[1][:24], keys(b), b[:120] if s != 200 else "")

print("== MUSIXMATCH mac-ios")
MH = {"User-Agent": "Musixmatch/2025120901 CFNetwork/3860.300.31 Darwin/25.2.0", "x-mxm-app-version": "2025120901"}
B = "https://apic-appmobile.musixmatch.com/ws/1.1/"
s, b, _ = req(B + "token.get?app_id=mac-ios-v2.0&format=json", MH)
tok = None
try:
    j = json.loads(b); print("token http", s, "hdr", j["message"]["header"]); tok = (j["message"]["body"] or {}).get("user_token")
except Exception as e: print("token fail", s, b[:200])
print("token?", bool(tok), (tok or "")[:6])
if tok:
    for extra in [{"track_spotify_id": "spotify:track:" + SID}, {"track_spotify_id": SID}, {"q_track": T, "q_artist": A, "q_duration": D}]:
        p = {"app_id": "mac-ios-v2.0", "usertoken": tok, "format": "json", "namespace": "lyrics_richsynched", "subtitle_format": "mxm", "optional_calls": "track.richsync"}
        p.update(extra)
        s, b, _ = req(B + "macro.subtitles.get?" + urllib.parse.urlencode(p), MH)
        try:
            j = json.loads(b); mc = j["message"]["body"]["macro_calls"]
            t = mc["matcher.track.get"]["message"]["body"]
            t = t.get("track", {}) if isinstance(t, dict) else {}
            rs = mc.get("track.richsync.get", {}).get("message", {})
            rb = (rs.get("body") or {}).get("richsync", {}).get("richsync_body") if isinstance(rs.get("body"), dict) else None
            arr = json.loads(rb) if rb else []
            print(list(extra)[0], "http", s, "match", t.get("track_name", "")[:20] == T[:20], "rich", t.get("has_richsync"), "richsync hdr", rs.get("header", {}).get("status_code"),
                  "lines", len(arr), "linekeys", sorted(arr[0].keys()) if arr else None, "calls", sorted(mc.keys()))
        except Exception as e: print(list(extra)[0], "fail", s, str(e)[:80], b[:200])
    p = {"app_id": "mac-ios-v2.0", "usertoken": tok, "format": "json", "track_spotify_id": "spotify:track:" + SID, "selected_language": "es", "page_size": "1"}
    s, b, _ = req(B + "crowd.track.translations.get?" + urllib.parse.urlencode(p), MH)
    try:
        j = json.loads(b); tl = j["message"]["body"]["translations_list"]; print("translations http", s, "count", len(tl), "keys", sorted(tl[0]["translation"].keys())[:12] if tl else None)
    except Exception as e: print("translations fail", s, b[:200])

print("== NETEASE")
NH = {"Referer": "https://music.163.com", "User-Agent": "Mozilla/5.0"}
s, b, _ = req(f"https://music.163.com/api/search/get?s={q(T + ' ' + A)}&type=1&limit=5", NH)
sid = None
try:
    songs = json.loads(b)["result"]["songs"]; print("search", s, len(songs), "first keys", sorted(songs[0].keys())[:15]); sid = songs[0]["id"]
    print(" first name ok", songs[0]["name"] == T, "dur", songs[0].get("duration"))
except Exception as e: print("search fail", s, b[:200])
if sid:
    s, b, _ = req(f"https://music.163.com/api/song/lyric/v1?id={sid}&lv=-1&kv=-1&tv=-1&rv=-1&yv=-1&ytv=-1&yrv=-1", NH)
    try:
        o = json.loads(b); y = (o.get("yrc") or {}).get("lyric", ""); l = (o.get("lrc") or {}).get("lyric", "")
        print("lyric", s, "keys", sorted(o.keys()), "yrc lines", len(re.findall(r"^\[\d+,\d+\]", y, re.M)), "yrc words", len(re.findall(r"\(\d+,\d+,", y)), "lrc lines", len(l.splitlines()))
    except Exception as e: print("lyric fail", s, b[:200])

print("== KUGOU")
s, b, _ = req(f"https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword={q(A + ' - ' + T)}&duration={D*1000}&hash=")
try:
    c = json.loads(b)["candidates"]; print("search", s, len(c), sorted(c[0].keys()) if c else None)
    if c:
        s2, b2, _ = req(f"https://lyrics.kugou.com/download?ver=1&client=pc&id={c[0]['id']}&accesskey={c[0]['accesskey']}&fmt=krc&charset=utf8")
        o = json.loads(b2); raw = base64.b64decode(o.get("content", ""))
        key = bytes([64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105])
        print(" download", s2, "keys", sorted(o.keys()), "header", raw[:4])
        dec = bytes(raw[4 + i] ^ key[i % 16] for i in range(len(raw) - 4))
        txt = zlib.decompress(dec).decode("utf-8", "replace")
        print(" krc lines", len(re.findall(r"^\[\d+,\d+\]", txt, re.M)), "words", len(re.findall(r"<\d+,\d+,\d+>", txt)))
except Exception as e: print("kugou fail", s, str(e)[:80], b[:120])

print("== QQ")
QH = {"Referer": "https://y.qq.com/", "Content-Type": "application/json"}
body = {"comm": {"ct": "19", "cv": "1859", "uin": "0"}, "req": {"method": "DoSearchForQQMusicDesktop", "module": "music.search.SearchCgiService", "param": {"num_per_page": 5, "page_num": 1, "query": T + " " + A, "search_type": 0}}}
s, b, _ = req("https://u.y.qq.com/cgi-bin/musicu.fcg", QH, json.dumps(body).encode(), "POST")
try:
    lst = json.loads(b)["req"]["data"]["body"]["song"]["list"]; print("search", s, len(lst), "first keys", sorted(lst[0].keys())[:12])
    qid = lst[0]["id"]; print(" name ok", lst[0].get("name") == T, "interval", lst[0].get("interval"))
    for crypt in [0, 1]:
        b2 = {"comm": {"ct": "19", "cv": "1859", "uin": "0"}, "req": {"method": "GetPlayLyricInfo", "module": "music.musichallSong.PlayLyricInfo", "param": {"songID": qid, "crypt": crypt, "qrc": 1, "trans": 1, "roma": 1, "ct": 19, "cv": 1859}}}
        s3, b3, _ = req("https://u.y.qq.com/cgi-bin/musicu.fcg", QH, json.dumps(b2).encode(), "POST")
        d = json.loads(b3)["req"]["data"]; lyr = d.get("lyric", "")
        print(" lyric crypt", crypt, s3, "keys", sorted(d.keys())[:12], "len", len(lyr), "looks hex", bool(re.fullmatch(r"[0-9A-Fa-f]+", lyr[:200] or "x")), "has LyricContent", "LyricContent" in lyr)
except Exception as e: print("qq fail", s, str(e)[:80], b[:160])
