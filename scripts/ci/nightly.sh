#!/usr/bin/env bash
# Builds the commit that is checked out (the workflow runs it for every commit on main) and publishes it to the nightly repo as a branch named after the commit:
# the jar and a README with the commit's name and description. The nightly repo's main README then points at the latest one.
# NIGHTLY_TOKEN is a token that can push to RATR2/repack-nightly (the REPACK_NIGHTLY_TOKEN secret). For a test, NIGHTLY_REPO_URL
# can name any other git remote, such as a local bare repository. FORCE=true publishes even when this commit was the last one.
set -euo pipefail
cd "$(dirname "$0")/../.."

SHA_FULL=$(git rev-parse HEAD)
SHA=${SHA_FULL:0:7}
SOURCE_WEB=https://github.com/RATR2/repack
NIGHTLY_WEB=https://github.com/RATR2/repack-nightly
KEEP=${NIGHTLY_KEEP:-30}

if [ -z "${NIGHTLY_REPO_URL:-}" ]; then
    if [ -z "${NIGHTLY_TOKEN:-}" ]; then
        echo "The REPACK_NIGHTLY_TOKEN secret is not set, so there is nowhere to publish the nightly build." >&2
        exit 1
    fi
    NIGHTLY_REPO_URL="https://x-access-token:${NIGHTLY_TOKEN}@github.com/RATR2/repack-nightly.git"
fi
GIT=(git -c user.name="github-actions[bot]" -c user.email="41898282+github-actions[bot]@users.noreply.github.com")

# The nightly repo's main branch holds the README and .builds, the list of published commits, oldest first.
MAIN_DIR=$(mktemp -d)
if git ls-remote --exit-code --heads "$NIGHTLY_REPO_URL" main >/dev/null 2>&1; then
    git clone -q --branch main "$NIGHTLY_REPO_URL" "$MAIN_DIR"
else
    git init -q -b main "$MAIN_DIR"
    git -C "$MAIN_DIR" remote add origin "$NIGHTLY_REPO_URL"
fi
touch "$MAIN_DIR/.builds"
if [ "$(tail -n 1 "$MAIN_DIR/.builds")" = "$SHA" ] && [ "${FORCE:-false}" != "true" ]; then
    echo "Nothing new: $SHA is already the latest nightly."
    exit 0
fi

# The technical version: 0.1.2-SNAPSHOT becomes 0.1.2-snapshot-nightly.
BASE=$(grep -E '^version=' gradle.properties | head -1 | cut -d= -f2 | tr '[:upper:]' '[:lower:]')
VERSION="${BASE}-nightly"
./gradlew build -Pversion="$VERSION" --console=plain
JAR="build/libs/repack-${VERSION}.jar"
test -f "$JAR"

SUBJECT=$(git log -1 --format=%s)
BODY=$(git log -1 --format=%b | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}')

# One branch per build, with no history of its own: the jar and its README.
BUILD_DIR=$(mktemp -d)
git init -q -b "$SHA" "$BUILD_DIR"
git -C "$BUILD_DIR" remote add origin "$NIGHTLY_REPO_URL"
cp "$JAR" "$BUILD_DIR/"
{
    echo '<div align="center">'
    echo
    echo "**${SUBJECT} - [\`${SHA}\`](${SOURCE_WEB}/commit/${SHA_FULL})**"
    if [ -n "$BODY" ]; then
        echo
        echo "$BODY"
    fi
    echo
    echo '</div>'
    echo
    echo "<sub>Technical version: \`${VERSION}\`</sub>"
} > "$BUILD_DIR/README.md"
"${GIT[@]}" -C "$BUILD_DIR" add .
"${GIT[@]}" -C "$BUILD_DIR" commit -q -m "$SUBJECT"
git -C "$BUILD_DIR" push -q --force origin "$SHA"

# main: remember the build, point the README at it, and drop the oldest builds beyond the ones we keep.
{ grep -vxF "$SHA" "$MAIN_DIR/.builds" || true; echo "$SHA"; } > "$MAIN_DIR/.builds.new"
mv "$MAIN_DIR/.builds.new" "$MAIN_DIR/.builds"
EXCESS=$(( $(wc -l < "$MAIN_DIR/.builds") - KEEP ))
if [ "$EXCESS" -gt 0 ]; then
    for old in $(head -n "$EXCESS" "$MAIN_DIR/.builds"); do
        git -C "$MAIN_DIR" push -q origin --delete "$old" 2>/dev/null || true
    done
    tail -n "$KEEP" "$MAIN_DIR/.builds" > "$MAIN_DIR/.builds.new"
    mv "$MAIN_DIR/.builds.new" "$MAIN_DIR/.builds"
fi
cat > "$MAIN_DIR/README.md" <<README
# Repack nightly

Nightly builds of [Repack](${SOURCE_WEB}). Every build is a branch named after the commit it was built from.

latest publish: [\`${SHA}\`](${SOURCE_WEB}/commit/${SHA_FULL}) ([branch](${NIGHTLY_WEB}/tree/${SHA}))
README
"${GIT[@]}" -C "$MAIN_DIR" add README.md .builds
"${GIT[@]}" -C "$MAIN_DIR" commit -q -m "$SUBJECT"
git -C "$MAIN_DIR" push -q origin main
echo "Published $SHA as $VERSION."
