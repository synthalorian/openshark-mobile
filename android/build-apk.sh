#!/bin/bash
# Build OpenShark Android APK
# Run this on your development machine (not Termux)

set -e

echo "🦈 OpenShark APK Builder"
echo "======================="

# Check prerequisites
check_command() {
    if ! command -v "$1" &> /dev/null; then
        echo "❌ $1 is not installed"
        return 1
    fi
    echo "✓ $1 found"
}

echo "Checking prerequisites..."
check_command java || exit 1
check_command javac || exit 1

if [ ! -d "$ANDROID_HOME" ] && [ ! -d "$ANDROID_SDK_ROOT" ]; then
    echo "❌ ANDROID_HOME or ANDROID_SDK_ROOT not set"
    echo "Download Android Studio or command line tools:"
    echo "  https://developer.android.com/studio"
    exit 1
fi
echo "✓ Android SDK found"

# Find the Android project
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
echo "Project directory: $PROJECT_DIR"

cd "$PROJECT_DIR"

# Make gradlew executable
if [ -f "./gradlew" ]; then
    chmod +x ./gradlew
fi

echo ""
echo "Building APK..."
./gradlew assembleDebug

# Find the output APK
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
    echo ""
    echo "✅ Build successful!"
    echo ""
    echo "APK location: $PROJECT_DIR/$APK_PATH"
    echo ""
    echo "Install to device:"
    echo "  adb install $APK_PATH"
    echo ""
    echo "Or copy to phone and install manually."
else
    echo "❌ APK not found at expected path"
    exit 1
fi
