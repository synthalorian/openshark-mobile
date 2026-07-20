#!/bin/bash
# OpenShark Mobile — Master Install Script
# Does EVERYTHING. Just run this.

set -e

OPENSOURCE_DIR="${1:-$HOME/openshark}"
MOBILE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

print_header() {
    echo ""
    echo -e "${CYAN}${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${CYAN}${BOLD}║                                                              ║${NC}"
    echo -e "${CYAN}${BOLD}║           🦈 OpenShark Mobile — Full Install                 ║${NC}"
    echo -e "${CYAN}${BOLD}║                                                              ║${NC}"
    echo -e "${CYAN}${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

print_step() {
    echo ""
    echo -e "${BLUE}${BOLD}▶ $1${NC}"
    echo -e "${BLUE}$(printf '%*s' "${#1}" '' | tr ' ' '─')${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_info() {
    echo -e "${YELLOW}ℹ $1${NC}"
}

# ──────────────────────────────────────────
# Phase 0: Detect Environment
# ──────────────────────────────────────────

print_header

IN_TERMUX=false
if [ -n "$TERMUX_VERSION" ] || [ -d "/data/data/com.termux" ]; then
    IN_TERMUX=true
    print_info "Running in Termux environment"
else
    print_info "Running on development machine"
fi

# ──────────────────────────────────────────
# Phase 1: Validate OpenShark Repo
# ──────────────────────────────────────────

print_step "Phase 1: Validating OpenShark Repository"

if [ ! -d "$OPENSOURCE_DIR" ]; then
    print_error "OpenShark directory not found: $OPENSOURCE_DIR"
    echo ""
    echo "Options:"
    echo "  1. Clone it: git clone https://github.com/synthalorian/openshark.git"
    echo "  2. Specify path: ./install.sh /path/to/openshark"
    exit 1
fi

if [ ! -f "$OPENSOURCE_DIR/Cargo.toml" ]; then
    print_error "Invalid OpenShark directory (no Cargo.toml found)"
    exit 1
fi

if [ ! -d "$OPENSOURCE_DIR/.git" ]; then
    print_error "OpenShark repo must be a git repository (for patch application)"
    echo "Run: cd $OPENSOURCE_DIR && git init"
    exit 1
fi

print_success "OpenShark found at: $OPENSOURCE_DIR"

# ──────────────────────────────────────────
# Phase 2: Apply Patches
# ──────────────────────────────────────────

print_step "Phase 2: Applying Integration Patches"

cd "$OPENSOURCE_DIR"

# Check for uncommitted changes
if ! git diff --quiet HEAD 2>/dev/null; then
    print_error "You have uncommitted changes in OpenShark"
    echo "Commit or stash them first:"
    echo "  git add -A && git commit -m 'wip'"
    exit 1
fi

# Apply patches
PATCHES=(
    "$MOBILE_DIR/patches/0001-add-dependencies.patch"
    "$MOBILE_DIR/patches/0002-add-http-module.patch"
    "$MOBILE_DIR/patches/0003-add-server-command.patch"
)

for patch in "${PATCHES[@]}"; do
    patch_name=$(basename "$patch")
    if git apply --check "$patch" 2>/dev/null; then
        git apply "$patch"
        print_success "Applied: $patch_name"
    else
        print_error "Failed to apply: $patch_name"
        echo "This patch may already be applied or the code has diverged."
        echo "Check manually or use --ignore-whitespace flag."
        exit 1
    fi
done

# ──────────────────────────────────────────
# Phase 3: Copy Gateway Module
# ──────────────────────────────────────────

print_step "Phase 3: Installing HTTP Gateway"

if [ ! -f "$OPENSOURCE_DIR/src/gateway/http.rs" ]; then
    cp "$MOBILE_DIR/src/gateway/http.rs" "$OPENSOURCE_DIR/src/gateway/http.rs"
    print_success "Copied: src/gateway/http.rs"
else
    print_info "http.rs already exists, skipping copy"
fi

if ! grep -q "pub mod http;" "$OPENSOURCE_DIR/src/gateway/mod.rs"; then
    echo "pub mod http;" >> "$OPENSOURCE_DIR/src/gateway/mod.rs"
    print_success "Added: pub mod http to gateway/mod.rs"
else
    print_info "pub mod http already in gateway/mod.rs"
fi

# ──────────────────────────────────────────
# Phase 4: Verify Dependencies
# ──────────────────────────────────────────

print_step "Phase 4: Verifying Dependencies"

if ! grep -q "async-stream" "$OPENSOURCE_DIR/Cargo.toml"; then
    print_error "Dependencies not properly patched. Check 0001 patch."
    exit 1
fi

print_success "Dependencies verified"

# ──────────────────────────────────────────
# Phase 5: Build OpenShark Server
# ──────────────────────────────────────────

if [ "$IN_TERMUX" = true ]; then
    print_step "Phase 5: Building OpenShark Server (Termux)"
    
    # Install deps if needed
    if ! command -v rustc &> /dev/null; then
        print_info "Installing Rust..."
        pkg install -y rust cargo sqlite pkg-config
    fi
    
    print_info "Building (this may take 10-30 minutes on mobile)..."
    cargo build --release 2>&1 | tee /tmp/openshark-build.log
    
    if [ -f "$OPENSOURCE_DIR/target/release/openshark" ]; then
        print_success "Build complete!"
        
        # Create symlink
        mkdir -p "$HOME/.local/bin"
        ln -sf "$OPENSOURCE_DIR/target/release/openshark" "$HOME/.local/bin/openshark"
        print_success "Linked to: $HOME/.local/bin/openshark"
    else
        print_error "Build failed. Check /tmp/openshark-build.log"
        exit 1
    fi
else
    print_step "Phase 5: Build (Skipped on dev machine)"
    print_info "Build on your Termux device with: cargo build --release"
fi

# ──────────────────────────────────────────
# Phase 6: Setup Android App
# ──────────────────────────────────────────

print_step "Phase 6: Android App Setup"

ANDROID_PROJECT="$MOBILE_DIR/android"
if [ -d "$ANDROID_PROJECT" ]; then
    print_success "Android project ready at: $ANDROID_PROJECT"
    print_info "Build APK with: cd $ANDROID_PROJECT && ./build-apk.sh"
else
    print_error "Android project not found"
fi

# ──────────────────────────────────────────
# Phase 7: Create Default Config
# ──────────────────────────────────────────

print_step "Phase 7: Creating Default Config"

mkdir -p ~/.config/openshark
if [ ! -f ~/.config/openshark/config.toml ]; then
    cat > ~/.config/openshark/config.toml << 'EOF'
version = "1.1.0"
default_model = "gemma4:e2b"
auto_route = false
cost_limit_usd = 0.0

[agent]
name = "openshark-mobile"
display_name = "OpenShark 🦈"
role = "coding assistant"
origin = "Termux on Android"
purpose = "Ship code fast"
tagline = "Let's build the future."
tone = "Professional but friendly"
style = "Concise and thorough"
greeting = "Hey! Ready to code?"
farewell = "See you next session!"
emoji = "🦈"
catchphrases = ["Let's do this!", "Ship it!"]
behavioral_rules = [
    "Always verify before claiming success",
    "Show the code, don't just describe it",
]

[memory]
db_path = "~/.config/openshark/memory.db"

# Local Ollama Provider (Primary - Works Offline)
[providers.ollama]
base_url = "http://127.0.0.1:11434/v1"
api_key = "ollama"
kind = "openai-compatible"

[[providers.ollama.models]]
name = "gemma4:e2b"
context_length = 8192
cost_per_1k_input = 0.0
cost_per_1k_output = 0.0
capabilities = ["code", "chat", "analysis"]

[[providers.ollama.models]]
name = "gemma4:e4b"
context_length = 8192
cost_per_1k_input = 0.0
cost_per_1k_output = 0.0
capabilities = ["code", "chat", "analysis"]

# Cloud providers (uncomment and set API key to use)
# [providers.openai]
# base_url = "https://api.openai.com/v1"
# api_key = "${OPENAI_API_KEY}"
# 
# [[providers.openai.models]]
# name = "gpt-4o"
# context_length = 128000
# cost_per_1k_input = 0.005
# cost_per_1k_output = 0.015
# capabilities = ["code", "chat", "analysis"]
EOF
    print_success "Created: ~/.config/openshark/config.toml"
else
    print_info "Config already exists"
fi

# ──────────────────────────────────────────
# Phase 8: Final Summary
# ──────────────────────────────────────────

echo ""
echo -e "${GREEN}${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}${BOLD}║                    🎉 INSTALL COMPLETE!                      ║${NC}"
echo -e "${GREEN}${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "${BOLD}What was done:${NC}"
echo "  ✓ Patched Cargo.toml with HTTP gateway dependencies"
echo "  ✓ Added 'Server' command to CLI"
echo "  ✓ Installed src/gateway/http.rs (Axum server)"
echo "  ✓ Created default config at ~/.config/openshark/config.toml"
echo ""

if [ "$IN_TERMUX" = true ]; then
    echo -e "${BOLD}Next steps in Termux:${NC}"
    echo ""
    echo "  1. ${CYAN}Setup Local LLM:${NC}"
    echo "     bash $MOBILE_DIR/scripts/complete-setup.sh"
    echo ""
    echo "  2. ${CYAN}Start the server:${NC}"
    echo "     openshark server --port 9876"
    echo ""
    echo "  3. ${CYAN}Install Android APK${NC} (from dev machine):"
    echo "     cd $MOBILE_DIR/android && ./gradlew assembleDebug"
    echo "     adb install app/build/outputs/apk/debug/app-debug.apk"
    echo ""
    echo "  4. ${CYAN}Open app and chat!${NC} Default URL: http://127.0.0.1:9876"
else
    echo -e "${BOLD}Next steps on dev machine:${NC}"
    echo ""
    echo "  1. ${CYAN}Build the APK:${NC}"
    echo "     cd $MOBILE_DIR/android"
    echo "     ./build-apk.sh"
    echo ""
    echo "  2. ${CYAN}Install to phone:${NC}"
    echo "     adb install app/build/outputs/apk/debug/app-debug.apk"
    echo ""
    echo "  3. ${CYAN}On your phone (Termux):${NC}"
    echo "     bash $MOBILE_DIR/scripts/complete-setup.sh"
    echo "     openshark server --port 9876"
    echo ""
    echo "  4. ${CYAN}Open app and chat!${NC}"
fi

echo ""
echo -e "${YELLOW}${BOLD}Important:${NC}"
echo "  • Keep Termux running for Ollama to stay alive"
echo "  • Add Termux to battery optimization whitelist"
echo "  • Gemma 4 E2B download is ~7GB — use WiFi"
echo ""
echo -e "${CYAN}This is the wave. 🎹🦞${NC}"
echo ""
