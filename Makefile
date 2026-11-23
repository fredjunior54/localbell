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

rpm: release-linux ## Build the Fedora RPM package
	./scripts/build-linux-packages.sh rpm "$(VERSION)"

deb: release-linux ## Build the Debian package
	./scripts/build-linux-packages.sh deb "$(VERSION)"

tarball: release-linux ## Build the Linux desktop archive

clean: ## Remove build artifacts
	rm -rf bin/ dist/

release-linux: ## Build the portable Linux amd64 archive
	./scripts/build-linux-packages.sh binary "$(VERSION)"
