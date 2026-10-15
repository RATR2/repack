#!/usr/bin/env bash
# next-version.sh <released version> [next]  ->  the snapshot to continue with: next if given, else the next patch number.
set -euo pipefail
released=${1:?released version}
next=${2:-}
if [ -n "$next" ]; then
    echo "$next-SNAPSHOT"
    exit 0
fi
core=${released%%-*}
IFS=. read -r major minor patch <<< "$core"
echo "$major.$minor.$((patch + 1))-SNAPSHOT"
