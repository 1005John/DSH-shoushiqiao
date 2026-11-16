# PC Access from Phone (DSH Go)

## USE THE HELPER SCRIPT (do not hand-roll URLs)

All PC access goes through `/sdcard/Download/pc.sh`. It picks the fastest route
by itself (LAN first, public as fallback) and caches the choice.

```sh
# search
sh /sdcard/Download/pc.sh get "/search?name=<urlencoded>&max=10"

# stat / list / health / index status
sh /sdcard/Download/pc.sh get "/stat?path=<urlencoded full path>"
sh /sdcard/Download/pc.sh get "/list?path=<urlencoded dir>"

# download to the phone
sh /sdcard/Download/pc.sh get "/file?path=<urlencoded>" -o /sdcard/Download/x.pdf

# upload from the phone
sh /sdcard/Download/pc.sh post "/upload?path=D:/dir/x.txt" --data-binary @/sdcard/Download/x.txt

# which route is in use right now
sh /sdcard/Download/pc.sh base
```

Output is the raw JSON (or file). No token handling needed — the script has it.

### URL-encoding reminder

Chinese and spaces must be percent-encoded inside the path argument:

```sh
P=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" 'D:\华为家庭存储\My_Document')
sh /sdcard/Download/pc.sh get "/list?path=$P"
```

## Underlying routes (for reference only)

| Path | Base | Speed |
|---|---|---|
| LAN | `http://<pc-lan-ip>:8094` | ~22 MB/s |
| Public | `https://39-106-60-159.sslip.io/pc` | ~0.4 MB/s |

`pc.sh` decides automatically; you should not construct these URLs yourself.

## ACCESS ROOT (important)

Two folders are accessible. Everything else (D:\Work, C:, Windows, ...) is
rejected with `{"error":"path outside allowed root or excluded"}`.

```
D:\华为家庭存储\                              <- office docs (Huawei NAS sync)
  My_Document\  .public_files\Network_Share_Resource\
  我的应用\                                   <- EXCLUDED (app caches)

D:\HuaweiMoveData\Users\USERNAME\Desktop\        <- the Windows desktop
```

`GET /pc/health` returns `"roots":[...]` with the exact list.
Search covers both roots automatically (single index, ~143k files).

### Placeholder files (cloud-backed)

Most files are Windows cloud placeholders: name/size/mtime are local, content is
on the NAS. Reading one triggers a download from the NAS and can take seconds to
minutes. Practical consequences:

- Search / stat / list: always instant (index, 143119 files).
- First read of a large file: slow (hydration). Later reads: fast.
- If the Huawei client is not running, reads fail with
  `The cloud file provider is not running` -> ask the user to open 华为家庭存储.

## IMPORTANT: encode Chinese paths

Any path containing Chinese must be URL-encoded before putting it in a query
string, otherwise the server sees a mangled path.

Linux/Android shell example:

```bash
P=$(python3 -c "import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))" 'D:\华为家庭存储\My_Document')
curl -k -H "X-Token: $TOKEN" "https://39-106-60-159.sslip.io/pc/list?path=$P"
```

Or pre-encode: `D:\华为家庭存储` -> `D%3A%5C%E5%8D%8E%E4%B8%BA%E5%AE%B6%E5%BA%AD%E5%AD%98%E5%82%A8`

## 1. PC File Service (fast, no LLM)

Header: `X-Token: YOUR_PC_FILE_TOKEN`

| Endpoint | Purpose |
|---|---|
| `GET /pc/health` | liveness + current root |
| `GET /pc/search?name=KEYWORD&max=20` | find files by name (inside root only) |
| `GET /pc/stat?path=FULLPATH` | exists / size / mtime |
| `GET /pc/list?path=DIR` | list a directory |
| `GET /pc/file?path=FULLPATH` | download to phone (`curl -o`) |
| `POST /pc/upload?path=FULLPATH` | write bytes back (raw body) |
| `POST /pc/mkdir?path=DIR` | create directory |
| `POST /pc/delete?path=FULLPATH` | move to trash (not permanent) |
| `GET /pc/index/status` | index size / build time |
| `POST /pc/reindex` | force rebuild (takes ~3 s) |

### Search is indexed — use it first

46542 files are indexed (built 2026-11-16, ~3 s, refreshed every 30 min).
A search returns in ~100 ms with `"source":"index"`.

- `GET /pc/search?name=合同` -> index lookup, milliseconds.
- `GET /pc/search?name=合同&root=<subdir>` -> live walk of that subtree
  (use when you need files synced in the last few minutes).
- If a result says `"source":"walk"`, it did not use the index.
- If `truncated:true`, narrow the keyword or add `root=`.

Prefer searching by a distinctive substring of the filename (Chinese works,
just URL-encode it).

## 2. PC Agent (slow, LLM, has memory)

Header: `X-Token: YOUR_PC_AGENT_TOKEN`

- `POST /agent/task` JSON `{"prompt":"...","session":"work"}` -> `{"ok":true,"id":"..."}`
- `GET /agent/task/<id>` -> `{"status":"queued|running|done|error","output":"...","turn":N}`
  Poll every 3-5 s; typical 5-60 s.
- `GET /agent/sessions` ; `POST /agent/reset {"session":"work"}`

The agent runs `dsh headless` with full PC access and remembers earlier tasks
**within the same session key**.

## 3. Choosing

| Request | Use |
|---|---|
| Does file X exist? | `/pc/search` then `/pc/stat` |
| Send me file X | `/pc/file` -> `/sdcard/Download`, report path |
| Modify file X | `/pc/file` down -> edit -> `/pc/upload` back |
| Multi-step / Office / PDF / Excel | `/agent/task` and poll |

## 4. Notes

- PC must be on and online; it is offline at night.
- Wrong token -> `{"error":"unauthorized"}`.
- Use `curl -k` (self-signed cert).
- Search only walks the root; a huge tree may hit the 25 s cap and set `truncated:true`.
