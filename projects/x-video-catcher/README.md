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

| Layer | Hook point | Why it survives obfuscation |
| --- | --- | --- |
| A | `java.net.URL(String)` | platform class |
| B | `MediaPlayer.setDataSource(String)` | platform class |
| C | `org.chromium.net.CronetEngine.newUrlRequestBuilder` | loaded reflectively, paired with native code |
| D | `okhttp3.Request$Builder.url` | only present if names survived; its absence is itself a finding |

Each layer attaches independently. A missing class is logged and skipped rather than
thrown, so one failure cannot take down the probe or the host app.

Only URLs that look like media are recorded (`video.twimg.com`, `amplify_video`,
`.m3u8`, `.mp4`, segments). These callbacks run on the app's network hot path, so
filtering happens before any stack trace is materialised.

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

## Build

Cloud CI only — see `.github/workflows/build-x-video-catcher.yml`. The workflow runs the
unit tests, builds the APK, then asserts the module contract on the built artifact:
`assets/xposed_init` names the entry class, `xposedminversion` is present, and the Xposed
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
   anything. If it is absent, the module did not load; nothing else needs diagnosing.
4. Play a video, then share the file.

The launcher activity reports whether the module is active in *its own* process (the
framework rewrites `ModuleStatus.isModuleActive()`, so "not active" is a real reading,
not a stored flag). It cannot confirm the hook attached inside X — the log file existing
is what proves that, which is why it is written on attach rather than on first URL.

## Status

Stage 1 (probe) implemented, with log export. The download feature is deliberately not
built yet: it should be designed against the hook points the probe actually confirms
on-device, not against guesses.
