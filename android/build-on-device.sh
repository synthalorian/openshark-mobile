#!/bin/bash
# Build OpenShark Android APK on-device (Termux)
# This installs the Android SDK + JDK and builds the APK directly on your phone

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

ANDROID_DIR="$HOME/android-sdk"
PROJECT_DIR="${1:-$HOME/openshark/openshark-mobile/android}"

echo -e "${BLUE}╔════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}║     🦈 OpenShark APK Builder — On-Device (Termux)    ║${NC}"
echo -e "${BLUE}╚════════════════════════════════════════════════════════╝${NC}"
echo ""

# Check Termux
if [ -z "$TERMUX_VERSION" ] && [ ! -d "/data/data/com.termux" ]; then
    echo -e "${RED}Error: Must run in Termux${NC}"
    exit 1
fi

# Step 1: Install dependencies
echo -e "${BLUE}▶ Step 1/5: Installing dependencies...${NC}"
pkg update -y
pkg install -y openjdk-17 curl unzip

# Step 2: Download Android SDK
echo -e "${BLUE}▶ Step 2/5: Setting up Android SDK...${NC}"

if [ ! -d "$ANDROID_DIR" ]; then
    mkdir -p "$ANDROID_DIR"
    cd "$ANDROID_DIR"
    
    echo "Downloading Android command line tools..."
    curl -L -o cmdline-tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
    
    echo "Extracting..."
    unzip -q cmdline-tools.zip
    mkdir -p cmdline-tools/latest
    mv cmdline-tools/bin cmdline-tools/lib cmdline-tools/latest/
    rm cmdline-tools.zip
    
    # Accept licenses
    yes | cmdline-tools/latest/bin/sdkmanager --licenses || true
    
    # Install required SDK components
    cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
    
    echo -e "${GREEN}✓ Android SDK installed${NC}"
else
    echo -e "${GREEN}✓ Android SDK already present${NC}"
fi

# Step 3: Set environment
echo -e "${BLUE}▶ Step 3/5: Configuring environment...${NC}"

export ANDROID_HOME="$ANDROID_DIR"
export JAVA_HOME="/data/data/com.termux/files/usr/lib/jvm/java-17"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$JAVA_HOME/bin:$PATH"

# Add to bashrc for future sessions
if ! grep -q "ANDROID_HOME" "$HOME/.bashrc"; then
    cat >> "$HOME/.bashrc" << EOF

# Android SDK
export ANDROID_HOME="$ANDROID_DIR"
export JAVA_HOME="/data/data/com.termux/files/usr/lib/jvm/java-17"
export PATH="\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$JAVA_HOME/bin:\$PATH"
EOF
fi

echo -e "${GREEN}✓ Environment configured${NC}"

# Step 4: Verify project
echo -e "${BLUE}▶ Step 4/5: Verifying project...${NC}"

if [ ! -f "$PROJECT_DIR/build.gradle.kts" ]; then
    echo -e "${RED}Error: Android project not found at $PROJECT_DIR${NC}"
    echo "Usage: $0 /path/to/openshark-mobile/android"
    exit 1
fi

echo -e "${GREEN}✓ Project found${NC}"

# Step 5: Build APK
echo -e "${BLUE}▶ Step 5/5: Building APK...${NC}"
echo -e "${YELLOW}This may take 15-30 minutes on mobile. Keep Termux alive.${NC}"
echo ""

cd "$PROJECT_DIR"

# Make gradlew executable
chmod +x gradlew 2>/dev/null || true

# Build
./gradlew assembleDebug 2>&1 | tee /tmp/apk-build.log

# Check result
APK_PATH="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"

if [ -f "$APK_PATH" ]; then
    APK_SIZE=$(du -h "$APK_PATH" | cut -f1)
    echo ""
    echo -e "${GREEN}╔════════════════════════════════════════════════════════╗${NC}"
    echo -e "${GREEN}║              ✅ APK BUILD SUCCESSFUL!                  ║${NC}"
    echo -e "${GREEN}╚════════════════════════════════════════════════════════╝${NC}"
    echo ""
    echo "APK location: $APK_PATH"
    echo "APK size: $APK_SIZE"
    echo ""
    echo "Install with:"
    echo "  pm install -r $APK_PATH"
    echo ""
    echo "Or share it:"
    echo "  cp $APK_PATH ~/storage/downloads/OpenShark.apk"
else
    echo ""
    echo -e "${RED}✗ Build failed${NC}"
    echo "Check log: /tmp/apk-build.log"
    exit 1
fi
