#!/bin/zsh
#
# Checks the Swift sources with SwiftFormat and SwiftLint, and fails when
# either of them reports anything.
#
# With --staged it formats and lints the staged Swift files instead, for use
# as a pre-commit hook. The formatting goes into the index and the working
# tree. The script then fails, so that the hook holds the commit back and the
# formatting can be reviewed.
#
# Based on https://merowing.info/posts/improve-build-times-by-extracting-3rd-party-tooling-to-processing-script/
#
set -euo pipefail

SCRIPT_DIR="${0:A:h}"
FSKIT_DIR="${SCRIPT_DIR:h}"
REPO_ROOT="${FSKIT_DIR:h}"
FINAL_STATUS=0

# git-format-staged.py exits with 0 after it reformatted a file, so anything it prints counts as a failure as well.
process_output() {
	echo "==> Running $1"
	local output tool_status=0
	output="$("${@:2}" 2>&1)" || tool_status=$?
	if (( tool_status )) || [[ -n "$output" ]]; then
		print -r -- "$output"
		FINAL_STATUS=1
	fi
}

if [[ "${1:-}" == "--staged" ]]; then
	# git-format-staged.py passes paths relative to the repository root
	cd "$REPO_ROOT"
	process_output "SwiftFormat" python3 fskit/scripts/git-format-staged.py -f 'swiftformat stdin --stdinpath "{}" --quiet' 'fskit/*.swift'
	process_output "SwiftLint" python3 fskit/scripts/git-format-staged.py --no-write -f 'swiftlint --use-stdin --strict --quiet --config fskit/.swiftlint.yml >&2' 'fskit/*.swift'
	if (( FINAL_STATUS )); then
		echo "error: changes were made or are required, see the output above" >&2
	fi
else
	cd "$FSKIT_DIR"
	swiftformat --lint . || FINAL_STATUS=1
	swiftlint --strict --quiet . || FINAL_STATUS=1
fi

exit $FINAL_STATUS
