#!/usr/bin/env bash
# Prepares a Mac to build the iOS app for a personal device and runs the reply-audio unit tests.
# Safe to re-run. Usage (from the repo root):
#   tools/mac-build/setup.sh [--trim-entitlements] [--skip-tests]
set -euo pipefail

TRIM_ENTITLEMENTS=false
SKIP_TESTS=false
for arg in "$@"; do
  case "$arg" in
    --trim-entitlements) TRIM_ENTITLEMENTS=true ;;
    --skip-tests) SKIP_TESTS=true ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

cd "$(git rev-parse --show-toplevel)"
ok()   { printf '  \033[32mOK\033[0m   %s\n' "$1"; }
fail() { printf '  \033[31mNG\033[0m   %s\n' "$1"; FAILED=1; }
FAILED=0

echo "== 1. Prerequisites"
if xcodebuild -version >/dev/null 2>&1; then ok "Xcode: $(xcodebuild -version | head -1)"; else fail "Xcode not found (install from the App Store, then: sudo xcode-select -s /Applications/Xcode.app)"; fi
if JAVA17=$(/usr/libexec/java_home -v 17 2>/dev/null); then ok "JDK 17: $JAVA17"; export JAVA_HOME="$JAVA17"; else fail "JDK 17 not found (brew install openjdk@17)"; fi
if command -v pod >/dev/null 2>&1; then ok "CocoaPods: $(pod --version)"; else fail "CocoaPods not found (brew install cocoapods)"; fi
SDK_DIR="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [ -d "$SDK_DIR" ]; then ok "Android SDK: $SDK_DIR"; else fail "Android SDK not found at $SDK_DIR (install Android Studio and run its setup wizard)"; fi
if [ "$FAILED" -ne 0 ]; then echo "Fix the NG items above, then re-run."; exit 1; fi

echo "== 2. Local configuration (not committed)"
if [ -f local.properties ] && grep -q '^sdk.dir=' local.properties; then
  ok "local.properties already has sdk.dir"
else
  echo "sdk.dir=$SDK_DIR" >> local.properties; ok "wrote sdk.dir to local.properties"
fi
if [ -f iosApp/iosApp/GoogleService-Info.plist ]; then
  ok "GoogleService-Info.plist present"
else
  cp iosApp/iosApp/GoogleService-Info-dummy.plist iosApp/iosApp/GoogleService-Info.plist
  ok "copied dummy GoogleService-Info.plist"
fi
if git describe --tags --abbrev=0 >/dev/null 2>&1; then
  ok "version tag: $(git describe --tags --abbrev=0)"
else
  git fetch --tags origin >/dev/null 2>&1 || true
  if git describe --tags --abbrev=0 >/dev/null 2>&1; then
    ok "version tag (fetched): $(git describe --tags --abbrev=0)"
  else
    git tag 1.0.0; ok "created local tag 1.0.0"
  fi
fi
if $TRIM_ENTITLEMENTS; then
  cat > iosApp/iosApp/iosApp.entitlements <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>com.apple.developer.healthkit</key>
	<true/>
</dict>
</plist>
EOF
  git update-index --skip-worktree iosApp/iosApp/iosApp.entitlements
  ok "trimmed iosApp.entitlements to HealthKit only (marked skip-worktree, so it is never committed)"
fi

if ! $SKIP_TESTS; then
  echo "== 3. Reply audio unit tests"
  ./gradlew :experimental:testAndroidHostTest --tests 'coredevices.ring.external.replyaudio.*'
  ok "unit tests passed"
  echo "== 4. Kotlin compile for iOS device"
  ./gradlew :composeApp:compileKotlinIosArm64
  ok "iOS Kotlin compile passed"
fi

echo "== 5. CocoaPods"
./gradlew podInstall
ok "pods installed"

echo
echo "Done. Next: open iosApp/iosApp.xcworkspace in Xcode, set Signing (Team + Bundle ID), and run on the iPhone."
