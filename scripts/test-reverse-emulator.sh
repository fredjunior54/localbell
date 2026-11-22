#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
serial=${1:?Usage: scripts/test-reverse-emulator.sh emulator-SERIAL [HOST_PORT]}
port=${2:-19421}
[[ $serial =~ ^emulator-[0-9]+$ ]] || { echo 'Select an isolated emulator, not a physical device.' >&2; exit 2; }
[[ $port =~ ^[1-9][0-9]{0,4}$ ]] && (( port <= 65535 )) || { echo 'Invalid host port.' >&2; exit 2; }
[[ $(adb -s "$serial" shell getprop sys.boot_completed) == 1 ]] || { echo 'The emulator must be running.' >&2; exit 2; }

cargo test --locked --manifest-path server/Cargo.toml --no-run
(cd app && ./gradlew --no-daemon -Pkotlin.compiler.execution.strategy=in-process assembleDebug assembleDebugAndroidTest)
adb -s "$serial" install -r app/app/build/outputs/apk/debug/app-debug.apk
adb -s "$serial" install -r app/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
redirect=$(adb -s "$serial" emu redir add "tcp:$port:19420")
[[ $redirect == OK* ]] || { printf '%s\n' "$redirect" >&2; exit 1; }
report=$(mktemp)
trap 'adb -s "$serial" emu redir del "tcp:$port" >/dev/null; rm -f -- "$report"' EXIT

timeout 65s adb -s "$serial" shell am instrument -w -e reverseInterop true \
    -e class 'io.github.fredjunior54.localbell.ui.screens.ReverseSessionControlsTest#pairsWithRustAndDeliversATestNotification' \
    io.github.fredjunior54.localbell.test/androidx.test.runner.AndroidJUnitRunner >"$report" 2>&1 &
android=$!
rust_result=0
android_result=0
dbus-run-session -- env LOCALBELL_PRIVATE_DBUS_TEST=1 LOCALBELL_EMULATOR_PORT="$port" \
    cargo test --locked --manifest-path server/Cargo.toml emulator_reverse_session_delivers_test_notification || rust_result=$?
wait "$android" || android_result=$?
sed -n '1,70p' "$report"
(( rust_result == 0 && android_result == 0 )) && grep -q '^OK (1 test)' "$report"
