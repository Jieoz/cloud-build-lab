# cloud-build-lab

Cloud build lab for Jay's APK/EXE experiments.

## Purpose

Use this repo as the default CI-first build lane so CI-friendly builds do not consume Jay's local machine/runtime resources.

## Build registry

Project metadata lives in `build-manifest.json`.
It records:
- project kind
- source path inside this repo
- workflow file
- artifact name
- delivery type
- release asset pattern

## Current projects

### 1. moto-chargeboost-toggle
- Kind: Android APK
- Source: `projects/moto-chargeboost-toggle`
- Workflow: `.github/workflows/build-moto-chargeboost-toggle.yml`
- Artifact: `moto-chargeboost-toggle-debug-apk`
- CI note: project currently does **not** include a Gradle wrapper (`gradlew`, `gradle/wrapper/*`), so the workflow uses GitHub Actions' Gradle setup instead of `./gradlew`.
- Default behavior: build + artifact upload
- Manual release mode: run workflow with `release=true` to also create a GitHub Release and attach the APK

### 2. tianruoocr
- Kind: Windows EXE ZIP
- Source: `projects/tianruoocr`
- Workflow: `.github/workflows/build-tianruoocr.yml`
- Artifact: `tianruoocr-fixed-google-translation`
- CI note: hosted Windows runner lacked .NET Framework 4.0 targeting pack, so the project build target was raised to `v4.8` for CI compatibility.
- Default behavior: build + artifact upload
- Manual release mode: run workflow with `release=true` to also create a GitHub Release and attach the ZIP

## Helper entry points

From the main workspace:
- unified dispatch + wait + fetch helper: `scripts/cloud_build_deliver.py`
- artifact wait/fetch helper: `scripts/github_actions_wait_and_fetch_artifact.py`
- GitHub API commit fallback for repo updates: `scripts/github_repo_commit_via_api.py`

## Notes
- This repo is meant to hold clean CI-friendly copies or mirrors of buildable projects.
- Local-only builder assumptions (for example `/opt/android-sdk` in `local.properties`) should be stripped or adapted for GitHub Actions.
- When normal git HTTPS push drifts but repo API permissions are confirmed, prefer the GitHub API commit fallback over repeating the same broken push path.
