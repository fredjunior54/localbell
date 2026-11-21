---
name: build-and-packaging
description: Build LocalBell and understand its local release artifacts.
---

# Build and Packaging

- `make build` builds the development Go binary. The Makefile injects the version through Go linker flags.
- `make release-linux VERSION=X.Y.Z` builds `dist/localbell-linux-amd64.tar.gz`, containing only the Linux amd64 executable `localbell`.
- The release script uses Gradle `assembleRelease` and signs the APK with the local keystore. It publishes `localbell-X.Y.Z.apk` and the Linux archive only.
- The RPM and DEB Make targets remain available for local packaging. The Arch recipe points to the fork's release archive and the matching tagged service file. No package repository deployment is configured.
- GitHub Actions runs CI only. Run `scripts/bump-version.sh` from `main` for official releases; see `README.md` for prerequisites and recovery.
