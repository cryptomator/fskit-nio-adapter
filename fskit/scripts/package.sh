#!/bin/zsh
#
# Assembles and codesigns FSKitNioHost.app with the embedded
# FSKitNioExtension.appex — no .xcodeproj involved.
#
# Layout produced under build/:
#   FSKitNioHost.app/Contents/
#     Info.plist
#     MacOS/FSKitNioHost
#     Extensions/FSKitNioExtension.appex/Contents/
#       Info.plist
#       MacOS/FSKitNioExtension
#
# Environment:
#   CODESIGN_IDENTITY     signing identity (default: "-" = ad-hoc)
#   PROVISIONING_PROFILE  .provisionprofile authorizing com.apple.developer.fskit.fsmodule,
#                         embedded into the appex. Required for mounting: the entitlement
#                         is restricted, so ExtensionKit refuses to launch an unprovisioned
#                         extension (extensionKit error 2). Ad-hoc builds compile and
#                         register but cannot mount.
#   HOST_BUNDLE_ID        bundle identifier of the host app (default: org.cryptomator.fskit.host).
#                         The extension's identifier is this plus ".extension".
#   FS_TYPE_NAME          file system type name, as passed to `mount -t` (default: cryptomatorfs)
#   SKIP_SWIFT=1          skip swift build
#
set -euo pipefail

SCRIPT_DIR="${0:A:h}"
FSKIT_DIR="${SCRIPT_DIR:h}"
BUILD_DIR="$FSKIT_DIR/build"
IDENTITY="${CODESIGN_IDENTITY:--}"
HOST_BUNDLE_ID="${HOST_BUNDLE_ID:-org.cryptomator.fskit.host}"
FS_TYPE_NAME="${FS_TYPE_NAME:-cryptomatorfs}"

# 1. Build the Swift targets
if [[ "${SKIP_SWIFT:-0}" != "1" ]]; then
	echo "==> Building Swift targets"
	(cd "$FSKIT_DIR" && swift build -c release)
fi
BIN_DIR="$(cd "$FSKIT_DIR" && swift build -c release --show-bin-path)"

# 2. Assemble the bundle tree
echo "==> Assembling bundle"
APP="$BUILD_DIR/FSKitNioHost.app"
APPEX="$APP/Contents/Extensions/FSKitNioExtension.appex"
rm -rf "$BUILD_DIR"
mkdir -p "$APP/Contents/MacOS" "$APPEX/Contents/MacOS"

cp "$FSKIT_DIR/Bundle/FSKitNioHost-Info.plist" "$APP/Contents/Info.plist"
cp "$BIN_DIR/FSKitNioHost" "$APP/Contents/MacOS/FSKitNioHost"
cp "$FSKIT_DIR/Bundle/FSKitNioExtension-Info.plist" "$APPEX/Contents/Info.plist"
cp "$BIN_DIR/FSKitNioExtension" "$APPEX/Contents/MacOS/FSKitNioExtension"

/usr/libexec/PlistBuddy -c "Set :CFBundleIdentifier $HOST_BUNDLE_ID" "$APP/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleIdentifier $HOST_BUNDLE_ID.extension" "$APPEX/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set :EXAppExtensionAttributes:FSShortName $FS_TYPE_NAME" "$APPEX/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set :EXAppExtensionAttributes:FSPersonalities:CryptomatorFS:FSName $FS_TYPE_NAME" "$APPEX/Contents/Info.plist"

# 3. Optionally embed a provisioning profile. The profile-backed signature must
# carry matching application-identifier/team-identifier entitlements (Xcode adds
# these automatically; here they are derived from the profile).
APPEX_ENTITLEMENTS="$FSKIT_DIR/Bundle/FSKitNioExtension.entitlements"
if [[ -n "${PROVISIONING_PROFILE:-}" ]]; then
	echo "==> Embedding provisioning profile"
	cp "$PROVISIONING_PROFILE" "$APPEX/Contents/embedded.provisionprofile"
	APP_IDENTIFIER="$(security cms -D -i "$PROVISIONING_PROFILE" | plutil -extract 'Entitlements.com\.apple\.application-identifier' raw -o - -)"
	TEAM_IDENTIFIER="$(security cms -D -i "$PROVISIONING_PROFILE" | plutil -extract 'TeamIdentifier.0' raw -o - -)"
	APPEX_ENTITLEMENTS="$BUILD_DIR/FSKitNioExtension.entitlements"
	cp "$FSKIT_DIR/Bundle/FSKitNioExtension.entitlements" "$APPEX_ENTITLEMENTS"
	/usr/libexec/PlistBuddy -c "Add :com.apple.application-identifier string $APP_IDENTIFIER" "$APPEX_ENTITLEMENTS"
	/usr/libexec/PlistBuddy -c "Add :com.apple.developer.team-identifier string $TEAM_IDENTIFIER" "$APPEX_ENTITLEMENTS"
	echo "    application-identifier: $APP_IDENTIFIER"
fi

# 4. Codesign inside-out: appex, then app. Hardened runtime and notarization
# are deliberately omitted: local mounts only need a valid seal, and Gatekeeper
# never evaluates unquarantined local builds.
echo "==> Codesigning (identity: $IDENTITY)"
codesign --force --sign "$IDENTITY" \
	--entitlements "$APPEX_ENTITLEMENTS" "$APPEX"
codesign --force --sign "$IDENTITY" \
	--entitlements "$FSKIT_DIR/Bundle/FSKitNioHost.entitlements" "$APP"

echo "==> Done: $APP"
echo "Next steps:"
echo "  ditto '$APP' /Applications/FSKitNioHost.app"
echo "  codesign --verify --deep --strict /Applications/FSKitNioHost.app   # must pass, or fskitd won't launch the extension"
echo "  open /Applications/FSKitNioHost.app   # register the extension with the system"
echo "  Enable it in System Settings > General > Login Items & Extensions > File System Extensions (use the \"By Category\" view)"
echo "  pluginkit -m | grep -i fskit   # verify registration"
