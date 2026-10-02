#!/bin/zsh
#
# Exercises mounted volumes: starts SmokeMountMain, which mounts and unmounts
# directories and a throwaway vault on command, works on each volume through
# the shell, and scans the JVM's log for failures the shell did not see.
#
# Needs macOS 27 and the extension, built with the default file system type
# name, installed and enabled, see the README.
#
# Usage: smoke-test.sh [--stop-on-failure] [scenario ...]
#
#   --stop-on-failure  stop at the first failed check and leave the volumes as
#                      they are until return is pressed. Needs a terminal.
#   scenario           run only the named scenarios instead of all of them
#
# Scenarios:
#   basics io listing modes renames names links unreadable-directory
#   open-unlinked parallel two-mounts occupied external-unmount busy-unmount
#   read-only vault
#
# Exit status: 0 if every check passed, 1 if checks failed, 2 if the test could
# not run, 129, 130 or 143 if a signal ended it.
#
set -euo pipefail

SCRIPT_DIR="${0:A:h}"
FSKIT_DIR="${SCRIPT_DIR:h}"
REPO_ROOT="${FSKIT_DIR:h}"
SCENARIOS=(basics io listing modes renames names links unreadable-directory open-unlinked parallel two-mounts occupied external-unmount busy-unmount read-only vault)

usage() {
	echo "usage: smoke-test.sh [--stop-on-failure] [scenario ...]" >&2
	echo "scenarios: $SCENARIOS" >&2
	exit 2
}

STOP_ON_FAILURE=0
typeset -aU SELECTED
for argument in "$@"; do
	if [[ "$argument" == --stop-on-failure ]]; then
		STOP_ON_FAILURE=1
	elif (( ${SCENARIOS[(Ie)$argument]} )); then
		SELECTED+=("$argument")
	else
		usage
	fi
done
if (( STOP_ON_FAILURE )) && [[ ! -t 0 ]]; then
	echo "error: --stop-on-failure needs a terminal on standard input" >&2
	usage
fi
if (( ${#SELECTED} == 0 )); then
	SELECTED=($SCENARIOS)
fi

PROGRAM_PID=""
integer CHECKS=0
typeset -a FAILED
# the offset in the program's log at which its last reply ends. Replies and the log lines of the sessions share one stream, so whatever the JVM logged before it answered a command lies before that reply.
integer REPLY_END=0
integer FIRST_MOUNT=1
typeset -A RENDEZVOUS_DIRS
# the offset up to which the log is scanned
integer LOG_CURSOR=0
integer WINDOW_START=0
# pairs of offsets between which the script unmounted one volume
typeset -a WINDOWS
EXPECTED_WARNING=""
typeset -a BACKGROUND_PIDS
integer CLEANED_UP=0

# --- checks ---

pass() {
	CHECKS+=1
	print -r -- "  pass  $1"
}

# fail <description> [<detail>]
fail() {
	CHECKS+=1
	FAILED+=("$SCENARIO: $1")
	print -r -- "  FAIL  $1"
	if [[ -n "${2:-}" ]]; then
		print -r -- "$2" | sed 's/^/        /'
	fi
	if (( STOP_ON_FAILURE )); then
		echo "==> Stopped. Mounted volumes:"
		own_mount_points | sed 's/^/  /'
		echo "Log: $PROGRAM_LOG"
		echo "Press return to clean up."
		read -r
		finish
	fi
}

# check <description> <command> [<argument> ...]: passes if the command succeeds. The command runs in a subshell.
check() {
	local description="$1" output
	shift
	if output="$("$@" 2>&1)"; then
		pass "$description"
	else
		fail "$description" "$output"
	fi
}

# check_fails <description> <part of the error output> <command> [<argument> ...]: passes if the command fails with that output
check_fails() {
	local description="$1" expected="$2" output
	shift 2
	if output="$("$@" 2>&1)"; then
		fail "$description" "succeeded"
	elif [[ "$output" != *"$expected"* ]]; then
		fail "$description" "$output"
	else
		pass "$description"
	fi
}

# check_lines <command> [<argument> ...]: records one check per line the command prints. A line "ok <description>" passes, any other fails.
check_lines() {
	local line output
	output="$("$@" 2>&1)"
	for line in "${(@f)output}"; do
		if [[ "$line" == "ok "* ]]; then
			pass "${line#ok }"
		else
			fail "${line#not ok }"
		fi
	done
}

# the cleanup follows from the EXIT trap
finish() {
	integer exit_status=$(( ${#FAILED} > 0 ))
	echo "==> $CHECKS checks, ${#FAILED} failed, exit status $exit_status"
	if (( exit_status )); then
		print -r -- "  ${(pj:\n  :)FAILED}"
	fi
	exit $exit_status
}

abort_run() {
	fail "$1"
	finish
}

# --- the program ---

program_alive() {
	[[ -n "$PROGRAM_PID" ]] && kill -0 "$PROGRAM_PID" 2> /dev/null
}

# Waits up to 60 s for the program to exit.
await_exit() {
	local attempt
	for attempt in {1..300}; do
		program_alive || return 0
		sleep 0.2
	done
	return 1
}

# Prints a process and its descendants.
process_tree() {
	local child
	print -r -- "$1"
	for child in $(pgrep -P "$1"); do
		process_tree "$child"
	done
}

# await_reply <timeout in seconds>: waits for the reply that follows the last one and leaves it in REPLY_MARKER and REPLY_MESSAGE. Fails if none arrives in time or the program is gone.
await_reply() {
	local found REPLY_ID
	integer polls=$(( $1 * 5 )) alive length
	while true; do
		# looked up first: once the program is gone, its last reply is in the log
		program_alive && alive=1 || alive=0
		# a last line that is still incomplete is left for the next look
		found="$(tail -c +$(( REPLY_END + 1 )) "$PROGRAM_LOG" | /usr/bin/perl -ne '
			BEGIN { binmode(STDIN); binmode(STDOUT) }
			next if $found || !/\n\z/;
			$length += length;
			if (/SmokeMountMain - REPLY (.*)/) { $found = 1; print "$length $1\n" }
		')"
		if [[ -n "$found" ]]; then
			read -r length REPLY_MARKER REPLY_ID REPLY_MESSAGE <<< "$found"
			REPLY_END+=$length
			return 0
		fi
		(( alive && polls-- > 0 )) || return 1
		sleep 0.2
	done
}

# the seconds the program is given to answer a command. Each lies above what the provider takes at worst. For quit, that holds as long as no volume is left for the program to unmount.
typeset -A TIMEOUTS=(mount 90 unmount 45 unmount-forced 45 sync 10 quit 60)

# request <command> [<field> ...]: sends a command to the program and waits for its reply
request() {
	program_alive || abort_run "the program is running"
	print -u3 -r -- "${(pj:\t:)@}" 2> /dev/null || abort_run "the program takes commands"
	await_reply "$TIMEOUTS[$1]" || abort_run "the program answers $1 within $TIMEOUTS[$1] s"
}

# reply_is <marker> [<part of the message>]
reply_is() {
	if [[ "$REPLY_MARKER" != "$1" || "$REPLY_MESSAGE" != *"${2:-}"* ]]; then
		print -r -- "the program replied $REPLY_MARKER $REPLY_MESSAGE"
		return 1
	fi
}

# --- the mount table ---

# Prints "<source> on <mount point>" for every cryptomatorfs volume, split off a line of the mount table as MountTable.sourcesOfMountsAt does.
cryptomatorfs_mounts() {
	local line
	/sbin/mount | while IFS= read -r line; do
		if [[ "${line##* \(}" == cryptomatorfs,* ]]; then
			print -r -- "${line% \(*}"
		fi
	done
}

# Prints the source of the cryptomatorfs volume mounted at a mount point, if there is one.
mounted_source() {
	local mount entry="on ${1:A}"
	cryptomatorfs_mounts | while IFS= read -r mount; do
		if [[ "$mount" == *" $entry" ]]; then
			print -r -- "${mount% $entry}"
		fi
	done
}

# Prints the options the mount table lists for whatever is mounted at a mount point.
mount_options() {
	local line
	/sbin/mount | while IFS= read -r line; do
		if [[ "${line% \(*}" == *" on ${1:A}" ]]; then
			print -r -- "${line##* \(}"
		fi
	done
}

# Prints the mount points of the cryptomatorfs volumes inside the work directory. The work directory is new for each run, so these are this run's volumes. A path with ".." in it does not count, so that no line of the mount table can lead the cleanup out of the work directory.
own_mount_points() {
	local mount mount_point
	cryptomatorfs_mounts | while IFS= read -r mount; do
		mount_point="${mount#* on }"
		if [[ "$mount_point" == "${WORK_DIR:A}/"* && "$mount_point" == "${mount_point:a}" ]]; then
			print -r -- "$mount_point"
		fi
	done
}

# --- mounting and unmounting ---

# mount_volume <id> <plain|vault> <rw|ro> <backing directory> <mount point>: fails, having recorded the failure, unless the mount table confirms the volume. After a failure, nothing may be written to the mount point.
mount_volume() {
	local id="$1" mount_point="$5" source
	mkdir -p "$mount_point"
	request mount "$@"
	if [[ "$REPLY_MARKER" != MOUNTED ]]; then
		if (( FIRST_MOUNT )) && [[ "$REPLY_MESSAGE" == *"Unable to invoke task"* || "$REPLY_MESSAGE" == *"is disabled"* ]]; then
			echo "error: the extension must be installed and enabled, see the README: $REPLY_MESSAGE" >&2
			exit 2
		fi
		fail "$id is mounted" "$REPLY_MESSAGE"
		return 1
	fi
	FIRST_MOUNT=0
	source="$(mounted_source "$mount_point")"
	if [[ -z "$source" ]]; then
		fail "$id is mounted" "the mount table does not list $mount_point"
		return 1
	fi
	RENDEZVOUS_DIRS[$id]="${${source#file://}%/}"
	if [[ ! -d "${RENDEZVOUS_DIRS[$id]}" ]]; then
		fail "$id is mounted" "the mount table names the source $source, which is no manifest directory"
		return 1
	fi
	pass "$id is mounted"
}

is_empty_directory() {
	[[ -d "$1" && ! -L "$1" && -z "$(ls -A "$1")" ]]
}

# check_unmounted <id> <mount point>: confirms that nothing is left of a volume
check_unmounted() {
	check "$1 is gone from the mount table" test -z "$(mounted_source "$2")"
	check "the manifest directory of $1 is deleted" test ! -e "${RENDEZVOUS_DIRS[$1]}"
	check "the mount point of $1 is an empty directory" is_empty_directory "$2"
}

# An unmount window is the part of the log in which the script unmounts one volume. It starts at a reply of its own, so that a session that ended earlier lies outside it. It ends with the reply to the last unmount command. Sessions are not told apart by volume, so the one session that ends in a window is taken for the unmounted volume's.
open_window() {
	request sync "$SCENARIO"
	WINDOW_START=$REPLY_END
}

close_window() {
	WINDOWS+=($WINDOW_START $REPLY_END)
}

# unmount_volume <id> <mount point>: if the unmount fails, records the failure and unmounts by force, so that the run can go on
unmount_volume() {
	local id="$1" mount_point="$2"
	open_window
	request unmount "$id"
	if [[ "$REPLY_MARKER" == UNMOUNTED ]]; then
		close_window
		pass "$id is unmounted"
		check_unmounted "$id" "$mount_point"
	else
		fail "$id is unmounted" "$REPLY_MESSAGE"
		request unmount-forced "$id"
		close_window
	fi
}

# --- the log ---

# scan_log <offset>: fails the scenario if the log reports a defect between the cursor and that offset, which becomes the cursor
scan_log() {
	local problems
	problems="$(tail -c +$(( LOG_CURSOR + 1 )) "$PROGRAM_LOG" | EXPECTED_WARNING="$EXPECTED_WARNING" LC_ALL=C awk -v offset="$LOG_CURSOR" -v to="$1" -v windows="$WINDOWS" '
		BEGIN { count = split(windows, bounds, " ") }
		{ start = offset; offset += length($0) + 1 }
		start >= to { next }
		# every line of the fs package is a failure it reported for an operation
		/^[0-9:.]+ \[[^]]*\] [A-Z]+ org\.cryptomator\.frontend\.fskit\.fs\./ { print "the fs package reported a failure: " $0 }
		/^[0-9:.]+ \[[^]]*\] (WARN|ERROR) / {
			if (ENVIRON["EXPECTED_WARNING"] != "" && index($0, ENVIRON["EXPECTED_WARNING"])) {
				expected++
			} else {
				print "unexpected: " $0
			}
		}
		/ org\.cryptomator\.frontend\.fskit\.BridgeSession - Session ended/ {
			window = 0
			for (i = 1; i < count; i += 2) {
				if (start >= bounds[i] && start < bounds[i + 1]) {
					window = i
				}
			}
			if (!window) {
				print "a session ended outside an unmount: " $0
			} else if (++ended[window] > 1) {
				print "a second session ended during one unmount: " $0
			}
		}
		# a clean run must contain one session end per unmount and the expected warning. That shows that the session rule and the warning rule still match the log. The rule for the fs package has no such control.
		END {
			for (i = 1; i < count; i += 2) {
				if (!ended[i]) {
					print "no session ended during an unmount"
				}
			}
			if (ENVIRON["EXPECTED_WARNING"] != "" && !expected) {
				print "the expected warning is missing: " ENVIRON["EXPECTED_WARNING"]
			}
		}
	')" || problems="the log could not be scanned"
	LOG_CURSOR=$1
	WINDOWS=()
	EXPECTED_WARNING=""
	if [[ -z "$problems" ]]; then
		pass "the log reports no failure"
	else
		fail "the log reports no failure" "$problems"
	fi
}

# --- helpers of the scenarios ---

# Prints the names in a directory, without the entries macOS adds to a volume.
entries() {
	ls -A "$1" | grep -v -E '^(\._.*|\.fseventsd|\.Trashes)$'
}

checksum() {
	shasum -a 256 | cut -d ' ' -f 1
}

echo_to() {
	echo "$1" > "$2"
}

append_to() {
	echo "$1" >> "$2"
}

# rm -r may report a companion file it had listed as missing and fail although everything is removed (see the README's known limitations), so only the result counts.
remove_tree() {
	rm -r "$1" > /dev/null 2>&1
}

# Prints the space df reports as available at a mount point, in KiB, or nothing if df fails.
available_kb() {
	df -k "$1" 2> /dev/null | awk 'NR == 2 { print $4 }'
}

# shows_space_of <mount point> <backing directory>: df reports the same available space for both, to within 4 MiB. Right after a larger write, that shows the volume no longer reports the space from before the write, whatever else takes or frees space on the disk during the run.
shows_space_of() {
	local mounted="$(available_kb "$1")" backing="$(available_kb "$2")"
	if [[ -z "$mounted" || -z "$backing" ]] || (( mounted - backing > 4096 || backing - mounted > 4096 )); then
		print -r -- "df reported ${mounted:-nothing} KiB at the mount point and ${backing:-nothing} KiB for the backing store"
		return 1
	fi
}

# a mount point whose volume is gone is a plain directory, which ls lists as well
volume_answers() {
	[[ -n "$(mounted_source "$1")" ]] && ls "$1"
}

# Prints a file's modification and access time as touch -t takes them.
times_of() {
	stat -f '%Sm %Sa' -t %Y%m%d%H%M "$1"
}

# move_with_open_file <directory> <target>: moves a directory while a file in it is open, and writes to the file before and after
move_with_open_file() {
	/usr/bin/perl -e 'open(my $file, ">", "$ARGV[0]/open.txt") or die "open: $!"; syswrite($file, "before") or die "write: $!"; rename($ARGV[0], $ARGV[1]) or die "rename: $!"; syswrite($file, " and after") or die "write: $!"; close($file) or die "close: $!"' "$1" "$2"
}

write_and_fsync() {
	/usr/bin/perl -e 'use IO::Handle; open(my $file, ">", $ARGV[0]) or die "open: $!"; syswrite($file, "synced") or die "write: $!"; $file->sync or die "fsync: $!"; close($file) or die "close: $!"' "$1"
}

# Creates a file with mode 0444 and writes to it through the descriptor that created it.
create_read_only() {
	/usr/bin/perl -e 'use Fcntl; sysopen(my $file, $ARGV[0], O_WRONLY | O_CREAT | O_EXCL, 0444) or die "open: $!"; syswrite($file, "data") or die "write: $!"; close($file) or die "close: $!"' "$1"
}

create_with_umask() {
	(umask "$1" && : > "$2")
}

# create_files <directory> <count>
create_files() {
	local i
	mkdir "$1" || return 1
	for i in {1..$2}; do
		: > "$1/file-$i" || return 1
	done
}

# touch_extreme <date> <file> [<seconds>]: sets a date the volume may refuse. touch may fail with "Invalid argument". If it succeeds, stat must show the date, or <seconds>, the time the date is known to be cut down to.
touch_extreme() {
	local output shown
	if output="$(touch -t "$1" "$2" 2>&1)"; then
		shown="$(stat -f '%Sm %m' -t %Y%m%d%H%M "$2")"
		if [[ -z "$shown" || ( "${shown% *}" != "$1" && "${shown#* }" != "${3:-}" ) ]]; then
			print -r -- "stat shows $shown"
			return 1
		fi
	elif [[ "$output" != *"Invalid argument"* ]]; then
		print -r -- "$output"
		return 1
	fi
}

# parallel_writes <directory> <first source> <second source>: two loops write files while a third lists the directory
parallel_writes() {
	local directory="$1" first="$2" second="$3" i
	integer first_writer second_writer lister failed=0
	mkdir "$directory" || return 1
	(for i in {1..20}; do cp "$first" "$directory/first-$i.bin" || exit 1; done) &
	first_writer=$!
	(for i in {1..20}; do cp "$second" "$directory/second-$i.bin" || exit 1; done) &
	second_writer=$!
	# ends by itself once the writers have, so that it does not outlive an interrupted run
	(while kill -0 $first_writer 2> /dev/null || kill -0 $second_writer 2> /dev/null; do ls -l "$directory" > /dev/null || exit 1; done) &
	lister=$!
	wait $first_writer || failed=1
	wait $second_writer || failed=1
	wait $lister || failed=1
	return $failed
}

# parallel_writes_intact <directory> <first source> <second source>: every file holds what its loop wrote
parallel_writes_intact() {
	local i
	for i in {1..20}; do
		cmp -s "$2" "$1/first-$i.bin" && cmp -s "$3" "$1/second-$i.bin" || return 1
	done
}

# remove_while_open <file> <content> <directory> ...: removes a file while it is open and prints an "ok" or "not ok" line per expectation. None of the directories may list the file while it is open or afterwards, nor an .nfs.* entry, which FSKit's own emulation of such files would leave.
remove_while_open() {
	/usr/bin/perl - "$@" <<'PERL'
use strict;
use Fcntl qw(SEEK_SET SEEK_END);
my ($path, $content, @directories) = @ARGV;
my ($name) = $path =~ m{([^/]+)$};
sub report {
	my ($ok, $description) = @_;
	print(($ok ? "ok " : "not ok "), $description, "\n");
}
sub leftovers {
	my @found;
	for my $directory (@directories) {
		opendir(my $handle, $directory) or return ("$directory: $!");
		push(@found, grep { $_ eq $name || /^\.nfs\./ } readdir($handle));
	}
	return @found;
}
open(my $file, "+<", $path) or die "open: $!\n";
report(unlink($path), "a file that is open is removed");
report(!leftovers(), "no listing shows it or an .nfs entry while it is open");
my $read = "";
sysseek($file, 0, SEEK_SET);
sysread($file, $read, length($content) + 1);
report($read eq $content, "it still reads from the start");
sysseek($file, 0, SEEK_END);
my $written = syswrite($file, "x" x 4096);
report(defined($written) && $written == 4096 && (stat($file))[7] == length($content) + 4096, "it reports its size after growing by 4,096 bytes");
report(close($file), "it closes");
report(!leftovers(), "no listing shows it or an .nfs entry after it is closed");
PERL
}

# check_basics <mount point> <directory to confirm in>: changes are confirmed in the backing directory, or through the mount point where that holds no readable files
check_basics() {
	local mnt="$1" confirm="$2"
	check "ls -la lists the root" ls -la "$mnt"
	check "echo creates a file" echo_to one "$mnt/file.txt"
	check "echo appends to it" append_to two "$mnt/file.txt"
	check "cat reads it" test "$(cat "$mnt/file.txt")" = $'one\ntwo'
	check "the file holds both lines" test "$(cat "$confirm/file.txt")" = $'one\ntwo'
	check "truncate shrinks the file" truncate -s 3 "$mnt/file.txt"
	check "the shrunk file reports its size" test "$(stat -f %z "$mnt/file.txt")" -eq 3
	check "the shrunk file keeps its first bytes" test "$(cat "$confirm/file.txt")" = one
	check "truncate grows the file" truncate -s 4096 "$mnt/file.txt"
	check "the grown file reports its size" test "$(stat -f %z "$mnt/file.txt")" -eq 4096
	check "the grown file has that size" test "$(wc -c < "$confirm/file.txt")" -eq 4096
	check "the grown file is filled with zeros" test "$(tail -c +4 "$confirm/file.txt" | LC_ALL=C tr -d '\0' | wc -c)" -eq 0
	check "mkdir creates a directory" mkdir "$mnt/dir"
	check "the directory exists" test -d "$confirm/dir"
	check "echo creates a second file" echo_to replacement "$mnt/other.txt"
	check "mv moves a file over an existing file" mv "$mnt/other.txt" "$mnt/file.txt"
	check "the replaced file has the new content" test "$(cat "$confirm/file.txt")" = replacement
	check "the moved file is gone" test ! -e "$confirm/other.txt"
	check "mv moves a directory that contains an open file" move_with_open_file "$mnt/dir" "$mnt/moved"
	check "the open file took both writes" test "$(cat "$confirm/moved/open.txt")" = "before and after"
	check "the directory is gone from its old place" test ! -e "$confirm/dir"
	check "rm removes a file" rm "$mnt/moved/open.txt"
	check "rmdir removes a directory" rmdir "$mnt/moved"
	check "both are gone" test ! -e "$confirm/moved"
}

# check_parallel <mount point> <directory to confirm in>
check_parallel() {
	local first="$SCENARIO_DIR/first.bin" second="$SCENARIO_DIR/second.bin"
	head -c 262144 /dev/urandom > "$first"
	head -c 262144 /dev/urandom > "$second"
	check "two loops write files while a third lists the directory" parallel_writes "$1/parallel" "$first" "$second"
	check "every file holds what was written" parallel_writes_intact "$2/parallel" "$first" "$second"
}

# --- scenarios ---
#
# Each scenario mounts volumes of its own, so that a volume that loses its extension costs one scenario.
# Fixtures are placed in the backing directory before it is mounted, since nothing else may modify a mounted directory. Only the first read of a fixture is sure to reach the adapter: a cache may answer for what was read or written through the mount before.

basics() {
	mount_volume "$SCENARIO" plain rw "$BACKING" "$MNT" || return 0
	check_basics "$MNT" "$BACKING"
	unmount_volume "$SCENARIO" "$MNT"
}

# reads and writes larger than a frame, which holds 1 MiB
io() {
	local source="$SCENARIO_DIR/source.bin" expected
	head -c $(( 11 * 1024 * 1024 )) /dev/urandom > "$source"
	cp "$source" "$BACKING/cat-fixture.bin"
	cp "$source" "$BACKING/dd-fixture.bin"
	expected="$(checksum < "$source")"
	mount_volume "$SCENARIO" plain rw "$BACKING" "$MNT" || return 0
	check "cat reads a file larger than 10 MiB" test "$(cat "$MNT/cat-fixture.bin" | checksum)" = "$expected"
	check "dd reads another in blocks of 4 MiB" test "$(dd if="$MNT/dd-fixture.bin" bs=4m 2> /dev/null | checksum)" = "$expected"
	check "cp writes a file larger than 10 MiB" cp "$source" "$MNT/cp.bin"
	check "the copy is intact" test "$(checksum < "$BACKING/cp.bin")" = "$expected"
	check "dd writes it in blocks of 4 MiB" dd if="$source" of="$MNT/dd.bin" bs=4m
	check "that copy is intact" test "$(checksum < "$BACKING/dd.bin")" = "$expected"
	check "fsync on a written file succeeds" write_and_fsync "$MNT/synced.txt"
	check "the synced file holds what was written" test "$(cat "$BACKING/synced.txt")" = synced
	check "df shows the backing store's free space after the copies" shows_space_of "$MNT" "$BACKING"
	unmount_volume "$SCENARIO" "$MNT"
}

# a directory large enough to be listed in several pages
listing() {
	mount_volume "$SCENARIO" plain rw "$BACKING" "$MNT" || return 0
	check "5,000 files are created in one directory" create_files "$MNT/many" 5000
	check "ls lists all of them" test "$(ls "$MNT/many" | wc -l)" -eq 5000
	check "ls -l succeeds" ls -l "$MNT/many"
	check "the backing directory holds all of them" test "$(ls "$BACKING/many" | wc -l)" -eq 5000
	remove_tree "$MNT/many"
	check "rm -r removes the directory" test ! -e "$BACKING/many"
	unmount_volume "$SCENARIO" "$MNT"
}

modes() {
	touch -t 201501010000 "$BACKING/times.txt"
	mount_volume "$SCENARIO" plain rw "$BACKING" "$MNT" || return 0
	check "a file created with mode 0444 is written through its descriptor" create_read_only "$MNT/read-only.txt"
	check "it holds what was written" test "$(cat "$BACKING/read-only.txt")" = data
	check "it has mode 0444" test "$(stat -f %Lp "$BACKING/read-only.txt")" = 444
	check "mkdir -m 0700 creates a directory" mkdir -m 0700 "$MNT/private"
	check "it has mode 0700" test "$(stat -f %Lp "$BACKING/private")" = 700
	check "a file is created under umask 077" create_with_umask 077 "$MNT/umask-077.txt"
	check "it has mode 0600" test "$(stat -f %Lp "$BACKING/umask-077.txt")" = 600
	check "a file is created under umask 0" create_with_umask 0 "$MNT/umask-0.txt"
	check "it has mode 0666" test "$(stat -f %Lp "$BACKING/umask-0.txt")" = 666
	check "chmod changes a mode" chmod 640 "$MNT/umask-0.txt"
	check "it has mode 0640" test "$(stat -f %Lp "$BACKING/umask-0.txt")" = 640
	check "touch -m sets the modification time" touch -m -t 202001020304 "$MNT/times.txt"
	check "stat shows it, and the access time unchanged" test "$(times_of "$MNT/times.txt")" = "202001020304 201501010000"
	check "the backing directory has both times" test "$(times_of "$BACKING/times.txt")" = "202001020304 201501010000"
	check "touch -a sets the access time" touch -a -t 202102030405 "$MNT/times.txt"
	check "stat shows it, and the modification time unchanged" test "$(times_of "$MNT/times.txt")" = "202001020304 202102030405"
	check "the backing directory has both times" test "$(times_of "$BACKING/times.txt")" = "202001020304 202102030405"
	check "a date before 1970 is applied or refused" touch_extreme 196001010000 "$MNT/times.txt"
	check "the volume answers afterwards" volume_answers "$MNT"
	# the JDK sets no time later than the largest number of nanoseconds a long holds, in the year 2262
	check "a date far in the future is applied, cut down or refused" touch_extreme 999912312359 "$MNT/times.txt" 9223372036
	check "the volume answers afterwards" volume_answers "$MNT"
	unmount_volume "$SCENARIO" "$MNT"
}

renames() {
	echo content > "$BACKING/file.txt"
	mkdir -p "$BACKING/dir" "$BACKING/outer/inner"
	echo kept > "$BACKING/outer/inner/kept.txt"
	mount_volume "$SCENARIO" plain rw "$BACKING" "$MNT" || return 0
	check "mv renames a file within its directory" mv "$MNT/file.txt" "$MNT/renamed.txt"
	check "the backing directory holds the new name" test "$(entries "$BACKING")" = $'dir\nouter\nrenamed.txt'
	check "mv moves it into another directory" mv "$MNT/renamed.txt" "$MNT/dir/moved.txt"
	check "the backing directory holds it there" test "$(cat "$BACKING/dir/moved.txt")" = content
	check "it is gone from its old place" test "$(entries "$BACKING")" = $'dir\nouter'
	check_fails "moving a directory below itself is refused" "Invalid argument" mv "$MNT/outer" "$MNT/outer/inner/moved"
	check "the tree is intact" test "$(cat "$BACKING/outer/inner/kept.txt")" = kept
	unmount_volume "$SCENARIO" "$MNT"
}

# names in both Unicode forms, given as bytes so that they do not depend on the locale
names() {
	local a_composed=$'\xc3\xa4.txt' a_decomposed=$'a\xcc\x88.txt' u_decomposed=$'u\xcc\x88.txt'
	local o_decomposed=$'o\xcc\x88.txt' o_composed=$'\xc3\xb6.txt'
	echo fixture > "$BACKING/$u_decomposed"
	echo cased > "$BACKING/File.txt"
	mount_volume "$SCENARIO" plain rw "$BACKING" "$MNT" || return 0
	check "a file is created under a composed name" echo_to composed "$MNT/$a_composed"
	check "it is found under the decomposed name" test "$(cat "$MNT/$a_decomposed")" = composed
	check "a file is created under a decomposed name" echo_to decomposed "$MNT/$o_decomposed"
	check "it is found under the composed name" test "$(cat "$MNT/$o_composed")" = decomposed
	check "the backing directory stores it composed" test "$(entries "$BACKING" | LC_ALL=C grep -c -x -F -e "$o_composed")" -eq 1
	check "each is one file there" test "$(entries "$BACKING" | wc -l)" -eq 4
	check "a name stored decomposed is listed" test "$(ls "$MNT" | LC_ALL=C grep -c -x -F -e "$u_decomposed")" -eq 1
	check "it opens" test "$(cat "$MNT/$u_decomposed")" = fixture
	check_fails "a name that differs in case is absent" "No such file or directory" cat "$MNT/file.txt"
	unmount_volume "$SCENARIO" "$MNT"
}

links() {
	mkdir "$BACKING/sub" "$SCENARIO_DIR/target"
	echo regular > "$BACKING/sub/regular.txt"
	echo kept > "$SCENARIO_DIR/target/kept.txt"
	ln -s "$SCENARIO_DIR/target" "$BACKING/sub/link"
	mount_volume "$SCENARIO" plain rw "$BACKING" "$MNT" || return 0
	check "ls -l shows the link as a link" test "$(ls -l "$MNT/sub" 2> /dev/null | grep -c '^l')" -eq 1
	check_fails "cd into the link fails" "" cd "$MNT/sub/link"
	check_fails "ln -s is refused as unsupported" "Operation not supported" ln -s "$MNT/sub/regular.txt" "$MNT/sub/new-symlink"
	check_fails "ln is refused as unsupported" "Operation not supported" ln "$MNT/sub/regular.txt" "$MNT/sub/new-hardlink"
	check "neither link exists" test "$(entries "$BACKING/sub")" = $'link\nregular.txt'
	remove_tree "$MNT/sub"
	check "rm -r removes the directory that holds the link" test ! -e "$BACKING/sub"
	check "the link's target is untouched" test "$(cat "$SCENARIO_DIR/target/kept.txt")" = kept
	unmount_volume "$SCENARIO" "$MNT"
}

# below a directory that may be searched but not read, like the .Trashes directory macOS creates in a volume's root
unreadable-directory() {
	mkdir -p "$BACKING/trash/501"
	chmod 311 "$BACKING/trash"
	mount_volume "$SCENARIO" plain rw "$BACKING" "$MNT" || return 0
	check "a file is created" echo_to content "$MNT/trash/501/file.txt"
	check "the backing directory holds it" test "$(cat "$BACKING/trash/501/file.txt")" = content
	check "mv renames it" mv "$MNT/trash/501/file.txt" "$MNT/trash/501/renamed.txt"
	check "the backing directory holds the new name" test "$(entries "$BACKING/trash/501")" = renamed.txt
	check "rm removes it" rm "$MNT/trash/501/renamed.txt"
	check "the backing directory holds it no longer" test -z "$(entries "$BACKING/trash/501")"
	unmount_volume "$SCENARIO" "$MNT"
}

open-unlinked() {
	mkdir "$BACKING/dir"
	print -n 0123456789 > "$BACKING/dir/unlinked.txt"
	mount_volume "$SCENARIO" plain rw "$BACKING" "$MNT" || return 0
	check_lines remove_while_open "$MNT/dir/unlinked.txt" 0123456789 "$MNT/dir" "$BACKING/dir" "$BACKING"
	unmount_volume "$SCENARIO" "$MNT"
}

parallel() {
	mount_volume "$SCENARIO" plain rw "$BACKING" "$MNT" || return 0
	check_parallel "$MNT" "$BACKING"
	unmount_volume "$SCENARIO" "$MNT"
}

two-mounts() {
	local second_backing="$SCENARIO_DIR/second backing" second_mnt="$SCENARIO_DIR/Smoke (1)"
	mkdir "$second_backing"
	echo one > "$BACKING/one.txt"
	echo two > "$second_backing/two.txt"
	mount_volume "$SCENARIO-1" plain rw "$BACKING" "$MNT" || return 0
	if mount_volume "$SCENARIO-2" plain rw "$second_backing" "$second_mnt"; then
		check "the first shows its own directory" test "$(entries "$MNT")" = one.txt
		check "the second shows its own directory" test "$(entries "$second_mnt")" = two.txt
		check "a file is written to the first" echo_to written "$MNT/written.txt"
		check "its backing directory holds it" test "$(entries "$BACKING")" = $'one.txt\nwritten.txt'
		check "it does not appear in the second" test "$(entries "$second_mnt")" = two.txt
		check "nor in its backing directory" test "$(entries "$second_backing")" = two.txt
		check "the mount table lists both" test -n "$(mounted_source "$MNT")" -a -n "$(mounted_source "$second_mnt")"
		unmount_volume "$SCENARIO-2" "$second_mnt"
	fi
	unmount_volume "$SCENARIO-1" "$MNT"
}

occupied() {
	mkdir "$SCENARIO_DIR/other"
	echo content > "$BACKING/file.txt"
	mount_volume "$SCENARIO" plain rw "$BACKING" "$MNT" || return 0
	request mount "$SCENARIO-again" plain rw "$SCENARIO_DIR/other" "$MNT"
	check "a second mount at the mount point is refused" reply_is MOUNT_FAILED "Something is already mounted"
	check "the first mount still reads" test "$(cat "$MNT/file.txt")" = content
	check "it still writes" echo_to written "$MNT/written.txt"
	check "its backing directory holds what was written" test "$(cat "$BACKING/written.txt")" = written
	unmount_volume "$SCENARIO" "$MNT"
}

external-unmount() {
	mount_volume "$SCENARIO" plain rw "$BACKING" "$MNT" || return 0
	# the session ends with the umount from the shell, not with the program's unmount
	open_window
	check "umount from the shell succeeds" /sbin/umount "$MNT"
	request unmount "$SCENARIO"
	close_window
	check "the program's unmount succeeds" reply_is UNMOUNTED
	check "the log says that there was nothing to do" test "$(tail -c +$(( WINDOW_START + 1 )) "$PROGRAM_LOG" | grep -c -F "already unmounted. Nothing to do.")" -eq 1
	check_unmounted "$SCENARIO" "$MNT"
}

busy-unmount() {
	local ready="$SCENARIO_DIR/holder-ready" refusal='`umount` returned with non-zero exit code'
	integer holder
	mount_volume "$SCENARIO" plain rw "$BACKING" "$MNT" || return 0
	(cd "$MNT" && : > "$ready" && exec sleep 600) &
	holder=$!
	BACKGROUND_PIDS+=($holder)
	until [[ -e "$ready" ]] || ! kill -0 $holder 2> /dev/null; do
		sleep 0.1
	done
	# the provider logs the output of the umount that fails
	EXPECTED_WARNING="$refusal"
	open_window
	request unmount "$SCENARIO"
	check "unmount is refused while a process works in the volume" reply_is UNMOUNT_FAILED "$refusal"
	check "the volume stays mounted" test -n "$(mounted_source "$MNT")"
	request unmount-forced "$SCENARIO"
	close_window
	check "a forced unmount succeeds" reply_is UNMOUNTED
	check_unmounted "$SCENARIO" "$MNT"
	kill $holder 2> /dev/null
	BACKGROUND_PIDS=(${BACKGROUND_PIDS:#$holder})
}

read-only() {
	echo content > "$BACKING/file.txt"
	mount_volume "$SCENARIO" plain ro "$BACKING" "$MNT" || return 0
	check "reading works" test "$(cat "$MNT/file.txt")" = content
	check_fails "touch is refused" "Read-only file system" touch "$MNT/new.txt"
	check "the backing directory is unchanged" test "$(entries "$BACKING")" = file.txt
	check "the mount table lists the volume as read-only" test "$(mount_options "$MNT" | grep -c -w read-only)" -eq 1
	unmount_volume "$SCENARIO" "$MNT"
}

# nothing can be placed in a vault from outside, so every file is written through the mount and confirmed there
vault() {
	local large="$SCENARIO_DIR/large.bin"
	head -c $(( 11 * 1024 * 1024 )) /dev/urandom > "$large"
	mount_volume "$SCENARIO-1" vault rw "$BACKING" "$MNT" || return 0
	check_basics "$MNT" "$MNT"
	check_parallel "$MNT" "$MNT"
	check "a directory is created" mkdir "$MNT/dir"
	check "a file is created in it" echo_to 0123456789 "$MNT/dir/unlinked.txt"
	check_lines remove_while_open "$MNT/dir/unlinked.txt" $'0123456789\n' "$MNT/dir" "$MNT"
	check "cp writes a file larger than 10 MiB" cp "$large" "$MNT/large.bin"
	# no operation may come between the write and df, since every reply refreshes the free space the volume reports
	check "df shows the backing store's free space right after the write" shows_space_of "$MNT" "$BACKING"
	unmount_volume "$SCENARIO-1" "$MNT"
	# reopens the vault from disk as a new volume
	mount_volume "$SCENARIO-2" vault rw "$BACKING" "$MNT" || return 0
	check "the reopened vault holds the file that replaced another" test "$(cat "$MNT/file.txt")" = replacement
	check "it holds the files written in parallel" parallel_writes_intact "$MNT/parallel" "$SCENARIO_DIR/first.bin" "$SCENARIO_DIR/second.bin"
	check "it holds the large file" cmp -s "$large" "$MNT/large.bin"
	check "it holds nothing that was removed" test "$(entries "$MNT")" = $'dir\nfile.txt\nlarge.bin\nparallel'
	check "the directory of the removed file is listed" ls -A "$MNT/dir"
	check "it is empty" test -z "$(entries "$MNT/dir")"
	unmount_volume "$SCENARIO-2" "$MNT"
}

# --- run ---

# cleanup [<exit status>]: runs once, however the script ends. Every phase runs whether or not the earlier ones succeeded.
cleanup() {
	integer exit_status=${1:-$?}
	local mount_point
	set +e
	if (( CLEANED_UP )); then
		return
	fi
	CLEANED_UP=1
	trap '' HUP INT TERM
	if [[ -n "$PROGRAM_PID" ]]; then
		print -u3 quit 2> /dev/null
		exec 3>&-
		if ! await_exit; then
			# the script runs without job control, so the Maven JVM and the program's JVM share its process group and cannot be stopped as a group
			kill -9 $(process_tree "$PROGRAM_PID") 2> /dev/null
		fi
		wait "$PROGRAM_PID" 2> /dev/null
	fi
	own_mount_points | while IFS= read -r mount_point; do
		/sbin/umount -f "$mount_point"
	done
	if (( ${#BACKGROUND_PIDS} )); then
		kill $BACKGROUND_PIDS 2> /dev/null
	fi
	# with it go the manifest directories the program could no longer delete
	rm -rf "$JVM_TMP"
	# a directory that may not be read cannot be removed
	chmod -R u+rwX "$WORK_DIR" 2> /dev/null
	if (( ${#FAILED} == 0 )) && [[ -z "$(own_mount_points)" ]]; then
		rm -rf "$WORK_DIR"
	else
		echo "Work directory with the JVM's log: $WORK_DIR"
	fi
	exit $exit_status
}

# A start-up command that fails ends the script with status 2. Left to errexit, it would end it with 1, the status of failed checks.
# below a symbolic link, so the mount points differ from the paths the mount table names
WORK_DIR="$(mktemp -d /tmp/fskit-smoke.XXXXXX)" || exit 2
PROGRAM_LOG="$WORK_DIR/jvm.log"
PROGRAM_OUT="$WORK_DIR/maven.out"
PROGRAM_STDIN="$WORK_DIR/program.stdin"
# the provider creates its manifest directories in the JVM's temp directory
JVM_TMP="$WORK_DIR/jvm-tmp"
trap cleanup EXIT
# a signal would end the script without running the EXIT trap.
# The signal traps clean up themselves: when Ctrl-C in a terminal also ends a command substitution the script is waiting for inside nested functions, zsh 5.9 runs the INT trap, but not the EXIT trap after its `exit`.
trap 'cleanup 129' HUP
trap 'cleanup 130' INT
trap 'cleanup 143' TERM
# so would a write to a program that is gone
trap '' PIPE

mkdir "$JVM_TMP" || exit 2
mkfifo "$PROGRAM_STDIN" || exit 2
: > "$PROGRAM_LOG" || exit 2
echo "==> Starting SmokeMountMain"
# the program writes its log and its replies to standard error
(cd "$REPO_ROOT" && JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Djava.io.tmpdir=$JVM_TMP" ./mvnw -B -q test -Pmirror -Dmirror.mainClass=org.cryptomator.frontend.fskit.mount.SmokeMountMain < "$PROGRAM_STDIN" > "$PROGRAM_OUT" 2> "$PROGRAM_LOG") &
PROGRAM_PID=$!
exec 3> "$PROGRAM_STDIN" || exit 2
if ! await_reply 120; then
	echo "error: SmokeMountMain did not start:" >&2
	cat "$PROGRAM_OUT" "$PROGRAM_LOG" >&2
	exit 2
fi

# from here on, a failed command is at most a failed check. Inside a function, zsh would also end the script on it without running the EXIT trap.
set +e
for SCENARIO in $SELECTED; do
	echo "==> $SCENARIO"
	SCENARIO_DIR="$WORK_DIR/$SCENARIO"
	BACKING="$SCENARIO_DIR/backing"
	MNT="$SCENARIO_DIR/mnt"
	mkdir -p "$BACKING"
	"$SCENARIO"
	request sync "$SCENARIO"
	scan_log "$REPLY_END"
done

echo "==> shutdown"
SCENARIO=shutdown
request quit
if await_exit && wait "$PROGRAM_PID"; then
	pass "the program exits without a failure"
else
	fail "the program exits without a failure"
fi
scan_log "$(stat -f %z "$PROGRAM_LOG")"
finish
