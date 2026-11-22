#!/usr/bin/env bash
set -euo pipefail

if (( EUID == 0 || $# != 0 )); then
    printf 'Run ./uninstall.sh as your desktop user, without sudo or arguments.\n' >&2
    exit 1
fi
unit=${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/localbell.service
if [[ -e $unit ]]; then
    systemctl --user disable --now localbell.service
    rm -- "$unit"
    systemctl --user daemon-reload
fi
rm -f -- "$HOME/.local/bin/localbell" "$HOME/.local/share/applications/localbell.desktop"
printf 'LocalBell uninstalled. Pairing and configuration were preserved.\n'
