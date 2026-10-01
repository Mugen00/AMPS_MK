# Music sources for the AMPS Android app — implementation-ready spec

**Verification date:** 2026-10-01 (all timestamps in responses are from that date)
**Scope:** (a) metadata search by track title, (b) legally importable free-licensed audio.
**Red line:** the app must never extract audio from streaming services. Only endpoints in
this document return a *file URL for a freely-licensed work*.

---

## 0. Methodology and tooling limits (read this first)

| Tool | Result |
| --- | --- |
| `pwsh` + `curl.exe https://example.com` | **No outbound network** from the sandbox shell → `curl` exit code `000`. Verified. No escalated permissions were requested. |
| `web_fetch` | Works. Returns **status code + body text**. Used for all body verification. |
| Response **headers** (CORS, rate limit) | Obtained indirectly via `https://api.hackertarget.com/httpheaders/?q=<urlencoded-target>` — that service performs a real GET against the target and returns the full response header chain. This is a real observation of the live header set. |
| Custom request headers (e.g. `User-Agent`, `Referer`) | **Cannot be set.** `web_fetch` sends its own UA. This limits two verifications, flagged as **НЕ ПРОВЕРЕНО** below. |
| Audio downloads | **None performed.** Only metadata/HEAD-style header inspection. |

Consequence: anything that depends on a request header we could not inject is marked
**НЕ ПРОВЕРЕНО**. Nothing else is guessed — every field name below appears in a real
response quoted in this document or in official documentation that is linked and quoted.

**Note on MusicBrainz User-Agent:** our `web_fetch` calls (which send a non-`Java`
User-Agent) were **not** blocked — see §2.4.

---

## 1. iTunes Search API — metadata only

### 1.1 Endpoint and live verification

| Call | Status | Verified |
| --- | --- | --- |
| `GET https://itunes.apple.com/search?term=daft+punk&entity=song&limit=2` | **200** | ✅ |
| `GET https://itunes.apple.com/lookup?id=697195462` | **200** | ✅ |
| `GET https://itunes.apple.com/search?term=beatles&entity=musicArtist&limit=2` | **200** | ✅ |
| `GET https://itunes.apple.com/search?term=no+results+xyzzy+999999&entity=song&limit=1` | **200** | ✅ |
| `GET https://itunes.apple.com/search?term=YOASOBI&entity=song&limit=1&country=JP` | **200** | ✅ |

### 1.2 Exact JSON fields (verbatim from a real 200)

`entity=song`:

```json
{"wrapperType":"track", "kind":"song", "artistId":5468295, "collectionId":697194953,
 "trackId":697195462, "artistName":"Daft Punk", "collectionName":"Discovery",
 "trackName":"One More Time", "collectionCensoredName":"Discovery",
 "trackCensoredName":"One More Time",
 "artistViewUrl":"https://music.apple.com/us/artist/daft-punk/5468295?uo=4",
 "collectionViewUrl":"https://music.apple.com/us/album/one-more-time/697194953?i=697195462&uo=4",
 "trackViewUrl":"https://music.apple.com/us/album/one-more-time/697194953?i=697195462&uo=4",
 "previewUrl":"https://audio-ssl.itunes.apple.com/itunes-assets/AudioPreview221/v4/...plus.aac.p.m4a",
 "artworkUrl30":".../100x100bb.jpg", "artworkUrl60":"...", "artworkUrl100":"...",
 "collectionPrice":9.99, "trackPrice":1.29, "releaseDate":"2000-11-30T08:00:00Z",
 "collectionExplicitness":"notExplicit", "trackExplicitness":"notExplicit",
 "discCount":1, "discNumber":1, "trackCount":14, "trackNumber":1,
 "trackTimeMillis":320357, "country":"USA", "currency":"USD",
 "primaryGenreName":"Dance", "isStreamable":true}
```

`entity=musicArtist`:

```json
{"wrapperType":"artist", "artistType":"Artist", "artistName":"The Beatles",
 "artistLinkUrl":"https://music.apple.com/us/artist/the-beatles/136975?uo=4",
 "artistId":136975, "amgArtistId":3644,
 "primaryGenreName":"Rock", "primaryGenreId":21}
```

Empty result (real 200):

```json
{"resultCount":0, "results": []}
```

> **Legal note.** `previewUrl` is Apple's official 30-second preview (AAC/m4a), served under
> Apple's terms. It is **not** a full track and must not be persisted or redistributed.
> Use it for in-app preview playback only, or skip it. It is not a substitute for the
> "free tracks" tab.

### 1.3 Artwork sizing — the non-obvious part

`artworkUrl100` ends in `/100x100bb.jpg`. The `bb` is the literal placeholder for **both**
dimensions. To get any size, replace **both** occurrences:

```
https://is1-ssl.mzstatic.com/image/thumb/Music221/.../dj.qrikkdwj.jpg/100x100bb.jpg
→ replace "100x100bb" with "600x600bb" for 600×600
```

This is required for every artwork call. **Verified pattern** (values 30/60/100 confirmed present).

### 1.4 CORS — VERIFIED YES

Real response headers captured from `https://itunes.apple.com/search?term=test&entity=song&limit=1`:

```
HTTP/1.1 200 OK
Content-Type: text/javascript; charset=utf-8
Access-Control-Allow-Origin: *
Cache-Control: max-age=86400
Vary: Accept-Encoding
x-apple-orig-url: https://mzstoreservices-mr.itunes.apple.com/search?term=test&entity=song&limit=1
```

✅ **`Access-Control-Allow-Origin: *` is sent.** CORS is *not* a blocker here — though for a
native Android app (OkHttp/Retrofit) CORS is irrelevant anyway; browsers enforce it, HTTP
clients do not.

### 1.5 `country` parameter — optional, but recommended

- `country` is **not required**. Omitting it works (all calls above without `country` returned 200).
- When omitted, the store defaults to **USA** (`"country":"USA"`, `"currency":"USD"` observed).
- With `country=JP` the response changed to `"country":"JPN","currency":"JPY"`, and a
  Japan-only artist (`YOASOBI`) was returned that is absent from the US catalogue.
- Note the **asymmetry**: the `country` *response field* is `"JPN"`, but the *request*
  parameter is ISO-3166 **alpha-2 lowercase** (`jp`, `ru`, `gb`, `us`). Do not feed the
  response value back into the request.
- **Recommendation:** always pass `country` derived from `Locale.getDefault().country`.
  For an all-caps/blank locale fall back to `us`.

### 1.6 Rate limits

**НЕ ПРОВЕРЕНО** — the documented/observed iTunes rate limits. Apple publishes no numeric
limit in the Search API response; no rate-limit headers (`X-RateLimit-*`, `Retry-After`)
were present in the header capture above. Practical guidance for the app: cache aggressively,
debounce the search box by ≥350 ms, and cap at ~20 requests/minute/user.

### 1.7 Parameters relevant to the app

`term` (required), `country`, `entity` (`song` | `album` | `musicArtist` | `podcast`),
`limit` (1–200, default 20), `lang`, `attribute`, `explicit`, `media=music`.
`lookup` uses `id` (or `amgArtistId` / `upc` / `isbn`) + optional `entity`, and returns the
same `results[]` shape (verified: `lookup?id=697195462` → `resultCount:1`, identical field set).

---

## 2. MusicBrainz — metadata only

### 2.1 Live verification

| Call | Status |
| --- | --- |
| `GET /ws/2/recording?query=artist:"Radiohead" AND recording:"Creep"&fmt=json&limit=2` | **200** |
| `GET /ws/2/recording/c5b644d0-c748-44c0-bebc-0f58140aaa83?inc=artists+releases&fmt=json` | **200** |
| `GET /ws/2/release-group?query=releasegroup:"In Rainbows"&fmt=json&limit=3` | **200** |
| `GET /ws/2/artist?query=artist:"Boards of Canada"&fmt=json&limit=1` | **200** |
| `GET /ws/2/work?query=work:"Hallelujah"&fmt=json&limit=1` | **200** |
| `GET /doc/MusicBrainz_API` | **200** |
| `GET /doc/MusicBrainz_API/Rate_Limiting` | **200** |
| `GET /doc/MusicBrainz_API/Rate_Limit` (old URL) | **404** — use `Rate_Limiting` |

Base URL: `https://musicbrainz.org/ws/2/`. JSON requires either `Accept: application/json`
or `fmt=json`; **`fmt=json` wins if both are set** (quoted from the API doc page).

### 2.2 REQUIRED User-Agent policy — exact format

From `https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting` (verified 200, verbatim):

> "Each request sent to MusicBrainz needs to include a User-Agent header, with enough
> information for us (MusicBrainz) to contact the application maintainers. We strongly
> suggest including your application's version number in the User-Agent string too.
> … We suggest that your User-Agent string should look like:
> `Application name/<version> ( contact-url )` or `Application name/<version> ( contact-email )`"

Kotlin value for this app:

```
AMPS/1.0.0 ( https://github.com/<owner>/AMPS )
```

### 2.3 "Anonymous" User-Agents get throttled HARD — critical for OkHttp

The same page lists User-Agents that are treated as **anonymous** and throttled to ~50 req/s
average with **503** beyond that:

| User-Agent String | Version |
| --- | --- |
| `<blank>` | – |
| **`Java`** | any |
| `Python-urllib` | any |
| `Jakarta Commons-HttpClient` | any |
| `Apache-HttpClient` | UNAVAILABLE (java 1.4) |

⚠️ **`Apache-HttpClient` is the classic Android/Java default.** If the app uses
`HttpURLConnection` (default OkHttp UA on Android is `okhttp/x.y.z`, which is fine) or Apache
HttpClient, you land in the throttled bucket. **Always set an explicit UA interceptor.**

### 2.4 Observed rate-limit behaviour (live headers)

Real headers from `GET /ws/2/artist?query=artist=beatles&fmt=json`:

```
HTTP/1.1 200 OK
X-RateLimit-Limit: 400
X-RateLimit-Remaining: 194
X-RateLimit-Reset: 1790894691
Access-Control-Allow-Origin: *
X-MB-Gateway: rudi
```

* MusicBrainz now exposes **`X-RateLimit-Limit` / `-Remaining` / `-Reset`** — use these
  instead of guessing. `Reset` is a Unix timestamp.
* ✅ **CORS: `Access-Control-Allow-Origin: *` is sent.**
* Our `web_fetch` requests (non-`Java`, non-blank UA) were **never throttled** across ~8 calls
  at high concurrency, consistent with the doc's "for other user-agents: allow through".
* Documented policy: **1 request/second per source IP** (average); exceeding it → **503** for
  *all* requests from that IP until the rate drops. Global cap 300 req/s.
* Practical: enforce a **global 1.1 s spacing** on MusicBrainz calls and a token bucket, and
  back off with exponential delay on 503.

### 2.5 Field names (verbatim, real 200 responses)

**Search envelope** — `/recording`, `/release-group`, `/artist`, `/work` all share:

```json
{"created":"2026-10-01T22:43:48.866Z","count":192,"offset":0,"recordings":[ ... ]}
```

Top-level array key per entity: `recordings`, `release-groups`, `artists`, `works`.

**recording** (`/ws/2/recording?query=…`):

```json
{"id":"c5b644d0-…","score":100,"artist-credit-id":"021661c1-…","title":"Creep",
 "disambiguation":"live, 1996-08-14: Great Woods, Boston, MA, USA","video":null,
 "artist-credit":[{"name":"Radiohead","artist":{"id":"a74b1b7f-…","name":"Radiohead",
   "sort-name":"Radiohead","aliases":[…]}}],
 "releases":[{"id":"7061dc52-…","count":1,"title":"1996-08-14: …","status":"Bootleg",
   "artist-credit":[…],
   "release-group":{"id":"2fafad4d-…","type-id":"f529b476-…","title":"1996-08-14: …",
     "primary-type":"Album","secondary-types":["Live"],"secondary-type-ids":[…]},
   "track-count":11,
   "media":[{"id":"…","position":1,"track":[{"id":"ac6ec376-…","number":"7","title":"Creep"}],
             "track-count":11,"track-offset":6}]}],
 "length":315373}
```

⚠️ **`length` is in MILLISECONDS** (`315373` = 5 m 15 s), and it is **frequently `null`**
(observed on a studio/live recording). Never assume non-null.

**recording lookup** `/ws/2/recording/<mbid>?inc=artists+releases&fmt=json` — **200**, shape:

```json
{"video":false,"disambiguation":"live, 1996-08-14: …",
 "releases":[{"packaging":null,"status":"Bootleg","barcode":null,
   "artist-credit":[{"name":"Radiohead","joinphrase":"","artist":{"type":"Group",
     "sort-name":"Radiohead","type-id":"e431f5f6-…","country":"GB","id":"a74b1b7f-…",
     "name":"Radiohead","disambiguation":""}}],
   "quality":"normal","status-id":"1156806e-…",
   "text-representation":{"language":"eng","script":"Latn"},
   "title":"1996-08-14: Great Woods, Boston, MA, USA","packaging-id":null,
   "disambiguation":"","id":"7061dc52-…"}],
 "id":"c5b644d0-…","title":"Creep",
 "artist-credit":[{"joinphrase":"","name":"Radiohead","artist":{…}}],
 "length":null}
```

Note: with `inc=artists+releases` the **top-level `artist-credit` is present**; artist MBIDs
live at `artist-credit[i].artist.id`. `text-representation.language`/`script` only appear on
release objects.

**release-group** — extra fields vs recording: `primary-type` (`Album` | `Single` | `EP` | …),
`primary-type-id`, `secondary-types[]` (`Live`, `Remix`, …), `first-release-date`, `count`
(number of releases), `tags[]` (`{"count":12,"name":"rock"}`), `first-release-date` is
`YYYY-MM-DD`.

**artist** — extra fields: `type` (`Group` | `Person`), `country` (`"GB"`),
`area` / `begin-area` objects, `disambiguation`, `isnis[]`, `life-span`
(`{"begin":"1986","ended":null}` — **years are strings**, `ended` nullable),
`tags[]`, `aliases[]`.

**work** — extra fields: `type` (`Song`, `Composition`, …), `language`,
`iswcs[]`, `languages[]`, and `relations[]` with `type` (`composer`, `lyricist`, `performance`)
plus nested `artist` / `recording` objects. ⚠️ `work` search responses are **very large**
(~49 KB for one hit) — cap `limit=1` and truncate `relations` aggressively, or the app will
blow up memory/parse time.

### 2.6 Useful knobs (from the verified doc page)

* Search: `/<entity>?query=…&limit=…&offset=…`. **`limit` max 100, default 25.**
* Browse (pagination supported): `/<entity>?<browsing-entity>=<MBID>&limit=…&offset=…`
* `inc=` for `/recording`: `releases`, `release-groups`, `artist-credits`, `isrcs`
  — multiple joined with `+`.
* `inc=genres` / `inc=tags` give genre tags.
* Release status filter: `status=official|promotion|bootleg|pseudo-release|withdrawn|cancelled`.
  **Use `status=official`** to suppress bootlegs — the "Creep" query above returned two
  *Bootleg* releases first, which would confuse a music player.
* `/isrc/<ISRC>` → list of recordings (great for matching a local file's ISRC).
* Auth is **not** required for any read endpoint.

---

## 3. Cover Art Archive — cover images

### 3.1 ⚠️ Major change: CAA now redirects to archive.org

`coverartarchive.org` is **no longer the data host**. Real header chain captured:

```
HTTP/1.1 307 TEMPORARY REDIRECT
Location: https://archive.org/download/mbid-1a33443c-3fff-450f-8298-efbc65659d32/index.json
Access-Control-Allow-Origin: *
X-MB-Gateway: rudi

HTTP/1.1 302 Found                       ← archive.org → node
Location: https://dn711201.ca.archive.org/0/items/mbid-1a33443c-…/index.json

HTTP/1.1 200 OK
Content-Type: application/json
access-control-allow-origin: *
access-control-allow-credentials: true
access-control-allow-headers: Accept-Encoding,Accept-Language,Authorization,…,Range,Sentry-Trace,X-Requested-With
```

**Kotlin/OkHttp must `followRedirects(true)` (the default) or it will break.** A cross-origin
redirect from `coverartarchive.org` to `archive.org` to a `*.ca.archive.org` node is normal
here and is *not* an error.

⚠️ If your HTTP layer strips `Authorization` on cross-host redirect (common security default),
CAA will still work because CAA needs no auth.

### 3.2 Verified URLs and statuses

| Call | Status | Notes |
| --- | --- | --- |
| `GET https://coverartarchive.org/release-group/6e335887-…` (In Rainbows) | **307 → 200** | index.json served |
| `GET https://coverartarchive.org/release-group/f4b3beba-5c93-4d0d-a45c-123bd1d0e9e5` (nonexistent) | **404** | see §3.4 |
| `GET https://archive.org/metadata/mbid-f6efda86-9dd8-427a-b564-53ce1e7489e6` | **200** | real art present |
| `GET https://archive.org/metadata/mbid-6e335887-60ba-38f0-95af-fae7774336bf` | **200, body `{}`** | release-group MBIDs are *not* archive items |

### 3.3 JSON shape (real 200, release MBID `f6efda86-…`, trimmed)

```json
{"files":[
  {"name":"__ia_thumb.jpg","source":"original","size":21499,"format":"Item Tile"},
  {"name":"index.json","source":"original","size":856,"format":"JSON"},
  {"name":"mbid-f6efda86-…-46216761021.jpg","source":"original","size":395209,
   "format":"JPEG","md5":"435f62ce…","sha1":"0a3d4767…"},
  {"name":"mbid-f6efda86-…-46216761021_thumb250.jpg","source":"derivative",
   "format":"JPEG 250px Thumb","original":"mbid-…-46216761021.jpg","size":41661},
  {"name":"mbid-f6efda86-…-46216761021_thumb500.jpg", …, "format":"JPEG 500px Thumb"},
  {"name":"mbid-f6efda86-…-46216761021_thumb1200.jpg", …, "format":"JPEG 1200px Thumb"},
  {"name":"mbid-f6efda86-…_mb_metadata.xml", …, "format":"MusicBrainz Metadata"}],
 "files_count":14,
 "metadata":{"identifier":"mbid-f6efda86-…","collection":"coverartarchive",
   "mediatype":"image","noindex":"true","uploader":"caa@musicbrainz.org",
   "title":"In Rainbows","creator":"Radiohead","language":"eng",
   "external-identifier":["urn:mb_release_id:f6efda86-…",
                          "urn:mb_artist_id:a74b1b7f-…","urn:upc:634904032425"]},
 "server":"ia800400.us.archive.org"}
```

**Algorithm for the app:**

1. Find the best **release** MBID from MusicBrainz (not the release-group).
2. `GET https://archive.org/metadata/mbid-<releaseMbid>`.
3. If body is `{}` → **no art** (not an error, HTTP is 200).
4. Otherwise pick from `files[]` the first entry whose `name` ends with
   `_thumb500.jpg` (fall back `_thumb250.jpg`, then `-250.jpg`, then the `.jpg` whose
   `source=="original"` and `format=="JPEG"`).
5. Image URL = `https://archive.org/download/mbid-<releaseMbid>/<file.name>`.
   The redirect chain above **ends in HTTP 200 with `access-control-allow-origin: *`**,
   so Coil/Glide follows redirects by default and this works.

The legacy documented `thumbnails`/`front`/`back`/`types`/`approved` JSON from the CAA API
doc page is **no longer what the endpoint returns** — that shape now appears inside the
`index.json` behind the 307. Prefer the `/metadata/mbid-…` + filename-suffix approach above;
it is what I verified end-to-end.

### 3.4 Is "404 means no art" real? — YES, with a nuance

* Unknown MBID on `coverartarchive.org` → **404** with body
  `404 Not Found / # Not Found / No cover art found for release group <mbid>` (verified).
* Known release-group MBID with **no** art item → `coverartarchive.org` still redirects,
  but `archive.org/metadata/mbid-<release-group>` returns **HTTP 200 with `{}`**.
* Known release MBID **with** art → full file list (verified above).

So: treat **both** `404` and `200 + {}` as "no cover"; never surface an error for either.

### 3.5 Rate limits

Official doc (verified 200): *"There are currently no rate limiting rules in place at
http://coverartarchive.org."* No `X-RateLimit-*` headers were observed. Effectively unlimited.

---

## 4. Jamendo — free-licensed music with direct MP3 download

### 4.1 Is `client_id` mandatory? — **YES, mandatory**

Verified against the live API, twice:

```
GET https://api.jamendo.com/v3.0/tracks/?format=json&limit=2                 → HTTP 200
{"headers":{"status":"failed","code":5,
            "error_message":"Jamendo Api Invalid Client Id Error: Your credential is not authorized..",
            "warnings":"","results_count":0},"results":[]}

GET https://api.jamendo.com/v3.0/tracks/?client_id=2b58a9b8&format=json&…     → HTTP 200
{"headers":{"status":"failed","code":5,
            "error_message":"Jamendo Api Invalid Client Id Error: Your credential is not authorized.", …}}
```

⚠️ **Critical implementation detail: Jamendo returns HTTP 200 with an error body.**
Retrofit will parse it as success and you get an empty list with no error. **You must
inspect `headers.status` and `headers.code` on every response.**

Official error codes are documented at `https://developer.jamendo.com/v3.0/response-codes`
(referenced from the API docs nav). Observed live: **`code: 5`** = invalid client id.

### 4.2 Free signup URL — verified 200

```
https://devportal.jamendo.com/signup      → HTTP 200
```
The form asks for Username, Email, Password, Organization/Group Name, Country (required) and
an optional phone number. After signup you create an **application**, and each application
gets a **`client_id`**. Default plan is **read-only** — the entire read API is available
without approval; the Write API requires a manual "Read & Write" approval.
Terms of use: `https://devportal.jamendo.com/api_terms_of_use`.

### 4.3 ⚠️ The documented public test `client_id` is DEAD — could NOT verify a success response

`https://developer.jamendo.com/v3.0/authentication` (verified 200) states:

> "If you want to make some **quick tests**, you can use this **client id: 709fa152
> (ONLY for TESTING the read api)**."

I tested it. It is suspended:

```
GET https://api.jamendo.com/v3.0/tracks/?client_id=709fa152&format=json&limit=2&… → HTTP 200
{"headers":{"status":"failed","code":11,
  "error_message":"Jamendo Api Suspended Application Error: Your application has been suspended, please contact Jamendo",
  "warnings":"","results_count":0},"results":[]}
```

### 4.4 ⇒ **НЕ ПРОВЕРЕНО (live) for Jamendo track fields**

I could **not** obtain a working `client_id` without creating an account (out of scope, and I
will not register one). Therefore the field list below is taken from the **official
developer documentation page `https://developer.jamendo.com/v3.0/tracks` (verified HTTP 200)**,
including its own worked sample response — **not** from a request I made. Treat it as
high-confidence but formally unverified.

Fields documented for `GET /v3.0/tracks` (from the official sample response):

| Field | Example value | Meaning |
| --- | --- | --- |
| `id` | `"1848357"` | track id (**string**) |
| `name` | `"mañana será tarde"` | track title |
| `duration` | `272` | **seconds (int)** |
| `artist_id` / `artist_name` | `"421168"` / `"fankel"` | artist |
| `album_id` / `album_name` | `"368084"` / `"mañana será tarde"` | **empty for singles** |
| **`license_ccurl`** | `"http://creativecommons.org/licenses/by-nc-nd/3.0/"` | **CC licence URL** |
| `releasedate` | `"2021-04-11"` | `YYYY-MM-DD` |
| `album_image` | `"https://usercontent.jamendo.com?type=album&id=368084&width=300&trackid=1848357"` | empty for singles |
| `image` | — | **always present**; equals `album_image` for album tracks |
| **`audio`** | `"https://prod-1.storage.jamendo.com/?trackid=1848357&format=mp31&from=app-devsite"` | **STREAM** url |
| **`audiodownload`** | `"https://prod-1.storage.jamendo.com/download/track/1848357/mp32/"` | **FILE download url** |
| `audiodownload_allowed` | bool | **artist may forbid downloads**; since Aug 2020 `audiodownload` is `""` when false |
| `prourl`, `shorturl`, `shareurl` | `""`, `"https://jamen.do/t/1848357"`, `"https://www.jamendo.com/track/1848357"` | links |
| `position` | `1` | track no. in album |
| `waveform` | `"{\"peaks\":[0,0,…]}"` | **a JSON string containing JSON** — double-decode; can be ~30 KB per track |

`include=licenses` additionally returns (documented, not live-verified): `license_name`,
`license_imageurl`, `license_ccurl`. Other `include` values: `musicinfo`, `stats`, `lyrics`.

**Licence bitrate note (documented):** `audioformat` ∈ `mp31` (96 kbps) / `mp32` (VBR good) /
`ogg` / `flac`. `audio` defaults to `mp31`; `audiodownload` defaults to `mp32`. There is **no
`bitrate` field** — bitrate is implied by the format enum. Parse it from the URL
(`&format=mp31`) or from `audiodownload`.

### 4.5 Jamendo API Terms of Use — a real constraint for an Android app

Quoted from the signup page (verified 200):

> "They may not be reproduced without **JAMENDO's** express consent. **JAMENDO** hereby grants
> the Developer a license to use them, strictly limited to accessing, downloading and use them,
> for **private and personal purposes only**, within the framework of the use of the **API** by
> the Developer and for such time as you are registered for the **API**."

**This is the single biggest legal risk in this whole document.** A publicly distributed
Android app that republishes Jamendo audio is outside that grant. Usage is also monitored
("contact us if you exceed 500,000 hits"). **Recommendation: do not make Jamendo the primary
free-tracks source in a shipped app; treat it as opt-in / user-initiated, or drop it.**

---

## 5. ccMixter — Creative Commons, no key, direct file URLs

### 5.1 Live verification

| Call | Status |
| --- | --- |
| `GET /api/query?f=json&tags=ambient&limit=2` | **200** |
| `GET /api/query?f=json&tags=ambient&limit=2&lic=open&sinced=2026-01-01` | **200** |
| `GET /api/query?f=count&tags=ambient&limit=100` | **200** → `[4889]` |
| `GET /api/query?f=count&tags=ambient&lic=open&limit=100` | **200** → `[1011]` |
| `GET /api/query?f=count&tags=ambient&lic=by&limit=100` | **200** → `[985]` |
| `GET /api/query?f=count&tags=ambient&lic=nc&limit=100` | **200** → `[3110]` |
| `GET /api/query?f=count&tags=ambient&lic=pd&limit=100` | **200** → `[14]` |
| `GET /api/query?f=count&tags=ambient&lic=splus&limit=100` | **200** → `[177]` |
| `GET /api/query?f=json&limit=1&tags=electronica&lic=open&dataview=links` | **200** |
| `GET /api/query?f=json&ids=70665&dataview=links_dl` | **200** |
| `GET /api/query` docs (`https://ccmixter.org/query-api`) | **200** |
| `GET /api/doc` | **404** — docs live at `https://ccmixter.org/query-api` |

✅ **The query API works today, needs NO API key, and returns complete file URLs.**

### 5.2 ⚠️ `lic=open` — undocumented but **verified working**

`lic=open` is **not** in the official Appendix B license table (`by, nc, sa, nod, byncsa,
byncnd, s, splus, ncsplus, pd`), yet it **does** filter. Measured on `tags=ambient`:

| Filter | Count |
| --- | --- |
| *(none)* | **4889** |
| `lic=open` | **1011** |
| `lic=by` | 985 |
| `lic=nc` | 3110 |
| `lic=pd` | 14 |
| `lic=splus` | 177 |

`1011 < 4889` ⇒ it is a real filter, and `1011 < 985+3110` ⇒ it excludes the NC set.
It corresponds to dig.ccMixter's **"free for commercial use"** filter (i.e. CC-BY ∪ CC-BY-SA ∪
PD/CC0 ∪ Sampling+, excluding NonCommercial).

> **НЕ ПРОВЕРЕНО:** the exact set composition of `lic=open`. The counts above are
> consistent with the "commercial-use-permitting" reading but do not prove it (an item can
> carry several licence tags, so the parts do not sum).
> **Safe implementation:** use `lic=open` as the "commercial-safe" preset (verified to work)
> **and additionally filter client-side on `license_url`** — that field is authoritative and
> always present.

### 5.3 `sinced` / `befored` — verified working

`sinced=2026-01-01` returned results with `upload_date_format` of Mar/Sep 2026 (verified).
Format is `php strtodate`, so `"3 weeks ago"` and `"July 2006"` are also valid (per the
verified doc appendix).

### 5.4 Exact JSON fields (real 200, verbatim, trimmed)

```json
[{"upload_id":71220,
  "upload_name":"The Comfort Of Knowing",
  "upload_extra":{"usertags":"male_vocals,guitar,ambient,acoustic",
    "ccud":"big_bang_2026,remix,pell,media,ccplus,bpm_070_075",
    "systags":"non_commercial,audio,mp3,44k,stereo,CBR",
    "bpm":74.3,"relative_dir":"content/mykleanthony","featuring":"Javolenus",
    "ccplus":true,"nsfw":false,"num_reviews":2},
  "user_name":"mykleanthony",
  "upload_tags":",big_bang_2026,remix,pel…,non_commercial,audio,mp3,44k,stereo,CBR,…,",
  "upload_num_scores":3,
  "file_page_url":"https://ccmixter.org/files/mykleanthony/71220",
  "user_real_name":"mykleanthony",
  "artist_page_url":"https://ccmixter.org/people/mykleanthony",
  "license_logo_url":"https://ccmixter.org/ccskins/shared/images/lics/small-by-nc-3.png",
  "license_url":"https://creativecommons.org/licenses/by-nc/4.0/",
  "license_name":"Attribution Noncommercial (4.0)",
  "upload_date_format":"Wed, Sep 30, 2026 @ 9:47 AM",
  "files":[{"file_id":130269,"file_upload":71220,
    "file_name":"mykleanthony_-_The_Comfort_Of_Knowing.mp3",
    "file_nicname":"mp3",
    "file_format_info":{"media-type":"audio","format-name":"audio-mp3-mp3",
      "default-ext":"mp3","mime_type":"audio/mpeg","sr":"44k","ch":"stereo",
      "ps":"4:53","br":"CBR"},
    "file_extra":{"sha1":"REO3FCRZZQUOLBCIN2PKDCLUHJCI6L4U"},
    "file_filesize":" (6.72MB)",        ← note the LEADING SPACE, human-readable, not parseable
    "file_order":0,"file_is_remote":0,"file_num_download":0,
    "download_url":"https://ccmixter.org/content/mykleanthony/mykleanthony_-_The_Comfort_Of_Knowing.mp3",
    "local_path":"/var/www/ccmixter/content/mykleanthony/mykleanthony_-_The_Comfort_Of_Knowing.mp3",
    "file_rawsize":7047254}],
  "upload_description_plain":"…",
  "upload_description_html":"…"}]
```

**Where the audio URL lives:** `files[i].download_url`, plus a convenience
top-level `download_url` in the `dataview=links_dl` output (verified: `"download_url"` at the
upload level is present in that dataview).

**Where the licence lives:** `license_url`, `license_name`, `license_logo_url`.

**Duration:** **NOT a numeric field** — `files[i].file_format_info.ps` = `"4:53"` (`mm:ss`).
`file_format_info.br` = `"CBR"`/`"VBR"`, `sr` = `"44k"`, `ch` = `"stereo"`.
`bpm` is `upload_extra.bpm` and is sometimes `""` (string, may be empty).

⚠️ **`file_filesize` is human text** like `" (6.72MB)"` — use **`files[i].file_rawsize`**
(bytes, integer) for progress bars and quota checks.

⚠️ **Cover art:** there is **no cover field** in the API output. The upload page embeds an
avatar image at `https://ccmixter.org/content/<user>/<user>93x94.png` (observed in the HTML
of `/files/softmartin/70665`). Using that as artwork is scraping-by-convention, not an API
field — **НЕ ПРОВЕРЕНО** as a stable contract.

### 5.5 ⚠️ Do downloads need an API key or a page token? — No key, but **403 on bare GET**

`download_url` is a direct, plain HTTPS file URL — **no token, no key, no session**. Verified
request:

```
GET https://ccmixter.org/content/softmartin/softmartin_-_seaNsynths.mp3
→ HTTP/1.1 403 Forbidden
   Server: Apache/2.2.22 (Debian) PHP/5.4.36
   Content-Type: text/html   Content-Length: 30
```

The same URL is what ccMixter's own player JS uses
(`$('_ep_70665').href = 'https://ccmixter.org/content/softmartin/softmartin_-_seaNsynths.mp3'`
— verified in the HTML of the file page). So the URL is canonical; the **403 comes from
origin-side filtering of non-browser clients**.

> **НЕ ПРОВЕРЕНО:** which header fixes it. I cannot set `User-Agent` or `Referer` with the
> available tooling. **Hypothesis (must be tested on-device):** send a browser-like
> `User-Agent` **and** `Referer: https://ccmixter.org/files/<user>/<id>`.
> **Fallback if 403 persists:** do not hotlink at all — open `file_page_url` in a Custom Tab
> and let the user download from the site, which is unambiguously permitted.

### 5.6 CORS

Real headers from `GET /api/query?f=json&tags=ambient&limit=1`:

```
HTTP/1.1 200 OK
Server: Apache/2.2.22 (Debian) PHP/5.4.36-0+deb7u3
Vary: Origin,Accept-Encoding
Content-Type: text/plain          ← note: text/plain, not application/json
Content-Encoding: gzip
```

❌ **No `Access-Control-Allow-Origin`.** `Vary: Origin` is present but no ACAO is emitted.
Also the declared content type is `text/plain`, not `application/json`.
**For a native Android client both are irrelevant** — but if any web/companion UI is planned,
ccMixter cannot be called from a browser page.

### 5.7 Rate limits

**НЕ ПРОВЕРЕНО** — ccMixter publishes no limits and returned no rate-limit headers. But the
server is **Apache 2.2 / PHP 5.4 on Debian 7** — visibly an old stack. Treat as fragile:
cache aggressively, max ~1 request/second, and never poll.

### 5.8 The "dig" API

`dig.ccmixter.org` (verified 200) is a **browsing front-end**, not a separate API. Its own
React client is documented in the sidebar as
`https://github.com/victor-stone/dig-react`. Its filters map onto the same
`https://ccmixter.org/api/query` endpoints with the parameters in §5.2. **Use `/api/query`.**

---

## 6. FreePD — ⛔ **DEAD. Do not implement.**

### 6.1 The site is closed

`GET https://freepd.com/` → **HTTP 200**, body is a closure notice (verbatim):

> **FreePD.com - Site Closed**
> "# The Music Has Moved On. … After 17 years of sharing millions of free-to-use, Public Domain
> music downloads with creators worldwide, we have officially taken the service offline. The
> hosting and maintenance of the site have ceased. … \[freepd.com\] is now permanently closed.
> … 2008-2025"

### 6.2 robots.txt — exists but is EMPTY (verified via headers)

```
GET https://freepd.com/robots.txt
HTTP/1.1 200 OK
Server: Apache/2.4.56 (Debian)
Content-Type: text/plain
Last-Modified: Sat, 31 Jan 2026 02:08:57 GMT
Cache-Control: max-age=86400
Transfer-Encoding: chunked
(no Content-Length, zero-byte body)
```

`www.freepd.com/robots.txt` → identical **200 / empty**. So the file exists, has
**zero directives**, and `Content-Length` is absent because the body is empty.

### 6.3 API? — None, and moot.

There is no JSON feed, no RSS, no documented API — **and there is nothing left to query.**
**Recommendation: remove FreePD from the source list entirely.** Any scraping plan is
unnecessary (nothing to scrape) and any "FreePD mirror" is a third-party re-hosting of
public-domain material with unclear provenance — do not link to it.

If you need a **public-domain** free-music replacement, use **Internet Archive** with
`licenseurl:*publicdomain*` (§7) — verified working, 2395 matching netlabel items.

---

## 7. Internet Archive — public-domain + CC audio, no key

### 7.1 Live verification

| Call | Status | Result |
| --- | --- | --- |
| `GET /advancedsearch.php?q=collection:(opensource_audio) AND mediatype:(audio)&fl[]=identifier&fl[]=title&fl[]=licenseurl&fl[]=creator&rows=3&page=1&output=json` | **200** | `numFound: 2967588` |
| `GET /advancedsearch.php?q=collection:(netlabels) AND licenseurl:(*creativecommons.org*)&fl[]=identifier&fl[]=title&fl[]=licenseurl&fl[]=creator&fl[]=year&rows=3&page=1&output=json` | **200** | `numFound: 62151` |
| `GET /advancedsearch.php?q=collection:(netlabels) AND licenseurl:(*publicdomain*)&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=licenseurl&fl[]=year&fl[]=item_size&rows=3&page=1&output=json` | **200** | `numFound: 2395` |
| `GET /metadata/irrational-activity-catua` | **200** | see §7.3 |
| `GET /metadata/tpdm087` | **200** | has `licenseurl` |
| headers for `GET /download/tpdm087/tpdm087_08.01.18_Terrorist.mp3` | **302 → 200** | `Content-Type: audio/mpeg`, `Content-Length: 75579284`, `access-control-allow-origin: *` |

### 7.2 Search response shape (real 200, verbatim)

```json
{"responseHeader":{"status":0,"QTime":677,
   "params":{"query":"collection:(opensource_audio) AND mediatype:(audio)",
             "qin":"collection:(opensource_audio) AND mediatype:(audio)",
             "fields":"identifier,title,licenseurl,creator",
             "wt":"json","rows":3,"start":0}},
 "response":{"numFound":2967588,"start":0,"docs":[
   {"creator":"Dr. Lacy Couch","identifier":"thems-fightn-words-part-2",
    "title":"Thems Fightn Words Part 2"},
   {"identifier":"iron-man-mark-ii-test-flight","title":"Iron Man Mark II Test Flight"},
   {"identifier":"irrational-activity-catua","title":"Irrational Activity - Catua"}]}}
```

⚠️ **`licenseurl` is simply OMITTED from docs that have no licence** (see the first query).
Always handle it as nullable — never assume the field is present just because you asked for it.

Working licence filters (all verified):

```
licenseurl:(*creativecommons.org*)        → 62151 items in collection:netlabels
licenseurl:(*publicdomain*)                → 2395  items in collection:netlabels
```

Observed `licenseurl` values: `http://creativecommons.org/licenses/by-nc-nd/3.0/`,
`http://creativecommons.org/licenses/by-nc-sa/3.0/us/`,
`http://creativecommons.org/publicdomain/mark/1.0/`,
`http://creativecommons.org/publicdomain/zero/1.0/`.

Use collections `netlabels`, `opensource_audio`, `etree` (audio), `audio_music`.

### 7.3 `/metadata/<identifier>` shape (real 200, `tpdm087`, trimmed)

```json
{"d1":"ia600406.us.archive.org","d2":"ia800406.us.archive.org","dir":"/9/items/tpdm087",
 "files":[
  {"name":"tpd087large.jpg","source":"original","format":"JPEG","size":660697},
  {"name":"tpdm087_08.01.18_Terrorist.mp3","source":"original","format":"VBR MP3",
   "title":"[tpdm087] 08.01.18 Terrorist","creator":"Take Pills Die Records",
   "album":"[tpdm087] 08.01.18 Terrorist","artist":"Take Pills Die Records",
   "md5":"e57e213c…","sha1":"566ad65a…","size":75579284,
   "length":"3149.14","height":"960","width":"960","genre":"Folk","track":"01"},
  {"name":"tpdm087_meta.xml","source":"metadata","format":"Metadata"}],
 "files_count":10,"item_size":76892163,
 "metadata":{"identifier":"tpdm087","title":"Take Pills Die - 08.01.18 Terrorist [tpdm087]",
   "creator":"Andrew Cauthen","mediatype":"audio",
   "collection":["takepillsdie","netlabels"],
   "date":"2008-01-18","year":"2008",
   "subject":"IDM;Ambient;Avant Garde;Experimental Electronic;Folk;Pop;Drone;…",
   "licenseurl":"http://creativecommons.org/licenses/by-nc-sa/3.0/us/"},
 "server":"ia800406.us.archive.org"}
```

**How to get audio files:**
1. `files[]` → pick entries where `format` ∈ `{"VBR MP3", "MP3", "128Kbps MP3", "Ogg Vorbis",
   "Flac", "WAV"}` (an item usually has exactly one audio derivative per track).
2. Download URL = **`https://archive.org/download/<identifier>/<encodeURIComponent(files[i].name)>`**
   — verified below.
3. `files[i].length` = **seconds as a STRING** (`"3149.14"`), `files[i].size` = bytes (int),
   `files[i].track` = track number string.
4. Cover art = first `files[]` entry with `format == "JPEG"` (excluding `_thumb`/`__ia_thumb`).

**License fields:** `metadata.licenseurl` (nullable), `metadata.collection` (may be a
**string or an array** — observed both `"opensource_audio"` and `["takepillsdie","netlabels"]`),
`metadata.creator`, `metadata.title`, `metadata.date`/`metadata.year`,
`metadata.publicdate`/`metadata.addeddate`.

### 7.4 Download URL verified end-to-end (headers only, no audio saved)

```
GET https://archive.org/download/tpdm087/tpdm087_08.01.18_Terrorist.mp3

HTTP/1.1 302 Found
Access-Control-Allow-Origin: *
Location: https://dn711006.ca.archive.org/0/items/tpdm087/tpdm087_08.01.18_Terrorist.mp3
Onion-Location: https://archivep75mbjunhxc6x4j5mwjmomyxb573v42baldlqu56ruil2oiad.onion/download/…
Strict-Transport-Security: max-age=15724800

HTTP/1.1 200 OK
Server: nginx
Content-Type: audio/mpeg
Content-Length: 75579284
Accept-Ranges: bytes                      ← HTTP Range supported → resumable downloads
access-control-allow-origin: *
access-control-allow-headers: …,Range,…
```

✅ **No key, no token, CORS `*`, `Accept-Ranges: bytes`.** This is the most robust download
path of everything in this document.

### 7.5 Rate limits & CORS

* ❌ **НЕ ПРОВЕРЕНО** — a numeric rate limit. No rate-limit headers observed.
  IA's documented guidance is conservative use; for an app, cache metadata aggressively and
  never poll.
* ✅ CORS: `Access-Control-Allow-Origin: *` on both `advancedsearch.php`, `/metadata/*`,
  and `/download/*`.

---

## 8. Ranking table

| Source | (a) Metadata search quality | (b) Free-licensed audio download | (c) API key needed | (d) Public-endpoint reliability | Notes |
| --- | --- | --- | --- | --- | --- |
| **iTunes Search API** | ★★★★★ Best-in-class. Clean, fast, unambiguous; `trackName`/`artistName`/`collectionName`, artwork, duration, genre, release date, explicit flag. | ⛔ **None** (only a 30 s AAC preview — not a file, terms-restricted) | **No** | ★★★★★ Very high. `Access-Control-Allow-Origin: *`, CDN-cached (`max-age=86400`), no rate-limit headers | Commercial catalogue only. For **identifying** a track, nothing beats it. |
| **MusicBrainz** | ★★★★☆ Excellent and *open* — but heavy payloads and many bootleg/duplicate rows (`"status":"Bootleg"`); `length` often `null` | ⛔ **None** | **No** (auth only for write) | ★★★★☆ High, but hard rules: meaningful `User-Agent` + **1 req/s per IP**, else **503**. Now exposes `X-RateLimit-*` | The **only** source that gives you a durable `MBID` — the join key that unlocks Cover Art Archive and IA's MB-indexed items. |
| **Cover Art Archive** | ⛔ Not a search API — MBID → images only | ⛔ Images only | **No** | ★★★★★ Effectively unlimited (official: *"no rate limiting rules in place"*) | ⚠️ **Now redirects `coverartarchive.org` → `archive.org` → node.** Must follow redirects. 404 **and** `200 + {}` both mean "no art". |
| **Jamendo** | ★★★☆☆ Decent (tags, moods, duration) | ★★★★ **Yes — direct `audiodownload` MP3/OGG/FLAC** | **YES — `client_id` mandatory** (returns HTTP 200 + `code:5`/`code:11`) | ★★☆☆☆ Fragile. The documented public test id `709fa152` is **suspended**. All content under **"private and personal purposes only"** terms. | ⛔ **Do not ship as a primary source.** ToS conflict + registration friction + no anonymous access. |
| **ccMixter** | ★★★☆☆ Tag/author search only; no title search beyond tags; no cover field | ★★★★ **Yes — `files[].download_url`**, mp3/flac/ogg/zip, every item CC-licensed | **No** | ★★★☆☆ Works today, but Apache 2.2 / **PHP 5.4 / Debian 7** stack; no rate-limit headers; **bare GET on the file URL → 403** | Best anonymous CC source. `lic=open` verified working (1011/4889 ambient). Filter client-side on `license_url` too. |
| **FreePD** | — | — | — | **⛔ DEAD** | **Site permanently closed (2008–2025).** `robots.txt` exists but is **empty** (zero directives). Nothing to integrate. |
| **Internet Archive** | ★★★☆☆ Item-level search, not artist/track search; heavy metadata quality | ★★★★★ **Yes** — direct `/download/<id>/<file>`, Range-supported, any format, PD + CC | **No** | ★★★★☆ Very high. `Access-Control-Allow-Origin: *`, multi-server, onion mirror. Licence field `metadata.licenseurl` is **nullable**. | Largest verified corpus: 2 967 588 `opensource_audio` items, 62 151 CC-licensed netlabels, 2 395 public-domain netlabels. |

---

## 9. Recommendation — what the app should actually use

**For the "Free tracks" tab: (1) Internet Archive, (2) ccMixter, (3) MusicBrainz for identity/artwork.**

**For track-title lookup: iTunes Search first, MusicBrainz second (and use MB's MBID to get art).**

**Drop FreePD. Treat Jamendo as optional/off-by-default.**

### 9.1 Source #1 — Internet Archive (primary free-tracks backend)

**Why:** it is the only source that simultaneously gives (i) a genuinely free licence, (ii) an
anonymous, keyless, CORS-open endpoint, (iii) `Accept-Ranges: bytes` so downloads resume,
(iv) a huge verified corpus (2 967 588 audio items; 2 395 public-domain netlabels; 62 151
CC-licensed), and (v) `md5`/`sha1` per file so the app can verify integrity — which your
workspace rules require. Its weak point is search ergonomics, which the Kotlin layer below
fixes.

**Request 1 — search:**

```
GET https://archive.org/advancedsearch.php
      ?q=collection%3A%28netlabels%29+AND+mediatype%3A%28audio%29
        +AND+licenseurl%3A%28*creativecommons.org*%29
        +AND+title%3A%28%22QUERY%22%29
      &fl%5B%5D=identifier &fl%5B%5D=title &fl%5B%5D=creator
      &fl%5B%5D=licenseurl  &fl%5B%5D=year      &fl%5B%5D=item_size
      &sort%5B%5D=downloads+desc
      &rows=25 &page=1 &output=json
```

Swap `licenseurl:*creativecommons.org*` → `*publicdomain*` for a PD-only toggle, or drop the
clause to include unlicensed items (show a warning badge).

**Request 2 — files:**

```
GET https://archive.org/metadata/{identifier}
```

**Request 3 — download:**

```
GET https://archive.org/download/{identifier}/{urlEncodedFileName}
```

**Field-by-field Kotlin mapping:**

| JSON path | Kotlin property | Type | Notes |
| --- | --- | --- | --- |
| `response.numFound` | `FreeTrack.totalHits` | `Long` | |
| `response.start` | `FreeTrack.start` | `Int` | |
| `response.docs[i].identifier` | `sourceId` | `String` | **primary key**; feeds every later call |
| `response.docs[i].title` | `title` | `String` | may contain HTML entities (verified `\u00ab`, `\u00df`) → run through `Html.fromHtml` / unescape |
| `response.docs[i].creator` | `artistName` | `String?` | string **or** array in some items → normalise to `List<String>` |
| `response.docs[i].licenseurl` | `licenseUrl` | `String?` | **may be absent entirely** |
| `response.docs[i].year` | `year` | `Int?` | string or int |
| `response.docs[i].item_size` | `totalBytes` | `Long?` | |
| `metadata.files[i].name` | `audioFileName` | `String` | **URL-encode** when building the download URL |
| `metadata.files[i].format` | `mimeHint` | `String` | match `{"VBR MP3","MP3","128Kbps MP3","Ogg Vorbis","Flac","WAV"}` |
| `metadata.files[i].size` | `fileBytes` | `Long` | for the progress bar |
| `metadata.files[i].length` | `durationSec` | `Double?` | **string** `"3149.14"` → `toDoubleOrNull()` |
| `metadata.files[i].track` | `trackNo` | `Int?` | string |
| `metadata.files[i].md5` / `sha1` | `md5` / `sha1` | `String?` | verify after download |
| `metadata.files[i].format == "JPEG"` (non-thumb) | `coverFileName` | `String?` | cover = `…/download/{id}/{name}` |
| `metadata.collection` | `collections` | `List<String>` | **string OR array** — handle both |

**Provenance sidecar (write it next to the file, per the workspace rules):**
`source = "archive.org"`, `identifier`, `license_url`, `creator`, `title`, `sha1`.

### 9.2 Source #2 — ccMixter (secondary free-tracks backend)

**Why:** it is the best *anonymous* Creative Commons source — no key, no registration, every
item carries a machine-readable `license_url`/`license_name`, and `files[].download_url` is a
plain static path. It fills the gap where Internet Archive has no item for a given genre.
Its two real risks — the 403 on bare file GET and the ancient server stack — both have
mitigations in §9.5.

**Request:**

```
GET https://ccmixter.org/api/query
      ?f=json
      &tags={tag1}+{tag2}
      &lic=open                 ← verified working: licences permitting commercial use
      &limit=20
      &sinced={YYYY-MM-DD}      ← optional recency filter
```

Also useful (verified): `&s=<text>` free-text search, `&u=<username>`, `&reqtags=`,
`&sort=date|name|score|lic`, `&dataview=links_dl` (adds a top-level `download_url`),
`&f=count` (returns just `[N]` — handy for building the category facet list).

**Field-by-field Kotlin mapping:**

| JSON path | Kotlin property | Type | Notes |
| --- | --- | --- | --- |
| `[i].upload_id` | `sourceId` | `Long` | |
| `[i].upload_name` | `title` | `String` | |
| `[i].user_name` | `artistLogin` | `String` | |
| `[i].user_real_name` | `artistName` | `String?` | the human-facing name |
| `[i].artist_page_url` | `artistUrl` | `String` | attribution link |
| `[i].file_page_url` | `pageUrl` | `String` | attribution page — **required for CC-BY compliance** |
| **`[i].license_url`** | `licenseUrl` | `String` | authoritative; filter on this client-side too |
| `[i].license_name` | `licenseName` | `String` | e.g. `"Attribution Noncommercial (4.0)"` |
| `[i].license_logo_url` | `licenseLogoUrl` | `String` | small PNG badge |
| `[i].upload_date_format` | `uploadedAt` | `String?` | display-only, **not** parseable |
| `[i].upload_num_scores` | `scoreCount` | `Int` | |
| `[i].upload_extra.bpm` | `bpm` | `Double?` | may be `""` |
| `[i].upload_extra.ccplus` | `isCcPlus` | `Boolean` | ccMixter "royalty free" tier |
| `[i].upload_extra.nsfw` | `isNsfw` | `Boolean` | filter out |
| `[i].upload_tags` | `tags` | `List<String>` | leading/trailing commas — `split(',').map(trim).filter{isNotEmpty}` |
| **`[i].files[j].download_url`** | `audioUrl` | `String` | **the file** |
| `[i].files[j].file_name` | `fileName` | `String` | |
| `[i].files[j].file_format_info.ps` | `durationSec` | `Int` | `"4:53"` → parse `mm:ss` |
| `[i].files[j].file_format_info.mime_type` | `mimeType` | `String` | e.g. `"audio/mpeg"` |
| `[i].files[j].file_format_info.br` | `bitrateKind` | `String?` | `"CBR"` / `"VBR"` — **no numeric bitrate field exists** |
| `[i].files[j].file_format_info.sr` | `sampleRate` | `String?` | `"44k"` |
| **`[i].files[j].file_rawsize`** | `fileBytes` | `Long` | use this, **not** `file_filesize` |
| `[i].files[j].file_extra.sha1` | `sha1` | `String?` | integrity |

Pick the file with `file_format_info.media-type == "audio"` and the best of
`mp3 > flac > ogg`; ignore `application/zip` and `archive/zip` entries.

### 9.3 Source #3 — MusicBrainz (identity, not audio) + Cover Art Archive (artwork)

**Why:** MusicBrainz is the only keyless source that mints a durable **MBID**, and the MBID
is the join key for Cover Art Archive. MusicBrainz + CAA turn a bare title into
"title / artist / album / year / duration / genre + cover art" with no commercial API key and
no ToS friction. This is what powers the **track-identification** half of the app and the
artwork column of the free-tracks tab (when an IA/ccMixter item has no cover, or when the user
searches a known song).

**Requests:**

```
# 1) search (enforce ≥1100 ms spacing to all MB hosts)
GET https://musicbrainz.org/ws/2/recording
      ?query=recording%3A%22QUERY%22+AND+artist%3A%22ARTIST%22
      &fmt=json&limit=25
      → then re-filter releases with status=official, or add &inc=... in step 2

# 2) enrich one hit (optional)
GET https://musicbrainz.org/ws/2/recording/{recordingMbid}?inc=artists+releases&fmt=json

# 3) cover art — MUST follow redirects
GET https://archive.org/metadata/mbid-{releaseMbid}      → pick files[].name (…_thumb500.jpg)
GET https://archive.org/download/mbid-{releaseMbid}/{name}
```

**Field-by-field Kotlin mapping:**

| JSON path | Kotlin property | Type | Notes |
| --- | --- | --- | --- |
| `recordings[0].count` | `totalHits` | `Int` | envelope-level |
| `recordings[0].offset` | `start` | `Int` | |
| `recordings[i].id` | `mbid` | `String` | recording MBID |
| `recordings[i].title` | `title` | `String` | |
| `recordings[i].score` | `relevance` | `Int` | 0–100; **sort by it**, do not trust server order alone |
| `recordings[i].disambiguation` | `versionHint` | `String?` | `"live, 1996-08-14: …"` — **show this in the UI** |
| **`recordings[i].length`** | `durationMs` | `Long?` | **milliseconds, frequently `null`** |
| `recordings[i].artist-credit[0].name` | `artistName` | `String?` | join `artist-credit` with `joinphrase` when present |
| `recordings[i].artist-credit[0].artist.id` | `artistMbid` | `String?` | |
| `recordings[i].releases[0].id` | `releaseMbid` | `String?` | **the CAA key** |
| `recordings[i].releases[0].title` | `albumTitle` | `String?` | |
| `recordings[i].releases[0].status` | `releaseStatus` | `String?` | **keep only `"Official"`** |
| `recordings[i].releases[0].date` | `releaseDate` | `String?` | add `inc=...`/browse if absent |
| `recordings[i].releases[0].release-group.primary-type` | `releaseType` | `String?` | `Album`/`Single`/`EP` |
| `recordings[i].releases[0].media[0].track[0].number` | `trackNo` | `String?` | **string** |
| `release-groups[i].first-release-date` | `releaseDate` | `String?` | `YYYY-MM-DD` |
| `release-groups[i].primary-type` / `secondary-types[]` | `releaseType` / `subTypes` | `String?` / `List<String>` | |
| `artists[i].name`, `.type`, `.country`, `.life-span.begin`, `.disambiguation` | `artistName`/`artistType`/`artistCountry`/`activeFrom`/`artistHint` | | `life-span` years are **strings**, `ended` nullable |
| `works[i].title`, `.type`, `.iswcs[]` | `workTitle`/`workType`/`iswcs` | | response is **huge** — cap `limit=1` |

**CAA mapping:** `files[].name` (string containing `_thumb500.jpg` / `_thumb250.jpg`) →
`coverUrl = "https://archive.org/download/mbid-$releaseMbid/${name}"`. Treat **HTTP 404** *and*
**HTTP 200 with body `{}`** as "no cover".

### 9.4 Cross-cutting Kotlin implementation notes

* **Uniform UA interceptor** (required by MusicBrainz §2.3, good hygiene everywhere):
  `AMPS/<version> ( <url-or-email> )`. **Never** ship the default
  `Apache-HttpClient` UA.
* **Rate limiting:** a per-host token bucket — MusicBrainz **1.1 s** spacing (and a 503 →
  exponential backoff, honouring `X-RateLimit-Reset`), ccMixter **1.0 s**, iTunes ≥350 ms
  debounce, archive.org unthrottled but cached.
* **Caching:** `Cache-Control: max-age=86400` from iTunes and
  `X-Fastcgi-Cache` from archive.org mean disk-cached HTTP is the single biggest win.
  DiskLruCache keyed on the full URL.
* **Redirects:** `followRedirects(true)` (default) is mandatory for CAA and archive.org
  `/download/*`.
* **Retrofit gotchas:** mark all Jamendo fields nullable and add an interceptor that throws
  when `headers.status != "success"`; decode `waveform` twice (JSON string inside a JSON
  string); `ccMixter` returns `Content-Type: text/plain`, so don't gate on the media type.
* **Duration parsing:** MusicBrainz = ms `Long?`; ccMixter = `"m:ss"` `String`; Jamendo = seconds
  `Int`; archive.org = `"3149.14"` seconds `String`. Normalise all four to one
  `@Serializable data class Duration(val seconds: Int)`.
* **Provenance:** every imported file must persist
  `{source, sourceId, licenseUrl, licenseName, authorName, authorUrl, pageUrl, fileSha1}` and
  render attribution from it. CC-BY **and CC-BY-SA require visible attribution** — this is a
  licence obligation, not a nicety.

### 9.5 Known gaps that must be tested on a real device

| Gap | Why it is unverified | Test |
| --- | --- | --- |
| ccMixter `download_url` 403 bypass | I cannot set `User-Agent`/`Referer` | Request with `User-Agent: Mozilla/5.0 …` + `Referer: <file_page_url>`; if still 403, open `file_page_url` in a Custom Tab |
| ccMixter `lic=open` exact composition | Undocumented; counts overlap | Fetch 200 rows with `lic=open`, tally distinct `license_url` values |
| ccMixter cover art URL | Not an API field | `https://ccmixter.org/content/<user>/<user>93x94.png` — confirm it still 200s |
| Jamendo track fields | Public test `client_id` is suspended (code 11) | Register at `devportal.jamendo.com/signup`, then re-run §4.4 |
| iTunes / ccMixter numeric rate limits | No limits published or headers returned | Measure in staging; back off politely |
