#!/usr/bin/env bash
# Single gate for every LSPosed module artifact in this repo.
#
# One implementation, one class of problem: contract + identity + test evidence.
# Workflows call this instead of growing their own copies of the same assertions.
#
#   verify_lsp_artifact.sh <apk> <entry-class> <scope-csv> [test-results-dir]
#
# Checks
#   1. libxposed packaging: META-INF/xposed/{java_init.list,module.prop,scope.list}
#   2. minApiVersion >= 102 (LSPosed 2.3 removes the legacy XSharedPreferences bridge)
#   3. no legacy markers: assets/xposed_init, xposedmodule/xposedsharedprefs/
#      xposedminversion/xposedscope meta-data in the merged manifest
#   4. signer == the one shared fixed key (in-place upgrade must survive)
#   5. if a test-results dir is given: it must exist, contain XML, and report
#      tests > 0 with 0 failures (a green task with zero tests proves nothing)
set -euo pipefail

APK=${1:?usage: verify_lsp_artifact.sh <apk> <entry-class> <scope-csv> [test-results-dir]}
ENTRY_EXPECTED=${2:?entry class required}
SCOPE_EXPECTED=${3:?scope csv required}
RESULTS_DIR=${4:-}

FIXED_SIGNER=1815c41d47083714332df8399f97d4abcaad8803f7a7d6b1aed25507491b8d2d
MIN_API=102

fail() { echo "::error::$*"; exit 1; }

test -f "$APK" || fail "artifact not found: $APK"
BT=""
if [ -n "${ANDROID_HOME:-}" ]; then
  BT=$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)
fi

# ---- 1. libxposed packaging -------------------------------------------------
ENTRY=$(unzip -p "$APK" META-INF/xposed/java_init.list 2>/dev/null | tr -d '\r\n') \
  || fail "missing META-INF/xposed/java_init.list (not a libxposed module)"
test "$ENTRY" = "$ENTRY_EXPECTED" \
  || fail "java_init.list entry is '$ENTRY', expected '$ENTRY_EXPECTED'"

PROP=$(unzip -p "$APK" META-INF/xposed/module.prop 2>/dev/null) \
  || fail "missing META-INF/xposed/module.prop"
echo "$PROP" | grep -qx 'staticScope=true' || echo "note: staticScope is not true (dynamic scope)"

API=$(echo "$PROP" | sed -n 's/^minApiVersion=//p' | tr -d '\r')
test -n "$API" || fail "module.prop has no minApiVersion"
test "$API" -ge "$MIN_API" || fail "minApiVersion $API < $MIN_API: breaks on LSPosed 2.3"

SCOPE=$(unzip -p "$APK" META-INF/xposed/scope.list 2>/dev/null | tr -d '\r' | paste -sd, -)
# Requires every declared target, not literal equality: a module may legitimately
# also scope its own package (settings UI / self-diagnostics).
IFS=',' read -ra WANT <<< "$SCOPE_EXPECTED"
for want in "${WANT[@]}"; do
  case ",$SCOPE," in
    *",$want,"*) ;;
    *) fail "scope.list is '$SCOPE', missing required '$want'" ;;
  esac
done

# ---- 2. legacy markers ------------------------------------------------------
if unzip -l "$APK" | grep -q 'assets/xposed_init'; then
  fail "legacy assets/xposed_init survived packaging"
fi
# Scan the binary manifest directly instead of shelling out to aapt: the check then
# behaves the same on a runner and on a bare host, and it can be self-tested.
python3 - "$APK" <<'PY' || fail "legacy xposed meta-data survived packaging"
import sys, zipfile
flags = ("xposedmodule", "xposedsharedprefs", "xposedminversion", "xposedscope")
man = zipfile.ZipFile(sys.argv[1]).read("AndroidManifest.xml")
found = [f for f in flags
         if f.encode("utf-16-le") in man or f.encode("utf-8") in man]
if found:
    print("legacy meta-data: " + ",".join(found))
    sys.exit(1)
PY

# ---- 3. signing identity ----------------------------------------------------
if [ -n "$BT" ] && [ -x "$BT/apksigner" ]; then
  ACTUAL=$("$BT/apksigner" verify --print-certs "$APK" \
    | grep -m1 -i 'certificate SHA-256 digest' \
    | sed -E 's/.*digest: *//' | tr 'A-Z' 'a-z')
else
  # No SDK on this host: read the v1 PKCS#7 block directly. Same digest, same key.
  ACTUAL=$(python3 - "$APK" <<'PY'
import os, re, subprocess, sys, tempfile, zipfile
z = zipfile.ZipFile(sys.argv[1])
blobs = [n for n in z.namelist() if re.match(r"META-INF/[^/]+\.(RSA|EC|DSA)$", n, re.I)]
if not blobs:
    print("")
    sys.exit(0)
with tempfile.TemporaryDirectory() as tmp:
    sig = os.path.join(tmp, "s")
    open(sig, "wb").write(z.read(blobs[0]))
    p7 = subprocess.run(["openssl", "pkcs7", "-inform", "DER", "-in", sig, "-print_certs"],
                        capture_output=True, text=True)
    if p7.returncode != 0:
        print("")
        sys.exit(0)
    cert = os.path.join(tmp, "c.pem")
    open(cert, "w").write(p7.stdout)
    out = subprocess.run(["openssl", "x509", "-in", cert, "-noout", "-fingerprint", "-sha256"],
                         capture_output=True, text=True).stdout
print(out.split("=", 1)[1].replace(":", "").strip().lower() if "=" in out else "")
PY
)
  [ -n "$ACTUAL" ] || fail "cannot read a v1 signer (no apksigner on this host, no PKCS#7 block)"
fi
[ "$ACTUAL" = "$FIXED_SIGNER" ] \
  || fail "APK is not signed with the fixed key (got $ACTUAL). In-place upgrade would fail."

# ---- 4. test evidence -------------------------------------------------------
# A project may have no unit suite at all; what is forbidden is claiming green
# without evidence. The caller states which case it is, so "no tests" is a
# decision recorded in the workflow rather than a silent pass.
if [ -n "$RESULTS_DIR" ]; then
  python3 - "$RESULTS_DIR" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
files = sorted(glob.glob(sys.argv[1] + "/*.xml"))
if not files:
    sys.exit("::error::no test result XML in " + sys.argv[1]
             + " — did the suite run? Remove the results-dir argument only for a project with no suite at all.")
total = failed = 0
for f in files:
    r = ET.parse(f).getroot()
    total += int(r.get("tests") or 0)
    failed += int(r.get("failures") or 0) + int(r.get("errors") or 0)
    print(f"{r.get('name')}: tests={r.get('tests')} failures={r.get('failures')}")
print(f"TOTAL tests={total} failures={failed}")
if total == 0:
    sys.exit("::error::zero tests executed")
if failed:
    sys.exit(f"::error::{failed} test failure(s)")
PY
else
  echo "note: no unit suite declared for this project (contract+identity still enforced)"
fi

echo "OK: entry=$ENTRY minApiVersion=$API scope=$SCOPE signer=${ACTUAL:0:8}…"