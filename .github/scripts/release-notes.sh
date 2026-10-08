#!/bin/sh
#
# Prints the section of CHANGELOG.md for a version, which becomes the body of
# its GitHub release, and fails when the changelog has none.
#
# Usage: release-notes.sh <version>
#
set -eu

version="${1:?usage: release-notes.sh <version>}"
changelog="$(dirname "$0")/../../CHANGELOG.md"

# the section runs from its heading "## [<version>](...)" to the next "## " heading
notes="$(awk -v heading="## [$version]" '
	index($0, heading) == 1 { found = 1; next }
	found && /^## / { exit }
	found { print }
' "$changelog" | sed '/./,$!d')"

if [ -z "$notes" ]; then
	echo "error: CHANGELOG.md has no section for $version" >&2
	exit 1
fi
printf '%s\n' "$notes"
