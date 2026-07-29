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

## Output

Both sinks are best-effort and never throw into X:

- `XposedBridge.log` — visible in the LSPosed manager log screen.
- `<X cache dir>/xvc-probe.jsonl` — one JSON object per candidate URL, containing the
  timestamp, the hook that saw it, the URL, and a trimmed stack trace. The stack trace is
  the valuable part: it names the obfuscated classes that fetch media, which is what a
  later, narrower hook needs.

Only URLs that look like media are recorded (`video.twimg.com`, `amplify_video`,
`.m3u8`, `.mp4`, segments). These callbacks run on the app's network hot path, so
filtering happens before any stack trace is materialised. `MediaUrlsTest` locks in both
the URLs that must pass and the ones that must be dropped.

## Build

Cloud CI only — see `.github/workflows/build-x-video-catcher.yml`. The workflow runs the
unit tests, builds the APK, then asserts the module contract on the built artifact:
`assets/xposed_init` names the entry class, `xposedminversion` is present, and the Xposed
API is *not* bundled into the APK (a bundled copy breaks hook dispatch).

Do not add `android.aapt2FromMavenOverride` to `gradle.properties` — that is a
host-specific path and the Maven aapt2 is x86_64-only.

## Usage

1. Install the APK, enable the module in LSPosed, and set its scope to X.
2. Force-stop X so it restarts with the module attached.
3. Play a video, then read the LSPosed log or pull the JSONL from X's cache dir.

The launcher activity reports whether the module is currently active.

## Status

Stage 1 (probe) implemented. The download feature is deliberately not built yet: it
should be designed against the hook points the probe actually confirms on-device, not
against guesses.
