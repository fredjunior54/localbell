.PHONY: help build test test-server test-app up lint lint-server lint-app format format-server format-app rpm deb tarball release-linux clean

SERVER_DIR = server
APP_DIR = app
VERSION ?= $(shell sed -n 's/^version = "\(.*\)"/\1/p' $(SERVER_DIR)/Cargo.toml)
DEB_VERSION := $(shell echo "$(VERSION)" | sed 's/^\([0-9]\)/\1/; t; s/^/0.0.0+git./')

help: ## Show available commands
	@echo "LocalBell - Available commands:"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}'
	@echo ""

build: ## Build the binary
	cargo build --locked --release --manifest-path $(SERVER_DIR)/Cargo.toml
	install -Dm755 $(SERVER_DIR)/target/release/localbell bin/localbell

test: test-server test-app ## Run all tests

test-server: ## Run server tests
	cargo test --locked --manifest-path $(SERVER_DIR)/Cargo.toml

test-app: ## Run Android app tests
	cd $(APP_DIR) && ./gradlew test --quiet

lint: lint-server lint-app ## Run all linters

lint-server: ## Run server linter
	cargo fmt --check --manifest-path $(SERVER_DIR)/Cargo.toml
	cargo clippy --locked --all-targets --all-features --manifest-path $(SERVER_DIR)/Cargo.toml -- -D warnings

lint-app: ## Run Android app linter
	cd $(APP_DIR) && ./gradlew lint detekt spotlessCheck

format: format-server format-app ## Format all code

format-server: ## Format Rust code
	cargo fmt --manifest-path $(SERVER_DIR)/Cargo.toml

format-app: ## Format Kotlin code
	cd $(APP_DIR) && ./gradlew spotlessApply

up: ## Start the desktop app
	RUST_LOG="$${RUST_LOG:-localbell=debug}" cargo run --locked --manifest-path $(SERVER_DIR)/Cargo.toml

rpm: build ## Build RPM package
	mkdir -p ~/rpmbuild/SOURCES ~/rpmbuild/SPECS ~/rpmbuild/BUILD ~/rpmbuild/RPMS ~/rpmbuild/SRPMS
	tar --transform "s,^,localbell-$(VERSION)/," \
	    -czf ~/rpmbuild/SOURCES/localbell-$(VERSION).tar.gz \
	    bin/localbell packaging/ LICENSE
	rpmbuild -bb --define "pkg_version $(VERSION)" packaging/rpm/localbell.spec

deb: build ## Build DEB package
	rm -rf /tmp/localbell-deb
	mkdir -p dist
	mkdir -p /tmp/localbell-deb/usr/bin
	mkdir -p /tmp/localbell-deb/usr/share/doc/localbell
	mkdir -p /tmp/localbell-deb/DEBIAN
	cp bin/localbell /tmp/localbell-deb/usr/bin/
	cp LICENSE /tmp/localbell-deb/usr/share/doc/localbell/copyright
	sed 's/$${VERSION}/$(DEB_VERSION)/' packaging/deb/DEBIAN/control > /tmp/localbell-deb/DEBIAN/control
	dpkg-deb --root-owner-group --build /tmp/localbell-deb dist/localbell_$(DEB_VERSION)_amd64.deb

tarball: release-linux ## Build the Linux desktop archive

clean: ## Remove build artifacts
	rm -rf bin/ dist/

release-linux: ## Build the versioned Linux amd64 desktop archive
	cargo build --locked --release --target x86_64-unknown-linux-gnu --manifest-path $(SERVER_DIR)/Cargo.toml
	mkdir -p dist
	install -m755 $(SERVER_DIR)/target/x86_64-unknown-linux-gnu/release/localbell dist/localbell
	test "$$(dist/localbell --version)" = "$(VERSION)"
	tar -czf dist/localbell-$(VERSION)-linux-amd64.tar.gz -C dist localbell
