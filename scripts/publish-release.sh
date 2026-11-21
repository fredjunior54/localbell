#!/usr/bin/env bash
# Publish an explicitly selected, locally verified LocalBell build.
set -euo pipefail

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }

install_wifi_apk() {
    local apk=$1 sdk=$2 serial=${LOCALBELL_ADB_WIFI_DEVICE:-} adb listing attempt
    if [[ -z $serial ]] && command -v fish >/dev/null; then
        # shellcheck disable=SC2016
        serial=$(fish -c 'printf "%s" "$LOCALBELL_ADB_WIFI_DEVICE"') || serial=''
    fi
    if [[ ! $serial =~ ^[A-Za-z0-9_-]+\._adb-tls-connect\._tcp\.?$ ]]; then
        printf 'Wi-Fi APK install skipped: configure LOCALBELL_ADB_WIFI_DEVICE with the phone TLS serial.\n'
        return 0
    fi
    adb=$(type -P adb) || adb=$sdk/platform-tools/adb
    if [[ ! -x $adb ]] || ! command -v timeout >/dev/null; then
        printf 'Wi-Fi APK install skipped: adb or timeout is unavailable.\n'
        return 0
    fi
    for attempt in 1 2 3; do
        if listing=$(timeout -k 2s 3s "$adb" devices -l) &&
            awk -v serial="$serial" '$1 == serial && $2 == "device" { found = 1 } END { exit !found }' <<< "$listing"; then
            if timeout -k 5s 90s "$adb" -s "$serial" install -r "$apk"; then
                printf 'Installed the verified APK over Wi-Fi.\n'
            else
                printf 'Warning: Wi-Fi APK installation failed or timed out; the GitHub release remains published.\n' >&2
            fi
            return 0
        fi
        if [[ $attempt != 3 ]]; then sleep 1; fi
    done
    printf 'Wi-Fi APK install skipped: the configured phone is not online and authorized.\n'
}

[[ $# == 1 && $1 =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || fail 'Usage: scripts/publish-release.sh vX.Y.Z'
tag=$1
version=${tag#v}
repo=fredjunior54/localbell
cd "$(git rev-parse --show-toplevel)"
[[ $(git remote get-url origin) == "git@github.com:$repo.git" && $(git remote get-url --push origin) == "git@github.com:$repo.git" ]] || fail 'Origin must point to LocalBell over SSH.'
release_dir=$(git rev-parse --absolute-git-dir)/localbell-releases
assets=$release_dir/$tag
apk=localbell-$version.apk
archive=localbell-$version-linux-amd64.tar.gz
[[ -d $assets ]] || fail "No locally prepared build for $tag."
exec 9>"$release_dir/lock"
flock -n 9 || fail 'Another LocalBell build or publication is running.'
for file in commit SHA256SUMS "$apk" "$archive"; do
    [[ -f $assets/$file && ! -L $assets/$file ]] || fail "Missing or unexpected retained file: $file"
done
commit=$(<"$assets/commit")
[[ $commit =~ ^[0-9a-f]{40}$ && $(git rev-parse --verify "$commit^{commit}") == "$commit" ]] || fail 'Invalid retained commit.'
git show "$commit:app/app/build.gradle.kts" | grep -Fx "        versionName = \"$version\"" >/dev/null || fail 'Retained commit has the wrong Android version.'
checksums=$(cd "$assets" && sha256sum -- "$apk" "$archive")
[[ $checksums == "$(<"$assets/SHA256SUMS")" ]] || fail 'Retained artifacts do not match their checksums.'
apk_digest=${checksums%% *}
archive_digest=${checksums##*$'\n'}
archive_digest=${archive_digest%% *}

gh auth status --hostname github.com >/dev/null 2>&1 || fail 'Authenticate GitHub CLI first.'
git fetch --no-tags origin refs/heads/main
git merge-base --is-ancestor "$commit" FETCH_HEAD || fail 'The prepared commit is not on origin/main.'
ref=refs/tags/$tag
remote=$(git ls-remote origin "$ref" "$ref^{}")
if [[ -n $remote ]]; then
    remote_commit=$(awk -v ref="$ref" '$2 == ref { direct = $1 } $2 == ref "^{}" { peeled = $1 } END { print (peeled ? peeled : direct) }' <<< "$remote")
    [[ $remote_commit == "$commit" ]] || fail 'Remote tag points to a different commit.'
fi
if git show-ref --verify --quiet "$ref"; then
    [[ $(git rev-parse "$ref^{commit}") == "$commit" ]] || fail 'Local tag points to a different commit.'
elif [[ -z $remote ]]; then
    git tag -a "$tag" "$commit" -m "Release $tag"
fi
if [[ -z $remote ]]; then git push origin "$ref:$ref"; fi

release_id() { gh release view "$tag" --repo "$repo" --json databaseId --jq .databaseId; }
if ! id=$(release_id); then
    gh release create "$tag" --repo "$repo" --verify-tag --generate-notes --draft
    id=$(release_id)
fi
[[ $id =~ ^[0-9]+$ ]] || fail 'Could not identify the GitHub release.'
release_json() { gh api "repos/$repo/releases/$id"; }
matching_assets() {
    jq -e --arg tag "$tag" --arg apk "$apk" --arg linux "$archive" \
        --arg apkDigest "sha256:$apk_digest" --arg linuxDigest "sha256:$archive_digest" '
        .tag_name == $tag and (.prerelease == false) and
        ([.assets[].name] | length == (unique | length)) and
        all(.assets[]; .state == "uploaded" and
            ((.name == $apk and .digest == $apkDigest) or
             (.name == $linux and .digest == $linuxDigest)))' >/dev/null
}
release=$(release_json)
matching_assets <<< "$release" || fail 'GitHub release metadata or assets do not match the prepared build.'
if jq -e '.draft == true' <<< "$release" >/dev/null; then
    for file in "$apk" "$archive"; do
        if ! jq -e --arg file "$file" 'any(.assets[]; .name == $file)' <<< "$release" >/dev/null; then
            gh release upload "$tag" --repo "$repo" "$assets/$file"
        fi
    done
    release=$(release_json)
    matching_assets <<< "$release" || fail 'Uploaded assets do not match the prepared build.'
    jq -e '.assets | length == 2' <<< "$release" >/dev/null || fail 'Both assets must be uploaded before publication.'
    gh release edit "$tag" --repo "$repo" --draft=false
fi
release=$(release_json)
matching_assets <<< "$release" || fail 'Published assets do not match the prepared build.'
jq -e '.draft == false and (.assets | length == 2)' <<< "$release" >/dev/null || fail 'Release is not public with both assets.'
jq -r .html_url <<< "$release"

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z $sdk ]] && command -v fish >/dev/null; then
    # shellcheck disable=SC2016
    sdk=$(fish -c 'printf "%s" "$ANDROID_HOME"') || sdk=''
fi
install_wifi_apk "$assets/$apk" "$sdk"
