# cloud-build-lab

Cloud build lab for Jay's APK/EXE experiments.

## Current projects

### 1. moto-chargeboost-toggle
- Source: imported from local workspace `deliverables/moto-chargeboost-toggle`
- Workflow: `.github/workflows/build-moto-chargeboost-toggle.yml`
- CI note: project currently does **not** include a Gradle wrapper (`gradlew`, `gradle/wrapper/*`), so the workflow uses GitHub Actions' Gradle setup instead of `./gradlew`.
- Default behavior: build + artifact upload
- Manual release mode: run workflow with `release=true` to also create a GitHub Release and attach the APK

## Notes
- This repo is meant to hold clean CI-friendly copies or mirrors of buildable projects.
- Local-only builder assumptions (for example `/opt/android-sdk` in `local.properties`) should be stripped or adapted for GitHub Actions.
- A second APK project will be added after its current source root is recovered in the workspace.
