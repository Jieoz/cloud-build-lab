# Timeline Unlocker (LSPosed Module)

An LSPosed (Xposed) module that spoofs telephony country/operator inside Google
Play Services and Google Maps so that GMS Location History / Timeline can be
enabled on devices whose SIM is registered in a region where Google has
restricted the feature.

## Disclaimer

Spoofing telephony identity inside Google Play Services likely violates the
Google Terms of Service and may put your Google account at risk. This module
is provided for personal, educational use with **no warranty**. Use at your
own risk.

## Scope

The module only loads in:

- `com.google.android.gms`
- `com.google.android.gsf`
- `com.google.android.apps.maps`

What each process gets:

- `com.google.android.gms` / `com.google.android.gsf`:
  `TelephonyManager.getSimCountryIso{,ForPhone}` &rarr; `us`, and
  `SystemProperties.get(...)` for `gsm.(sim.)?operator.(numeric|iso-country)`
  &rarr; `310030` / `us`. This is what makes the account eligible for Timeline.
- `com.google.android.apps.maps`: **no telephony spoof.** Maps keeps reading the
  real SIM, so it keeps its own WGS-84 &rarr; GCJ-02 correction for the live
  location dot. The module only shifts Timeline history points (see below).

This pairing is the only one that gave both a visible Timeline and aligned
maps on the test device. Spoofing `us` inside Maps too makes Maps drop its own
correction; re-adding it with a `Location` hook left the dot and the road /
satellite layers apart, so that path was removed.

## Build

```bash
./gradlew :xposed:assembleDebug
# output: xposed/build/outputs/apk/debug/xposed-debug.apk
```

Release builds never use the Android debug key. Configure all four environment
variables to produce a signed release APK:

```text
RELEASE_STORE_FILE
RELEASE_STORE_PASSWORD
RELEASE_KEY_ALIAS
RELEASE_KEY_PASSWORD
```

Without those variables, `assembleRelease` produces an unsigned APK suitable
for build verification only. Keep the release keystore and its passwords out
of the repository.

Pushing a `v*` tag runs the GitHub release job. Configure these repository
secrets first: `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, and `RELEASE_APK_CERT_SHA256`.
The release job stops before building if any required secret is absent.

On Windows, create a new keystore and configure all five secrets interactively:

```powershell
.\scripts\setup-release-signing.ps1
```

The script requires `keytool`, an authenticated `gh` CLI, and a remote named
`origin` pointing to GitHub. It creates a PKCS12 keystore in the user profile
by default and never writes its passwords to the repository.

## Install

1. Install the APK with `adb install` (or any installer).
2. In LSPosed manager, enable **Timeline Unlocker (Xposed)**.
3. Confirm the scope includes the three Google packages above.
4. Open the module app and tap **重新加载地图和 Play 服务** (root: force-stops
   Maps, GMS and GSF so they start again with the hooks, then reopens Maps).
   No reboot needed. Without root, force-stop the three apps in app info.
5. If the in-app Timeline entry is hidden that day (the server decides it),
   open the module app and tap **在地图中打开时间轴**. It opens Timeline through
   a Maps deep link, so it does not depend on any one menu entry.

## GCJ-02 offset compensation

Maps itself corrects the live location dot because it still sees the real
China SIM. Timeline history comes from GMS in raw WGS-84, so the Maps process
hooks `PlaceCandidate$Point(int, int)` &mdash; the Parcelable constructor used
when Maps reads Visit / Activity data from GMS over Binder &mdash; and applies
the public WGS-84 &rarr; GCJ-02 transform inside mainland China. Hong Kong,
Macao, and Taiwan are excluded. GMS / GSF still see original WGS-84 values.

### Regional boundary data

The module uses simplified OpenStreetMap administrative-boundary polygons for
Hong Kong, Macao, and Taiwan rather than the broad GCJ-02 bounding box. The
generated boundary source is checked in, so builds never make a network request.
It is an explicit, maintainable exclusion policy, not a claim that Google Maps'
internal tile coverage exactly follows administrative boundaries.

The boundary source, ODbL attribution, and fixed OpenStreetMap relation IDs are
in [`NOTICE`](NOTICE). To refresh the generated data, use Node.js 18 or later:

```bash
node scripts/generate-region-boundaries.mjs
git diff -- xposed/src/main/java/io/github/timeline_unlocker/xposed/RegionBoundary.java
```

Review any boundary diff before committing it. The generator requests geometry
simplified to `0.00005` degrees (roughly 5 m) and writes it to
`RegionBoundary.java`.

This compensation is **only** installed in `com.google.android.apps.maps`;
GMS / GSF still see the original WGS-84 values, which is what Location
History upload expects.

For Timeline history points, the hook `PlaceCandidate$Point(int, int)` —
the Parcelable constructor used when Maps deserializes Visit / Activity data
received from GMS over Binder — applies the same transform on read, so
historical entries align with the tiles as well.

## Known limitations and risks

**The Timeline entry itself is decided server-side.** The same build has shown
and hidden the in-app entry on different days. The deep-link button in the
module app is the fallback for those days.

**Anything Maps derives from shifted history points and persists or uploads
leaves the device as GCJ-02-mislabelled WGS-84.** The intended data flow is:
GMS records Timeline history from unhooked WGS-84 locations, and Maps only
*renders* that data. A dex-level inspection of the Maps build this module was
developed against supports that model — the only production path found for
`PlaceCandidate$Point` is the Parcelable `CREATOR.createFromParcel` (GMS
&rarr; Maps IPC) plus a static defaults table in the rendering pipeline, and
serialization (`writeToParcel`) writes the int fields directly without
re-entering the hooked constructor. However, Maps versions differ: any flow
that uploads Maps-derived coordinates (manually edited places, visit
confirmations, on-device Timeline sync, location sharing) could persist
GCJ-02 values. To verify on your device: stand at a known location, manually
create/save a Timeline entry, then check whether the saved point aligns with
reality after a sync/export round-trip.

**The GCJ-02 transform uses the public approximation** with a coarse China
bounding box. Accuracy degrades near box edges (Hong Kong, Macau, Taiwan
areas are inside the box but outside the well-calibrated region), and if a
Chinese ROM's location stack already delivers GCJ-02 coordinates, the
compensation would double-offset those locations.

## Troubleshooting

1. Confirm the module is enabled in LSPosed and the scope includes
   `com.google.android.gms`, `com.google.android.gsf`, and
   `com.google.android.apps.maps`.
2. After enabling or updating the module, tap **重新加载地图和 Play 服务** in
   the module app (or force-stop all three packages) — hooks install at
   process start. A reboot is not needed.
3. Check hook installation in LSPosed logs or via:

   ```bash
   adb logcat | grep -i TimelineUnlocker
   ```

   Each successful hook group logs once per process, e.g.
   `hooked N overload(s) of TelephonyManager.getSimCountryIso -> us`.
   Failures are logged too; a `not found` message usually means the target
   app version changed — open an issue with the log output.
4. Diagnostic log (off by default). Turn it on in the module app; it takes
   effect at once in every running hooked process (LSPosed pushes the switch),
   and lines buffered since process start are written first. Then reproduce. Every hooked process writes its own file:

   ```text
   Download/TimelineUnlocker/timeline-yyyyMMdd-maps.txt
   Download/TimelineUnlocker/timeline-yyyyMMdd-gms.txt
   Download/TimelineUnlocker/timeline-yyyyMMdd-gms.persistent.txt
   ...
   ```

   Send all of them. Each file starts with a `header` line (module, host and
   framework versions). The Maps file also records every Activity resume and
   new intent, so a Timeline deep link shows up as
   `activity ... action=VIEW data=https://www.google.com/maps/timeline`.
   Every line is mirrored to the LSPosed module log as well.
