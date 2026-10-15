#!/usr/bin/env bash
# Reads the tags from a commit message (default: the last commit) and prints them as KEY=value lines.
# A tag is a line of its own, so a message that only talks about "release" in a sentence does nothing:
#   version="0.1.0"   the version to release (or to set, without release)
#   release           publish a GitHub release for that version
#   next="0.2.0"      the version to continue with after the release (default: the next patch number)
# Also prints NOTES_FILE, the message without its tag lines, for the release description.
set -euo pipefail

message=${1:-$(git log -1 --pretty=%B)}
notes_file=${2:-}

version=$(printf '%s\n' "$message" | sed -nE 's/^version="([0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?)"[[:space:]]*$/\1/p' | head -1)
next=$(printf '%s\n' "$message" | sed -nE 's/^next="([0-9]+\.[0-9]+\.[0-9]+)"[[:space:]]*$/\1/p' | head -1)
release=false
if printf '%s\n' "$message" | grep -qxE 'release[[:space:]]*'; then release=true; fi

if [ -n "$notes_file" ]; then
    printf '%s\n' "$message" \
        | grep -vxE '(version="[^"]*"|next="[^"]*"|release|tags:?)[[:space:]]*' \
        | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}' > "$notes_file"
fi

echo "VERSION=$version"
echo "NEXT=$next"
echo "RELEASE=$release"
