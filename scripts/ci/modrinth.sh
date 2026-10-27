#!/usr/bin/env bash
# Publishes a release jar to Modrinth. Needs MODRINTH_TOKEN (a personal access token with the "Create versions" scope) and
# MODRINTH_PROJECT_ID; without either, nothing is published. VERSION, JAR and NOTES (a Markdown file) say what to publish.
# Game versions are every release from MIN_GAME_VERSION up to the newest one Modrinth knows.
set -euo pipefail

if [ -z "${MODRINTH_TOKEN:-}" ] || [ -z "${MODRINTH_PROJECT_ID:-}" ]; then
    echo "MODRINTH_TOKEN or MODRINTH_PROJECT_ID is not set, so nothing was published to Modrinth."
    exit 0
fi
: "${VERSION:?}" "${JAR:?}" "${NOTES:?}"
MIN_GAME_VERSION=${MIN_GAME_VERSION:-1.21.4}
API=https://api.modrinth.com/v2
CURL=(curl -fsS -A "RATR2/repack (github.com/RATR2/repack)")

# The tag list is newest first; keep the releases from the newest down to the oldest one Repack supports.
games=$("${CURL[@]}" "$API/tag/game_version" | jq -c --arg min "$MIN_GAME_VERSION" '
    map(select(.version_type == "release")) | map(.version) as $all
    | ($all | index($min)) as $i
    | if $i == null then error("Modrinth has no game version " + $min) else $all[0:$i + 1] end')

# PacketEvents is required at the version Repack is built against (build.gradle.kts); the rest are optional and use any version.
pe_version=$(grep -oE 'packetevents-spigot:[0-9.]+' build.gradle.kts | head -1 | cut -d: -f2)
pe=$("${CURL[@]}" "$API/project/packetevents/version?loaders=%5B%22spigot%22%5D" | jq -c --arg v "${pe_version}+spigot" 'map(select(.version_number == $v)) | first | {project_id, version_id: .id}')
[ "$pe" != "null" ] || { echo "PacketEvents ${pe_version}+spigot is not on Modrinth." >&2; exit 1; }
optional=$(for slug in worldguard worldedit fastasyncworldedit viaversion viabackwards skript; do
    "${CURL[@]}" "$API/project/$slug" | jq -c '{project_id: .id}'
done | jq -sc 'map(. + {dependency_type: "optional"})')

type=release
case "$VERSION" in *-*) type=beta ;; esac

data=$(jq -n \
    --arg project "$MODRINTH_PROJECT_ID" --arg version "$VERSION" --arg type "$type" \
    --arg changelog "$(cat "$NOTES")" --argjson pe "$pe" --argjson optional "$optional" --arg file "$(basename "$JAR")" \
    --argjson games "$games" '{
        project_id: $project,
        name: $version,
        version_number: $version,
        changelog: $changelog,
        version_type: $type,
        game_versions: $games,
        loaders: ["paper", "purpur"],
        dependencies: ([$pe + {dependency_type: "required"}] + $optional),
        featured: true,
        file_parts: ["file"],
        primary_file: "file"
    }')

# Without -f for this call, so a rejection shows Modrinth's own reason instead of only the status code.
response=$(curl -sS -A "RATR2/repack (github.com/RATR2/repack)" -w '\n%{http_code}' -X POST "$API/version" -H "Authorization: $MODRINTH_TOKEN" \
    -F "data=$data;type=application/json" -F "file=@$JAR;type=application/java-archive;filename=$(basename "$JAR")")
status=${response##*$'\n'}
if [ "$status" != 200 ]; then
    echo "Modrinth refused $VERSION (HTTP $status): ${response%$'\n'*}" >&2
    exit 1
fi
echo "Published $VERSION to Modrinth for ${MIN_GAME_VERSION} and newer."
