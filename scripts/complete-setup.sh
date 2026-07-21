#!/bin/bash
# OpenShark Mobile — Complete Setup Script
# This script sets up everything: OpenShark server + Local LLM + Android app

set -e

echo "🦈 OpenShark Mobile — Complete Setup"
echo "====================================="
echo ""
echo "This script will:"
echo "  1. Install Ollama (local LLM runtime)"
echo "  2. Download Gemma 4 (2B params)"
echo "  3. Configure OpenShark for local + cloud"
echo "  4. Start the server"
echo ""
read -p "Continue? (y/N) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    exit 0
fi

# Colors
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Check Termux
if [ -z "$TERMUX_VERSION" ] && [ ! -d "/data/data/com.termux" ]; then
    echo "⚠️  Warning: Not running in Termux"
    echo "This setup is designed for Android/Termux"
    echo "Continuing anyway..."
    echo ""
fi

echo -e "${BLUE}Phase 1/5: Installing dependencies...${NC}"
pkg update -y
pkg install -y ollama curl git jq termux-api nodejs

echo -e "${BLUE}Phase 2/5: Setting up Ollama...${NC}"

# Create startup script
mkdir -p ~/.termux/boot
cat > ~/.termux/boot/ollama-start.sh << 'EOF'
#!/data/data/com.termux/files/usr/bin/sh
if ! pgrep -x ollama > /dev/null; then
    export OLLAMA_MAX_VRAM=0
    export OLLAMA_MAX_LOADED_MODELS=1
    export OLLAMA_HOST=127.0.0.1:11434
    ollama serve > ~/.ollama/server.log 2>&1 &
fi
EOF
chmod +x ~/.termux/boot/ollama-start.sh

# Start Ollama
if ! pgrep -x ollama > /dev/null; then
    export OLLAMA_MAX_VRAM=0
    export OLLAMA_MAX_LOADED_MODELS=1
    export OLLAMA_HOST=127.0.0.1:11434
    ollama serve > ~/.ollama/server.log 2>&1 &
    
    echo -n "Waiting for Ollama"
    for i in {1..30}; do
        if curl -s http://127.0.0.1:11434/api/tags > /dev/null 2>&1; then
            echo -e " ${GREEN}✓${NC}"
            break
        fi
        sleep 1
        echo -n "."
    done
fi

echo -e "${BLUE}Phase 3/5: Downloading Gemma 4...${NC}"
echo -e "${YELLOW}This will download ~7GB. Ensure you have space and WiFi.${NC}"
echo ""
read -p "Download Gemma 4 E2B (2B params)? (y/N) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    ollama pull gemma4:e2b
    echo -e "${GREEN}✓ Gemma 4 E2B ready${NC}"
fi

echo ""
read -p "Download Gemma 4 E4B (4B params, ~13GB)? (y/N) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    ollama pull gemma4:e4b
    echo -e "${GREEN}✓ Gemma 4 E4B ready${NC}"
fi

echo -e "${BLUE}Phase 4/5: Setting up Kimi Proxy...${NC}"

# Copy proxy to local bin
PROXY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../proxy" && pwd)"
mkdir -p "$HOME/.local/bin"
cp "$PROXY_DIR/kimi-proxy.js" "$HOME/.local/bin/"
cp "$PROXY_DIR/proxy.sh" "$HOME/.local/bin/kimi-proxy"
chmod +x "$HOME/.local/bin/kimi-proxy"

echo -e "${GREEN}✓ Kimi proxy installed${NC}"

echo -e "${BLUE}Phase 5/5: Configuring OpenShark...${NC}"

# Create config
mkdir -p ~/.config/openshark
cat > ~/.config/openshark/config.toml << 'EOF'
version = "1.1.0"
default_model = "gemma4:e2b"
auto_route = false
cost_limit_usd = 0.0

[agent]
name = "openshark-mobile"
display_name = "OpenShark"
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

# Local Ollama Provider (Primary - Offline)
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

# Cloud — Kimi via OpenClaw Proxy (always-both mode)
[providers.kimi]
base_url = "http://127.0.0.1:9000/v1"
api_key = "local-proxy"
kind = "openai-compatible"

[[providers.kimi.models]]
name = "kimi-k3"
context_length = 1000000
cost_per_1k_input = 0.0
cost_per_1k_output = 0.0
capabilities = ["code", "chat", "analysis"]

[[providers.kimi.models]]
name = "kimi-k2.5"
context_length = 256000
cost_per_1k_input = 0.0
cost_per_1k_output = 0.0
capabilities = ["code", "chat", "analysis"]
EOF

echo -e "${GREEN}✓ Config created${NC}"

# Create shortcuts
cat >> ~/.bashrc << 'EOF'

# OpenShark Mobile
alias os-start='ollama serve > ~/.ollama/server.log 2>&1 &; sleep 2; kimii-proxy start; openshark server --port 9876'
alias os-server='openshark server --port 9876'
alias os-status='curl -s http://127.0.0.1:9876/v1/health | jq .'
alias os-models='curl -s http://127.0.0.1:9876/v1/models | jq .'
alias ollama-logs='tail -f ~/.ollama/server.log'
alias kimi-proxy-start='kimi-proxy start'
alias kimi-proxy-stop='kimi-proxy stop'
alias kimi-proxy-logs='kimi-proxy logs'
EOF

echo ""
echo -e "${GREEN}=====================================${NC}"
echo -e "${GREEN}Setup Complete! 🦈${NC}"
echo -e "${GREEN}=====================================${NC}"
echo ""
echo "Models available:"
echo "  🏠 Local:  gemma4:e2b, gemma4:e4b (via Ollama)"
echo "  ☁️  Cloud:  kimi-k3, kimi-k2.5 (via OpenClaw proxy)"
echo ""
echo "Next steps:"
echo ""
echo "1. Start everything:"
echo "   os-start"
echo ""
echo "2. Or start individually:"
echo "   ollama serve &"
echo "   kimi-proxy start"
echo "   openshark server --port 9876"
echo ""
echo "3. Install the Android APK:"
echo "   adb install openshark-mobile.apk"
echo ""
echo "4. Open the app and chat!"
echo ""
echo "Shortcuts added to ~/.bashrc:"
echo "   os-start          — Start Ollama + Kimi proxy + OpenShark"
echo "   os-server         — Start OpenShark server only"
echo "   os-status         — Check server health"
echo "   os-models         — List available models"
echo "   kimi-proxy-start  — Start Kimi cloud proxy"
echo "   kimi-proxy-stop   — Stop Kimi cloud proxy"
echo ""
echo -e "${YELLOW}Tip: Add Termux to battery whitelist so services stay running.${NC}"
