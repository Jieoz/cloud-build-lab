# X Video Catcher (probe stage)

An LSPosed module targeting the X (Twitter) Android client. **Stage 1 is a read-only
probe**: it does not add a download button and does not modify the host app. Its job is
to find out where X hands a playable media URL to its player on a real device, which is
the prerequisite for any download feature.

## Target

| Item | Value |
| --- | --- |
| Host app | `com.twitter.android` 12.11.1-release.0 |
| Device | Android 14 (API 34) |
| Framework | LSPosed (legacy Xposed API 82) |

## Why a probe first

X ships an R8-obfuscated release build wrapped in pairip integrity protection. App class
and method names change between versions and carry no stable meaning, so hooking them by
name is not maintainable. Every hook here therefore attaches to a boundary that *cannot*
be renamed:

| Layer | Hook point | Status on X 12.11.1 |
| --- | --- | --- |
| A | `java.net.URL(String)` | **active** — carried 85 of 86 captured hits |
| B | `MediaPlayer.setDataSource(String)` | active, no hits (X uses its own player) |
| C | `org.chromium.net.CronetEngine.newUrlRequestBuilder` | **absent** — no Cronet in this build |
| D | `okhttp3.Request$Builder.url` | active — OkHttp names survived obfuscation |

Each layer attaches independently. A missing class is recorded in the summary line and
skipped; only an unexpected failure gets a stack trace, so a launch does not look like
it errored just because this build of X lacks Cronet.

Only URLs that look like media are recorded. The path must look like media *and* the
host must be known — matching the host alone logged `video.twimg.com/robots.txt` on
every launch. Filtering happens before any stack trace is materialised, since these
callbacks run on the app's network hot path.

## What the probe found

From a real capture on the target device (86 media hits, 7 videos, all via OkHttp →
`java.net.URL`):

```
https://video.twimg.com/<kind>/<id>/pl/<key>.m3u8                     master playlist
https://video.twimg.com/<kind>/<id>/pl/avc1/1920x1080/<key>.m3u8      video variant
https://video.twimg.com/<kind>/<id>/pl/mp4a/128000/<key>.m3u8         audio variant
https://video.twimg.com/<kind>/<id>/vid/avc1/0/0/1920x1080/<k>.mp4    video init segment
https://video.twimg.com/<kind>/<id>/vid/avc1/0/3000/1920x1080/<k>.m4s video segment
https://video.twimg.com/<kind>/<id>/aud/mp4a/0/3000/128000/<k>.m4s    audio segment
```

Consequences for the download stage:

- **Audio and video are separate tracks.** Fetching video segments alone yields a silent
  file; both tracks have to be taken and muxed.
- **The master playlist is the only URL worth keeping.** Every resolution and the audio
  track are reachable from it.
- **The player requests several resolutions while adapting**, so "what was playing" is
  not "the best available". `MediaUrls.highestResolution` ranks by pixel count, not
  height — one capture contained both 720x1280 and 1920x1080, and comparing height would
  rank the portrait clip higher.
- `kind` observed so far: `amplify_video` only. `ext_tw_video` (user uploads) and
  `tweet_video` (GIFs) are handled by the filter but not yet confirmed against a capture.

## Getting the log out

The hooks run inside X's process, under X's UID. They cannot write into this module's
storage, and anything written into X's own private dirs needs root to retrieve. So the
host process writes the log itself, into shared storage:

```
X process
  hook -> ProbeLog (queue) -> ProbeSink -> Download/XVideoCatcher/xvc-probe-YYYYMMDD.jsonl
```

- **Queued, not synchronous.** A file write per URL on the network hot path is not
  acceptable, so records are batched by a low-priority daemon thread. The queue is
  bounded and drops on overflow: losing records under a flood is fine, stalling X is not.
- **No provider, by design.** An earlier version routed records to a `ContentProvider`
  in this module. That cannot work: since Android 11, a process can only resolve a
  `content://` authority it declares in its own `<queries>`, and X's manifest is not
  ours to change — so every insert failed to resolve and all records were dropped
  silently. A CI check now fails the build if the APK declares any provider authority.
- **The writing app owns the file**, so no storage permission is involved on API 29+.
  One file per day keeps growth bounded.
- **The module UI cannot read the log.** Android only shows a non-media file in Downloads
  to the app that created it, and X is the writer. The UI therefore reports status and
  the exact path instead of pretending to export.

To send a log: open `Download/XVideoCatcher/`, long-press the file, share.

Quick check without a file manager, if adb is handy:

```
adb shell ls -l /sdcard/Download/XVideoCatcher/
```

## Signing

Builds are signed with a **fixed** key held in CI secrets (`XVC_KEYSTORE_B64` and
friends), not the per-machine debug key Gradle generates by default. That default is
regenerated on every CI runner, so consecutive builds had different signatures and
Android refused to install one over another — every update meant uninstalling first,
losing the LSPosed scope selection with it.

CI **fails** if the keystore secret is missing and asserts the built APK's certificate
digest equals the pinned value, so a silent fallback to a throwaway debug key cannot
ship. Changing the key later would again require uninstalling everywhere; treat the
pinned digest in the workflow as frozen.

> Builds before `0.3.0-probe` used a throwaway debug key. Upgrading from `0.1.0` or
> `0.2.0` requires one uninstall; from `0.3.0` on, in-place upgrade works.

## Build

Cloud CI only — see `.github/workflows/build-x-video-catcher.yml`. The workflow runs the
unit tests, reports real per-class test counts (Gradle is silent on success, so a green
step alone would not prove any test ran), builds the APK, verifies the signing key, then
asserts the module contract on the built artifact: `assets/xposed_init` names the entry
class, `xposedminversion` is present, no provider authority is declared, and the Xposed
API is *not* bundled into the APK (a bundled copy breaks hook dispatch).

Tests cover the JSONL line contract (an unescaped newline splits a record and corrupts
the log), the URL filter's pass and drop sets, and — under Robolectric, against a real
Android context — `ProbeSink` writing, appending without truncation, and the advertised
path matching where it actually writes. Those assert on bytes on disk rather than a
mocked resolver: a mocked sink is precisely what would have reported success while
dropping every record.

Do not add `android.aapt2FromMavenOverride` to `gradle.properties` — that is a
host-specific path and the Maven aapt2 is x86_64-only.

Note: `scripts/cloud_build_register_project.py` regenerates this README and the workflow
from templates. Re-apply the unit-test and APK-contract steps after re-registering.

## Usage

1. Install the APK, enable the module in LSPosed, and set its scope to X.
2. Force-stop X so it restarts with the module attached.
3. Check `Download/XVideoCatcher/` — the log file is written on attach, before you play
   anything.
4. Play a video, then share the file.

If the folder is absent, check the LSPosed log for `XVideoCatcher` before concluding the
module did not load. Every record goes to both the file and the LSPosed log, so the
LSPosed side still shows the attach line if the file path itself is what broke — which is
exactly what happened in `0.3.0` and below, where records produced before the host
Application existed were discarded and the folder never appeared on a working module.

The launcher activity reports whether the module is active in *its own* process (the
framework rewrites `ModuleStatus.isModuleActive()`, so "not active" is a real reading,
not a stored flag). It cannot confirm the hook attached inside X — the log file existing
is what proves that, which is why it is written on attach rather than on first URL.

## Status

Stage 1 (probe) implemented, with log export. The download feature is deliberately not
built yet: it should be designed against the hook points the probe actually confirms
on-device, not against guesses.
