#!/bin/bash
# Start OpenClaw Gateway + Kimi Proxy together
# This keeps both services running so OpenShark can use cloud models

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Find the proxy script
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROXY_SCRIPT="$SCRIPT_DIR/proxy/kimi-proxy.js"
PROXY_PORT="${KIMI_PROXY_PORT:-9000}"

echo -e "${BLUE}${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${BLUE}${BOLD}║     🎹🦞 OpenClaw + Kimi Proxy Launcher                     ║${NC}"
echo -e "${BLUE}${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""

# Check if Node.js is available
if ! command -v node &> /dev/null; then
    echo -e "${RED}✗ Node.js not found. Install with: pkg install nodejs${NC}"
    exit 1
fi

# Check proxy script exists
if [ ! -f "$PROXY_SCRIPT" ]; then
    echo -e "${RED}✗ Proxy script not found: $PROXY_SCRIPT${NC}"
    exit 1
fi

# ── Start Kimi Proxy ───────────────────────────────────────
echo -e "${BLUE}▶ Starting Kimi Proxy on port $PROXY_PORT...${NC}"

# Check if already running
if lsof -i :$PROXY_PORT > /dev/null 2>&1; then
    echo -e "${YELLOW}⚠ Port $PROXY_PORT already in use. Proxy may already be running.${NC}"
else
    nohup node "$PROXY_SCRIPT" --port "$PROXY_PORT" > "$HOME/.kimi-proxy.log" 2>&1 &
    PROXY_PID=$!
    sleep 2
    
    if kill -0 $PROXY_PID 2>/dev/null; then
        echo -e "${GREEN}✓ Kimi Proxy started (PID: $PROXY_PID)${NC}"
        echo "  Log: $HOME/.kimi-proxy.log"
    else
        echo -e "${RED}✗ Kimi Proxy failed to start. Check log.${NC}"
        cat "$HOME/.kimi-proxy.log" 2>/dev/null | tail -10
        exit 1
    fi
fi

# ── Check OpenClaw ─────────────────────────────────────────
echo ""
echo -e "${BLUE}▶ Checking OpenClaw gateway...${NC}"

if pgrep -f "openclaw" > /dev/null 2>&1; then
    echo -e "${GREEN}✓ OpenClaw gateway is running${NC}"
else
    echo -e "${YELLOW}⚠ OpenClaw gateway not detected.${NC}"
    echo "  Start it with: openclaw gateway start"
    echo "  Or in another Termux session."
fi

# ── Status ─────────────────────────────────────────────────
echo ""
echo -e "${GREEN}${BOLD}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}${BOLD}║                    Services Status                           ║${NC}"
echo -e "${GREEN}${BOLD}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "${BOLD}Kimi Proxy:${NC}"
echo "  URL:     http://127.0.0.1:$PROXY_PORT/v1"
echo "  Health:  curl http://127.0.0.1:$PROXY_PORT/v1/health"
echo "  Models:  curl http://127.0.0.1:$PROXY_PORT/v1/models"
echo ""
echo -e "${BOLD}OpenShark Config:${NC}"
echo "  ~/.config/openshark/config.toml"
echo ""
echo -e "${BOLD}Providers:${NC}"
echo "  🏠 Local:  ollama → http://127.0.0.1:11434 (offline)"
echo "  ☁️ Cloud:  kimi  → http://127.0.0.1:$PROXY_PORT (via OpenClaw)"
echo ""
echo -e "${CYAN}This is the wave. 🎹🦞${NC}"
echo ""

# ── Keep alive ─────────────────────────────────────────────
echo "Press Ctrl+C to stop proxy. OpenClaw runs separately."
echo ""

# Wait for user interrupt
trap 'echo -e "\n${YELLOW}Stopping proxy...${NC}"; pkill -f "kimi-proxy.js"; exit 0' INT

while true; do
    sleep 1
    if ! pgrep -f "kimi-proxy.js" > /dev/null 2>&1; then
        echo -e "${RED}Proxy exited unexpectedly.${NC}"
        exit 1
    fi
done
