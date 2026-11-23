#!/usr/bin/env bash
set -euo pipefail

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
[[ $# == 2 && $2 =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail 'Usage: build-linux-packages.sh {binary|deb|rpm|all} VERSION'
mode=$1 version=$2
[[ $mode =~ ^(binary|deb|rpm|all)$ ]] || fail "Unknown build mode: $mode"
root=$(cd -- "$(dirname -- "$0")/.." && pwd -P)
cd "$root"
case $mode in
    binary) tools=(docker) ;;
    deb) tools=(dpkg-deb) ;;
    rpm) tools=(rpmbuild) ;;
    all) tools=(dpkg-deb rpmbuild) ;;
esac
for tool in "${tools[@]}"; do
    command -v "$tool" >/dev/null || fail "Missing tool: $tool"
done
work=$(mktemp -d)
trap 'rm -rf -- "$work"' EXIT
mkdir -p dist
run() {
    docker run --rm --platform linux/amd64 --user "$(id -u):$(id -g)" \
        --env HOME=/tmp --mount "type=bind,src=$work,dst=/work" "$@"
}
if [[ $mode == binary ]]; then
    cargo_home=${CARGO_HOME:-$HOME/.cargo}
    mkdir -p "$cargo_home/localbell-release"
    external_target=${LOCALBELL_RELEASE_TARGET_DIR:-$cargo_home/localbell-release/target}
    mkdir -p "$external_target"
    run --env CARGO_HOME=/work/cargo --env CARGO_TARGET_DIR=/target \
        --mount "type=bind,src=$external_target,dst=/target" \
        --mount "type=bind,src=$root/server,dst=/src/server,readonly" \
        --mount "type=bind,src=$root/assets,dst=/src/assets,readonly" \
        rust:1.96-bookworm cargo build --locked --release --manifest-path /src/server/Cargo.toml
    install -m755 "$external_target/release/localbell" dist/localbell
    [[ $(dist/localbell --version) == "$version" ]] || fail 'The build does not match the release version.'
    tar -czf "dist/localbell-$version-linux-amd64.tar.gz" -C dist localbell
    exit 0
fi
[[ -x dist/localbell && $(dist/localbell --version) == "$version" ]] || fail 'Build the matching Linux release binary first.'
entry=$work/localbell.desktop
sed 's|^Exec=.*|Exec=/usr/bin/localbell|' packaging/localbell.desktop > "$entry"
if [[ $mode == deb || $mode == all ]]; then
    tree=$work/deb
    install -Dm755 dist/localbell "$tree/usr/bin/localbell"
    install -Dm644 "$entry" "$tree/usr/share/applications/localbell.desktop"
    install -Dm644 server/assets/localbell-icon.png "$tree/usr/share/icons/hicolor/128x128/apps/localbell.png"
    install -Dm644 LICENSE "$tree/usr/share/doc/localbell/copyright"
    install -Dm644 server/assets/NERD-FONTS.txt "$tree/usr/share/doc/localbell/NERD-FONTS.txt"
    install -Dm644 server/assets/CaskaydiaCove-LICENSE.txt "$tree/usr/share/doc/localbell/CaskaydiaCove-LICENSE.txt"
    install -Dm644 server/assets/Symbols-Nerd-LICENSE.txt "$tree/usr/share/doc/localbell/Symbols-Nerd-LICENSE.txt"
    mkdir -p "$tree/DEBIAN"
    sed "s/\${VERSION}/$version/" packaging/deb/DEBIAN/control > "$tree/DEBIAN/control"
    name=localbell_${version}_amd64.deb
    dpkg-deb --root-owner-group --build "$tree" "$work/$name"
    install -m644 "$work/$name" "dist/$name"
fi
if [[ $mode == rpm || $mode == all ]]; then
    tree=$work/localbell-$version
    mkdir -p "$tree" "$work/rpm/SOURCES"
    cp -- dist/localbell "$entry" LICENSE server/assets/{localbell-icon.png,NERD-FONTS.txt,CaskaydiaCove-LICENSE.txt,Symbols-Nerd-LICENSE.txt} "$tree/"
    tar -czf "$work/rpm/SOURCES/localbell-$version.tar.gz" -C "$work" "localbell-$version"
    cp packaging/rpm/localbell.spec "$work/"
    rpmbuild -bb --target x86_64 --define "_topdir $work/rpm" \
        --define "pkg_version $version" "$work/localbell.spec"
    install -m644 "$work/rpm/RPMS/x86_64/localbell-$version-1.x86_64.rpm" "dist/localbell-$version-1.x86_64.rpm"
fi
