---
name: bump-version
description: Synchronize version metadata and publish a LocalBell release.
---

# Bump Version

End every iteration that changes project files with one patch release. Only Fred Junior may authorize an explicit `minor` or `major` bump. Do not release a no-op iteration.

```bash
./scripts/bump-version.sh "fix: Describe the change" -- path/to/changed-file
```

The script uses Gradle's `versionName` as the baseline, increments Android `versionCode`, updates Arch `pkgver` and `.SRCINFO`, and supplies the version to the Cargo package, desktop build, and Git tag. RPM and DEB templates keep their dynamic version placeholders. Do not edit these values manually before running the script.
