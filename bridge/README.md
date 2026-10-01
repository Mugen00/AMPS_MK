# AMPS Bridge

Zero-dependency Node.js HTTP bridge that exposes your **local** MCP servers
(trace.moe + imgfind) as a small JSON API, so an Android phone on the same
Wi-Fi can look up anime screenshots.

* No `node_modules`, no build step, no `npm install` — only `node:` builtins.
* Spawns each MCP server over stdio (newline-delimited JSON-RPC 2.0), lazily, and
  restarts it once if it crashes.
* Everything the phone sends is written to a unique temp file, handed to the MCP
  tool as a path, and deleted in a `finally` block.

```
Android app ──HTTP/JSON──▶ AMPS Bridge ──stdio JSON-RPC──▶ trace.moe MCP (node)
                                        └──────────────────▶ imgfind MCP   (node)
                                                                 └──▶ api.trace.moe / SauceNAO
```

## Requirements

Node.js >= 18.17 (tested on v24.12.0, Windows 11) and the two MCP servers
already installed on this machine:

| Node | Command |
|------|---------|
| `tracemoe` | `C:\Users\Miha2003\.dsh\mcp\trace-moe-mcp\node_modules\trace.moe-mcp\dist\index.js` |
| `imgfind`  | `C:\Users\Miha2003\.dsh\mcp\imgfind-mcp\index.js` |

## Run

```powershell
cd C:\Users\Miha2003\Documents\deepseek-harness\default-workspace\AMPS\bridge
node src\server.mjs            # or: npm start
```

Startup prints every LAN IPv4 address in copy-pasteable form:

```
  AMPS Bridge v1.0.0
  listening on   http://0.0.0.0:8787
  keys           trace.moe: configured | SauceNAO: NOT configured
  LAN            http://192.168.0.103:8787   (Wi-Fi)

  copy-paste checks:
    curl http://192.168.0.103:8787/api/health
    ...
```

On the phone: connect to the **same Wi-Fi**, then point the app at
`http://<LAN-IP>:8787`. Use the IP, never `localhost` (that would be the phone).
Logs are JSON lines on stdout/stderr; `Ctrl+C` shuts down cleanly and kills both
child processes.

### Verify the installation

```powershell
node smoke-test.mjs
```

It spawns both MCP servers, performs the real `initialize` handshake, prints
`tools/list` for each, then calls `get_account_quota` / `config_status` when the
keys are configured. Exit code is non-zero if a handshake or `tools/list` fails.

## Configuration

Priority: **CLI args > environment variables > `.env` file next to `bridge/` > defaults.**

| Key | Default | Meaning |
|-----|---------|---------|
| `PORT` | `8787` | TCP port |
| `HOST` | `0.0.0.0` | bind address (`127.0.0.1` keeps it off the LAN) |
| `TRACE_MOE_API_KEY` | — | passed to both MCP servers as-is |
| `SAUCENAO_API_KEY` | — | passed to imgfind; without it `sauce.configured` is `false` |
| `MCP_TRACEMOE_CMD` | path above | script launched with `node` |
| `MCP_IMGFIND_CMD` | path above | script launched with `node` |
| `LOG_LEVEL` | `info` | `debug` \| `info` \| `warn` \| `error` \| `silent` |
| `REQUEST_TIMEOUT_MS` | `60000` | per MCP tool call; exceeded ⇒ HTTP 504 |

```powershell
node src\server.mjs --port 9000 --log-level debug
$env:SAUCENAO_API_KEY = 'xxx'; node src\server.mjs
# .env file
PORT=8787
TRACE_MOE_API_KEY=xxx
```

Key **values are never logged** — only `configured: true/false`.

## HTTP API

All responses are JSON. CORS is open (`Access-Control-Allow-Origin: *`,
`GET, POST, OPTIONS`, headers `content-type, x-filename, x-cut-borders, x-anilist-id`).

### `GET /api/health`

```json
{"ok":true,"version":"1.0.0","uptimeSec":42,
 "keys":{"traceMoe":true,"sauceNao":false},
 "nodes":{"tracemoe":{"ready":true,"tools":["search_anime_by_image_url", "..."]},
          "imgfind":{"ready":true,"tools":["find_image_source", "..."]}}}
```

Never fails because a node is down: a broken node reports
`{"ready":false,"error":"…","tools":[],"stderr":"…"}` and the HTTP status stays `200`.

### `POST /api/frame/lookup`

Raw image bytes as the request body (`image/jpeg` or `image/png`, ≤ 25 MB).
Optional headers: `x-filename` (default `frame.png`), `x-cut-borders`
(`"false"` disables border cropping), `x-anilist-id` (restrict to one AniList ID).

```bash
curl -X POST --data-binary @frame.jpg -H "x-filename: frame.jpg" \
     http://192.168.0.103:8787/api/frame/lookup
```

```json
{"engine":"trace.moe","matched":true,
 "anilist":{"id":21,"idMal":21,"title":{"romaji":"ONE PIECE","english":"One Piece","native":"ONE PIECE"},
            "description":"…","coverImage":{"extraLarge":"…"},"episodes":26,
            "genres":["Action"],"startDate":{"year":1999,"month":10,"day":20},
            "status":"FINISHED","format":"TV","averageScore":80,"popularity":100000,
            "synonyms":[],"isAdult":false},
 "episode":1,"frame":37012,"timestamp":12.34,"similarity":0.9723,
 "video":{"id":"kSM7abcde","part":1,"length":null,"url":"https://media.trace.moe/video/kSM7abcde/960x540"},
 "raw":"### trace.moe Search Results …"}
```

No match ⇒ HTTP **200** with `{"matched":false,"raw":"No matching anime scene found on trace.moe."}`.

### `POST /api/frame/identify`

Same input; runs trace.moe **and** imgfind `find_image_source` concurrently.

```json
{"trace":{ …same shape as lookup… },
 "sauce":{"configured":true,"matched":true,"similarity":0.9321,"index":7,
          "source":"SauceNAO","title":"Nico Robin","url":"https://www.pixiv.net/artworks/1234",
          "author":"fanart","characters":[],"tags":[],"raw":"…"}}
```

Without `SAUCENAO_API_KEY` the response is still `200` with
`"sauce":{"configured":false}`.

### `GET /api/anime/search?q=<name>`

`{"query":"One Piece","results":[{"anilist":{ …anilist object… },"similarity":null}]}`

### Errors

| Status | Body | When |
|--------|------|------|
| 400 | `{"error":"bad_request","message":"…"}` | empty/non-image body, bad content-type, body > 25 MB, missing `q` |
| 404 | `{"error":"not_found"}` | unknown path/method |
| 500 | `{"error":"upstream_error","message":"…"}` | MCP server or upstream API failed (never includes a stack trace) |
| 504 | `{"error":"upstream_timeout"}` | tool call exceeded `REQUEST_TIMEOUT_MS` |

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `spawn EPERM` / health shows `ready:false` | The bridge cannot create child processes — you are inside a restricted sandbox/container. Run it from a normal terminal. |
| Phone says "connection refused" | Wrong address (use the LAN IP, not `localhost`), phone on a different Wi-Fi/guest network, or Windows Firewall blocking inbound TCP 8787 (`New-NetFirewallRule -DisplayName AMPS -Direction Inbound -LocalPort 8787 -Protocol TCP -Action Allow`). |
| `matched:false` for every frame | trace.moe key missing/expired or the image is not an anime frame; check `GET /api/health` → `keys.traceMoe`. |
| `sauce.configured:false` | `SAUCENAO_API_KEY` is not set for this process. |
| `upstream_timeout` | Raise `REQUEST_TIMEOUT_MS`, or the upstream API is slow/overloaded. |
| `EADDRINUSE` on start | Another process owns the port; the banner suggests the next free port. |
| Key env vars not picked up | They are read once at startup — restart the bridge after changing them. |

## Security

This server has **no authentication** by design (it is meant for a home LAN):

* It binds `0.0.0.0`, so anyone on a reachable network can spend your trace.moe
  and SauceNAO quota and read results. Prefer `--host 127.0.0.1` plus an SSH
  tunnel, or a private Wi-Fi (no guest network).
* If the port must be exposed, restrict it with a firewall rule to your LAN
  subnet and never port-forward it on the router.
* API keys are only read from your environment and forwarded to the child
  processes; they are never echoed in responses or logs — but the child process
  command line is yours, so keep the `.env` file readable only by your user.

## Files

| File | Purpose |
|------|---------|
| `src/server.mjs` | HTTP server, CORS, body limit, JSON-lines logging, banner, shutdown |
| `src/routes.mjs` | Routing + response normalization (`trace` / `sauce`) |
| `src/mcp-client.mjs` | `McpStdioClient`: spawn, handshake, request correlation, restart |
| `src/config.mjs` | `.env` parser, CLI/env/defaults, JSON-lines logger |
| `smoke-test.mjs` | Live handshake + `tools/list` check for both nodes |
