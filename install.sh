#!/usr/bin/env bash
set -euo pipefail

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
(( EUID != 0 )) || fail 'Run this installer as your desktop user, without sudo.'
(( $# <= 1 )) || fail 'Usage: ./install.sh [path/to/localbell]'
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
binary=${1:-$root/bin/localbell}
[[ -f $binary && -x $binary ]] || fail 'Provide an executable LocalBell binary or run make build first.'
for tool in systemctl ip xdg-open; do
    command -v "$tool" >/dev/null || fail "Required command not found: $tool"
done
systemctl --user show-environment >/dev/null || fail 'Cannot reach the systemd user manager.'

bin_dir=$HOME/.local/bin
apps_dir=$HOME/.local/share/applications
unit_dir=${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user
unit=$unit_dir/localbell.service

if [[ ! $binary -ef $bin_dir/localbell ]]; then
    install -Dm755 -- "$binary" "$bin_dir/localbell"
fi
install -Dm644 -- "$root/packaging/localbell.desktop" "$apps_dir/localbell.desktop"
install -Dm644 -- "$root/packaging/localbell-user.service" "$unit"
systemctl --user daemon-reload
systemctl --user enable localbell.service
if systemctl --user is-active --quiet graphical-session.target; then
    systemctl --user restart --no-block localbell.service
    printf 'LocalBell installed. Startup is queued for when IPv4 is available.\n'
else
    printf 'LocalBell installed. The service will start with a supported graphical session.\n'
fi
