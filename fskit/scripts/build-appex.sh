#!/bin/zsh
#
# Assembles FSKitNioExtension.appex from a release build for arm64, unsigned,
# with the Info.plist from Bundle/ and the version of pom.xml without
# -SNAPSHOT as its bundle version. For a release, CI sets that version from the
# tag before the build.
#
# The build builds every product, since Maven takes libFSKitNioSupport.dylib
# from its product directory. package.sh builds with the same flags and then
# runs this script with SKIP_SWIFT=1.
#
# Usage: build-appex.sh <output directory>
#
# Layout produced in the output directory:
#   FSKitNioExtension.appex/Contents/
#     Info.plist
#     MacOS/FSKitNioExtension
#
# Environment:
#   SKIP_SWIFT=1  skip swift build
#
set -euo pipefail

SCRIPT_DIR="${0:A:h}"
FSKIT_DIR="${SCRIPT_DIR:h}"
REPO_ROOT="${FSKIT_DIR:h}"
OUT_DIR="${1:?usage: build-appex.sh <output directory>}"
BUILD_FLAGS=(-c release --arch arm64)
VERSION="$(xmllint --xpath "/*[local-name()='project']/*[local-name()='version']/text()" "$REPO_ROOT/pom.xml")"
VERSION="${VERSION%-SNAPSHOT}"

if [[ "${SKIP_SWIFT:-0}" != "1" ]]; then
	echo "==> Building Swift targets"
	(cd "$FSKIT_DIR" && swift build $BUILD_FLAGS)
fi
BIN_DIR="$(cd "$FSKIT_DIR" && swift build $BUILD_FLAGS --show-bin-path)"

echo "==> Assembling FSKitNioExtension.appex"
APPEX="$OUT_DIR/FSKitNioExtension.appex"
rm -rf "$APPEX"
mkdir -p "$APPEX/Contents/MacOS"
cp "$FSKIT_DIR/Bundle/FSKitNioExtension-Info.plist" "$APPEX/Contents/Info.plist"
cp "$BIN_DIR/FSKitNioExtension" "$APPEX/Contents/MacOS/FSKitNioExtension"
/usr/libexec/PlistBuddy -c "Add :CFBundleShortVersionString string $VERSION" -c "Add :CFBundleVersion string $VERSION" "$APPEX/Contents/Info.plist"
