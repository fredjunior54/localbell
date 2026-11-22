#!/usr/bin/env bash
set -Eeuo pipefail

status() { printf '%s\n' "$*"; }
fail() { printf '❌ %s\n' "$*" >&2; exit 1; }
step() { current_step=$1; status "➡️  $current_step"; }
trap 'printf "❌ %s failed (exit %s).\n" "$current_step" "$?" >&2' ERR

step 'Validate installer arguments'
(( EUID != 0 )) || fail 'Run this installer as your desktop user, without sudo.'
(( $# <= 1 )) || fail 'Usage: ./install.sh [path/to/localbell]'
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
if (( $# == 0 )); then
    step 'Build the LocalBell release binary'
    command -v make >/dev/null || fail 'Required build command not found: make'
    command -v cargo >/dev/null || fail 'Required build command not found: cargo'
    make -C "$root" build
    binary=$root/bin/localbell
    status '✅ Release build completed.'
else
    binary=$1
    status '📦 Using the supplied release binary; skipping compilation.'
fi

step 'Check the binary and system requirements'
[[ -f $binary && -x $binary ]] || fail "Not an executable LocalBell binary: $binary"
for tool in systemctl ip xdg-open; do
    command -v "$tool" >/dev/null || fail "Required command not found: $tool"
done
systemctl --user show-environment >/dev/null || fail 'Cannot reach the systemd user manager.'

bin_dir=$HOME/.local/bin
apps_dir=$HOME/.local/share/applications
icon=${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/128x128/apps/localbell.png
unit_dir=${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user
unit=$unit_dir/localbell.service

step "Install the binary at $bin_dir/localbell"
if [[ ! $binary -ef $bin_dir/localbell ]]; then
    install -Dm755 -- "$binary" "$bin_dir/localbell"
else
    status 'ℹ️  The supplied binary is already at the installation path.'
fi
step "Install the desktop icon at $icon"
install -Dm644 -- "$root/server/assets/localbell-icon.png" "$icon"
step "Install the desktop launcher at $apps_dir/localbell.desktop"
install -Dm644 -- "$root/packaging/localbell.desktop" "$apps_dir/localbell.desktop"
step "Install the systemd user service at $unit"
install -Dm644 -- "$root/packaging/localbell-user.service" "$unit"
step 'Reload the systemd user manager'
systemctl --user daemon-reload
step 'Enable localbell.service'
systemctl --user enable localbell.service
if systemctl --user is-active --quiet graphical-session.target; then
    step 'Queue startup or restart for localbell.service'
    systemctl --user restart --no-block localbell.service
    status '✅ LocalBell installed. The service will start when IPv4 is available.'
else
    status '✅ LocalBell installed. The service will start with a supported graphical session.'
fi
