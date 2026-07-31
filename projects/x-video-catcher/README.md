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
storage directly, and anything written into X's own private dirs needs root to retrieve —
which is why records are routed across the process boundary instead:

```
X process                         module process
  hook -> ProbeLog (queue)  --->  ProbeProvider -> files/xvc-probe.jsonl
                                                     |
                              share sheet <- files/export/ (FileProvider)
```

- **Queued, not synchronous.** A binder call per URL on the network hot path is not
  acceptable, so records are batched by a low-priority daemon thread. The queue is
  bounded and drops on overflow: losing records under a flood is fine, stalling X is not.
- **`ProbeProvider` must be exported** for X's process to reach it, which means every app
  on the device can see it too. Access is therefore checked per call against
  `ProbeContract.ALLOWED_WRITERS`, not delegated to the manifest.
- **`FileProvider` publishes `files/export/` only** — never the live log the writer
  thread is still appending to.
- Each export is prefixed with module version, host X version, device, and Android
  version. Without that, a returned log cannot be tied to the build that produced it.

In the app: **Export / share log** builds the file and opens the share sheet (Telegram,
Drive, mail, anything). **Refresh** shows the current record count. **Clear log** resets
between attempts.

Quick check without the UI, if adb is handy:

```
adb shell content query --uri content://com.jiesa.xvideocatcher.probe/log
```

## Build

Cloud CI only — see `.github/workflows/build-x-video-catcher.yml`. The workflow runs the
unit tests, builds the APK, then asserts the module contract on the built artifact:
`assets/xposed_init` names the entry class, `xposedminversion` is present, and the Xposed
API is *not* bundled into the APK (a bundled copy breaks hook dispatch).

Tests cover the JSONL line contract (an unescaped newline splits a record and corrupts
the export), the URL filter's pass and drop sets, and — under Robolectric, against a real
Android context — the full insert-persist-export path plus rejection of foreign callers.

Do not add `android.aapt2FromMavenOverride` to `gradle.properties` — that is a
host-specific path and the Maven aapt2 is x86_64-only.

Note: `scripts/cloud_build_register_project.py` regenerates this README and the workflow
from templates. Re-apply the unit-test and APK-contract steps after re-registering.

## Usage

1. Install the APK, enable the module in LSPosed, and set its scope to X.
2. Force-stop X so it restarts with the module attached.
3. Play a video, then open this app and tap **Export / share log**.

The launcher activity reports whether the module is currently active. That status comes
from the framework rewriting `ModuleStatus.isModuleActive()`, so "not active" is a real
reading rather than a stored flag that could go stale.

## Status

Stage 1 (probe) implemented, with log export. The download feature is deliberately not
built yet: it should be designed against the hook points the probe actually confirms
on-device, not against guesses.
