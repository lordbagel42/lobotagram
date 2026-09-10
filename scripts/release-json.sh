#!/usr/bin/env bash
# Emit the patches.json that ReVanced Manager's "Enter URL" flow consumes.
#
#   scripts/release-json.sh <version> <created_at> <notes-file> <download_url>
#                           [signature_download_url]
#
# The schema is app.revanced.manager.network.dto.ReVancedAsset: download_url,
# created_at, description, version, and the optional signature_download_url.
# See docs/06-revanced-manager-source.md.
#
# created_at is a kotlinx.datetime.LocalDateTime, so it must be ISO-8601 with
# NO offset and NO trailing Z. Anything this script is given is converted to
# UTC and stripped, which is what api.revanced.app emits too.

set -euo pipefail

die() { echo "release-json.sh: $*" >&2; exit 1; }

[ "$#" -ge 4 ] || die "usage: release-json.sh <version> <created_at> <notes-file> <download_url> [signature_download_url]"

version="$1"
created_at_raw="$2"
notes_file="$3"
download_url="$4"
signature_download_url="${5:-}"

[ -n "$version" ] || die "version is empty"
[ -n "$download_url" ] || die "download_url is empty"
[ -f "$notes_file" ] || die "notes file $notes_file does not exist"

command -v jq > /dev/null || die "jq is required"

# Normalise to UTC and drop the zone: 2026-09-10T12:34:56, never ...Z.
created_at="$(date -u -d "$created_at_raw" +%Y-%m-%dT%H:%M:%S)" \
    || die "could not parse a date out of '$created_at_raw'"

# description has no default in ReVancedAsset, so it must be a non-empty
# string or Manager reports "This URL is pointing to an unsupported source."
description="$(cat "$notes_file")"
if [ -z "${description//[[:space:]]/}" ]; then
    description="Lobotagram $version"
fi

jq -n \
    --arg version "$version" \
    --arg created_at "$created_at" \
    --arg description "$description" \
    --arg download_url "$download_url" \
    --arg signature_download_url "$signature_download_url" \
    '{
        version: $version,
        created_at: $created_at,
        description: $description,
        download_url: $download_url,
    }
    + (if $signature_download_url == "" then {}
       else { signature_download_url: $signature_download_url } end)'
