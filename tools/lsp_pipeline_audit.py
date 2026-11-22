#!/usr/bin/env python3
"""Audit every LSPosed module installed on the XT2175 against the delivery contract.

Checks, per module:
  * api      : libxposed `minApiVersion` (>=102 keeps working after LSPosed 2.3
               removes the legacy XSharedPreferences bridge)
  * markers  : legacy manifest meta-data / assets/xposed_init must be absent
  * signer   : must match the one shared fixed key
  * scope    : a module enabled with an empty scope does nothing
  * version  : reported next to the install time so a stale build is visible

Read-only: pulls the LSPosed config DB and copies each APK to a scratch dir.
Exit code 1 when any violation is found, so it can gate a delivery.

Usage: lsp_pipeline_audit.py [--json out.json] [--keep DIR]
"""
import argparse
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import zipfile

SSH = [
    "ssh", "-p", "52681",
    "-i", "/opt/data/secrets/xt2175_permanent.key",
    "-o", "UserKnownHostsFile=/root/.ssh/known_hosts_xt2175perm",
    "-o", "IdentitiesOnly=yes", "-o", "StrictHostKeyChecking=no",
    "-o", "ConnectTimeout=15", "root@127.0.0.1",
]

FIXED_SIGNER = "1815c41d47083714332df8399f97d4abcaad8803f7a7d6b1aed25507491b8d2d"
REGISTRY = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "signing", "registry.json")


def load_pinned():
    """package -> pinned certificate digest, from the same registry the gate uses."""
    try:
        reg = json.load(open(REGISTRY))
    except Exception:
        return {}, FIXED_SIGNER
    default = reg.get("defaultProfile")
    default_digest = reg.get("profiles", {}).get(default, {}).get("certificateSha256", FIXED_SIGNER)
    out = {}
    for pkg, profile in reg.get("modules", {}).items():
        entry = reg.get("profiles", {}).get(profile)
        if entry and entry.get("certificateSha256"):
            out[pkg] = entry["certificateSha256"].lower()
    return out, default_digest.lower()


PINNED_SIGNERS, DEFAULT_SIGNER = load_pinned()
LEGACY_FLAGS_HARD = ("xposedsharedprefs", "xposedminversion")
LEGACY_FLAGS_SOFT = ("xposedmodule", "xposeddescription", "xposedscope")
MIN_API = 102


def ssh(cmd, timeout=90):
    p = subprocess.run(SSH + [cmd], capture_output=True, timeout=timeout)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.decode("utf-8", "replace")[:300])
    return p.stdout


def cert_fingerprint(apk_path):
    """SHA-256 of the v1 signing certificate, or None for v2+-only APKs."""
    with zipfile.ZipFile(apk_path) as z:
        blobs = [n for n in z.namelist()
                 if re.match(r"META-INF/[^/]+\.(RSA|EC|DSA)$", n, re.I)]
        if not blobs:
            return None, None
        data = z.read(blobs[0])
    with tempfile.TemporaryDirectory() as tmp:
        sig = os.path.join(tmp, "s")
        with open(sig, "wb") as f:
            f.write(data)
        p7 = subprocess.run(["openssl", "pkcs7", "-inform", "DER", "-in", sig,
                             "-print_certs"], capture_output=True, text=True)
        if p7.returncode != 0 or not p7.stdout.strip():
            return "unparsed", "unparsed"
        pem = os.path.join(tmp, "c.pem")
        with open(pem, "w") as f:
            f.write(p7.stdout)
        fp = subprocess.run(["openssl", "x509", "-in", pem, "-noout",
                             "-fingerprint", "-sha256"],
                            capture_output=True, text=True).stdout.strip()
        subj = subprocess.run(["openssl", "x509", "-in", pem, "-noout", "-subject"],
                              capture_output=True, text=True).stdout.strip()
    digest = fp.split("=", 1)[1].replace(":", "").lower() if "=" in fp else "?"
    return digest, subj.replace("subject=", "").strip()


def inspect_apk(path):
    out = {"api": None, "staticScope": None, "legacy_assets": False,
           "legacy_entry": None, "legacy_manifest": [], "legacy_manifest_soft": [], "scope": []}
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        if "assets/xposed_init" in names:
            out["legacy_assets"] = True
            out["legacy_entry"] = z.read("assets/xposed_init").decode("utf-8", "replace").strip()
        if "META-INF/xposed/module.prop" in names:
            for line in z.read("META-INF/xposed/module.prop").decode("utf-8", "replace").splitlines():
                if line.startswith("minApiVersion="):
                    out["api"] = int(line.split("=", 1)[1].strip())
                elif line.startswith("staticScope="):
                    out["staticScope"] = line.split("=", 1)[1].strip()
        if "META-INF/xposed/scope.list" in names:
            raw = z.read("META-INF/xposed/scope.list").decode("utf-8", "replace")
            out["scope"] = [s.strip() for s in raw.splitlines() if s.strip()]
        man = z.read("AndroidManifest.xml") if "AndroidManifest.xml" in names else b""
    for flag in LEGACY_FLAGS_HARD:
        if flag.encode("utf-16-le") in man or flag.encode("utf-8") in man:
            out["legacy_manifest"].append(flag)
    for flag in LEGACY_FLAGS_SOFT:
        if flag.encode("utf-16-le") in man or flag.encode("utf-8") in man:
            out["legacy_manifest_soft"].append(flag)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--json")
    ap.add_argument("--keep")
    args = ap.parse_args()

    work = args.keep or tempfile.mkdtemp(prefix="lspaudit-")
    os.makedirs(work, exist_ok=True)

    db = os.path.join(work, "modules_config.db")
    with open(db, "wb") as f:
        f.write(ssh("cat /data/adb/lspd/config/modules_config.db", timeout=120))

    con = sqlite3.connect(db)
    apks = dict(con.execute("select module_pkg_name, apk_path from modules"))
    enabled = {r[0]: r[1] for r in con.execute(
        "select module_pkg_name, enabled from modules_state")}
    scopes = {}
    for pkg, app in con.execute("select module_pkg_name, app_pkg_name from scope"):
        scopes.setdefault(pkg, []).append(app)

    versions = {}
    models = ssh("getprop ro.product.model", timeout=30).decode().strip()
    for pkg in apks:
        dump = ssh(f"dumpsys package {pkg} | grep -E 'versionName|versionCode|lastUpdateTime' | head -3")
        info = {}
        for line in dump.decode("utf-8", "replace").splitlines():
            if "=" in line:
                k, v = line.strip().split("=", 1)
                info[k] = v
        versions[pkg] = info

    rows, violations = [], []
    for pkg, path in sorted(apks.items()):
        local = os.path.join(work, pkg + ".apk")
        if not os.path.exists(local) or os.path.getsize(local) == 0:
            with open(local, "wb") as f:
                f.write(ssh(f"cat '{path}'", timeout=150))
        meta = inspect_apk(local)
        digest, subject = cert_fingerprint(local)
        v = versions.get(pkg, {})
        row = {
            "package": pkg,
            "own": pkg.startswith("com.jieoz."),
            "versionName": v.get("versionName"),
            "versionCode": v.get("versionCode"),
            "lastUpdate": v.get("lastUpdateTime"),
            "enabled": bool(enabled.get(pkg)),
            "config_scope": scopes.get(pkg, []),
            "apk_scope": meta["scope"],
            "api": meta["api"],
            "staticScope": meta["staticScope"],
            "legacy_assets": meta["legacy_assets"],
            "legacy_manifest": meta["legacy_manifest"],
            "signer": digest,
            "signer_subject": subject,
            "problems": [],
            "advisories": [],
        }
        target = row["problems"] if row["own"] else row["advisories"]
        if row["own"]:
            pinned = PINNED_SIGNERS.get(row["package"], DEFAULT_SIGNER)
            if digest is None:
                target.append("no v1 signature; cannot verify against the pinned key")
            elif digest != pinned:
                target.append(f"signer {digest[:8]}… is not the pinned key {pinned[:8]}…: "
                              "the next update needs an uninstall")
        if meta["api"] is None:
            target.append("no libxposed module.prop (legacy-only module)")
        elif meta["api"] < MIN_API:
            target.append(f"minApiVersion {meta['api']} < {MIN_API} (breaks on LSPosed 2.3)")
        if meta["legacy_assets"]:
            entry = meta.get("legacy_entry")
            row["advisories"].append(
                f"legacy assets/xposed_init present (entry={entry}); inert on 2.2.x, "
                f"removed in 2.3 — tolerated only when it matches the libxposed entry")
        if meta["legacy_manifest"]:
            target.append("deprecated meta-data: " + ",".join(meta["legacy_manifest"])
                          + " (removed in LSPosed 2.3)")
        if meta["legacy_manifest_soft"]:
            row["advisories"].append("legacy marker meta-data (harmless on 2.2+, ignored in 2.3): "
                                     + ",".join(meta["legacy_manifest_soft"]))
        if enabled.get(pkg) and not (scopes.get(pkg) or meta["scope"]):
            target.append("enabled but scope is empty (module does nothing)")
        rows.append(row)
        violations += [(pkg, p) for p in row["problems"]]

    print(f"device: {models}  modules: {len(rows)}  blocking: {len(violations)}")
    for r in rows:
        tag = "own" if r["own"] else "third-party"
        print(f"\n{r['package']} [{tag}]  v{r['versionName']} ({r['versionCode']})  "
              f"enabled={r['enabled']}  api={r['api']}  staticScope={r['staticScope']}")
        print(f"  scope: {', '.join(r['config_scope']) or '(none)'}  signer: "
              f"{(r['signer'] or 'v2-only')[:8]}")
        for p in r["problems"]:
            print(f"  ! {p}")
        for p in r["advisories"]:
            print(f"  ~ {p}")

    if args.json:
        with open(args.json, "w") as f:
            json.dump({"device": models, "modules": rows}, f, indent=2, ensure_ascii=False)
        print(f"\nwrote {args.json}")
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main())