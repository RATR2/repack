#!/usr/bin/env bash
# set-version.sh <version>  ->  writes the version into gradle.properties.
set -euo pipefail
cd "$(dirname "$0")/../.."
sed -i -E "s/^version=.*/version=${1:?version}/" gradle.properties
grep -qx "version=$1" gradle.properties
