#!/usr/bin/env bash
# Builds the iOS app for the connected iPhone, installs it over the existing app and relaunches it.
# Run from a normal terminal (code signing needs the login keychain). Usage: tools/mac-build/ios-build-install.sh
set -euo pipefail

cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)/iosApp"
UDID="$(xcrun xctrace list devices 2>/dev/null | awk '/^== Simulators ==/{exit} /iPhone/ && match($0, /\(([0-9A-F]{8}-[0-9A-F]{16})\)$/) {print substr($0, RSTART + 1, RLENGTH - 2); exit}')"
[ -n "$UDID" ] || { echo "No iPhone connected (connect it over USB and unlock it)." >&2; exit 1; }
APP=build/Build/Products/Debug-iphoneos/Pebble.app

# Xcode does not always re-embed a rebuilt Kotlin framework; force a fresh copy.
rm -rf "$APP/Frameworks/ComposeApp.framework"

echo "== Building for $UDID (this can take a few minutes)"
xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -configuration Debug \
  -destination "id=$UDID" -derivedDataPath build -allowProvisioningUpdates build 2>&1 | tail -3

echo "== Installing"
xcrun devicectl device install app --device "$UDID" "$APP" >/dev/null
BUNDLE_ID="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$APP/Info.plist")"
echo "== Launching $BUNDLE_ID"
xcrun devicectl device process launch --device "$UDID" --terminate-existing "$BUNDLE_ID" >/dev/null \
  || echo "Could not launch (is the iPhone locked?). Open the app by hand."
echo "Done."
