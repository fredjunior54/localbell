#!/usr/bin/env bash
# Publish an explicitly selected, locally verified LocalBell build.
set -euo pipefail

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }

status() { printf '%s\n' "$*" >&2; }

upload_asset() (
    # Keep authorization out of curl's arguments and shell traces.
    set +x
    local endpoint=$1 path=$2 name=${2##*/} token response
    token=$(gh auth token --hostname github.com) || fail 'Could not obtain GitHub CLI authorization.'
    [[ -n $token && $token != *$'\n'* && $token != *$'\r'* ]] || fail 'Invalid GitHub authorization.'
    local options=(--proto '=https' --connect-timeout 15 --max-time 1800
        --fail --output /dev/null --write-out '%{http_code}' --request POST
        --header @- --header 'Content-Type: application/octet-stream' --data-binary "@$path")
    local headers="Authorization: Bearer $token"
    # The generated asset names need no URL escaping.
    if [[ -t 2 ]]; then
        response=$(curl -q --progress-bar "${options[@]}" "$endpoint?name=$name" <<< "$headers") || return 1
    else
        response=$(curl -q "${options[@]}" "$endpoint?name=$name" <<< "$headers" 2> >(tr '\r' '\n' >&2)) || return 1
    fi
    [[ $response == 201 ]]
)

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

refresh_dist() {
    local dist=$1 assets=$2 history=$3 entry archive
    shift 3
    local managed='^localbell(-[0-9]+\.[0-9]+\.[0-9]+(\.apk|-linux-amd64\.tar\.gz|-1\.x86_64\.rpm)|_[0-9]+\.[0-9]+\.[0-9]+_amd64\.deb|-linux-amd64\.tar\.gz|-migration-test\.apk)?$'
    [[ ! -L $dist ]] || fail 'Refusing to update a symlinked dist directory.'
    mkdir -p -- "$dist"
    local old=()
    for entry in "$dist"/*; do
        [[ ${entry##*/} =~ $managed ]] || continue
        [[ -f $entry && ! -L $entry ]] || fail "Refusing to move unexpected dist entry: $entry"
        old+=("$entry")
    done
    if ((${#old[@]})); then
        archive=$(mktemp -d "$history/previous-dist.XXXXXXXX")
        mv -- "${old[@]}" "$archive/"
        printf 'Previous local outputs moved to: %s\n' "$archive"
    fi
    for entry in "$@"; do cp -- "$assets/$entry" "$dist/"; done
}

build_release() (
    for tool in make docker dpkg-deb rpmbuild java keytool file tar; do
        command -v "$tool" >/dev/null || fail "Missing tool: $tool"
    done
    signing_dir=${ANDROID_SIGNING_DIR:-}
    if [[ -z $signing_dir ]] && command -v fish >/dev/null; then
        # shellcheck disable=SC2016
        signing_dir=$(fish -c 'printf "%s" "$ANDROID_SIGNING_DIR"')
    fi
    [[ -n $signing_dir && -d $signing_dir ]] || fail 'Set ANDROID_SIGNING_DIR to the local signing directory.'
    signing_dir=$(cd "$signing_dir" && pwd -P)
    shopt -s nullglob
    keys=("$signing_dir"/*.jks "$signing_dir"/*.keystore "$signing_dir"/*.p12)
    [[ ${#keys[@]} -eq 1 && -f ${keys[0]} && -r ${keys[0]} ]] || fail 'Expected exactly one readable .jks, .keystore, or .p12 file.'
    LOCALBELL_KEYSTORE=${keys[0]}
    if [[ -z ${KEYSTORE_PASSWORD:-} && -r $signing_dir/release-password ]]; then
        KEYSTORE_PASSWORD=$(<"$signing_dir/release-password")
    fi
    [[ -n ${KEYSTORE_PASSWORD:-} ]] || fail 'Provide KEYSTORE_PASSWORD or the local release-password file.'
    export KEYSTORE_PASSWORD LOCALBELL_KEYSTORE
    if [[ -z ${KEY_ALIAS:-} ]]; then
        aliases=$(keytool -J-Duser.language=en -J-Duser.country=US -list -keystore "$LOCALBELL_KEYSTORE" -storepass:env KEYSTORE_PASSWORD 2>/dev/null | sed -n 's/^\([^,]*\),.*PrivateKeyEntry.*/\1/p') || fail 'Could not unlock the keystore; check the local signing configuration.'
        [[ -n $aliases && $aliases != *$'\n'* ]] || fail 'Set KEY_ALIAS to the private key alias.'
        KEY_ALIAS=$aliases
    fi
    export KEY_ALIAS KEY_PASSWORD=${KEY_PASSWORD:-$KEYSTORE_PASSWORD}
    sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
    if [[ -z $sdk ]] && command -v fish >/dev/null; then
        # shellcheck disable=SC2016
        sdk=$(fish -c 'printf "%s" "$ANDROID_HOME"')
    fi
    [[ -d $sdk ]] || fail 'Set ANDROID_HOME to the Android SDK.'
    apksigners=("$sdk"/build-tools/*/apksigner)
    [[ ${#apksigners[@]} -gt 0 ]] || fail 'Install Android SDK build tools.'
    apksigner=$(printf '%s\n' "${apksigners[@]}" | LC_ALL=C sort -V | tail -n 1)
    aapt=${apksigner%/*}/aapt
    [[ -x $apksigner && -x $aapt ]] || fail 'Android SDK signing or inspection tool is not executable.'

    work=$(mktemp -d "$release_dir/work.XXXXXXXX")
    trap 'rm -rf -- "${work:?}"' EXIT
    mkdir "$work/tree"
    GIT_INDEX_FILE=$work/index git read-tree "$commit"
    GIT_INDEX_FILE=$work/index git checkout-index -a --prefix="$work/tree/"
    cd "$work/tree"
    code=$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+)$/\1/p' app/app/build.gradle.kts)
    [[ $code =~ ^[1-9][0-9]*$ ]] || fail 'Invalid Android version code in the release commit.'
    make release-linux VERSION="$version"
    ./scripts/build-linux-packages.sh all "$version"
    (cd app && ANDROID_HOME="$sdk" ./gradlew assembleRelease --no-daemon)
    apk=app/app/build/outputs/apk/release/app-release.apk
    "$apksigner" verify "$apk"
    badging=$("$aapt" dump badging "$apk")
    [[ $badging == *"package: name='io.github.fredjunior54.localbell' versionCode='$code' versionName='$version'"* ]] || fail 'APK metadata does not match the release.'
    archive=dist/localbell-$version-linux-amd64.tar.gz
    [[ $(tar -tzf "$archive") == localbell && -x dist/localbell ]] || fail 'Archive must contain only the desktop binary.'
    [[ $(file -b dist/localbell) == *'ELF 64-bit'*'x86-64'* && $(dist/localbell --version) == "$version" ]] || fail 'Desktop binary architecture or version does not match the release.'
    mkdir "$work/assets"
    cp "$apk" "$work/assets/localbell-$version.apk"
    cp "$archive" "dist/$deb" "dist/$rpm" "$work/assets/"
    printf '%s\n' "$commit" > "$work/assets/commit"
    (cd "$work/assets" && sha256sum -- "${files[@]}" > SHA256SUMS)
    mv -T -- "$work/assets" "$assets"
)

[[ $# -le 1 ]] || fail 'Usage: scripts/publish-release.sh [vX.Y.Z]'
repo=fredjunior54/localbell
cd "$(git rev-parse --show-toplevel)"
[[ $(git remote get-url origin) == "git@github.com:$repo.git" && $(git remote get-url --push origin) == "git@github.com:$repo.git" ]] || fail 'Origin must point to LocalBell over SSH.'
head=$(git rev-parse HEAD)
android_version() {
    git show "$1:app/app/build.gradle.kts" | sed -nE 's/^[[:space:]]*versionName = "([0-9]+\.[0-9]+\.[0-9]+)"$/\1/p'
}
if [[ $# == 0 ]]; then
    tag=v$(android_version "$head")
else
    tag=$1
fi
[[ $tag =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || fail 'Expected a version in vX.Y.Z format.'
version=${tag#v}
release_dir=$(git rev-parse --absolute-git-dir)/localbell-releases
mkdir -p "$release_dir"
exec 9>"$release_dir/lock"
flock -n 9 || fail 'Another LocalBell build or publication is running.'

for tool in gh jq curl; do
    command -v "$tool" >/dev/null || fail "Missing tool: $tool"
done
gh auth status --hostname github.com >/dev/null 2>&1 || fail 'Authenticate GitHub CLI first.'
find_release() {
    gh api --hostname github.com --paginate --slurp "repos/$repo/releases?per_page=100" |
        jq -ce --arg tag "$tag" '
            if type != "array" or any(.[]; type != "array") then error("Unexpected release list")
            else [.[][] | select(.tag_name == $tag)] |
                if length > 1 then error("Multiple releases match the tag") else first // {} end
            end'
}
release=$(find_release) || fail 'Could not check GitHub for the selected release.'
if jq -e '.draft == false' <<< "$release" >/dev/null; then
    url=$(jq -er '.html_url' <<< "$release") || fail 'Published release has no URL.'
    printf '%s is already published: %s\n' "$tag" "$url"
    exit 0
fi
id=$(jq -r '.id // empty' <<< "$release")
if [[ $release != '{}' ]]; then
    [[ $id =~ ^[0-9]+$ ]] || fail 'Unexpected release ID.'
    jq -e '.draft == true' <<< "$release" >/dev/null || fail 'Unexpected release metadata.'
fi
assets=$release_dir/$tag
apk=localbell-$version.apk
archive=localbell-$version-linux-amd64.tar.gz
deb=localbell_${version}_amd64.deb
rpm=localbell-$version-1.x86_64.rpm
files=("$apk" "$archive" "$deb" "$rpm")
git fetch --no-tags origin refs/heads/main
if [[ -e $assets || -L $assets ]]; then
    [[ -d $assets && ! -L $assets ]] || fail 'Unexpected retained build directory.'
    for file in commit SHA256SUMS "${files[@]}"; do
        [[ -f $assets/$file && ! -L $assets/$file ]] || fail "Missing or unexpected retained file: $file. For a legacy two-asset draft, retain the existing build and publish a new patch with all four assets; do not overwrite the draft assets."
    done
    commit=$(<"$assets/commit")
    [[ $commit =~ ^[0-9a-f]{40}$ && $(git rev-parse --verify "$commit^{commit}") == "$commit" ]] || fail 'Invalid retained commit.'
elif [[ $# == 0 ]]; then
    commit=$head
else
    # Select the commit that introduced this version, not a later commit sharing it.
    pattern=${version//./[.]}
    candidates=$(git log --first-parent --format=%H -G "^[[:space:]]*versionName = \"$pattern\"$" FETCH_HEAD -- app/app/build.gradle.kts)
    commit=''
    for candidate in $candidates; do
        if [[ $(android_version "$candidate") == "$version" ]]; then
            [[ -z $commit ]] || fail 'Multiple commits introduced this version.'
            commit=$candidate
        fi
    done
    [[ -n $commit ]] || fail "No commit introduced $tag on origin/main."
fi
[[ $# != 0 || $commit == "$head" ]] || fail 'Retained artifacts do not belong to the current commit.'
git merge-base --is-ancestor "$commit" FETCH_HEAD || fail 'The release commit is not on origin/main.'
[[ $(android_version "$commit") == "$version" ]] || fail 'Release commit has the wrong Android version.'
server_version=$(git show "$commit:server/Cargo.toml" | sed -nE 's/^version = "([0-9]+\.[0-9]+\.[0-9]+)"$/\1/p')
[[ $server_version == "$version" ]] || fail 'Android and Linux versions differ in the release commit.'
ref=refs/tags/$tag
remote=$(git ls-remote origin "$ref" "$ref^{}")
if [[ -n $remote ]]; then
    remote_commit=$(awk -v ref="$ref" '$2 == ref { direct = $1 } $2 == ref "^{}" { peeled = $1 } END { print (peeled ? peeled : direct) }' <<< "$remote")
    [[ $remote_commit == "$commit" ]] || fail 'Remote tag points to a different commit.'
fi
if git show-ref --verify --quiet "$ref"; then
    [[ $(git rev-parse "$ref^{commit}") == "$commit" ]] || fail 'Local tag points to a different commit.'
fi
if [[ ! -d $assets ]]; then
    jq -e '(.assets // []) | length == 0' <<< "$release" >/dev/null || fail 'Cannot rebuild an existing draft with uploaded assets; recover the retained build.'
    printf 'Building signed release artifacts for %s (%s).\n' "$tag" "$commit"
    build_release
else
    status "Reusing retained release artifacts for $tag."
fi
checksums=$(cd "$assets" && sha256sum -- "${files[@]}")
[[ $checksums == "$(<"$assets/SHA256SUMS")" ]] || fail 'Retained artifacts do not match their checksums.'
expected=$(jq -Rn '[inputs | split("  ") | {(.[1]): ("sha256:" + .[0])}] | add' <<< "$checksums")
refresh_dist "$PWD/dist" "$assets" "$release_dir" "${files[@]}"
if [[ -z $remote ]] && ! git show-ref --verify --quiet "$ref"; then
    git tag -a "$tag" "$commit" -m "Release $tag"
fi
if [[ -z $remote ]]; then
    status "Pushing tag $tag to GitHub."
    git push origin "$ref:$ref"
else
    status "Tag $tag is already on GitHub."
fi

if [[ -z $id ]]; then
    status "Creating a draft release for $tag."
    release=$(gh api --hostname github.com --method POST "repos/$repo/releases" \
        -f "tag_name=$tag" -f "target_commitish=$commit" -F draft=true -F generate_release_notes=true) ||
        fail 'Could not create the GitHub release draft.'
    id=$(jq -er --arg tag "$tag" 'select(.draft == true and .tag_name == $tag) | .id' <<< "$release") ||
        fail 'GitHub did not return the requested release draft.'
else
    status "Resuming the draft release for $tag."
fi
[[ $id =~ ^[0-9]+$ ]] || fail 'Could not identify the GitHub release.'
release_json() { gh api --hostname github.com "repos/$repo/releases/$id"; }
matching_assets() {
    jq -e --arg tag "$tag" --argjson expected "$expected" '
        .tag_name == $tag and (.prerelease == false) and
        ([.assets[].name] | length == (unique | length)) and
        all(.assets[]; .state == "uploaded" and
            ($expected[.name] != null and .digest == $expected[.name]))' >/dev/null
}
matching_assets <<< "$release" || fail 'GitHub release metadata or assets do not match the prepared build.'
if jq -e '.draft == true' <<< "$release" >/dev/null; then
    endpoint="https://uploads.github.com/repos/$repo/releases/$id/assets"
    advertised=$(jq -er '.upload_url' <<< "$release") || fail 'GitHub did not return an upload URL.'
    [[ $advertised == "$endpoint{?name,label}" ]] || fail 'Unexpected GitHub release upload URL.'
    for file in "${files[@]}"; do
        if jq -e --arg file "$file" 'any(.assets[]; .name == $file)' <<< "$release" >/dev/null; then
            status "Already uploaded and verified: $file."
        else
            status "Uploading $file ($(stat -c '%s' -- "$assets/$file") bytes). Waiting for GitHub confirmation after transfer."
            upload_asset "$endpoint" "$assets/$file" ||
                fail "GitHub did not confirm $file. Rerun the same command to check the draft and retry."
            status "GitHub accepted $file."
        fi
    done
    status 'Verifying all remote asset checksums.'
    release=$(release_json)
    matching_assets <<< "$release" || fail 'Uploaded assets do not match the prepared build.'
    jq -e '.assets | length == 4' <<< "$release" >/dev/null || fail 'All four assets must be uploaded before publication.'
    status "Publishing $tag on GitHub."
    gh api --hostname github.com --method PATCH "repos/$repo/releases/$id" -F draft=false >/dev/null
fi
status 'Checking the public release.'
release=$(release_json)
matching_assets <<< "$release" || fail 'Published assets do not match the prepared build.'
jq -e '.draft == false and (.assets | length == 4)' <<< "$release" >/dev/null || fail 'Release is not public with all four assets.'
jq -r .html_url <<< "$release"

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z $sdk ]] && command -v fish >/dev/null; then
    # shellcheck disable=SC2016
    sdk=$(fish -c 'printf "%s" "$ANDROID_HOME"') || sdk=''
fi
status 'Checking for the configured Wi-Fi ADB phone.'
# Do not let a newly started ADB daemon inherit the release lock.
install_wifi_apk "$assets/$apk" "$sdk" 9>&-
