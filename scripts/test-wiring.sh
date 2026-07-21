#!/bin/bash
# OpenShark Mobile — Wiring Test Script
# Validates local LLM + Kimi proxy connectivity

set -e

GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'
BOLD='\033[1m'

echo -e "${BLUE}${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}${BOLD}║           🦈 OpenShark Mobile — Wiring Test                  ║${NC}"
echo -e "${BLUE}${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""

PASS=0
FAIL=0

# ── Helper Functions ──────────────────────────────────────────
function pass() {
    echo -e "${GREEN}✓${NC} $1"
    ((PASS++))
}

function fail() {
    echo -e "${RED}✗${NC} $1"
    ((FAIL++))
}

function info() {
    echo -e "${BLUE}▶${NC} $1"
}

function warn() {
    echo -e "${YELLOW}⚠${NC} $1"
}

# ── Test 1: Ollama (Local LLM) ────────────────────────────────
echo ""
echo -e "${BOLD}Test 1/4: Local LLM (Ollama)${NC}"
echo "─────────────────────────────────────────────────────────────"

if command -v ollama &> /dev/null; then
    pass "Ollama binary found"
else
    fail "Ollama not installed. Run: pkg install ollama"
fi

if curl -s http://127.0.0.1:11434/api/tags > /dev/null 2>&1; then
    pass "Ollama server responding on :11434"
    
    MODELS=$(curl -s http://127.0.0.1:11434/api/tags | grep -o '"name":"[^"]*"' | cut -d'"' -f4)
    if echo "$MODELS" | grep -q "gemma4"; then
        pass "Gemma 4 model available"
    else
        warn "Gemma 4 not downloaded. Run: ollama pull gemma4:e2b"
    fi
    
    if echo "$MODELS" | grep -q "qwen"; then
        pass "Qwen model available"
    else
        info "Qwen not downloaded (optional)"
    fi
else
    fail "Ollama not running. Start with: ollama serve &"
fi

# ── Test 2: Kimi Proxy (Cloud) ────────────────────────────────
echo ""
echo -e "${BOLD}Test 2/4: Cloud Proxy (Kimi)${NC}"
echo "─────────────────────────────────────────────────────────────"

if curl -s http://127.0.0.1:9000/v1/health > /dev/null 2>&1; then
    pass "Kimi proxy responding on :9000"
    
    if curl -s http://127.0.0.1:9000/v1/models | grep -q "kimi-k3"; then
        pass "Kimi models accessible"
    else
        warn "Proxy running but models not listed"
    fi
else
    fail "Kimi proxy not running. Start with: bash proxy/proxy.sh start"
fi

# ── Test 3: OpenShark Config ──────────────────────────────────
echo ""
echo -e "${BOLD}Test 3/4: Configuration${NC}"
echo "─────────────────────────────────────────────────────────────"

CONFIG_PATHS=(
    "$HOME/.config/openshark/config.toml"
    "$HOME/.openshark/config.toml"
)

CONFIG_FOUND=false
for p in "${CONFIG_PATHS[@]}"; do
    if [ -f "$p" ]; then
        pass "Config found: $p"
        CONFIG_FOUND=true
        
        if grep -q "providers.ollama" "$p"; then
            pass "Ollama provider configured"
        else
            warn "Ollama provider missing from config"
        fi
        
        if grep -q "providers.kimi" "$p"; then
            pass "Kimi provider configured"
        else
            warn "Kimi provider missing from config"
        fi
        
        break
    fi
done

if [ "$CONFIG_FOUND" = false ]; then
    fail "No OpenShark config found. Run setup first."
fi

# ── Test 4: Android App Connectivity ──────────────────────────
echo ""
echo -e "${BOLD}Test 4/4: Android App${NC}"
echo "─────────────────────────────────────────────────────────────"

if [ -f "android/app/build/outputs/apk/debug/app-debug.apk" ]; then
    APK_SIZE=$(du -h android/app/build/outputs/apk/debug/app-debug.apk | cut -f1)
    pass "Debug APK built ($APK_SIZE)"
else
    info "APK not built yet. Run: cd android && ./gradlew assembleDebug"
fi

if command -v adb &> /dev/null; then
    DEVICES=$(adb devices | grep -v "List" | grep -v "^$" | wc -l)
    if [ "$DEVICES" -gt 0 ]; then
        pass "Android device connected ($DEVICES)"
    else
        warn "No Android device connected via ADB"
    fi
else
    info "ADB not installed (optional for testing)"
fi

# ── Summary ───────────────────────────────────────────────────
echo ""
echo -e "${BOLD}══════════════════════════════════════════════════════════════${NC}"
if [ $FAIL -eq 0 ]; then
    echo -e "${GREEN}${BOLD}All systems operational 🦈${NC}"
    echo ""
    echo "Start chatting:"
    echo "  1. Install APK on your device"
    echo "  2. Open OpenShark app"
    echo "  3. Tap model name to switch between local/cloud"
    echo ""
    echo "Or test via curl:"
    echo "  curl -s http://127.0.0.1:9000/v1/models"
else
    echo -e "${YELLOW}${BOLD}Issues found: $FAIL${NC}"
    echo ""
    echo "Fix the failures above, then run this script again."
fi
echo -e "${BOLD}══════════════════════════════════════════════════════════════${NC}"
