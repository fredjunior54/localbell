---
name: build-and-packaging
description: Build LocalBell and understand its local release artifacts.
---

# Build and Packaging

- `make build` builds the development desktop binary. Cargo supplies the version from the package manifest.
- `make release-linux VERSION=X.Y.Z` builds `dist/localbell-X.Y.Z-linux-amd64.tar.gz`, containing only the Linux amd64 executable `localbell`.
- The release script uses Gradle `assembleRelease` and signs the APK with the local keystore. It publishes `localbell-X.Y.Z.apk` and the Linux archive only.
- The RPM and DEB Make targets remain available for local packaging. The Arch recipe points to the fork's release archive . No package repository deployment is configured.
- GitHub Actions runs CI only. Run `scripts/bump-version.sh` from `main` for official releases; see `README.md` for prerequisites and recovery.
