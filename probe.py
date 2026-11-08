# Prints only structure, never lyric text.
import json, urllib.request, urllib.parse
UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
def req(url, headers=None):
    h = {"User-Agent": UA}; h.update(headers or {})
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=25) as r: return r.status, r.read()
    except urllib.error.HTTPError as e: return e.code, e.read()
    except Exception as e: return -1, str(e).encode()
q = urllib.parse.quote
def shape(v, depth=0):
    if isinstance(v, dict): return {k: shape(x, depth + 1) for k, x in list(v.items())[:25]} if depth < 3 else "dict"
    if isinstance(v, list): return [shape(v[0], depth + 1), len(v)] if v else []
    if isinstance(v, str): return ("str", len(v), v[:40] if v.startswith("http") or len(v) < 12 else "")
    return v
print("== BINI shape")
s, b = req(f"https://lyrics-api.binimum.org/?track={q('Blinding Lights')}&artist={q('The Weeknd')}&album={q('After Hours')}&duration=200")
o = json.loads(b); print(s, json.dumps(shape(o))[:1500])
r0 = (o.get("results") or [{}])[0]
for k in ("lyricsUrl", "url", "ttml", "lyrics"):
    if isinstance(r0.get(k), str) and r0[k].startswith("http"):
        s2, b2 = req(r0[k]); print("fetch", k, s2, len(b2), b2[:80].decode('utf-8', 'replace').replace('\n', ' ')[:60] if b2[:1] == b'<' else "", "has <span begin", b'<span begin' in b2, "timing", b'itunes:timing' in b2)
print("== UNISON tries")
for song, artist in [("Blinding Lights", "The Weeknd"), ("Espresso", "Sabrina Carpenter"), ("Die With A Smile", "Lady Gaga"), ("APT.", "ROSÉ"), ("Anti-Hero", "Taylor Swift"), ("Not Like Us", "Kendrick Lamar")]:
    s, b = req(f"https://unison.boidu.dev/lyrics?song={q(song)}&artist={q(artist)}")
    try: o = json.loads(b); print(song, s, json.dumps(shape(o))[:600])
    except Exception: print(song, s, b[:80])
print("== MXM translations")
MH = {"User-Agent": "Musixmatch/2025120901 CFNetwork/3860.300.31 Darwin/25.2.0", "x-mxm-app-version": "2025120901"}
B = "https://apic-appmobile.musixmatch.com/ws/1.1/"
tok = json.loads(req(B + "token.get?app_id=mac-ios-v2.0&format=json", MH)[1])["message"]["body"]["user_token"]
for p in [{"track_spotify_id": "0VjIjW4GlUZAMYd2vXMi3b", "selected_language": "es"}, {"track_spotify_id": "0VjIjW4GlUZAMYd2vXMi3b", "selected_language": "es", "comment_format": "text", "part": "user"},
          {"track_spotify_id": "0VjIjW4GlUZAMYd2vXMi3b", "selected_language": "de", "page_size": 100}, {"track_spotify_id": "0VjIjW4GlUZAMYd2vXMi3b", "selected_language": "fr"}]:
    pp = {"app_id": "mac-ios-v2.0", "usertoken": tok, "format": "json"}; pp.update(p)
    s, b = req(B + "crowd.track.translations.get?" + urllib.parse.urlencode(pp), MH)
    try:
        j = json.loads(b); h = j["message"]["header"]; body = j["message"]["body"]
        tl = body.get("translations_list", []) if isinstance(body, dict) else []
        print(p.get("selected_language"), list(p)[2:] , "hdr", h.get("status_code"), "count", len(tl), json.dumps(shape(tl[0]))[:400] if tl else "")
    except Exception as e: print("fail", s, b[:150])
