#!/bin/zsh
#
# Checks that the Swift client and the Java server agree on the bridge
# protocol: starts BridgeServerMain, which serves an empty temporary directory
# without mounting anything, and runs the Swift InteropTests against it.
#
set -euo pipefail

SCRIPT_DIR="${0:A:h}"
FSKIT_DIR="${SCRIPT_DIR:h}"
REPO_ROOT="${FSKIT_DIR:h}"
WORK_DIR="$(mktemp -d)"
SERVER_LOG="$WORK_DIR/server.log"
SERVER_STDIN="$WORK_DIR/server.stdin"

# The server runs until its standard input yields anything or ends, so this script holds a pipe open and writes to it to stop the server.
mkfifo "$SERVER_STDIN"
echo "==> Starting Java server"
(cd "$REPO_ROOT" && ./mvnw -B -q test -Pmirror -P'!fskit-native' -Dmirror.mainClass=org.cryptomator.frontend.fskit.BridgeServerMain < "$SERVER_STDIN" > "$SERVER_LOG" 2>&1) &
SERVER_PID=$!
exec 3> "$SERVER_STDIN"

stop_server() {
	print -u3 stop
	exec 3>&-
	wait $SERVER_PID 2>/dev/null || true
	rm -rf "$WORK_DIR"
}
trap stop_server EXIT

RENDEZVOUS_DIR=""
for attempt in {1..240}; do
	RENDEZVOUS_DIR="$(sed -n 's/^RENDEZVOUS_DIR //p' "$SERVER_LOG")"
	if [[ -n "$RENDEZVOUS_DIR" ]]; then
		break
	elif ! kill -0 $SERVER_PID 2>/dev/null; then
		echo "error: the Java server exited early:" >&2
		cat "$SERVER_LOG" >&2
		exit 1
	fi
	sleep 0.5
done
if [[ -z "$RENDEZVOUS_DIR" ]]; then
	echo "error: the Java server did not start in time:" >&2
	cat "$SERVER_LOG" >&2
	exit 1
fi

echo "==> Running Swift client against $RENDEZVOUS_DIR"
(cd "$FSKIT_DIR" && FSKIT_INTEROP_RENDEZVOUS_DIR="$RENDEZVOUS_DIR" swift test --filter InteropTests)
