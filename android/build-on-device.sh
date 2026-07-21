#!/bin/bash
# OpenShark APK Builder — Full On-Device Build (Termux)
# Downloads Android SDK + builds APK entirely on your phone
# Requires: Termux (F-Droid), ~4GB free storage, WiFi

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

ANDROID_SDK_DIR="$HOME/android-sdk"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BUILD_LOG="/tmp/openshark-apk-build.log"

echo -e "${CYAN}${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}${BOLD}║           🦈 OpenShark APK Builder — Termux                  ║${NC}"
echo -e "${CYAN}${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""

# ── Verify Termux ──────────────────────────────────────────
if [ -z "$TERMUX_VERSION" ] && [ ! -d "/data/data/com.termux" ]; then
    echo -e "${RED}✗ This script must run inside Termux${NC}"
    echo "Install from F-Droid: https://f-droid.org/packages/com.termux/"
    exit 1
fi

# ── Check storage ──────────────────────────────────────────
FREE_GB=$(df -h "$HOME" | awk 'NR==2 {print $4}' | sed 's/G//')
echo -e "${BLUE}▶ Free storage: ${FREE_GB}GB${NC}"

# ── Step 1: Dependencies ───────────────────────────────────
echo ""
echo -e "${BLUE}${BOLD}▶ Step 1/6: Installing dependencies...${NC}"
pkg update -y
pkg install -y openjdk-17 curl unzip git

export JAVA_HOME="/data/data/com.termux/files/usr/lib/jvm/java-17"
export PATH="$JAVA_HOME/bin:$PATH"

# Add to bashrc if not present
if ! grep -q "JAVA_HOME" "$HOME/.bashrc" 2>/dev/null; then
    cat >> "$HOME/.bashrc" << 'EOF'

# Java / Android
export JAVA_HOME="/data/data/com.termux/files/usr/lib/jvm/java-17"
export PATH="$JAVA_HOME/bin:$PATH"
EOF
fi

echo -e "${GREEN}✓ Java 17 ready${NC}"

# ── Step 2: Android SDK ────────────────────────────────────
echo ""
echo -e "${BLUE}${BOLD}▶ Step 2/6: Setting up Android SDK...${NC}"

if [ ! -d "$ANDROID_SDK_DIR/cmdline-tools" ]; then
    mkdir -p "$ANDROID_SDK_DIR"
    cd "$ANDROID_SDK_DIR"
    
    echo "Downloading Android command line tools..."
    CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    curl -L -o cmdline-tools.zip "$CMDLINE_URL" 2>&1 | tail -3
    
    echo "Extracting..."
    unzip -q cmdline-tools.zip
    mkdir -p cmdline-tools/latest
    mv cmdline-tools/bin cmdline-tools/lib cmdline-tools/NOTICE.txt cmdline-tools/source.properties cmdline-tools/latest/ 2>/dev/null || true
    rm -f cmdline-tools.zip
    
    echo -e "${GREEN}✓ SDK downloaded${NC}"
else
    echo -e "${GREEN}✓ SDK already present${NC}"
fi

# Set SDK environment
export ANDROID_HOME="$ANDROID_SDK_DIR"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# Add to bashrc
if ! grep -q "ANDROID_HOME" "$HOME/.bashrc" 2>/dev/null; then
    cat >> "$HOME/.bashrc" << EOF

# Android SDK
export ANDROID_HOME="$ANDROID_SDK_DIR"
export PATH="\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH"
EOF
fi

# ── Step 3: SDK Components ─────────────────────────────────
echo ""
echo -e "${BLUE}${BOLD}▶ Step 3/6: Installing SDK components...${NC}"
echo -e "${YELLOW}  (Accepting licenses automatically)${NC}"

yes 2>/dev/null | sdkmanager --licenses > /dev/null 2>&1 || true

sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" 2>&1 | grep -v "^$" | tail -10

echo -e "${GREEN}✓ SDK components installed${NC}"

# ── Step 4: Verify Project ─────────────────────────────────
echo ""
echo -e "${BLUE}${BOLD}▶ Step 4/6: Verifying project...${NC}"

cd "$PROJECT_DIR"

if [ ! -f "build.gradle.kts" ]; then
    echo -e "${RED}✗ No build.gradle.kts found in $PROJECT_DIR${NC}"
    echo "Are you running this from the android/ directory?"
    exit 1
fi

echo -e "${GREEN}✓ Project verified${NC}"

# ── Step 5: Build ──────────────────────────────────────────
echo ""
echo -e "${BLUE}${BOLD}▶ Step 5/6: Building APK...${NC}"
echo -e "${YELLOW}  This will take 10-30 minutes. Keep Termux alive.${NC}"
echo ""

chmod +x gradlew 2>/dev/null || true

./gradlew assembleDebug --no-daemon --offline 2>&1 | tee "$BUILD_LOG" | while read line; do
    # Show progress indicators
    if echo "$line" | grep -q "CONFIGURE SUCCESSFUL"; then
        echo -e "${GREEN}✓ Gradle configured${NC}"
    elif echo "$line" | grep -q "BUILD SUCCESSFUL"; then
        echo -e "${GREEN}✓ Build successful!${NC}"
    elif echo "$line" | grep -q "BUILD FAILED"; then
        echo -e "${RED}✗ Build failed${NC}"
    fi
done

BUILD_STATUS=${PIPESTATUS[0]}

# ── Step 6: Deliver ────────────────────────────────────────
echo ""
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"

if [ -f "$APK_PATH" ] && [ $BUILD_STATUS -eq 0 ]; then
    APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
    
    echo -e "${GREEN}${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}${BOLD}║                  🎉 APK BUILD SUCCESSFUL!                    ║${NC}"
    echo -e "${GREEN}${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo -e "${BOLD}APK Location:${NC}"
    echo "  $APK_PATH"
    echo ""
    echo -e "${BOLD}Size:${NC} $APK_SIZE"
    echo ""
    echo -e "${BOLD}Install now:${NC}"
    echo "  pm install -r \"$APK_PATH\""
    echo ""
    echo -e "${BOLD}Or copy to Downloads:${NC}"
    echo "  cp \"$APK_PATH\" ~/storage/downloads/OpenShark.apk"
    echo ""
    echo -e "${CYAN}This is the wave. 🎹🦞${NC}"
else
    echo -e "${RED}${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${RED}${BOLD}║                    ✗ BUILD FAILED                            ║${NC}"
    echo -e "${RED}${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo "Check the full log:"
    echo "  cat $BUILD_LOG"
    echo ""
    echo "Common fixes:"
    echo "  - Run: pkg install openjdk-17"
    echo "  - Check internet connection"
    echo "  - Ensure 4GB+ free storage"
    exit 1
fi
