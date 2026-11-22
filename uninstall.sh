#!/usr/bin/env bash
set -Eeuo pipefail

step() { current_step=$1; printf '➡️  %s\n' "$current_step"; }
trap 'printf "❌ %s failed (exit %s).\n" "$current_step" "$?" >&2' ERR

step 'Validate uninstaller arguments'
if (( EUID == 0 || $# != 0 )); then
    printf '❌ Run ./uninstall.sh as your desktop user, without sudo or arguments.\n' >&2
    exit 1
fi

unit=${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/localbell.service
if [[ -e $unit ]]; then
    step 'Stop and disable localbell.service'
    systemctl --user disable --now localbell.service
    step "Remove the systemd user service at $unit"
    rm -- "$unit"
    step 'Reload the systemd user manager'
    systemctl --user daemon-reload
else
    printf 'ℹ️  No user service file found; skipping service removal.\n'
fi

icon=${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/128x128/apps/localbell.png
for file in "$HOME/.local/bin/localbell" "$HOME/.local/share/applications/localbell.desktop" "$icon"; do
    if [[ -e $file || -L $file ]]; then
        step "Remove $file"
        rm -- "$file"
    else
        printf 'ℹ️  Already absent: %s\n' "$file"
    fi
done
printf '✅ LocalBell uninstalled. Pairing and configuration were preserved.\n'
