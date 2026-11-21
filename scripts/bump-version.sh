#!/usr/bin/env bash
# Release only the explicitly listed files and the synchronized version metadata.
set -euo pipefail

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
usage() { fail 'Usage: scripts/bump-version.sh [patch|minor|major] "type: summary" -- path [path ...]'; }

bump='patch'
case ${1:-} in patch|minor|major) bump=$1; shift ;; esac
[[ $# -ge 3 && $2 == -- ]] || usage
subject=$1
shift 2
[[ $subject =~ ^(feat|fix|docs|chore|refactor):\ .+ && ${#subject} -le 72 && $subject != *$'\n'* ]] || usage

cd "$(git rev-parse --show-toplevel)"
root=$PWD
branch=$(git symbolic-ref --quiet --short HEAD) || fail 'Check out main before releasing.'
[[ $branch == main ]] || fail 'Releases must be cut from main.'
head=$(git rev-parse HEAD)
repo=fredjunior54/localbell
[[ $(git remote get-url origin) == "git@github.com:$repo.git" && $(git remote get-url --push origin) == "git@github.com:$repo.git" ]] || fail 'Origin must point to LocalBell over SSH.'

paths=()
add_path() {
    local path=$1 existing
    case $path in /*|.*/*/../*|../*|*/../*|*/..|./*|*//*|.git|.git/*|.agents|.agents/*|*.jks|*.keystore|*.p12|*.pem|*.key|.env|*/.env|.env.*|*/.env.*) fail "Unsupported release path: $path" ;; esac
    [[ ! -d $path && ! -L $path ]] || fail "Select individual regular files: $path"
    for existing in "${paths[@]}"; do [[ $existing != "$path" ]] || return 0; done
    paths+=("$path")
}
for path; do add_path "$path"; done
export GIT_LITERAL_PATHSPECS=1
if ! grep -Fxq README.md <<< "$(printf '%s\n' "${paths[@]}")"; then
    if ! git ls-files --error-unmatch README.md >/dev/null 2>&1 ||
        ! git diff --quiet HEAD -- README.md || ! git diff --cached --quiet -- README.md; then
        fail 'README.md has unselected changes; select it explicitly or set those changes aside.'
    fi
fi
metadata=(app/app/build.gradle.kts packaging/arch/PKGBUILD packaging/arch/.SRCINFO server/Cargo.toml server/Cargo.lock README.md)
for path in "${metadata[@]}"; do add_path "$path"; done

for tool in gh make makepkg cargo java keytool file tar flock; do
    command -v "$tool" >/dev/null || fail "Missing tool: $tool"
done
remote_head=$(git ls-remote --exit-code origin refs/heads/main)
[[ ${remote_head%%$'\t'*} == "$(git rev-parse refs/remotes/origin/main)" ]] || fail 'Fetch and review origin/main before releasing.'
git merge-base --is-ancestor "${remote_head%%$'\t'*}" "$head" || fail 'Local main is behind origin.'
gh auth status --hostname github.com >/dev/null 2>&1 || fail 'Authenticate GitHub CLI first.'

read_version() { sed -nE 's/^[[:space:]]*versionName = "([0-9]+\.[0-9]+\.[0-9]+)"$/\1/p'; }
read_readme_version() { sed -nE 's@^<p align="center"><strong>Version ([0-9]+\.[0-9]+\.[0-9]+)</strong></p>$@\1@p'; }
read_code() { sed -nE 's/^[[:space:]]*versionCode = ([0-9]+)$/\1/p'; }
current=$(git show "HEAD:${metadata[0]}" | read_version)
code=$(git show "HEAD:${metadata[0]}" | read_code)
[[ $current =~ ^(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})$ && $code =~ ^[1-9][0-9]{0,9}$ && $code -lt 2100000000 ]] || fail 'Invalid checked-in Android version.'
[[ $(read_version < "${metadata[0]}") == "$current" && $(read_code < "${metadata[0]}") == "$code" ]] || fail 'Do not edit Android version fields before releasing.'
IFS=. read -r major minor patch <<< "$current"
case $bump in
    major) major=$((major + 1)); minor=0; patch=0 ;;
    minor) minor=$((minor + 1)); patch=0 ;;
    patch) patch=$((patch + 1)) ;;
esac
version=$major.$minor.$patch
tag=v$version
[[ $major -lt 1000000000 && $minor -lt 1000000000 && $patch -lt 1000000000 ]] || fail 'Version component overflow.'
if git show-ref --verify --quiet "refs/tags/$tag"; then fail "Local tag already exists: $tag"; fi
remote_tag=$(git ls-remote --refs origin "refs/tags/$tag")
[[ -z $remote_tag ]] || fail "Remote tag already exists: $tag"

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
export ANDROID_HOME=$sdk
apksigners=("$sdk"/build-tools/*/apksigner)
[[ ${#apksigners[@]} -gt 0 ]] || fail 'Install Android SDK build tools.'
apksigner=$(printf '%s\n' "${apksigners[@]}" | LC_ALL=C sort -V | tail -n 1)
aapt=${apksigner%/*}/aapt
[[ -x $apksigner && -x $aapt ]] || fail 'Android SDK signing or inspection tool is not executable.'

release_dir=$(git rev-parse --absolute-git-dir)/localbell-releases
mkdir -p "$release_dir"
exec 9>"$release_dir/lock"
flock -n 9 || fail 'Another LocalBell release is running.'
[[ ! -e $release_dir/$tag ]] || fail "Artifacts already exist for $tag; inspect $release_dir/$tag."
work=$(mktemp -d "$release_dir/work.XXXXXXXX")
trap 'rm -rf -- "${work:?}"' EXIT
mkdir "$work/tree"
index=$work/index
GIT_INDEX_FILE=$index git read-tree "$head"
# Read selected paths from the working tree; staged but unselected changes stay out.
GIT_INDEX_FILE=$index git add -A -- "${paths[@]}"
selected_tree=$(GIT_INDEX_FILE=$index git write-tree)
[[ $selected_tree != "$(git rev-parse "$head^{tree}")" ]] || fail 'No selected changes to release.'
GIT_INDEX_FILE=$index git checkout-index -a --prefix="$work/tree/"
(
    cd "$work/tree"
    sed -i -E "s/(versionName = )\"[^\"]+\"/\1\"$version\"/; s/(versionCode = )[0-9]+/\1$((code + 1))/" "${metadata[0]}"
    [[ $(read_readme_version < README.md) == "$current" ]] || fail 'Expected one README version matching the current Android version.'
    sed -i -E "s#^(<p align=\"center\"><strong>Version )[0-9]+\.[0-9]+\.[0-9]+(</strong></p>)\$#\1${version}\2#" README.md
    [[ $(read_readme_version < README.md) == "$version" ]] || fail 'Could not synchronize the README version.'
    sed -i -E "s/^pkgver=.*/pkgver=$version/" "${metadata[1]}"
    sed -i -E "s/^version = \"$current\"$/version = \"$version\"/" server/Cargo.toml
    sed -i "/^name = \"localbell\"$/ { n; s/^version = .*/version = \"$version\"/; }" server/Cargo.lock
    (cd packaging/arch && makepkg --printsrcinfo > .SRCINFO)
    [[ $(read_version < "${metadata[0]}") == "$version" && $(read_code < "${metadata[0]}") == "$((code + 1))" ]]
    grep -qx "pkgver=$version" "${metadata[1]}"
    [[ $(sed -n 's/^[[:space:]]*pkgver = //p' "${metadata[2]}") == "$version" ]]
    grep -q '^Version:.*%{pkg_version}' packaging/rpm/lanotifica.spec
    grep -Fqx "Version: \${VERSION}" packaging/deb/DEBIAN/control
    env -u LOCALBELL_KEYSTORE -u KEYSTORE_PASSWORD -u KEY_ALIAS -u KEY_PASSWORD make test lint
    make release-linux VERSION="$version"
    (cd app && ./gradlew assembleRelease --no-daemon)
    apk=app/app/build/outputs/apk/release/app-release.apk
    "$apksigner" verify "$apk"
    badging=$("$aapt" dump badging "$apk")
    [[ $badging == *"package: name='io.github.fredjunior54.localbell' versionCode='$((code + 1))' versionName='$version'"* ]] || fail 'APK metadata does not match the release.'
    archive=dist/localbell-$version-linux-amd64.tar.gz
    [[ $(tar -tzf "$archive") == localbell && -x dist/localbell ]]
    [[ $(file -b dist/localbell) == *'ELF 64-bit'*'x86-64'* && $(dist/localbell --version) == "$version" ]]
    mkdir "$work/assets"
    cp "$apk" "$work/assets/localbell-$version.apk"
    cp "$archive" "$work/assets/"
)

[[ $(git rev-parse HEAD) == "$head" && $(git symbolic-ref --short HEAD) == "$branch" ]] || fail 'Checkout changed during the build.'
GIT_INDEX_FILE=$index git read-tree "$head"
GIT_INDEX_FILE=$index git add -A -- "${paths[@]}"
[[ $(GIT_INDEX_FILE=$index git write-tree) == "$selected_tree" ]] || fail 'Selected files changed during the build.'
for path in "${metadata[@]}"; do
    object=$(git hash-object -w "$work/tree/$path")
    GIT_INDEX_FILE=$index git update-index --cacheinfo "100644,$object,$path"
    cp "$work/tree/$path" "$root/$path"
done
expected_tree=$(GIT_INDEX_FILE=$index git write-tree)
git add -A -- "${paths[@]}"
git diff --cached --check -- "${paths[@]}"
git commit --only -m "$subject" -- "${paths[@]}"
[[ $(git rev-parse 'HEAD^{tree}') == "$expected_tree" ]] || fail 'Committed tree differs from the built tree; not tagging.'
git tag -a "$tag" -m "Release $tag"
mv "$work/assets" "$release_dir/$tag"
printf 'Verified release assets: %s/%s\n' "$release_dir" "$tag"
git push --atomic origin "HEAD:refs/heads/$branch" "refs/tags/$tag:refs/tags/$tag"
gh release create "$tag" --repo "$repo" --verify-tag --generate-notes \
    "$release_dir/$tag/localbell-$version.apk" "$release_dir/$tag/localbell-$version-linux-amd64.tar.gz"
