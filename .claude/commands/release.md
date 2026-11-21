---
name: release
description: Publish or recover a locally built LocalBell release.
---

# Release LocalBell

1. Work on `main` with `origin` pointing to the LocalBell fork. Configure GitHub CLI, the Android SDK, and the private signing directory as described in `README.md`.
2. Run `scripts/bump-version.sh` with a descriptive Conventional Commit subject and the changed files. The script validates signing, tests and lints the selected tree, and builds and verifies the signed APK and Linux amd64 archive before it commits.
3. The script pushes the branch and annotated tag together and uploads the two artifacts to GitHub Releases. GitHub Actions runs CI only.

If publication fails after the push, use the retained assets and retry `gh release create` for the same tag as documented in the README. If neither ref was pushed, inspect the push failure before retrying the existing branch and tag together. Do not generate another bump or move a tag to retry publication.
